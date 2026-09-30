package io.github.omerbar4.paymentledger.controller;

import io.github.omerbar4.paymentledger.dto.CreateTransactionRequest;
import io.github.omerbar4.paymentledger.dto.TransactionResponse;
import io.github.omerbar4.paymentledger.service.TransactionResult;
import io.github.omerbar4.paymentledger.service.TransactionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/transactions")
@Tag(name = "Transactions", description = "Idempotent payments and refunds")
public class TransactionController {

    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    public static final String IDEMPOTENT_REPLAYED = "Idempotent-Replayed";

    private final TransactionService transactionService;

    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @PostMapping
    @Operation(summary = "Create a payment",
            description = "Moves money between two accounts, writing a balanced DEBIT/CREDIT pair. "
                    + "Retrying with the same Idempotency-Key and body returns the original transaction.")
    @ApiResponse(responseCode = "201", description = "Transaction created (status POSTED or FAILED)")
    @ApiResponse(responseCode = "200", description = "Replay: the key was already used for this request")
    @ApiResponse(responseCode = "422", description = "Key reused with a different body, or currency mismatch")
    public ResponseEntity<TransactionResponse> create(
            @Parameter(description = "Client-generated unique key, e.g. a UUID", required = true)
            @RequestHeader(IDEMPOTENCY_KEY) @NotBlank @Size(max = 255) String idempotencyKey,
            @Valid @RequestBody CreateTransactionRequest request) {
        return respond(transactionService.createPayment(idempotencyKey, request));
    }

    @PostMapping("/{id}/refund")
    @Operation(summary = "Refund a payment in full",
            description = "Creates a REFUND transaction with reversing entries and marks the payment REFUNDED.")
    @ApiResponse(responseCode = "201", description = "Refund created")
    @ApiResponse(responseCode = "200", description = "Replay: the key was already used for this refund")
    @ApiResponse(responseCode = "409", description = "Payment already refunded or not refundable")
    public ResponseEntity<TransactionResponse> refund(
            @PathVariable UUID id,
            @Parameter(description = "Client-generated unique key, e.g. a UUID", required = true)
            @RequestHeader(IDEMPOTENCY_KEY) @NotBlank @Size(max = 255) String idempotencyKey) {
        return respond(transactionService.refund(idempotencyKey, id));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a transaction and its ledger entries")
    public TransactionResponse get(@PathVariable UUID id) {
        TransactionResult result = transactionService.get(id);
        return TransactionResponse.from(result.transaction(), result.entries());
    }

    private static ResponseEntity<TransactionResponse> respond(TransactionResult result) {
        TransactionResponse body = TransactionResponse.from(result.transaction(), result.entries());
        if (result.replayed()) {
            return ResponseEntity.status(HttpStatus.OK).header(IDEMPOTENT_REPLAYED, "true").body(body);
        }
        return ResponseEntity.created(URI.create("/transactions/" + body.id()))
                .header(IDEMPOTENT_REPLAYED, "false")
                .body(body);
    }
}
