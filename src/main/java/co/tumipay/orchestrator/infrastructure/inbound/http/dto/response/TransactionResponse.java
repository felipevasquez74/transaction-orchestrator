package co.tumipay.orchestrator.infrastructure.inbound.http.dto.response;

import java.time.OffsetDateTime;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@Schema(description = "Transaction details in the API response")
public class TransactionResponse {

    @Schema(description = "Unique transaction ID assigned by the orchestrator",
            example = "550e8400-e29b-41d4-a716-446655440000")
    @JsonProperty("transaction_id")
    private String transactionId;

    @Schema(description = "Transaction processing timestamp (ISO 8601)",
            example = "2024-11-15T10:30:00Z")
    @JsonProperty("processed_at")
    private OffsetDateTime processedAt;

    @Schema(description = "Client system transaction identifier",
            example = "ORDER-2024-001")
    @JsonProperty("client_transaction_id")
    private String clientTransactionId;

    @Schema(description = "Payment method used", example = "PSE")
    @JsonProperty("payment_method_id")
    private String paymentMethodId;

    @Schema(description = "ISO 4217 currency code", example = "COP")
    @JsonProperty("currency_code")
    private String currencyCode;

    @Schema(description = "ISO 3166-1 Alpha-2 country code", example = "CO")
    @JsonProperty("country_code")
    private String countryCode;

    @Schema(description = "Transaction description", example = "Purchase order #001")
    @JsonProperty("description")
    private String description;

    @Schema(description = "Current transaction status", example = "PROCESSING")
    @JsonProperty("status")
    private String status;
}
