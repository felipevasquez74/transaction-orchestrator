package co.tumipay.orchestrator.infrastructure.outbound.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("IdempotencyService Unit Tests")
class IdempotencyServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    private IdempotencyService idempotencyService;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        idempotencyService = new IdempotencyService(redisTemplate);
        ReflectionTestUtils.setField(idempotencyService, "ttlHours", 24L);
    }

    @Test
    @DisplayName("tryAcquireLock returns true when Redis SET NX succeeds")
    void tryAcquireLock_returnsTrueWhenAcquired() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        assertThat(idempotencyService.tryAcquireLock("TXN-001", "uuid-123")).isTrue();
        verify(valueOps).setIfAbsent(eq("idempotency:TXN-001"), eq("uuid-123:PROCESSING"), any(Duration.class));
    }

    @Test
    @DisplayName("tryAcquireLock returns false when key already exists")
    void tryAcquireLock_returnsFalseWhenKeyExists() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        assertThat(idempotencyService.tryAcquireLock("TXN-001", "uuid-123")).isFalse();
    }

    @Test
    @DisplayName("tryAcquireLock returns false when Redis returns null (treats as already acquired)")
    void tryAcquireLock_returnsFalseWhenRedisReturnsNull() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(null);
        assertThat(idempotencyService.tryAcquireLock("TXN-001", "uuid-123")).isFalse();
    }

    @Test
    @DisplayName("findTransactionId returns empty Optional when key not in Redis")
    void findTransactionId_returnsEmpty_whenKeyNotFound() {
        when(valueOps.get(anyString())).thenReturn(null);
        assertThat(idempotencyService.findTransactionId("TXN-001")).isEmpty();
    }

    @Test
    @DisplayName("findTransactionId extracts UUID from 'uuid:STATUS' value")
    void findTransactionId_returnsUuid_fromCompositeValue() {
        when(valueOps.get("idempotency:TXN-001")).thenReturn("uuid-123:PROCESSING");
        assertThat(idempotencyService.findTransactionId("TXN-001")).contains("uuid-123");
    }

    @Test
    @DisplayName("updateStatus writes 'uuid:STATUS' composite value with TTL")
    void updateStatus_writesCompositeValue() {
        idempotencyService.updateStatus("TXN-001", "uuid-123", "COMPLETED");
        verify(valueOps).set(
                eq("idempotency:TXN-001"),
                eq("uuid-123:COMPLETED"),
                any(Duration.class));
    }

    @Test
    @DisplayName("isAlreadyProcessed returns true when key exists in Redis")
    void isAlreadyProcessed_returnsTrueWhenKeyExists() {
        when(redisTemplate.hasKey("idempotency:TXN-001")).thenReturn(true);
        assertThat(idempotencyService.isAlreadyProcessed("TXN-001")).isTrue();
    }

    @Test
    @DisplayName("isAlreadyProcessed returns false when Redis returns null")
    void isAlreadyProcessed_returnsFalseWhenRedisReturnsNull() {
        when(redisTemplate.hasKey(anyString())).thenReturn(null);
        assertThat(idempotencyService.isAlreadyProcessed("TXN-001")).isFalse();
    }

    @Test
    @DisplayName("isAlreadyProcessed returns false when key does not exist")
    void isAlreadyProcessed_returnsFalseWhenKeyMissing() {
        when(redisTemplate.hasKey(anyString())).thenReturn(false);
        assertThat(idempotencyService.isAlreadyProcessed("TXN-999")).isFalse();
    }
}
