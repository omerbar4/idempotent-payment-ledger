package io.github.omerbar4.paymentledger.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "accounts")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "allow_negative_balance", nullable = false)
    private boolean allowNegativeBalance;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal balance;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Account() {
    }

    public Account(String name, String currency, boolean allowNegativeBalance) {
        this.name = name;
        this.currency = currency;
        this.allowNegativeBalance = allowNegativeBalance;
        this.balance = Money.normalize(BigDecimal.ZERO);
        this.createdAt = Instant.now();
    }

    public boolean canDebit(BigDecimal amount) {
        return allowNegativeBalance || balance.compareTo(amount) >= 0;
    }

    void debit(BigDecimal amount) {
        balance = balance.subtract(amount);
    }

    void credit(BigDecimal amount) {
        balance = balance.add(amount);
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getCurrency() {
        return currency;
    }

    public boolean isAllowNegativeBalance() {
        return allowNegativeBalance;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
