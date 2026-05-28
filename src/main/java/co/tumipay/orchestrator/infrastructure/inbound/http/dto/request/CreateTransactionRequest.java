package co.tumipay.orchestrator.infrastructure.inbound.http.dto.request;

import java.time.OffsetDateTime;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Schema(description = "Request body for creating a new payment transaction")
public class CreateTransactionRequest {

    @NotBlank(message = "client_transaction_id is required")
    @Size(max = 100, message = "client_transaction_id must not exceed 100 characters")
    @Schema(description = "Unique identifier in the client system (idempotency key)",
            example = "ORDER-2024-001", required = true)
    @JsonProperty("client_transaction_id")
    private String clientTransactionId;

    @NotNull(message = "amount is required")
    @Positive(message = "amount must be a positive number of cents")
    @Schema(description = "Transaction amount in cents, no decimal separator. E.g., 1000 = $10.00",
            example = "150000", required = true)
    @JsonProperty("amount")
    private Long amount;

    @NotBlank(message = "currency_code is required")
    @Pattern(regexp = "^[A-Z]{3}$", message = "currency_code must be a 3-letter ISO 4217 code (uppercase)")
    @Schema(description = "Currency code following ISO 4217", example = "COP", required = true)
    @JsonProperty("currency_code")
    private String currencyCode;

    @NotBlank(message = "country_code is required")
    @Pattern(regexp = "^[A-Z]{2}$", message = "country_code must be a 2-letter ISO 3166-1 Alpha-2 code (uppercase)")
    @Schema(description = "Country code following ISO 3166-1 Alpha-2", example = "CO", required = true)
    @JsonProperty("country_code")
    private String countryCode;

    @NotBlank(message = "payment_method_id is required")
    @Size(max = 50, message = "payment_method_id must not exceed 50 characters")
    @Schema(description = "Payment method identifier", example = "PSE", required = true)
    @JsonProperty("payment_method_id")
    private String paymentMethodId;

    @NotBlank(message = "webhook_url is required")
    @Size(max = 2048, message = "webhook_url must not exceed 2048 characters")
    @Schema(description = "HTTPS URL for transaction status webhook notifications",
            example = "https://api.yourcompany.com/webhooks/payments", required = true)
    @JsonProperty("webhook_url")
    private String webhookUrl;

    @NotBlank(message = "redirect_url is required")
    @Size(max = 2048, message = "redirect_url must not exceed 2048 characters")
    @Schema(description = "URL to redirect the customer after completing payment",
            example = "https://yourcompany.com/payment/result", required = true)
    @JsonProperty("redirect_url")
    private String redirectUrl;

    @Size(max = 500, message = "description must not exceed 500 characters")
    @Schema(description = "Optional human-readable description of the transaction",
            example = "Purchase order #ORDER-2024-001")
    @JsonProperty("description")
    private String description;

    @Schema(description = "Optional expiration timestamp for the payment link (ISO 8601)",
            example = "2024-12-31T23:59:59Z")
    @JsonProperty("expiration_time")
    private OffsetDateTime expirationTime;

    @NotNull(message = "customer is required")
    @Valid
    @Schema(description = "Customer information", required = true)
    @JsonProperty("customer")
    private CustomerRequest customer;
}
