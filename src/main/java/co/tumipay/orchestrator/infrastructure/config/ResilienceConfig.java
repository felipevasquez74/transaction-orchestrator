package co.tumipay.orchestrator.infrastructure.config;

import java.io.IOException;
import java.net.ConnectException;
import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Configuration
public class ResilienceConfig {

    public static final String PAYMENT_PROVIDER = "payment-provider";

    @Bean
    RetryRegistry retryRegistry() {

        RetryConfig config = RetryConfig.custom()
                .maxAttempts(3)
                .intervalFunction(
                        io.github.resilience4j.core.IntervalFunction
                                .ofExponentialBackoff(
                                        Duration.ofSeconds(1),
                                        2.0
                                )
                )
                .retryExceptions(IOException.class, ConnectException.class)
                .ignoreExceptions(
                        co.tumipay.orchestrator.domain.exception.ValidationException.class,
                        co.tumipay.orchestrator.domain.exception.DuplicateTransactionException.class,
                        co.tumipay.orchestrator.domain.exception.PaymentMethodNotFoundException.class
                )
                .build();

        return RetryRegistry.of(config);
    }

    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(50)                         // Open at 50% failure rate
                .slowCallRateThreshold(80)                        // Treat >5s calls as failures
                .slowCallDurationThreshold(Duration.ofSeconds(5))
                .waitDurationInOpenState(Duration.ofSeconds(30))  // 30s before retrying
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(20)                            // Last 20 calls
                .minimumNumberOfCalls(5)                          // Min calls before evaluating
                .permittedNumberOfCallsInHalfOpenState(3)
                .recordExceptions(IOException.class, ConnectException.class,
                        co.tumipay.orchestrator.domain.exception.PaymentProviderException.class)
                .build();

        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(config);

        registry.circuitBreaker(PAYMENT_PROVIDER).getEventPublisher()
                .onStateTransition(event -> log.warn(
                        "Circuit Breaker state changed. name={} from={} to={}",
                        event.getCircuitBreakerName(),
                        event.getStateTransition().getFromState(),
                        event.getStateTransition().getToState()))
                .onError(event -> log.error(
                        "Circuit Breaker recorded error. name={} duration={}ms error={}",
                        event.getCircuitBreakerName(),
                        event.getElapsedDuration().toMillis(),
                        event.getThrowable().getMessage()));

        return registry;
    }

}
