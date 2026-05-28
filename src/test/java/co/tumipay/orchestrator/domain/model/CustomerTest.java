package co.tumipay.orchestrator.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Customer Domain Model Tests")
class CustomerTest {

    @Test
    @DisplayName("getFullName with only first and last name")
    void getFullName_withOnlyFirstAndLast() {
        Customer c = Customer.builder().firstName("Juan").lastName("Pérez").build();
        assertThat(c.getFullName()).isEqualTo("Juan Pérez");
    }

    @Test
    @DisplayName("getFullName with all four name parts")
    void getFullName_withAllParts() {
        Customer c = Customer.builder()
                .firstName("Juan").middleName("Carlos").lastName("Pérez").secondLastName("García")
                .build();
        assertThat(c.getFullName()).isEqualTo("Juan Carlos Pérez García");
    }

    @Test
    @DisplayName("getFullName ignores blank middleName")
    void getFullName_ignoresBlankMiddleName() {
        Customer c = Customer.builder().firstName("Ana").middleName("   ").lastName("López").build();
        assertThat(c.getFullName()).isEqualTo("Ana López");
    }

    @Test
    @DisplayName("getFullName ignores blank secondLastName")
    void getFullName_ignoresBlankSecondLastName() {
        Customer c = Customer.builder().firstName("Ana").lastName("López").secondLastName("").build();
        assertThat(c.getFullName()).isEqualTo("Ana López");
    }

    @Test
    @DisplayName("toString excludes PII fields (email, documentNumber, phoneNumber)")
    void toString_excludesSensitiveFields() {
        Customer c = Customer.builder()
                .firstName("Juan").lastName("Pérez")
                .email("secret@email.com")
                .documentNumber("123456789")
                .phoneNumber("3001234567")
                .build();
        String str = c.toString();
        assertThat(str).doesNotContain("secret@email.com");
        assertThat(str).doesNotContain("123456789");
        assertThat(str).doesNotContain("3001234567");
        assertThat(str).contains("Juan");
        assertThat(str).contains("Pérez");
    }

    @Test
    @DisplayName("getters return correct values")
    void getters_returnAllFields() {
        Customer c = Customer.builder()
                .firstName("Luis").middleName("Alberto").lastName("Martínez").secondLastName("Gómez")
                .email("luis@test.com").documentType("CC").documentNumber("98765432")
                .countryCallingCode("+57").phoneNumber("3109876543")
                .build();
        assertThat(c.getFirstName()).isEqualTo("Luis");
        assertThat(c.getMiddleName()).isEqualTo("Alberto");
        assertThat(c.getLastName()).isEqualTo("Martínez");
        assertThat(c.getSecondLastName()).isEqualTo("Gómez");
        assertThat(c.getEmail()).isEqualTo("luis@test.com");
        assertThat(c.getDocumentType()).isEqualTo("CC");
        assertThat(c.getDocumentNumber()).isEqualTo("98765432");
        assertThat(c.getCountryCallingCode()).isEqualTo("+57");
        assertThat(c.getPhoneNumber()).isEqualTo("3109876543");
    }
}
