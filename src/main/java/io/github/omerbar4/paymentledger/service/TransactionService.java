package io.github.omerbar4.paymentledger.service;

import io.github.omerbar4.paymentledger.domain.Account;
import io.github.omerbar4.paymentledger.domain.LedgerEntry;
import io.github.omerbar4.paymentledger.domain.LedgerTransaction;
import io.github.omerbar4.paymentledger.domain.Money;
import io.github.omerbar4.paymentledger.domain.TransactionStatus;
import io.github.omerbar4.paymentledger.domain.TransactionType;
import io.github.omerbar4.paymentledger.dto.CreateTransactionRequest;
import io.github.omerbar4.paymentledger.exception.ApiException;
import io.github.omerbar4.paymentledger.repository.AccountRepository;
import io.github.omerbar4.paymentledger.repository.LedgerEntryRepository;
import io.github.omerbar4.paymentledger.repository.LedgerTransactionRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates payments and refunds. Each public method runs in a single database transaction
 * (READ COMMITTED): the idempotency key is claimed, the ledger entries are written, balances are
 * updated and the final status is set atomically. A crash mid-way rolls everything back,
 * including the idempotency claim, so a retry with the same key starts cleanly.
 */
@Service
public class TransactionService {

    static final String INSUFFICIENT_FUNDS = "INSUFFICIENT_FUNDS";

    private final LedgerTransactionRepository transactions;
    private final LedgerEntryRepository entries;
    private final AccountRepository accounts;

    public TransactionService(LedgerTransactionRepository transactions, LedgerEntryRepository entries,
                              AccountRepository accounts) {
        this.transactions = transactions;
        this.entries = entries;
        this.accounts = accounts;
    }

    @Transactional
    public TransactionResult createPayment(String idempotencyKey, CreateTransactionRequest request) {
        BigDecimal amount = Money.normalize(request.amount());
        String fingerprint = RequestFingerprint.of(TransactionType.PAYMENT, request.sourceAccountId(),
                request.destinationAccountId(), amount.toPlainString(), request.currency(), request.description());

        // Fast path for retries: no locks needed.
        var existing = transactions.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), fingerprint);
        }

        if (request.sourceAccountId().equals(request.destinationAccountId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SAME_ACCOUNT",
                    "Source and destination accounts must be different");
        }
        Map<UUID, Account> locked = lockAccounts(request.sourceAccountId(), request.destinationAccountId());
        Account source = locked.get(request.sourceAccountId());
        Account destination = locked.get(request.destinationAccountId());
        requireCurrency(source, request.currency());
        requireCurrency(destination, request.currency());

        LedgerTransaction candidate = LedgerTransaction.newPayment(idempotencyKey, fingerprint,
                source.getId(), destination.getId(), amount, request.currency(), request.description());
        if (!transactions.insertIfAbsent(candidate)) {
            // Lost the race: a concurrent request with the same key committed first.
            return replay(loadByKey(idempotencyKey), fingerprint);
        }
        LedgerTransaction payment = transactions.findById(candidate.getId()).orElseThrow();
        return post(payment, source, destination);
    }

    @Transactional
    public TransactionResult refund(String idempotencyKey, UUID originalId) {
        String fingerprint = RequestFingerprint.of(TransactionType.REFUND, originalId);

        var existing = transactions.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), fingerprint);
        }

        // Serializes all refund attempts for the same payment.
        LedgerTransaction original = transactions.findByIdForUpdate(originalId)
                .orElseThrow(() -> transactionNotFound(originalId));

        // Re-check after acquiring the lock: a concurrent request with the same key may have just committed.
        existing = transactions.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), fingerprint);
        }

        if (original.getType() != TransactionType.PAYMENT) {
            throw ApiException.unprocessable("TRANSACTION_NOT_REFUNDABLE", "Refund transactions cannot be refunded");
        }
        if (original.getStatus() == TransactionStatus.REFUNDED) {
            throw ApiException.conflict("TRANSACTION_ALREADY_REFUNDED",
                    "Transaction " + originalId + " has already been refunded");
        }
        if (!original.isRefundable()) {
            throw ApiException.conflict("TRANSACTION_NOT_REFUNDABLE",
                    "Only POSTED payments can be refunded; transaction is " + original.getStatus());
        }

        Map<UUID, Account> locked = lockAccounts(original.getSourceAccountId(), original.getDestinationAccountId());
        LedgerTransaction candidate = LedgerTransaction.newRefund(idempotencyKey, fingerprint, original);
        if (!transactions.insertIfAbsent(candidate)) {
            return replay(loadByKey(idempotencyKey), fingerprint);
        }
        LedgerTransaction refund = transactions.findById(candidate.getId()).orElseThrow();
        TransactionResult result = post(refund,
                locked.get(refund.getSourceAccountId()), locked.get(refund.getDestinationAccountId()));
        if (refund.getStatus() == TransactionStatus.POSTED) {
            original.markRefunded();
        }
        return result;
    }

    @Transactional(readOnly = true)
    public TransactionResult get(UUID id) {
        LedgerTransaction transaction = transactions.findById(id).orElseThrow(() -> transactionNotFound(id));
        return new TransactionResult(transaction, entries.findByTransactionIdOrderByDirectionDesc(id), false);
    }

    /** Writes the balanced debit/credit pair, or marks the transaction FAILED if funds are insufficient. */
    private TransactionResult post(LedgerTransaction transaction, Account from, Account to) {
        if (!from.canDebit(transaction.getAmount())) {
            transaction.markFailed(INSUFFICIENT_FUNDS);
            return new TransactionResult(transaction, List.of(), false);
        }
        List<LedgerEntry> posted = entries.saveAll(LedgerEntry.transfer(
                transaction.getId(), from, to, transaction.getAmount(), transaction.getCurrency()));
        transaction.markPosted();
        return new TransactionResult(transaction, posted, false);
    }

    private TransactionResult replay(LedgerTransaction existing, String fingerprint) {
        if (!existing.getRequestHash().equals(fingerprint)) {
            throw ApiException.unprocessable("IDEMPOTENCY_KEY_REUSED",
                    "Idempotency-Key was already used for a different request");
        }
        return new TransactionResult(existing,
                entries.findByTransactionIdOrderByDirectionDesc(existing.getId()), true);
    }

    private Map<UUID, Account> lockAccounts(UUID first, UUID second) {
        Map<UUID, Account> locked = accounts.findAllByIdForUpdate(Set.of(first, second)).stream()
                .collect(Collectors.toMap(Account::getId, Function.identity()));
        for (UUID id : List.of(first, second)) {
            if (!locked.containsKey(id)) {
                throw ApiException.notFound("ACCOUNT_NOT_FOUND", "Account " + id + " not found");
            }
        }
        return locked;
    }

    private static void requireCurrency(Account account, String currency) {
        if (!account.getCurrency().equals(currency)) {
            throw ApiException.unprocessable("CURRENCY_MISMATCH",
                    "Account " + account.getId() + " is denominated in " + account.getCurrency() + ", not " + currency);
        }
    }

    private LedgerTransaction loadByKey(String idempotencyKey) {
        return transactions.findByIdempotencyKey(idempotencyKey)
                .orElseThrow(() -> new IllegalStateException("Idempotency key conflict but no row found"));
    }

    private static ApiException transactionNotFound(UUID id) {
        return ApiException.notFound("TRANSACTION_NOT_FOUND", "Transaction " + id + " not found");
    }
}
