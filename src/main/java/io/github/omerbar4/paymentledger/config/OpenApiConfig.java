package io.github.omerbar4.paymentledger.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI ledgerOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Idempotent Payment Ledger API")
                .version("1.0.0")
                .description("Double-entry payment ledger with database-enforced idempotency."));
    }
}
