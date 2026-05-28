package co.tumipay.orchestrator.domain.model;

import java.time.OffsetDateTime;
import java.util.UUID;

import lombok.Builder;
import lombok.Getter;
import lombok.With;

@Getter
@Builder
@With
public class Transaction {

    private final UUID id;

    private final String clientTransactionId;

    private final Long amount;

    private final String currencyCode;

    private final String countryCode;

    private final String paymentMethodId;

    private final String webhookUrl;

    private final String redirectUrl;

    private final String description;

    private final OffsetDateTime expirationTime;

    private final TransactionStatus status;

    private final Customer customer;

    private final OffsetDateTime processedAt;

    private final OffsetDateTime createdAt;
    
    private final OffsetDateTime updatedAt;

    public boolean isExpired() {
        return expirationTime != null && OffsetDateTime.now().isAfter(expirationTime);
    }

    public static Transaction createNew(
            String clientTransactionId,
            Long amount,
            String currencyCode,
            String countryCode,
            String paymentMethodId,
            String webhookUrl,
            String redirectUrl,
            String description,
            OffsetDateTime expirationTime,
            Customer customer) {

        return Transaction.builder()
                .id(UUID.randomUUID())
                .clientTransactionId(clientTransactionId)
                .amount(amount)
                .currencyCode(currencyCode)
                .countryCode(countryCode)
                .paymentMethodId(paymentMethodId)
                .webhookUrl(webhookUrl)
                .redirectUrl(redirectUrl)
                .description(description)
                .expirationTime(expirationTime)
                .status(TransactionStatus.PENDING)
                .customer(customer)
                .processedAt(OffsetDateTime.now())
                .createdAt(OffsetDateTime.now())
                .build();
    }
}
