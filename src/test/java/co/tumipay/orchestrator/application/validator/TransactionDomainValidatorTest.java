package co.tumipay.orchestrator.application.validator;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import co.tumipay.orchestrator.domain.exception.ValidationException;
import co.tumipay.orchestrator.domain.model.Customer;
import co.tumipay.orchestrator.domain.model.Transaction;
import co.tumipay.orchestrator.infrastructure.security.SsrfGuard;

@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionDomainValidator Unit Tests")
class TransactionDomainValidatorTest {

    @Mock
    private SsrfGuard ssrfGuard;

    private TransactionDomainValidator validator;

    @BeforeEach
    void setUp() {
        // Default: SsrfGuard reports no violations (URLs are safe)
        when(ssrfGuard.validateSafety(anyString(), anyString())).thenReturn(null);
        validator = new TransactionDomainValidator(ssrfGuard);
        // @Value is not injected in unit tests without Spring context — set the default manually
        ReflectionTestUtils.setField(validator, "maxAmountCents", 10_000_000_000L);
    }

    @Test
    @DisplayName("Should pass validation for a valid transaction")
    void shouldPassForValidTransaction() {
        Transaction transaction = buildValidTransaction();
        assertThatCode(() -> validator.validate(transaction)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Should fail validation for invalid email format")
    void shouldFailForInvalidEmail() {
        Customer customer = Customer.builder()
                .firstName("Juan").lastName("Pérez").email("not-a-valid-email").build();
        Transaction transaction = buildTransactionWithCustomer(customer);
        assertThatThrownBy(() -> validator.validate(transaction))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("email");
    }

    @Test
    @DisplayName("Should fail validation for negative amount")
    void shouldFailForNegativeAmount() {
        Transaction transaction = Transaction.builder()
                .clientTransactionId("TEST-001").amount(-500L)
                .currencyCode("COP").countryCode("CO").paymentMethodId("PSE")
                .webhookUrl("https://api.test.com/wh").redirectUrl("https://test.com/result")
                .customer(validCustomer()).build();
        assertThatThrownBy(() -> validator.validate(transaction))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("amount");
    }

    @Test
    @DisplayName("Should fail validation for invalid currency code")
    void shouldFailForInvalidCurrencyCode() {
        Transaction transaction = buildValidTransaction().withCurrencyCode("XYZ");
        assertThatThrownBy(() -> validator.validate(transaction))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("currency_code");
    }

    @Test
    @DisplayName("Should fail validation for invalid webhook URL format")
    void shouldFailForInvalidWebhookUrl() {
        Transaction transaction = buildValidTransaction().withWebhookUrl("not-a-url");
        assertThatThrownBy(() -> validator.validate(transaction))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("webhook_url");
    }

    @Test
    @DisplayName("Should fail validation when document_type is provided without document_number")
    void shouldFailWhenDocumentTypeWithoutNumber() {
        Customer customer = Customer.builder()
                .firstName("Juan").lastName("Pérez")
                .documentType("CC")
                .build();
        assertThatThrownBy(() -> validator.validate(buildTransactionWithCustomer(customer)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("document_type")
                .hasMessageContaining("document_number");
    }

    @Test
    @DisplayName("Should fail validation when country_calling_code is provided without phone_number")
    void shouldFailWhenCallingCodeWithoutPhoneNumber() {
        Customer customer = Customer.builder()
                .firstName("Juan").lastName("Pérez")
                .countryCallingCode("+57")
                .build();
        assertThatThrownBy(() -> validator.validate(buildTransactionWithCustomer(customer)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("country_calling_code")
                .hasMessageContaining("phone_number");
    }

    @Test
    @DisplayName("Should fail validation for invalid country calling code format")
    void shouldFailForInvalidCallingCodeFormat() {
        Customer customer = Customer.builder()
                .firstName("Juan").lastName("Pérez")
                .countryCallingCode("57")     // missing leading +
                .phoneNumber("3001234567")
                .build();
        assertThatThrownBy(() -> validator.validate(buildTransactionWithCustomer(customer)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("country_calling_code");
    }

    private Transaction buildValidTransaction() {
        return Transaction.builder()
                .clientTransactionId("TEST-001").amount(100000L)
                .currencyCode("COP").countryCode("CO").paymentMethodId("PSE")
                .webhookUrl("https://api.test.com/webhook")
                .redirectUrl("https://test.com/result")
                .customer(validCustomer()).build();
    }

    private Transaction buildTransactionWithCustomer(Customer customer) {
        return buildValidTransaction().withCustomer(customer);
    }

    private Customer validCustomer() {
        return Customer.builder()
                .firstName("Juan").lastName("Pérez")
                .email("juan@example.com")
                .documentType("CC").documentNumber("1234567890")
                .build();
    }
}
