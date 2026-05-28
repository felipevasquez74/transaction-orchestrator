package co.tumipay.orchestrator.infrastructure.inbound.http.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ApiResponse Factory Method Tests")
class ApiResponseTest {

    @Test
    @DisplayName("success sets code 000, standard message, and wraps data")
    void success_setsCorrectFields() {
        ApiResponse<String> response = ApiResponse.success("payload");
        assertThat(response.getCode()).isEqualTo("000");
        assertThat(response.getMessage()).isEqualTo("Successful operation");
        assertThat(response.getData()).isEqualTo("payload");
    }

    @Test
    @DisplayName("success with null data still sets code 000")
    void success_withNullData() {
        ApiResponse<Void> response = ApiResponse.success(null);
        assertThat(response.getCode()).isEqualTo("000");
        assertThat(response.getData()).isNull();
    }

    @Test
    @DisplayName("error with data sets all three fields")
    void error_withData_setsAllFields() {
        List<String> violations = List.of("field A is required", "field B is invalid");
        ApiResponse<List<String>> response = ApiResponse.error("001", "Validation error", violations);
        assertThat(response.getCode()).isEqualTo("001");
        assertThat(response.getMessage()).isEqualTo("Validation error");
        assertThat(response.getData()).isEqualTo(violations);
    }

    @Test
    @DisplayName("error without data produces null data field")
    void error_withoutData_hasNullData() {
        ApiResponse<Void> response = ApiResponse.error("099", "Internal server error");
        assertThat(response.getCode()).isEqualTo("099");
        assertThat(response.getMessage()).isEqualTo("Internal server error");
        assertThat(response.getData()).isNull();
    }

    @Test
    @DisplayName("success with list data preserves the list")
    void success_withListData() {
        List<String> items = List.of("one", "two", "three");
        ApiResponse<List<String>> response = ApiResponse.success(items);
        assertThat(response.getData()).hasSize(3).containsExactly("one", "two", "three");
    }
}
