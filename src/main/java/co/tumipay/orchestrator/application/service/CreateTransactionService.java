package co.tumipay.orchestrator.application.service;

import java.util.List;

import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.tumipay.orchestrator.application.validator.TransactionDomainValidator;
import co.tumipay.orchestrator.domain.exception.DuplicateTransactionException;
import co.tumipay.orchestrator.domain.exception.PaymentMethodNotFoundException;
import co.tumipay.orchestrator.domain.model.Transaction;
import co.tumipay.orchestrator.domain.model.TransactionStatus;
import co.tumipay.orchestrator.domain.port.in.CreateTransactionUseCase;
import co.tumipay.orchestrator.domain.port.out.PaymentProviderPort;
import co.tumipay.orchestrator.domain.port.out.TransactionRepositoryPort;
import co.tumipay.orchestrator.infrastructure.outbound.cache.IdempotencyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class CreateTransactionService implements CreateTransactionUseCase {

    private final TransactionRepositoryPort transactionRepository;
    private final TransactionDomainValidator validator;
    private final List<PaymentProviderPort> paymentProviders;
    private final IdempotencyService idempotencyService;

    @Override
    @Transactional
    public Transaction execute(Transaction transaction) {
        String clientIdTxId = transaction.getClientTransactionId();

        MDC.put("client_transaction_id", clientIdTxId);
        MDC.put("payment_method", transaction.getPaymentMethodId());

        log.info("Processing transaction. client_transaction_id={} amount={} {} method={}",
        		clientIdTxId, transaction.getAmount(), transaction.getCurrencyCode(),
                transaction.getPaymentMethodId());

        try {
            validator.validate(transaction);

            boolean lockAcquired = idempotencyService.tryAcquireLock(
            		clientIdTxId, transaction.getId().toString());

            if (!lockAcquired) {
                log.warn("Idempotency lock already held. client_transaction_id={}", clientIdTxId);
                return idempotencyService.findTransactionId(clientIdTxId)
                        .flatMap(existingId -> {
                            try {
                                return java.util.Optional.of(transactionRepository
                                        .findById(java.util.UUID.fromString(existingId))
                                        .orElseThrow());
                            } catch (Exception e) {
                                return java.util.Optional.empty();
                            }
                        })
                        .orElseThrow(() -> new DuplicateTransactionException(clientIdTxId));
            }

            if (transactionRepository.existsByClientTransactionId(clientIdTxId)) {
                log.warn("DB duplicate detected. client_transaction_id={}", clientIdTxId);
                throw new DuplicateTransactionException(clientIdTxId);
            }

            Transaction savedTransaction = transactionRepository.save(transaction);
            log.info("Transaction persisted. transaction_id={} status=PENDING",
                    savedTransaction.getId());

            idempotencyService.updateStatus(clientIdTxId,
                    savedTransaction.getId().toString(), TransactionStatus.PENDING.name());

            PaymentProviderPort provider = resolveProvider(transaction.getPaymentMethodId());
            log.info("Dispatching to provider. provider={} transaction_id={}",
                    provider.getProviderId(), savedTransaction.getId());

            Transaction processedTransaction;
            try {
                processedTransaction = provider.process(savedTransaction);
                log.info("Provider response received. transaction_id={} status={}",
                        processedTransaction.getId(), processedTransaction.getStatus());
            } catch (Exception e) {
                log.error("Provider call failed. transaction_id={} error={}",
                        savedTransaction.getId(), e.getMessage());
                throw e;
            }

            Transaction finalTransaction = transactionRepository.save(processedTransaction);

            idempotencyService.updateStatus(clientIdTxId,
                    finalTransaction.getId().toString(),
                    finalTransaction.getStatus().name());

            log.info("Transaction completed. transaction_id={} status={}",
                    finalTransaction.getId(), finalTransaction.getStatus());

            return finalTransaction;

        } finally {
            MDC.remove("client_transaction_id");
            MDC.remove("payment_method");
        }
    }

    private PaymentProviderPort resolveProvider(String paymentMethodId) {
        return paymentProviders.stream()
                .filter(provider -> provider.supports(paymentMethodId))
                .findFirst()
                .orElseThrow(() -> {
                    log.error("No provider registered for payment_method={}", paymentMethodId);
                    return new PaymentMethodNotFoundException(paymentMethodId);
                });
    }
}
