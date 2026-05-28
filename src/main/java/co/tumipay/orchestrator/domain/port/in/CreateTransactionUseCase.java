package co.tumipay.orchestrator.domain.port.in;

import co.tumipay.orchestrator.domain.model.Transaction;

public interface CreateTransactionUseCase {

    Transaction execute(Transaction transaction);
}
