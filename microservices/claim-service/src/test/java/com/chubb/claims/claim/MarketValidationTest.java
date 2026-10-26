package com.chubb.claims.claim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * The accepted markets are configuration. These codes are TEST VALUES ONLY; they are not the six markets from the
 * brief, which the brief does not name.
 */
@TestPropertySource(properties = "claims.markets.allowed=SG, HK")
class MarketValidationTest extends IntegrationTestBase {

    @Test
    void configuredMarketsAreAccepted() throws Exception {
        postJson("/api/v1/claims", claimBody("SG", "MOTOR", "100.00", "INR")).andExpect(status().isCreated());
        postJson("/api/v1/claims", claimBody("HK", "MOTOR", "100.00", "HKD")).andExpect(status().isCreated());
    }

    @Test
    void unlistedMarketIs400AndNothingIsStored() throws Exception {
        postJson("/api/v1/claims", claimBody("AU", "MOTOR", "100.00", "AUD"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_MARKET"));
        assertEquals(0, jdbc.queryForObject("select count(*) from claims", Integer.class));
    }

    @Test
    void formatValidationStillAppliesFirst() throws Exception {
        postJson("/api/v1/claims", claimBody("sg", "MOTOR", "100.00", "INR"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
