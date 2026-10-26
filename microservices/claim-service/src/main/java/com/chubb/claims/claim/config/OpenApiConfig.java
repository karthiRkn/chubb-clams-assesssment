package com.chubb.claims.claim.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI claimServiceApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Claim Service API")
                        .version("v1")
                        .description("Claim record (create, read, list, correct), lifecycle rules, assignment, history and the management "
                                + "workload and liability reports. Every route needs a Bearer token from the User Service "
                                + "(POST /api/v1/auth/login); the allowed roles are listed in API_TEST_CASES.md. "
                                + "Routes under /internal/v1 are for the other services and accept only a service token."))
                .components(new Components().addSecuritySchemes("bearerAuth",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }
}
