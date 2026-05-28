package co.tumipay.orchestrator.infrastructure.outbound.provider.mock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import co.tumipay.orchestrator.domain.exception.PaymentProviderException;
import co.tumipay.orchestrator.domain.model.Transaction;
import co.tumipay.orchestrator.domain.model.TransactionStatus;

@DisplayName("MockPaymentProviderAdapter Unit Tests")
class MockPaymentProviderAdapterTest {

    private MockPaymentProviderAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new MockPaymentProviderAdapter();
    }

    @Test
    @DisplayName("getProviderId returns MOCK")
    void getProviderId_returnsMock() {
        assertThat(adapter.getProviderId()).isEqualTo("MOCK");
    }

    @Test
    @DisplayName("supports returns true for all configured payment methods")
    void supports_returnsTrueForConfiguredMethods() {
        assertThat(adapter.supports("PSE")).isTrue();
        assertThat(adapter.supports("CARD_VISA")).isTrue();
        assertThat(adapter.supports("CARD_MC")).isTrue();
        assertThat(adapter.supports("NEQUI")).isTrue();
        assertThat(adapter.supports("DAVIPLATA")).isTrue();
        assertThat(adapter.supports("EFECTY")).isTrue();
        assertThat(adapter.supports("BRE_B")).isTrue();
    }

    @Test
    @DisplayName("supports is case-insensitive")
    void supports_isCaseInsensitive() {
        assertThat(adapter.supports("pse")).isTrue();
        assertThat(adapter.supports("Nequi")).isTrue();
    }

    @Test
    @DisplayName("supports returns false for unknown payment method")
    void supports_returnsFalseForUnknownMethod() {
        assertThat(adapter.supports("UNKNOWN_BANK")).isFalse();
        assertThat(adapter.supports("WIRE")).isFalse();
    }

    @Test
    @DisplayName("supports returns false for null payment method")
    void supports_returnsFalseForNull() {
        assertThat(adapter.supports(null)).isFalse();
    }

    @Test
    @DisplayName("process returns transaction with PROCESSING status")
    void process_returnsTransactionWithProcessingStatus() {
        Transaction transaction = buildTransaction();
        Transaction result = adapter.process(transaction);
        assertThat(result.getStatus()).isEqualTo(TransactionStatus.PROCESSING);
        assertThat(result.getId()).isEqualTo(transaction.getId());
    }

    @Test
    @DisplayName("processWithFallback throws PaymentProviderException — circuit breaker open")
    void processWithFallback_throwsPaymentProviderException() {
        Transaction transaction = buildTransaction();
        RuntimeException cause = new RuntimeException("connection timeout");
        assertThatThrownBy(() -> adapter.processWithFallback(transaction, cause))
                .isInstanceOf(PaymentProviderException.class)
                .hasMessageContaining("MOCK")
                .hasMessageContaining("temporarily unavailable");
    }

    @Test
    @DisplayName("processWithRetryFallback throws PaymentProviderException — all retries exhausted")
    void processWithRetryFallback_throwsPaymentProviderException() {
        Transaction transaction = buildTransaction();
        RuntimeException cause = new RuntimeException("read timeout");
        assertThatThrownBy(() -> adapter.processWithRetryFallback(transaction, cause))
                .isInstanceOf(PaymentProviderException.class)
                .hasMessageContaining("MOCK")
                .hasMessageContaining("maximum retry attempts");
    }

    private Transaction buildTransaction() {
        return Transaction.createNew(
                "ORDER-001", 150_000L, "COP", "CO", "PSE",
                "https://webhook.example.com", "https://redirect.example.com",
                "Test transaction", null, null);
    }
}
