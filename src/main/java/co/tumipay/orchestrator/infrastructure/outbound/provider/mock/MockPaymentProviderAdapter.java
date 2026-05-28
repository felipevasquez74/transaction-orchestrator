package co.tumipay.orchestrator.infrastructure.outbound.provider.mock;

import java.util.Set;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import co.tumipay.orchestrator.domain.exception.PaymentProviderException;
import co.tumipay.orchestrator.domain.model.Transaction;
import co.tumipay.orchestrator.domain.model.TransactionStatus;
import co.tumipay.orchestrator.domain.port.out.PaymentProviderPort;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@ConditionalOnProperty(
        prefix = "tumipay.orchestrator.providers.mock",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class MockPaymentProviderAdapter implements PaymentProviderPort {

    private static final String PROVIDER_ID = "MOCK";

    private static final Set<String> SUPPORTED_METHODS = Set.of(
            "PSE", "CARD_VISA", "CARD_MC", "NEQUI", "DAVIPLATA", "EFECTY", "BRE_B"
    );

    @Override
    public String getProviderId() {
        return PROVIDER_ID;
    }

    @Override
    public boolean supports(String paymentMethodId) {
        return paymentMethodId != null
                && SUPPORTED_METHODS.contains(paymentMethodId.toUpperCase());
    }

    @Override
    @Retry(name = "payment-provider", fallbackMethod = "processWithRetryFallback")
    @CircuitBreaker(name = "payment-provider", fallbackMethod = "processWithFallback")
    public Transaction process(Transaction transaction) {
        log.info("[{}] Processing transaction. id={} method={} amount={} {}",
                PROVIDER_ID,
                transaction.getId(),
                transaction.getPaymentMethodId(),
                transaction.getAmount(),
                transaction.getCurrencyCode());

        simulateProviderCall(transaction);

        log.info("[{}] Transaction accepted by provider. id={}", PROVIDER_ID, transaction.getId());

        return transaction.withStatus(TransactionStatus.PROCESSING);
    }

    @SuppressWarnings("unused")
    public Transaction processWithFallback(Transaction transaction, Throwable cause) {
        log.error("[{}] Circuit Breaker OPEN — provider unavailable. id={} cause={}",
                PROVIDER_ID, transaction.getId(), cause.getMessage());
        throw new PaymentProviderException(PROVIDER_ID,
                "Payment provider temporarily unavailable. Please retry later.", cause);
    }

    @SuppressWarnings("unused")
    public Transaction processWithRetryFallback(Transaction transaction, Throwable cause) {
        log.error("[{}] All retry attempts exhausted. id={} cause={}",
                PROVIDER_ID, transaction.getId(), cause.getMessage());
        throw new PaymentProviderException(PROVIDER_ID,
                "Payment provider failed after maximum retry attempts.", cause);
    }

    private void simulateProviderCall(Transaction transaction) {
        log.debug("[{}] Simulating HTTP POST to provider gateway. transaction_id={}",
                PROVIDER_ID, transaction.getId());
        try {
            Thread.sleep(50 + (long)(Math.random() * 100));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PaymentProviderException(PROVIDER_ID, "Provider call interrupted");
        }
    }
}
