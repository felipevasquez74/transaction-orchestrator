package co.tumipay.orchestrator.infrastructure.inbound.http.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Schema(description = "Customer information object")
public class CustomerRequest {

    @Schema(description = "Legal document type code", example = "CC")
    @JsonProperty("document_type")
    private String documentType;

    @Schema(description = "Legal document number", example = "1234567890")
    @JsonProperty("document_number")
    private String documentNumber;

    @Schema(description = "Country calling code", example = "+57")
    @JsonProperty("country_calling_code")
    private String countryCallingCode;

    @Schema(description = "Phone number without country code, digits only", example = "3001234567")
    @JsonProperty("phone_number")
    private String phoneNumber;

    @Email(message = "customer.email must be a valid email address")
    @Schema(description = "Customer email address", example = "customer@example.com")
    @JsonProperty("email")
    private String email;

    @NotBlank(message = "customer.first_name is required")
    @Size(max = 100, message = "customer.first_name must not exceed 100 characters")
    @Schema(description = "Customer first name", example = "Juan", required = true)
    @JsonProperty("first_name")
    private String firstName;

    @Size(max = 100, message = "customer.middle_name must not exceed 100 characters")
    @Schema(description = "Customer middle name", example = "Carlos")
    @JsonProperty("middle_name")
    private String middleName;

    @NotBlank(message = "customer.last_name is required")
    @Size(max = 100, message = "customer.last_name must not exceed 100 characters")
    @Schema(description = "Customer first last name", example = "Pérez", required = true)
    @JsonProperty("last_name")
    private String lastName;

    @Size(max = 100, message = "customer.second_last_name must not exceed 100 characters")
    @Schema(description = "Customer second last name", example = "García")
    @JsonProperty("second_last_name")
    private String secondLastName;
}
