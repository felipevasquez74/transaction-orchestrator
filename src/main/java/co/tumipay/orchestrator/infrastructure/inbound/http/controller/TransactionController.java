package co.tumipay.orchestrator.infrastructure.inbound.http.controller;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import co.tumipay.orchestrator.domain.port.in.CreateTransactionUseCase;
import co.tumipay.orchestrator.domain.port.in.GetTransactionUseCase;
import co.tumipay.orchestrator.infrastructure.inbound.http.dto.request.CreateTransactionRequest;
import co.tumipay.orchestrator.infrastructure.inbound.http.dto.response.ApiResponse;
import co.tumipay.orchestrator.infrastructure.inbound.http.dto.response.TransactionResponse;
import co.tumipay.orchestrator.infrastructure.inbound.http.mapper.TransactionHttpMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping(value = "/api/v1/transactions", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Transactions", description = "Transaction Orchestration API")
public class TransactionController {

    private final CreateTransactionUseCase createTransactionUseCase;
    private final GetTransactionUseCase getTransactionUseCase;
    private final TransactionHttpMapper mapper;

    @Operation(
            summary = "Create and process a new transaction",
            description = "Receives a transaction request, validates it, persists it, " +
                    "and routes it to the appropriate payment provider adapter."
    )
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "201",
                    description = "Transaction created and processing initiated",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "422",
                    description = "Validation error - required fields missing or invalid format"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "Duplicate client_transaction_id"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "Payment method not supported")
    })
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<TransactionResponse>> createTransaction(
            @Valid @RequestBody CreateTransactionRequest request) {

        log.info("Received transaction request. client_transaction_id={}",
                request.getClientTransactionId());

        TransactionResponse response = mapper.toResponse(
                createTransactionUseCase.execute(mapper.toDomain(request)));

        log.info("Transaction created successfully. transaction_id={}", response.getTransactionId());

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success(response));
    }

    @Operation(
            summary = "Get transaction by ID",
            description = "Retrieves complete transaction information by the orchestrator-assigned UUID."
    )
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Transaction found",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "Transaction not found"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "Invalid UUID format")
    })
    @GetMapping("/{transaction_id}")
    public ResponseEntity<ApiResponse<TransactionResponse>> getTransaction(
            @Parameter(description = "Orchestrator-assigned UUID", required = true)
            @PathVariable("transaction_id") UUID transactionId) {

        log.debug("Querying transaction. transaction_id={}", transactionId);
        return ResponseEntity.ok(ApiResponse.success(
                mapper.toResponse(getTransactionUseCase.execute(transactionId))));
    }
}
