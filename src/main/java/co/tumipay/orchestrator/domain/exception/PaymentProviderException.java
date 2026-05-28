package co.tumipay.orchestrator.domain.exception;

public class PaymentProviderException extends BusinessException {

    private static final String ERROR_CODE = "010";

    public PaymentProviderException(String provider, String message) {
        super(ERROR_CODE, "Payment provider [" + provider + "] error: " + message);
    }

    public PaymentProviderException(String provider, String message, Throwable cause) {
        super(ERROR_CODE, "Payment provider [" + provider + "] error: " + message, cause);
    }
}
