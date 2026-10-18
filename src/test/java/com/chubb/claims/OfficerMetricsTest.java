package com.chubb.claims;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OfficerMetricsTest extends IntegrationTestBase {

    private static final String PATH = "/api/v1/management/officers";

    private void decide(String claimId, String decision, String settled, String currency, String officer) throws Exception {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("decision", decision);
        b.put("decisionReason", "Reason");
        if (settled != null) {
            b.put("settledAmount", new BigDecimal(settled));
        }
        b.put("currency", currency);
        b.put("decidedByOfficerId", officer);
        postJson("/api/v1/officer/claims/" + claimId + "/decision", b).andExpect(status().isOk());
    }

    @Test
    void emptyDatabaseGivesNoOfficers() throws Exception {
        getJson(PATH).andExpect(status().isOk())
                .andExpect(jsonPath("$.generatedAt").exists())
                .andExpect(jsonPath("$.note").value(org.hamcrest.Matchers.containsString("CURRENT assignee")))
                .andExpect(jsonPath("$.officers.length()").value(0));
    }

    @Test
    void unassignedClaimsAreNotAttributedToAnyOfficer() throws Exception {
        createClaim();
        getJson(PATH).andExpect(jsonPath("$.officers.length()").value(0));
    }

    @Test
    void workloadAndPerformanceArePerOfficerAndPerCurrency() throws Exception {
        // OFF-A: one SUBMITTED (assigned only), one UNDER_REVIEW, both INR; one settled AUD claim
        String aSubmitted = createClaim("SG", "MOTOR", "1000.00", "INR");
        assign(aSubmitted, "OFF-A");
        String aReview = createClaim("SG", "MOTOR", "500.00", "INR");
        review(aReview, "OFF-A");
        String aSettled = createClaim("AU", "PROPERTY", "700.00", "AUD");
        review(aSettled, "OFF-A");
        decide(aSettled, "SETTLED", "600.00", "AUD", "OFF-A");

        // OFF-B: one AWAITING_INFORMATION (USD), one settled INR, one rejected INR
        String bAwaiting = createClaim("US", "MOTOR", "150.00", "USD");
        review(bAwaiting, "OFF-B");
        postJson("/api/v1/officer/claims/" + bAwaiting + "/information-requests",
                Map.of("question", "Q", "requestedByOfficerId", "OFF-B")).andExpect(status().isCreated());
        String bSettled = createClaim("SG", "MOTOR", "300.00", "INR");
        review(bSettled, "OFF-B");
        decide(bSettled, "SETTLED", "250.00", "INR", "OFF-B");
        String bRejected = createClaim("SG", "MOTOR", "200.00", "INR");
        review(bRejected, "OFF-B");
        decide(bRejected, "REJECTED", null, "INR", "OFF-B");

        // unassigned claim must not appear anywhere
        createClaim("SG", "MOTOR", "9999.00", "INR");

        getJson(PATH).andExpect(status().isOk())
                .andExpect(jsonPath("$.officers.length()").value(2))
                // OFF-A (sorted first)
                .andExpect(jsonPath("$.officers[0].officerId").value("OFF-A"))
                .andExpect(jsonPath("$.officers[0].openClaimCount").value(2))
                .andExpect(jsonPath("$.officers[0].openClaimsByStatus.SUBMITTED").value(1))
                .andExpect(jsonPath("$.officers[0].openClaimsByStatus.UNDER_REVIEW").value(1))
                .andExpect(jsonPath("$.officers[0].openClaimsByStatus.AWAITING_INFORMATION").value(0))
                .andExpect(jsonPath("$.officers[0].openClaimedAmountByCurrency.length()").value(1))
                .andExpect(jsonPath("$.officers[0].openClaimedAmountByCurrency[0].currency").value("INR"))
                .andExpect(jsonPath("$.officers[0].openClaimedAmountByCurrency[0].claimCount").value(2))
                .andExpect(jsonPath("$.officers[0].openClaimedAmountByCurrency[0].amount").value(1500.00))
                .andExpect(jsonPath("$.officers[0].decidedCount").value(1))
                .andExpect(jsonPath("$.officers[0].settledCount").value(1))
                .andExpect(jsonPath("$.officers[0].rejectedCount").value(0))
                .andExpect(jsonPath("$.officers[0].settledAmountByCurrency.length()").value(1))
                .andExpect(jsonPath("$.officers[0].settledAmountByCurrency[0].currency").value("AUD"))
                .andExpect(jsonPath("$.officers[0].settledAmountByCurrency[0].amount").value(600.00))
                // OFF-B
                .andExpect(jsonPath("$.officers[1].officerId").value("OFF-B"))
                .andExpect(jsonPath("$.officers[1].openClaimCount").value(1))
                .andExpect(jsonPath("$.officers[1].openClaimsByStatus.AWAITING_INFORMATION").value(1))
                .andExpect(jsonPath("$.officers[1].openClaimsByStatus.UNDER_REVIEW").value(0))
                .andExpect(jsonPath("$.officers[1].openClaimedAmountByCurrency[0].currency").value("USD"))
                .andExpect(jsonPath("$.officers[1].openClaimedAmountByCurrency[0].amount").value(150.00))
                .andExpect(jsonPath("$.officers[1].decidedCount").value(2))
                .andExpect(jsonPath("$.officers[1].settledCount").value(1))
                .andExpect(jsonPath("$.officers[1].rejectedCount").value(1))
                .andExpect(jsonPath("$.officers[1].settledAmountByCurrency.length()").value(1))
                .andExpect(jsonPath("$.officers[1].settledAmountByCurrency[0].currency").value("INR"))
                .andExpect(jsonPath("$.officers[1].settledAmountByCurrency[0].amount").value(250.00));
    }

    @Test
    void officerWithOnlyDecisionsStillAppearsWithZeroOpenWork() throws Exception {
        String id = createClaim();
        review(id, "OFF-C");
        decide(id, "REJECTED", null, "INR", "OFF-C");
        getJson(PATH).andExpect(jsonPath("$.officers.length()").value(1))
                .andExpect(jsonPath("$.officers[0].officerId").value("OFF-C"))
                .andExpect(jsonPath("$.officers[0].openClaimCount").value(0))
                .andExpect(jsonPath("$.officers[0].openClaimedAmountByCurrency.length()").value(0))
                .andExpect(jsonPath("$.officers[0].decidedCount").value(1))
                .andExpect(jsonPath("$.officers[0].rejectedCount").value(1))
                .andExpect(jsonPath("$.officers[0].settledAmountByCurrency.length()").value(0));
    }

    @Test
    void currenciesAreNeverMixedWithinOneOfficer() throws Exception {
        assign(createClaim("SG", "MOTOR", "100.00", "INR"), "OFF-A");
        assign(createClaim("US", "MOTOR", "100.00", "USD"), "OFF-A");
        getJson(PATH).andExpect(jsonPath("$.officers[0].openClaimCount").value(2))
                .andExpect(jsonPath("$.officers[0].openClaimedAmountByCurrency.length()").value(2))
                .andExpect(jsonPath("$.officers[0].openClaimedAmountByCurrency[0].currency").value("INR"))
                .andExpect(jsonPath("$.officers[0].openClaimedAmountByCurrency[0].amount").value(100.00))
                .andExpect(jsonPath("$.officers[0].openClaimedAmountByCurrency[1].currency").value("USD"))
                .andExpect(jsonPath("$.officers[0].openClaimedAmountByCurrency[1].amount").value(100.00));
    }

    @Test
    void onlyGetIsAllowed() throws Exception {
        mvc.perform(post(PATH)).andExpect(status().isMethodNotAllowed());
    }
}
