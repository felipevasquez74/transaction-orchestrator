package co.tumipay.orchestrator.infrastructure.outbound.persistence.adapter;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import co.tumipay.orchestrator.domain.model.Transaction;
import co.tumipay.orchestrator.domain.port.out.TransactionRepositoryPort;
import co.tumipay.orchestrator.infrastructure.outbound.persistence.entity.TransactionEntity;
import co.tumipay.orchestrator.infrastructure.outbound.persistence.mapper.TransactionPersistenceMapper;
import co.tumipay.orchestrator.infrastructure.outbound.persistence.repository.TransactionJpaRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class TransactionRepositoryAdapter implements TransactionRepositoryPort {

    private final TransactionJpaRepository jpaRepository;
    private final TransactionPersistenceMapper mapper;

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public Transaction save(Transaction transaction) {
        TransactionEntity entity = mapper.toEntity(transaction);
        TransactionEntity savedEntity = entityManager.merge(entity);
        log.debug("Transaction saved to DB. id={}", savedEntity.getId());
        return mapper.toDomain(savedEntity);
    }

    @Override
    public Optional<Transaction> findById(UUID id) {
        return jpaRepository.findById(id.toString())
                .map(mapper::toDomain);
    }

    @Override
    public boolean existsByClientTransactionId(String clientTransactionId) {
        return jpaRepository.existsByClientTransactionId(clientTransactionId);
    }
}
