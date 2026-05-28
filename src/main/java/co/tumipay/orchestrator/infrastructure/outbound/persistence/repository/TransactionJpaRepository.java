package co.tumipay.orchestrator.infrastructure.outbound.persistence.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import co.tumipay.orchestrator.infrastructure.outbound.persistence.entity.TransactionEntity;

@Repository
public interface TransactionJpaRepository extends JpaRepository<TransactionEntity, String> {

    boolean existsByClientTransactionId(String clientTransactionId);
}
