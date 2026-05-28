package co.tumipay.orchestrator.domain.exception;

public class DuplicateTransactionException extends BusinessException {

    private static final String ERROR_CODE = "011";

    public DuplicateTransactionException(String clientTransactionId) {
        super(ERROR_CODE,
                "Transaction with client_transaction_id '" + clientTransactionId + "' already exists");
    }
}
