package io.github.omerbar4.paymentledger.controller;

import io.github.omerbar4.paymentledger.dto.AccountResponse;
import io.github.omerbar4.paymentledger.dto.CreateAccountRequest;
import io.github.omerbar4.paymentledger.service.AccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/accounts")
@Tag(name = "Accounts", description = "Ledger accounts and their balances")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping
    @Operation(summary = "Create an account")
    public ResponseEntity<AccountResponse> create(@Valid @RequestBody CreateAccountRequest request) {
        AccountResponse body = AccountResponse.from(accountService.create(request));
        return ResponseEntity.created(URI.create("/accounts/" + body.id())).body(body);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get an account and its current balance")
    public AccountResponse get(@PathVariable UUID id) {
        return AccountResponse.from(accountService.get(id));
    }
}
