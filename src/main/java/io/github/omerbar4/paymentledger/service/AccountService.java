package io.github.omerbar4.paymentledger.service;

import io.github.omerbar4.paymentledger.domain.Account;
import io.github.omerbar4.paymentledger.dto.CreateAccountRequest;
import io.github.omerbar4.paymentledger.exception.ApiException;
import io.github.omerbar4.paymentledger.repository.AccountRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {

    private final AccountRepository accounts;

    public AccountService(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @Transactional
    public Account create(CreateAccountRequest request) {
        return accounts.save(new Account(request.name(), request.currency(),
                Boolean.TRUE.equals(request.allowNegativeBalance())));
    }

    @Transactional(readOnly = true)
    public Account get(UUID id) {
        return accounts.findById(id)
                .orElseThrow(() -> ApiException.notFound("ACCOUNT_NOT_FOUND", "Account " + id + " not found"));
    }
}
