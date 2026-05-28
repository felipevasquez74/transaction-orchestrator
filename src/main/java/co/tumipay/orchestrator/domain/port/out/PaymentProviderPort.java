package co.tumipay.orchestrator.domain.port.out;

import co.tumipay.orchestrator.domain.model.Transaction;

public interface PaymentProviderPort {

    String getProviderId();

    boolean supports(String paymentMethodId);

    Transaction process(Transaction transaction);
}
