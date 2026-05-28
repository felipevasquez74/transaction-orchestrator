package co.tumipay.orchestrator.application.validator;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import co.tumipay.orchestrator.domain.exception.ValidationException;
import co.tumipay.orchestrator.domain.model.Customer;
import co.tumipay.orchestrator.domain.model.Transaction;

@DisplayName("TransactionDomainValidator Unit Tests")
class TransactionDomainValidatorTest {

    private final TransactionDomainValidator validator = new TransactionDomainValidator();

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
    @DisplayName("Should fail validation for invalid webhook URL")
    void shouldFailForInvalidWebhookUrl() {
        Transaction transaction = buildValidTransaction().withWebhookUrl("not-a-url");
        assertThatThrownBy(() -> validator.validate(transaction))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("webhook_url");
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
                .email("juan@example.com").documentType("CC").documentNumber("1234567890")
                .build();
    }
}
