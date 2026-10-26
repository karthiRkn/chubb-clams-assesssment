package com.chubb.claims.testsupport;

import com.chubb.claims.common.security.JwtUtil;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcBuilderCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.servlet.setup.ConfigurableMockMvcBuilder;
import org.springframework.test.web.servlet.setup.MockMvcConfigurer;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.context.WebApplicationContext;

/**
 * {@code @Import} this into a service's integration-test base class so its MockMvc sends a default identity
 * (see {@link TestAuth#defaultTo}). That keeps the many business-rule tests about the rule, not about login, while the
 * dedicated security tests state their caller explicitly.
 */
@TestConfiguration(proxyBeanMethods = false)
public class DefaultTestTokens {

    @Bean
    public MockMvcBuilderCustomizer defaultTestTokenCustomizer(JwtUtil jwt) {
        return builder -> builder.apply(new MockMvcConfigurer() {
            @Override
            public RequestPostProcessor beforeMockMvcCreated(ConfigurableMockMvcBuilder<?> b, WebApplicationContext context) {
                return TestAuth.defaultTo(jwt);
            }
        });
    }
}
