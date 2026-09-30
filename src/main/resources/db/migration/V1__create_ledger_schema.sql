-- Accounts hold a cached balance that is only ever changed together with ledger entries
-- in the same database transaction. Balance semantics: credits increase, debits decrease.
CREATE TABLE accounts (
    id                     UUID PRIMARY KEY,
    name                   VARCHAR(100)   NOT NULL,
    currency               VARCHAR(3)     NOT NULL,
    allow_negative_balance BOOLEAN        NOT NULL DEFAULT FALSE,
    balance                NUMERIC(19, 4) NOT NULL DEFAULT 0,
    created_at             TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT chk_accounts_currency_format CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT chk_accounts_non_negative_balance CHECK (allow_negative_balance OR balance >= 0)
);

CREATE TABLE transactions (
    id                     UUID PRIMARY KEY,
    idempotency_key        VARCHAR(255)   NOT NULL,
    request_hash           VARCHAR(64)    NOT NULL,
    type                   VARCHAR(16)    NOT NULL,
    status                 VARCHAR(16)    NOT NULL,
    source_account_id      UUID           NOT NULL REFERENCES accounts (id),
    destination_account_id UUID           NOT NULL REFERENCES accounts (id),
    amount                 NUMERIC(19, 4) NOT NULL,
    currency               VARCHAR(3)     NOT NULL,
    description            VARCHAR(255),
    failure_reason         VARCHAR(255),
    refund_of_id           UUID REFERENCES transactions (id),
    created_at             TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ    NOT NULL DEFAULT now(),
    -- The idempotency guarantee: one row per key, enforced by the database.
    CONSTRAINT uq_transactions_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT chk_transactions_type CHECK (type IN ('PAYMENT', 'REFUND')),
    CONSTRAINT chk_transactions_status CHECK (status IN ('PENDING', 'POSTED', 'REFUNDED', 'FAILED')),
    CONSTRAINT chk_transactions_amount_positive CHECK (amount > 0),
    CONSTRAINT chk_transactions_distinct_accounts CHECK (source_account_id <> destination_account_id),
    CONSTRAINT chk_transactions_refund_link CHECK ((type = 'REFUND') = (refund_of_id IS NOT NULL)),
    CONSTRAINT chk_transactions_refund_not_refunded CHECK (type = 'PAYMENT' OR status <> 'REFUNDED')
);

-- A payment can have at most one non-failed refund.
CREATE UNIQUE INDEX uq_transactions_one_refund_per_payment
    ON transactions (refund_of_id)
    WHERE refund_of_id IS NOT NULL AND status <> 'FAILED';

CREATE TABLE ledger_entries (
    id             UUID PRIMARY KEY,
    transaction_id UUID           NOT NULL REFERENCES transactions (id),
    account_id     UUID           NOT NULL REFERENCES accounts (id),
    direction      VARCHAR(6)     NOT NULL,
    amount         NUMERIC(19, 4) NOT NULL,
    currency       VARCHAR(3)     NOT NULL,
    created_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT chk_ledger_entries_direction CHECK (direction IN ('DEBIT', 'CREDIT')),
    CONSTRAINT chk_ledger_entries_amount_positive CHECK (amount > 0)
);

CREATE INDEX idx_ledger_entries_transaction_id ON ledger_entries (transaction_id);
CREATE INDEX idx_ledger_entries_account_id ON ledger_entries (account_id);

-- Double-entry invariant, checked at COMMIT time (deferred) so that all entries of a
-- transaction can be inserted before the check runs: debits = credits, single currency.
CREATE FUNCTION assert_transaction_balanced() RETURNS TRIGGER AS $$
DECLARE
    total_debits   NUMERIC;
    total_credits  NUMERIC;
    currency_count INTEGER;
BEGIN
    SELECT COALESCE(SUM(amount) FILTER (WHERE direction = 'DEBIT'), 0),
           COALESCE(SUM(amount) FILTER (WHERE direction = 'CREDIT'), 0),
           COUNT(DISTINCT currency)
      INTO total_debits, total_credits, currency_count
      FROM ledger_entries
     WHERE transaction_id = NEW.transaction_id;

    IF total_debits <> total_credits THEN
        RAISE EXCEPTION 'Unbalanced ledger transaction %: debits=% credits=%',
            NEW.transaction_id, total_debits, total_credits
            USING ERRCODE = 'check_violation';
    END IF;

    IF currency_count > 1 THEN
        RAISE EXCEPTION 'Ledger transaction % mixes currencies', NEW.transaction_id
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_ledger_entries_balanced
    AFTER INSERT ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION assert_transaction_balanced();

-- The ledger is append-only: corrections are made with new (reversing) entries.
CREATE FUNCTION reject_ledger_entry_mutation() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'ledger_entries is append-only (% rejected)', TG_OP
        USING ERRCODE = 'check_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_ledger_entries_append_only
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION reject_ledger_entry_mutation();
