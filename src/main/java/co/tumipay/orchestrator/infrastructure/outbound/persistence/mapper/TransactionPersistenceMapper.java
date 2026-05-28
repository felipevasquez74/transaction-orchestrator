package co.tumipay.orchestrator.infrastructure.outbound.persistence.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import co.tumipay.orchestrator.domain.model.Customer;
import co.tumipay.orchestrator.domain.model.Transaction;
import co.tumipay.orchestrator.infrastructure.outbound.persistence.entity.CustomerEntity;
import co.tumipay.orchestrator.infrastructure.outbound.persistence.entity.TransactionEntity;

@Mapper(componentModel = "spring")
public interface TransactionPersistenceMapper {

    @Mapping(target = "id", expression = "java(transaction.getId() != null ? transaction.getId().toString() : null)")
    @Mapping(target = "statusCode", expression = "java(transaction.getStatus() != null ? transaction.getStatus().name() : \"PENDING\")")
    @Mapping(target = "customer", source = "customer")
    TransactionEntity toEntity(Transaction transaction);

    @Mapping(target = "id", expression = "java(java.util.UUID.fromString(entity.getId()))")
    @Mapping(target = "status", expression = "java(co.tumipay.orchestrator.domain.model.TransactionStatus.valueOf(entity.getStatusCode()))")
    @Mapping(target = "customer", source = "customer")
    Transaction toDomain(TransactionEntity entity);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "documentTypeCode", source = "documentType")
    CustomerEntity toEntity(Customer customer);

    @Mapping(target = "documentType", source = "documentTypeCode")
    Customer toDomain(CustomerEntity entity);
}
