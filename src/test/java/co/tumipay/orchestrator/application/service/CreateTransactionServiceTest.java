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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import co.tumipay.orchestrator.application.validator.TransactionDomainValidator;
import co.tumipay.orchestrator.domain.exception.DuplicateTransactionException;
import co.tumipay.orchestrator.domain.model.Customer;
import co.tumipay.orchestrator.domain.model.Transaction;
import co.tumipay.orchestrator.domain.model.TransactionStatus;
import co.tumipay.orchestrator.domain.port.out.PaymentProviderPort;
import co.tumipay.orchestrator.domain.port.out.TransactionRepositoryPort;

@ExtendWith(MockitoExtension.class)
@DisplayName("CreateTransactionService Unit Tests")
class CreateTransactionServiceTest {

    @Mock
    private TransactionRepositoryPort transactionRepository;

    @Mock
    private TransactionDomainValidator validator;

    @Mock
    private PaymentProviderPort mockProvider;

    @InjectMocks
    private CreateTransactionService createTransactionService;

    private Transaction sampleTransaction;

    @BeforeEach
    void setUp() {
        createTransactionService = new CreateTransactionService(
                transactionRepository,
                validator,
                List.of(mockProvider)
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
        verify(transactionRepository).existsByClientTransactionId("ORDER-2024-001");
        verify(transactionRepository, times(2)).save(any(Transaction.class));
        verify(mockProvider).process(any(Transaction.class));
    }

    @Test
    @DisplayName("Should throw DuplicateTransactionException when client_transaction_id already exists")
    void shouldThrowDuplicateExceptionWhenIdExists() {
        // Given
        doNothing().when(validator).validate(any(Transaction.class));
        when(transactionRepository.existsByClientTransactionId("ORDER-2024-001")).thenReturn(true);

        // When / Then
        assertThatThrownBy(() -> createTransactionService.execute(sampleTransaction))
                .isInstanceOf(DuplicateTransactionException.class)
                .hasMessageContaining("ORDER-2024-001");

        verify(transactionRepository, never()).save(any(Transaction.class));
        verify(mockProvider, never()).process(any(Transaction.class));
    }

    @Test
    @DisplayName("Should persist transaction BEFORE sending to provider")
    void shouldPersistBeforeSendingToProvider() {
        // Given
        doNothing().when(validator).validate(any(Transaction.class));
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
