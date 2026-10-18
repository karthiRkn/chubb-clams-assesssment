package com.chubb.claims;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;

/** With no allow-list configured, any well-formed 2-letter code is still accepted (previous behaviour). */
class DefaultMarketTest extends IntegrationTestBase {

    @Test
    void anyTwoLetterMarketIsAcceptedWhenNoAllowListIsConfigured() throws Exception {
        postJson("/api/v1/claims", claimBody("ZZ", "MOTOR", "100.00", "INR")).andExpect(status().isCreated());
    }
}
