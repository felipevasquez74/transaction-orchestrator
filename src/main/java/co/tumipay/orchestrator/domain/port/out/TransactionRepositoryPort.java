package co.tumipay.orchestrator.domain.port.out;

import java.util.Optional;
import java.util.UUID;

import co.tumipay.orchestrator.domain.model.Transaction;

public interface TransactionRepositoryPort {

    Transaction save(Transaction transaction);

    Optional<Transaction> findById(UUID id);

    boolean existsByClientTransactionId(String clientTransactionId);
}
