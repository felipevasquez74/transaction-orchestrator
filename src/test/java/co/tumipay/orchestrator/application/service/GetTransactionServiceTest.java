package co.tumipay.orchestrator.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import co.tumipay.orchestrator.domain.exception.TransactionNotFoundException;
import co.tumipay.orchestrator.domain.model.Customer;
import co.tumipay.orchestrator.domain.model.Transaction;
import co.tumipay.orchestrator.domain.model.TransactionStatus;
import co.tumipay.orchestrator.domain.port.out.TransactionRepositoryPort;

@ExtendWith(MockitoExtension.class)
@DisplayName("GetTransactionService Unit Tests")
class GetTransactionServiceTest {

    @Mock
    private TransactionRepositoryPort transactionRepository;

    @InjectMocks
    private GetTransactionService getTransactionService;

    @Test
    @DisplayName("Should return transaction when found by ID")
    void shouldReturnTransactionWhenFound() {
        // Given
        UUID transactionId = UUID.randomUUID();
        Transaction expectedTransaction = buildTransaction(transactionId);
        when(transactionRepository.findById(transactionId)).thenReturn(Optional.of(expectedTransaction));

        // When
        Transaction result = getTransactionService.execute(transactionId);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(transactionId);
        assertThat(result.getClientTransactionId()).isEqualTo("ORDER-2024-001");
    }

    @Test
    @DisplayName("Should throw TransactionNotFoundException when transaction does not exist")
    void shouldThrowNotFoundWhenTransactionMissing() {
        // Given
        UUID nonExistentId = UUID.randomUUID();
        when(transactionRepository.findById(nonExistentId)).thenReturn(Optional.empty());

        // When / Then
        assertThatThrownBy(() -> getTransactionService.execute(nonExistentId))
                .isInstanceOf(TransactionNotFoundException.class)
                .hasMessageContaining(nonExistentId.toString());
    }

    private Transaction buildTransaction(UUID id) {
        Customer customer = Customer.builder()
                .firstName("María")
                .lastName("García")
                .email("maria@example.com")
                .build();

        return Transaction.builder()
                .id(id)
                .clientTransactionId("ORDER-2024-001")
                .amount(50000L)
                .currencyCode("COP")
                .countryCode("CO")
                .paymentMethodId("NEQUI")
                .webhookUrl("https://api.mycompany.com/webhooks")
                .redirectUrl("https://mycompany.com/result")
                .status(TransactionStatus.PROCESSING)
                .customer(customer)
                .processedAt(OffsetDateTime.now())
                .createdAt(OffsetDateTime.now())
                .build();
    }
}
