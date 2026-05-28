package co.tumipay.orchestrator.domain.model;

public enum TransactionStatus {
    PENDING,
    PROCESSING,
    APPROVED,
    REJECTED,
    EXPIRED,
    CANCELLED,
    ERROR;

    public boolean isFinal() {
        return this == APPROVED || this == REJECTED || this == EXPIRED
                || this == CANCELLED || this == ERROR;
    }
}
