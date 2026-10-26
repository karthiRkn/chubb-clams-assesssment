package com.chubb.claims.assessment.config;

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
    public OpenAPI assessmentServiceApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Assessment Service API")
                        .version("v1")
                        .description("Officer review with assessment notes, information requests and the claimant's answers, the "
                                + "approve/reject decision and the settlement of an approved claim, with the assessment history. Every "
                                + "route needs a Bearer token from the User Service (POST /api/v1/auth/login): CLAIMS_OFFICER (or ADMIN) "
                                + "for the officer work, CLAIMANT to answer a request. It calls the Claim Service, which owns the claim "
                                + "lifecycle. Assessment notes are internal and are never added to claimant-facing responses."))
                .components(new Components().addSecuritySchemes("bearerAuth",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }
}
