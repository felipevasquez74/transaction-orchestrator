package co.tumipay.orchestrator.infrastructure.inbound.http.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import co.tumipay.orchestrator.domain.model.Customer;
import co.tumipay.orchestrator.domain.model.Transaction;
import co.tumipay.orchestrator.domain.model.TransactionStatus;
import co.tumipay.orchestrator.domain.port.in.CreateTransactionUseCase;
import co.tumipay.orchestrator.domain.port.in.GetTransactionUseCase;
import co.tumipay.orchestrator.infrastructure.inbound.http.dto.request.CreateTransactionRequest;
import co.tumipay.orchestrator.infrastructure.inbound.http.dto.response.ApiResponse;
import co.tumipay.orchestrator.infrastructure.inbound.http.dto.response.TransactionResponse;
import co.tumipay.orchestrator.infrastructure.inbound.http.mapper.TransactionHttpMapper;

@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionController Unit Tests")
class TransactionControllerTest {

    @Mock
    private CreateTransactionUseCase createTransactionUseCase;

    @Mock
    private GetTransactionUseCase getTransactionUseCase;

    @Mock
    private TransactionHttpMapper mapper;

    private TransactionController controller;

    private Transaction sampleTransaction;
    private TransactionResponse sampleResponse;

    @BeforeEach
    void setUp() {
        controller = new TransactionController(createTransactionUseCase, getTransactionUseCase, mapper);

        Customer customer = Customer.builder()
                .firstName("Juan").lastName("Pérez").email("juan@example.com").build();

        sampleTransaction = Transaction.createNew(
                "ORDER-001", 150_000L, "COP", "CO", "PSE",
                "https://webhook.example.com", "https://redirect.example.com",
                "Test", null, customer);

        sampleResponse = TransactionResponse.builder()
                .transactionId(sampleTransaction.getId().toString())
                .clientTransactionId("ORDER-001")
                .status(TransactionStatus.PROCESSING.name())
                .currencyCode("COP")
                .countryCode("CO")
                .paymentMethodId("PSE")
                .build();
    }

    @Test
    @DisplayName("createTransaction returns 201 with transaction response")
    void createTransaction_returns201() {
        CreateTransactionRequest request = new CreateTransactionRequest();
        when(mapper.toDomain(request)).thenReturn(sampleTransaction);
        when(createTransactionUseCase.execute(sampleTransaction)).thenReturn(sampleTransaction);
        when(mapper.toResponse(sampleTransaction)).thenReturn(sampleResponse);

        ResponseEntity<ApiResponse<TransactionResponse>> response = controller.createTransaction(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().getCode()).isEqualTo("000");
        assertThat(response.getBody().getData()).isEqualTo(sampleResponse);
        verify(createTransactionUseCase).execute(sampleTransaction);
    }

    @Test
    @DisplayName("getTransaction returns 200 with transaction response")
    void getTransaction_returns200() {
        UUID txId = sampleTransaction.getId();
        when(getTransactionUseCase.execute(txId)).thenReturn(sampleTransaction);
        when(mapper.toResponse(sampleTransaction)).thenReturn(sampleResponse);

        ResponseEntity<ApiResponse<TransactionResponse>> response = controller.getTransaction(txId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getData()).isEqualTo(sampleResponse);
        verify(getTransactionUseCase).execute(txId);
    }

    @Test
    @DisplayName("createTransaction delegates mapping to the mapper, never builds Transaction directly")
    void createTransaction_delegatesToMapper() {
        CreateTransactionRequest request = new CreateTransactionRequest();
        when(mapper.toDomain(any(CreateTransactionRequest.class))).thenReturn(sampleTransaction);
        when(createTransactionUseCase.execute(any())).thenReturn(sampleTransaction);
        when(mapper.toResponse(any())).thenReturn(sampleResponse);

        controller.createTransaction(request);

        verify(mapper).toDomain(request);
        verify(mapper).toResponse(sampleTransaction);
    }
}
