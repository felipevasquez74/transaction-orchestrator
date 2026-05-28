package co.tumipay.orchestrator.domain.exception;

public class PaymentMethodNotFoundException extends BusinessException {

    private static final String ERROR_CODE = "008";

    public PaymentMethodNotFoundException(String paymentMethodId) {
        super(ERROR_CODE, "No active provider found for payment method: " + paymentMethodId);
    }
}
