package co.tumipay.orchestrator.infrastructure.inbound.http.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import co.tumipay.orchestrator.domain.model.Customer;
import co.tumipay.orchestrator.domain.model.Transaction;
import co.tumipay.orchestrator.infrastructure.inbound.http.dto.request.CreateTransactionRequest;
import co.tumipay.orchestrator.infrastructure.inbound.http.dto.request.CustomerRequest;
import co.tumipay.orchestrator.infrastructure.inbound.http.dto.response.TransactionResponse;

@Mapper(componentModel = "spring")
public interface TransactionHttpMapper {

    default Transaction toDomain(CreateTransactionRequest request) {
        return Transaction.createNew(
                request.getClientTransactionId(),
                request.getAmount(),
                request.getCurrencyCode(),
                request.getCountryCode(),
                request.getPaymentMethodId(),
                request.getWebhookUrl(),
                request.getRedirectUrl(),
                request.getDescription(),
                request.getExpirationTime(),
                toDomain(request.getCustomer())
        );
    }

    Customer toDomain(CustomerRequest customerRequest);

    @Mapping(target = "transactionId", expression = "java(transaction.getId().toString())")
    @Mapping(target = "status", expression = "java(transaction.getStatus().name())")
    TransactionResponse toResponse(Transaction transaction);
}
