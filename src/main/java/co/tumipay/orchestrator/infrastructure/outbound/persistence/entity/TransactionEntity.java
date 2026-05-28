package co.tumipay.orchestrator.infrastructure.outbound.persistence.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "transactions")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransactionEntity {

    @Id
    @Column(name = "id", columnDefinition = "CHAR(36)", nullable = false, updatable = false)
    private String id;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "client_transaction_id", length = 100, nullable = false, unique = true)
    private String clientTransactionId;

    @Column(name = "amount", nullable = false)
    private Long amount;

    @Column(name = "currency_code", columnDefinition = "CHAR(3)", nullable = false)
    private String currencyCode;

    @Column(name = "country_code", columnDefinition = "CHAR(2)", nullable = false)
    private String countryCode;

    @Column(name = "payment_method_id", length = 50, nullable = false)
    private String paymentMethodId;

    @Column(name = "webhook_url", length = 2048, nullable = false)
    private String webhookUrl;

    @Column(name = "redirect_url", length = 2048, nullable = false)
    private String redirectUrl;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "expiration_time")
    private OffsetDateTime expirationTime;

    @Column(name = "status_code", length = 20, nullable = false)
    private String statusCode;

    @OneToOne(cascade = CascadeType.ALL, fetch = FetchType.EAGER)
    @JoinColumn(name = "customer_id", nullable = false)
    private CustomerEntity customer;

    @Column(name = "processed_at", nullable = false)
    private OffsetDateTime processedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) id = UUID.randomUUID().toString();
        createdAt = OffsetDateTime.now();
        if (statusCode == null) statusCode = "PENDING";
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
