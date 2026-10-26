package com.chubb.claims.common.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Every call this service makes to another one carries a fresh, short-lived SERVICE token whose subject is this service's
 * name. It is added to the application's {@code RestClient.Builder}, and {@code DownstreamClient} clones that builder, so
 * no client class has to know about authentication. The caller's own token is deliberately not forwarded: the
 * {@code /internal/v1} routes accept only a SERVICE token, so a person's token can never be used on them directly.
 */
@Configuration
public class ServiceTokenConfig {

    @Bean
    public RestClientCustomizer serviceTokenCustomizer(JwtUtil jwt,
                                                       @Value("${spring.application.name:unknown-service}") String serviceName) {
        return builder -> builder.requestInterceptor((request, body, execution) -> {
            request.getHeaders().setBearerAuth(jwt.generateServiceToken(serviceName));
            return execution.execute(request, body);
        });
    }
}
