package co.tumipay.orchestrator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * TumiPay Transaction Orchestrator Microservice
 *
 * <p>Entrypoint of the Transaction Orchestration API built with Hexagonal Architecture.
 * This microservice acts as an orchestrator hub that receives transaction requests
 * and routes them to the appropriate payment provider adapter.</p>
 *
 * @author TumiPay Engineering
 * @version 1.0.0
 */
@SpringBootApplication
public class TransactionOrchestratorApplication {

    public static void main(String[] args) {
        SpringApplication.run(TransactionOrchestratorApplication.class, args);
    }
}
