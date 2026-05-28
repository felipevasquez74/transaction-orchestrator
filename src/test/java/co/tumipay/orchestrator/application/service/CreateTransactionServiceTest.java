package co.tumipay.orchestrator.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import co.tumipay.orchestrator.application.validator.TransactionDomainValidator;
import co.tumipay.orchestrator.domain.exception.DuplicateTransactionException;
import co.tumipay.orchestrator.domain.model.Customer;
import co.tumipay.orchestrator.domain.model.Transaction;
import co.tumipay.orchestrator.domain.model.TransactionStatus;
import co.tumipay.orchestrator.domain.port.out.PaymentProviderPort;
import co.tumipay.orchestrator.domain.port.out.TransactionRepositoryPort;
import co.tumipay.orchestrator.infrastructure.outbound.cache.IdempotencyService;

@ExtendWith(MockitoExtension.class)
@DisplayName("CreateTransactionService Unit Tests")
class CreateTransactionServiceTest {

    @Mock
    private TransactionRepositoryPort transactionRepository;

    @Mock
    private TransactionDomainValidator validator;

    @Mock
    private PaymentProviderPort mockProvider;

    @Mock
    private IdempotencyService idempotencyService;

    private CreateTransactionService createTransactionService;

    private Transaction sampleTransaction;

    @BeforeEach
    void setUp() {
        createTransactionService = new CreateTransactionService(
                transactionRepository,
                validator,
                List.of(mockProvider),
                idempotencyService
        );

        Customer customer = Customer.builder()
                .firstName("Juan")
                .lastName("Pérez")
                .email("juan.perez@example.com")
                .documentType("CC")
                .documentNumber("1234567890")
                .build();

        sampleTransaction = Transaction.createNew(
                "ORDER-2024-001",
                150000L,
                "COP",
                "CO",
                "PSE",
                "https://api.mycompany.com/webhooks",
                "https://mycompany.com/payment/result",
                "Test transaction",
                null,
                customer
        );
    }

    @Test
    @DisplayName("Should create transaction successfully when all data is valid")
    void shouldCreateTransactionSuccessfully() {
        // Given
        doNothing().when(validator).validate(any(Transaction.class));
        when(idempotencyService.tryAcquireLock(anyString(), anyString())).thenReturn(true);
        when(transactionRepository.existsByClientTransactionId(anyString())).thenReturn(false);
        when(transactionRepository.save(any(Transaction.class))).thenReturn(sampleTransaction);
        when(mockProvider.supports("PSE")).thenReturn(true);
        when(mockProvider.process(any(Transaction.class)))
                .thenReturn(sampleTransaction.withStatus(TransactionStatus.PROCESSING));

        // When
        Transaction result = createTransactionService.execute(sampleTransaction);

        // Then
        assertThat(result).isNotNull();
        verify(validator).validate(sampleTransaction);
        verify(idempotencyService).tryAcquireLock(anyString(), anyString());
        verify(transactionRepository).existsByClientTransactionId("ORDER-2024-001");
        verify(transactionRepository, times(2)).save(any(Transaction.class));
        verify(mockProvider).process(any(Transaction.class));
    }

    @Test
    @DisplayName("Should throw DuplicateTransactionException when client_transaction_id already exists in DB")
    void shouldThrowDuplicateExceptionWhenIdExists() {
        // Given
        doNothing().when(validator).validate(any(Transaction.class));
        when(idempotencyService.tryAcquireLock(anyString(), anyString())).thenReturn(true);
        when(transactionRepository.existsByClientTransactionId("ORDER-2024-001")).thenReturn(true);

        // When / Then
        assertThatThrownBy(() -> createTransactionService.execute(sampleTransaction))
                .isInstanceOf(DuplicateTransactionException.class)
                .hasMessageContaining("ORDER-2024-001");

        verify(transactionRepository, never()).save(any(Transaction.class));
        verify(mockProvider, never()).process(any(Transaction.class));
    }

    @Test
    @DisplayName("Should return existing transaction when idempotency lock is already held")
    void shouldReturnExistingTransactionWhenLockAlreadyHeld() {
        // Given
        doNothing().when(validator).validate(any(Transaction.class));
        when(idempotencyService.tryAcquireLock(anyString(), anyString())).thenReturn(false);
        when(idempotencyService.findTransactionId(anyString()))
                .thenReturn(java.util.Optional.of(sampleTransaction.getId().toString()));
        when(transactionRepository.findById(sampleTransaction.getId()))
                .thenReturn(java.util.Optional.of(sampleTransaction));

        // When
        Transaction result = createTransactionService.execute(sampleTransaction);

        // Then
        assertThat(result).isEqualTo(sampleTransaction);
        verify(transactionRepository, never()).save(any(Transaction.class));
        verify(mockProvider, never()).process(any(Transaction.class));
    }

    @Test
    @DisplayName("Should persist transaction BEFORE sending to provider")
    void shouldPersistBeforeSendingToProvider() {
        // Given
        doNothing().when(validator).validate(any(Transaction.class));
        when(idempotencyService.tryAcquireLock(anyString(), anyString())).thenReturn(true);
        when(transactionRepository.existsByClientTransactionId(anyString())).thenReturn(false);
        when(transactionRepository.save(any(Transaction.class))).thenReturn(sampleTransaction);
        when(mockProvider.supports("PSE")).thenReturn(true);
        when(mockProvider.process(any(Transaction.class))).thenReturn(sampleTransaction);

        // When
        createTransactionService.execute(sampleTransaction);

        // Then - verify order: save first, then process
        var inOrder = org.mockito.Mockito.inOrder(transactionRepository, mockProvider);
        inOrder.verify(transactionRepository).save(any(Transaction.class));
        inOrder.verify(mockProvider).process(any(Transaction.class));
    }
}
