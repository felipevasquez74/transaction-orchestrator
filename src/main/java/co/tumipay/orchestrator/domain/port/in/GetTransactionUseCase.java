package co.tumipay.orchestrator.domain.port.in;

import java.util.UUID;

import co.tumipay.orchestrator.domain.model.Transaction;

public interface GetTransactionUseCase {

    Transaction execute(UUID transactionId);
}
