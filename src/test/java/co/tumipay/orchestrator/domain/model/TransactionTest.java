package co.tumipay.orchestrator.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Transaction Domain Model Tests")
class TransactionTest {

    @Test
    @DisplayName("createNew sets id, PENDING status, and timestamps")
    void createNew_setsRequiredFields() {
        Transaction t = buildValid(null);
        assertThat(t.getId()).isNotNull();
        assertThat(t.getStatus()).isEqualTo(TransactionStatus.PENDING);
        assertThat(t.getCreatedAt()).isNotNull();
        assertThat(t.getProcessedAt()).isNotNull();
        assertThat(t.getClientTransactionId()).isEqualTo("TXN-001");
        assertThat(t.getAmount()).isEqualTo(100_000L);
        assertThat(t.getCurrencyCode()).isEqualTo("COP");
        assertThat(t.getCountryCode()).isEqualTo("CO");
        assertThat(t.getPaymentMethodId()).isEqualTo("PSE");
        assertThat(t.getWebhookUrl()).isEqualTo("https://webhook.example.com");
        assertThat(t.getRedirectUrl()).isEqualTo("https://redirect.example.com");
        assertThat(t.getDescription()).isEqualTo("Test description");
    }

    @Test
    @DisplayName("createNew generates unique IDs for each call")
    void createNew_generatesUniqueIds() {
        Transaction t1 = buildValid(null);
        Transaction t2 = buildValid(null);
        assertThat(t1.getId()).isNotEqualTo(t2.getId());
    }

    @Test
    @DisplayName("isExpired returns false when expirationTime is null")
    void isExpired_returnsFalse_whenNull() {
        assertThat(buildValid(null).isExpired()).isFalse();
    }

    @Test
    @DisplayName("isExpired returns true when expirationTime is in the past")
    void isExpired_returnsTrue_whenExpired() {
        Transaction t = Transaction.builder()
                .expirationTime(OffsetDateTime.now().minusHours(1)).build();
        assertThat(t.isExpired()).isTrue();
    }

    @Test
    @DisplayName("isExpired returns false when expirationTime is in the future")
    void isExpired_returnsFalse_whenNotYetExpired() {
        Transaction t = Transaction.builder()
                .expirationTime(OffsetDateTime.now().plusHours(1)).build();
        assertThat(t.isExpired()).isFalse();
    }

    @Test
    @DisplayName("withStatus produces a new immutable instance with the updated status")
    void withStatus_returnsNewInstanceWithUpdatedStatus() {
        Transaction original = buildValid(null);
        Transaction updated = original.withStatus(TransactionStatus.PROCESSING);
        assertThat(updated.getStatus()).isEqualTo(TransactionStatus.PROCESSING);
        assertThat(original.getStatus()).isEqualTo(TransactionStatus.PENDING);
        assertThat(updated.getId()).isEqualTo(original.getId());
        assertThat(updated.getAmount()).isEqualTo(original.getAmount());
    }

    @Test
    @DisplayName("withCustomer produces a new instance with the updated customer")
    void withCustomer_returnsNewInstanceWithUpdatedCustomer() {
        Transaction original = buildValid(null);
        Customer newCustomer = Customer.builder().firstName("Ana").lastName("García").build();
        Transaction updated = original.withCustomer(newCustomer);
        assertThat(updated.getCustomer()).isEqualTo(newCustomer);
        assertThat(original.getCustomer()).isNull();
    }

    private Transaction buildValid(OffsetDateTime expiration) {
        return Transaction.createNew(
                "TXN-001", 100_000L, "COP", "CO", "PSE",
                "https://webhook.example.com", "https://redirect.example.com",
                "Test description", expiration, null);
    }
}
