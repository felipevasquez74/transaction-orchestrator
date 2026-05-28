package co.tumipay.orchestrator.infrastructure.inbound.http.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@Schema(description = "Standard API response envelope")
public class ApiResponse<T> {

    @Schema(description = "Process response code. '000' indicates successful operation",
            example = "000")
    @JsonProperty("code")
    private final String code;

    @Schema(description = "Descriptive message of the operation result",
            example = "Successful operation")
    @JsonProperty("message")
    private final String message;

    @Schema(description = "Variable data payload - contains the operation result or error details")
    @JsonProperty("data")
    private final T data;

    public static <T> ApiResponse<T> success(T data) {
        return ApiResponse.<T>builder()
                .code("000")
                .message("Successful operation")
                .data(data)
                .build();
    }

    public static <T> ApiResponse<T> error(String code, String message, T data) {
        return ApiResponse.<T>builder()
                .code(code)
                .message(message)
                .data(data)
                .build();
    }

    public static ApiResponse<Void> error(String code, String message) {
        return ApiResponse.<Void>builder()
                .code(code)
                .message(message)
                .data(null)
                .build();
    }
}
