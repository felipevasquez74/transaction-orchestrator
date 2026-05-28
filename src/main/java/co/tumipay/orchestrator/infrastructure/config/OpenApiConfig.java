package co.tumipay.orchestrator.infrastructure.config;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI transactionOrchestratorOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("TumiPay Transaction Orchestrator API")
                        .description("Microservicio de Orquestación de Transacciones - TumiPay Fintech Platform. " +
                                "Permite la gestión de transacciones financieras con múltiples proveedores de pago " +
                                "mediante Arquitectura Hexagonal.")
                        .version("v1.0.0")
                        .contact(new Contact()
                                .name("TumiPay Engineering Team")
                                .url("https://www.tumipay.co")
                                .email("felipevasqueortiz@gmail.com"))
                        .license(new License()
                                .name("Proprietary")
                                .url("https://www.tumipay.co")))
                .servers(List.of(
                        new Server().url("http://localhost:8080").description("Local Development"),
                        new Server().url("https://api-dev.tumipay.co").description("Development"),
                        new Server().url("https://api.tumipay.co").description("Production")
                ));
    }
}
