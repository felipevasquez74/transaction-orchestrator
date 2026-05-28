package co.tumipay.orchestrator.infrastructure.inbound.http.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import co.tumipay.orchestrator.domain.exception.DuplicateTransactionException;
import co.tumipay.orchestrator.domain.exception.PaymentMethodNotFoundException;
import co.tumipay.orchestrator.domain.exception.PaymentProviderException;
import co.tumipay.orchestrator.domain.exception.TransactionNotFoundException;
import co.tumipay.orchestrator.domain.exception.ValidationException;
import co.tumipay.orchestrator.infrastructure.inbound.http.dto.response.ApiResponse;

@DisplayName("GlobalExceptionHandler Unit Tests")
class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
    }

    @Test
    @DisplayName("handleBeanValidation returns 422 with field error messages")
    void handleBeanValidation_returns422WithViolations() {
        MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
        BindingResult bindingResult = mock(BindingResult.class);
        FieldError fieldError = new FieldError("transaction", "amount", "must be positive");
        when(ex.getBindingResult()).thenReturn(bindingResult);
        when(bindingResult.getFieldErrors()).thenReturn(List.of(fieldError));

        ResponseEntity<ApiResponse<List<String>>> response = handler.handleBeanValidation(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().getData()).containsExactly("must be positive");
        assertThat(response.getBody().getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR.getCode());
    }

    @Test
    @DisplayName("handleValidation returns 422 with domain violations list")
    void handleValidation_returns422() {
        ValidationException ex = new ValidationException(List.of("amount is required", "currency invalid"));

        ResponseEntity<ApiResponse<List<String>>> response = handler.handleValidation(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().getData()).containsExactlyInAnyOrder("amount is required", "currency invalid");
    }

    @Test
    @DisplayName("handleValidation with single-message constructor")
    void handleValidation_withSingleMessage_returns422() {
        ValidationException ex = new ValidationException("Single validation failure");

        ResponseEntity<ApiResponse<List<String>>> response = handler.handleValidation(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().getData()).contains("Single validation failure");
    }

    @Test
    @DisplayName("handleTransactionNotFound returns 404")
    void handleTransactionNotFound_returns404() {
        TransactionNotFoundException ex = new TransactionNotFoundException(UUID.randomUUID());

        ResponseEntity<ApiResponse<Void>> response = handler.handleTransactionNotFound(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().getCode()).isEqualTo("007");
    }

    @Test
    @DisplayName("handleTransactionNotFound with clientTransactionId constructor returns 404")
    void handleTransactionNotFound_withClientId_returns404() {
        TransactionNotFoundException ex = new TransactionNotFoundException("ORDER-001");

        ResponseEntity<ApiResponse<Void>> response = handler.handleTransactionNotFound(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().getMessage()).contains("ORDER-001");
    }

    @Test
    @DisplayName("handleDuplicate returns 409")
    void handleDuplicate_returns409() {
        DuplicateTransactionException ex = new DuplicateTransactionException("ORDER-2024-001");

        ResponseEntity<ApiResponse<Void>> response = handler.handleDuplicate(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().getCode()).isEqualTo("011");
        assertThat(response.getBody().getMessage()).contains("ORDER-2024-001");
    }

    @Test
    @DisplayName("handlePaymentMethodNotFound returns 400")
    void handlePaymentMethodNotFound_returns400() {
        PaymentMethodNotFoundException ex = new PaymentMethodNotFoundException("CRYPTO");

        ResponseEntity<ApiResponse<Void>> response = handler.handlePaymentMethodNotFound(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getCode()).isEqualTo("008");
        assertThat(response.getBody().getMessage()).contains("CRYPTO");
    }

    @Test
    @DisplayName("handleProviderError returns 502")
    void handleProviderError_returns502() {
        PaymentProviderException ex = new PaymentProviderException("MOCK", "Gateway timeout");

        ResponseEntity<ApiResponse<Void>> response = handler.handleProviderError(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody().getCode()).isEqualTo("010");
        assertThat(response.getBody().getMessage()).contains("MOCK").contains("Gateway timeout");
    }

    @Test
    @DisplayName("handleMalformedJson returns 400 with cause message")
    void handleMalformedJson_returns400() {
        HttpMessageNotReadableException ex = mock(HttpMessageNotReadableException.class);
        when(ex.getMessage()).thenReturn("JSON parse error");
        when(ex.getMostSpecificCause()).thenReturn(new RuntimeException("Unexpected character at position 5"));

        ResponseEntity<ApiResponse<String>> response = handler.handleMalformedJson(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getMessage()).isEqualTo("Malformed JSON request body");
        assertThat(response.getBody().getData()).contains("Unexpected character");
    }

    @Test
    @DisplayName("handleTypeMismatch returns 400 with parameter name and value")
    void handleTypeMismatch_returns400() {
        MethodArgumentTypeMismatchException ex = mock(MethodArgumentTypeMismatchException.class);
        when(ex.getName()).thenReturn("transaction_id");
        when(ex.getValue()).thenReturn("not-a-uuid");

        ResponseEntity<ApiResponse<String>> response = handler.handleTypeMismatch(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getMessage()).contains("transaction_id").contains("not-a-uuid");
    }

    @Test
    @DisplayName("handleUnexpected returns 500")
    void handleUnexpected_returns500() {
        Exception ex = new RuntimeException("Something went terribly wrong");

        ResponseEntity<ApiResponse<Void>> response = handler.handleUnexpected(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().getCode()).isEqualTo(ErrorCode.INTERNAL_SERVER_ERROR.getCode());
    }
}
