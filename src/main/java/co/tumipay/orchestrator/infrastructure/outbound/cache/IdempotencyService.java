package co.tumipay.orchestrator.infrastructure.outbound.cache;

import java.time.Duration;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private static final String KEY_PREFIX = "idempotency:";

    private final StringRedisTemplate redisTemplate;

    @Value("${tumipay.orchestrator.idempotency.ttl-hours:24}")
    private long ttlHours;

    public boolean tryAcquireLock(String clientTransactionId, String transactionId) {
        String key = buildKey(clientTransactionId);
        String value = transactionId + ":PROCESSING";

        // SET key value NX EX ttl — atomic, no race condition possible
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(key, value, Duration.ofHours(ttlHours));

        boolean result = Boolean.TRUE.equals(acquired);
        log.debug("Idempotency lock {}. key={}", result ? "ACQUIRED" : "ALREADY_EXISTS", key);
        return result;
    }

    public void updateStatus(String clientTransactionId, String transactionId, String status) {
        String key = buildKey(clientTransactionId);
        String value = transactionId + ":" + status;
        redisTemplate.opsForValue().set(key, value, Duration.ofHours(ttlHours));
        log.debug("Idempotency key updated. key={} value={}", key, value);
    }

    public Optional<String> findTransactionId(String clientTransactionId) {
        String value = redisTemplate.opsForValue().get(buildKey(clientTransactionId));
        if (value == null) return Optional.empty();
        return Optional.of(value.split(":")[0]);
    }

    public boolean isAlreadyProcessed(String clientTransactionId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(buildKey(clientTransactionId)));
    }

    private String buildKey(String clientTransactionId) {
        return KEY_PREFIX + clientTransactionId;
    }
}
