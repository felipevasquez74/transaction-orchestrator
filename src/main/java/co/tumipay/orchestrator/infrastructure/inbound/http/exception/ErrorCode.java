package co.tumipay.orchestrator.infrastructure.inbound.http.exception;

public enum ErrorCode {

    SUCCESS("000", "Successful operation"),
    VALIDATION_ERROR("001", "Validation error"),
    INVALID_EMAIL("002", "Invalid email format"),
    INVALID_CURRENCY_CODE("003", "Invalid currency code"),
    INVALID_COUNTRY_CODE("004", "Invalid country code"),
    INVALID_AMOUNT("005", "Invalid amount"),
    INVALID_URL("006", "Invalid URL format"),
    TRANSACTION_NOT_FOUND("007", "Transaction not found"),
    PAYMENT_METHOD_NOT_SUPPORTED("008", "Payment method not supported"),
    INVALID_DOCUMENT_TYPE("009", "Invalid document type"),
    PROVIDER_ERROR("010", "Payment provider error"),
    DUPLICATE_TRANSACTION("011", "Duplicate client transaction ID"),
    INVALID_EXPIRATION_TIME("012", "Invalid expiration time"),
    INTERNAL_SERVER_ERROR("099", "Unexpected internal server error");

    private final String code;
    private final String defaultMessage;

    ErrorCode(String code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    public String getCode() {
        return code;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }
}
