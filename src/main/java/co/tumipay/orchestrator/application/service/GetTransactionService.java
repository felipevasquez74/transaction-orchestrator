package co.tumipay.orchestrator.application.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.tumipay.orchestrator.domain.exception.TransactionNotFoundException;
import co.tumipay.orchestrator.domain.model.Transaction;
import co.tumipay.orchestrator.domain.port.in.GetTransactionUseCase;
import co.tumipay.orchestrator.domain.port.out.TransactionRepositoryPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class GetTransactionService implements GetTransactionUseCase {

    private final TransactionRepositoryPort transactionRepository;

    @Override
    @Transactional(readOnly = true)
    public Transaction execute(UUID transactionId) {
        log.debug("Querying transaction. transaction_id={}", transactionId);
        return transactionRepository.findById(transactionId)
                .orElseThrow(() -> {
                    log.warn("Transaction not found. transaction_id={}", transactionId);
                    return new TransactionNotFoundException(transactionId);
                });
    }
}
