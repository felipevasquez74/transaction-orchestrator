package co.tumipay.orchestrator.domain.exception;

import java.util.UUID;

public class TransactionNotFoundException extends BusinessException {

    private static final String ERROR_CODE = "007";

    public TransactionNotFoundException(UUID transactionId) {
        super(ERROR_CODE, "Transaction not found with id: " + transactionId);
    }

    public TransactionNotFoundException(String clientTransactionId) {
        super(ERROR_CODE, "Transaction not found with client id: " + clientTransactionId);
    }
}
