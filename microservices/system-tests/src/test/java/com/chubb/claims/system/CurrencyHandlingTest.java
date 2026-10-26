package com.chubb.claims.system;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Currency rules. INR is the example currency; AUD is used only as a second currency so that grouping can be tested.
 * No exchange rate exists, and no list of supported currencies is enforced.
 */
class CurrencyHandlingTest extends SystemTestBase {

    @Test
    void amountAndCurrencyArePersistedAndReturnedByEveryClaimEndpoint() throws Exception {
        String inr = createClaim("SG", "MOTOR", "1234.50", "INR");
        String aud = createClaim("AU", "PROPERTY", "99.05", "AUD");

        getJson("/api/v1/claims/" + inr).andExpect(jsonPath("$.claimedAmount").value(1234.50))
                .andExpect(jsonPath("$.currency").value("INR"));
        getJson("/api/v1/claims/" + aud).andExpect(jsonPath("$.claimedAmount").value(99.05))
                .andExpect(jsonPath("$.currency").value("AUD"));
        getJson("/api/v1/officer/claims?market=AU").andExpect(jsonPath("$.content[0].claimedAmount").value(99.05))
                .andExpect(jsonPath("$.content[0].currency").value("AUD"));
        org.junit.jupiter.api.Assertions.assertEquals("AUD",
                jdbc.queryForObject("select currency from claims where claim_id = ?", String.class, aud));
    }

    @Test
    void missingOrMalformedCurrencyIsRejectedAndNothingIsStored() throws Exception {
        Map<String, Object> noCurrency = claimBody("SG", "MOTOR", "10.00", "INR");
        noCurrency.remove("currency");
        postJson("/api/v1/claims", noCurrency).andExpect(status().isBadRequest());
        for (String bad : new String[] {"inr", "IN", "INRR", "1NR", " "}) {
            postJson("/api/v1/claims", claimBody("SG", "MOTOR", "10.00", bad)).andExpect(status().isBadRequest());
        }
        org.junit.jupiter.api.Assertions.assertEquals(0, jdbc.queryForObject("select count(*) from claims", Integer.class));
    }

    @Test
    void liabilityIsGroupedPerCurrencyAndNeverMixedAcrossCurrencies() throws Exception {
        createClaim("SG", "MOTOR", "100.10", "INR");
        createClaim("SG", "MOTOR", "200.20", "INR");
        createClaim("AU", "MOTOR", "50.05", "AUD");
        getJson("/api/v1/management/summary")
                .andExpect(jsonPath("$.outstandingLiabilityByCurrency.length()").value(2))
                .andExpect(jsonPath("$.outstandingLiabilityByCurrency[?(@.currency=='INR')].estimatedLiability")
                        .value(300.30))
                .andExpect(jsonPath("$.outstandingLiabilityByCurrency[?(@.currency=='INR')].claimCount").value(2))
                .andExpect(jsonPath("$.outstandingLiabilityByCurrency[?(@.currency=='AUD')].estimatedLiability")
                        .value(50.05))
                .andExpect(jsonPath("$.liabilityNote").value(org.hamcrest.Matchers.containsString("CLAIMED amount")))
                .andExpect(jsonPath("$.liabilityNote").value(org.hamcrest.Matchers.containsString("no exchange-rate conversion")));
    }

    @Test
    void decisionInAnotherCurrencyIsRejectedAndTheClaimIsUntouched() throws Exception {
        String id = createClaimUnderReview(); // INR claim
        postJson("/api/v1/officer/claims/" + id + "/decision", Map.of("decision", "APPROVED",
                "decisionReason", "Approved.", "approvedAmount", "100.00", "currency", "AUD",
                "decidedByOfficerId", "off-1007"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CURRENCY_MISMATCH"));
        getJson("/api/v1/claims/" + id).andExpect(jsonPath("$.status").value("UNDER_REVIEW"));
    }

    @Test
    void approvedAmountKeepsTheClaimCurrencyAndTwoDecimals() throws Exception {
        String id = createClaimUnderReview();
        postJson("/api/v1/officer/claims/" + id + "/decision", Map.of("decision", "APPROVED",
                "decisionReason", "Approved.", "approvedAmount", "1000.5", "currency", "INR",
                "decidedByOfficerId", "off-1007"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvedAmount").value(1000.50))
                .andExpect(jsonPath("$.currency").value("INR"));
    }
}
