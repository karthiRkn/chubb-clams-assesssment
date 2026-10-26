package com.chubb.claims.system;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OfficerMetricsTest extends SystemTestBase {

    private static final String PATH = "/api/v1/management/officers";

    private void decide(String claimId, String decision, String settled, String currency, String officer) throws Exception {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("decision", decision);
        b.put("decisionReason", "Reason");
        if (settled != null) {
            b.put("approvedAmount", new BigDecimal(settled));
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
        // off-a: one SUBMITTED (assigned only), one UNDER_REVIEW, both INR; one AUD claim approved but not settled
        String aSubmitted = createClaim("SG", "MOTOR", "1000.00", "INR");
        assign(aSubmitted, "off-a");
        String aReview = createClaim("SG", "MOTOR", "500.00", "INR");
        review(aReview, "off-a");
        String aSettled = createClaim("AU", "PROPERTY", "700.00", "AUD");
        review(aSettled, "off-a");
        decide(aSettled, "APPROVED", "600.00", "AUD", "off-a"); // approved but not yet settled: still open work

        // off-b: one MORE_INFO_REQUIRED (USD), one approved and settled INR, one rejected INR
        String bAwaiting = createClaim("US", "MOTOR", "150.00", "USD");
        review(bAwaiting, "off-b");
        postJson("/api/v1/officer/claims/" + bAwaiting + "/information-requests",
                Map.of("question", "Q", "requestedByOfficerId", "off-b")).andExpect(status().isCreated());
        String bSettled = createClaim("SG", "MOTOR", "300.00", "INR");
        review(bSettled, "off-b");
        decide(bSettled, "APPROVED", "250.00", "INR", "off-b");
        postJson("/api/v1/officer/claims/" + bSettled + "/settlement", Map.of("settledByOfficerId", "off-b")).andExpect(status().isOk());
        String bRejected = createClaim("SG", "MOTOR", "200.00", "INR");
        review(bRejected, "off-b");
        decide(bRejected, "REJECTED", null, "INR", "off-b");

        // unassigned claim must not appear anywhere
        createClaim("SG", "MOTOR", "9999.00", "INR");

        getJson(PATH).andExpect(status().isOk())
                .andExpect(jsonPath("$.officers.length()").value(2))
                // off-a (sorted first)
                .andExpect(jsonPath("$.officers[0].officerId").value("off-a"))
                .andExpect(jsonPath("$.officers[0].openClaimCount").value(3))
                .andExpect(jsonPath("$.officers[0].openClaimsByStatus.APPROVED").value(1))
                .andExpect(jsonPath("$.officers[0].openClaimsByStatus.SUBMITTED").value(1))
                .andExpect(jsonPath("$.officers[0].openClaimsByStatus.UNDER_REVIEW").value(1))
                .andExpect(jsonPath("$.officers[0].openClaimsByStatus.MORE_INFO_REQUIRED").value(0))
                .andExpect(jsonPath("$.officers[0].openClaimedAmountByCurrency.length()").value(2))
                .andExpect(jsonPath("$.officers[0].openClaimedAmountByCurrency[0].currency").value("AUD"))
                .andExpect(jsonPath("$.officers[0].openClaimedAmountByCurrency[0].amount").value(700.00))
                .andExpect(jsonPath("$.officers[0].openClaimedAmountByCurrency[1].currency").value("INR"))
                .andExpect(jsonPath("$.officers[0].openClaimedAmountByCurrency[1].claimCount").value(2))
                .andExpect(jsonPath("$.officers[0].openClaimedAmountByCurrency[1].amount").value(1500.00))
                .andExpect(jsonPath("$.officers[0].decidedCount").value(1))
                .andExpect(jsonPath("$.officers[0].approvedCount").value(1))
                .andExpect(jsonPath("$.officers[0].settledCount").value(0))
                .andExpect(jsonPath("$.officers[0].rejectedCount").value(0))
                .andExpect(jsonPath("$.officers[0].approvedAmountByCurrency.length()").value(1))
                .andExpect(jsonPath("$.officers[0].approvedAmountByCurrency[0].currency").value("AUD"))
                .andExpect(jsonPath("$.officers[0].approvedAmountByCurrency[0].amount").value(600.00))
                // off-b
                .andExpect(jsonPath("$.officers[1].officerId").value("off-b"))
                .andExpect(jsonPath("$.officers[1].openClaimCount").value(1))
                .andExpect(jsonPath("$.officers[1].openClaimsByStatus.MORE_INFO_REQUIRED").value(1))
                .andExpect(jsonPath("$.officers[1].openClaimsByStatus.UNDER_REVIEW").value(0))
                .andExpect(jsonPath("$.officers[1].openClaimedAmountByCurrency[0].currency").value("USD"))
                .andExpect(jsonPath("$.officers[1].openClaimedAmountByCurrency[0].amount").value(150.00))
                .andExpect(jsonPath("$.officers[1].decidedCount").value(2))
                .andExpect(jsonPath("$.officers[1].approvedCount").value(1))
                .andExpect(jsonPath("$.officers[1].settledCount").value(1))
                .andExpect(jsonPath("$.officers[1].rejectedCount").value(1))
                .andExpect(jsonPath("$.officers[1].approvedAmountByCurrency.length()").value(1))
                .andExpect(jsonPath("$.officers[1].approvedAmountByCurrency[0].currency").value("INR"))
                .andExpect(jsonPath("$.officers[1].approvedAmountByCurrency[0].amount").value(250.00));
    }

    @Test
    void officerWithOnlyDecisionsStillAppearsWithZeroOpenWork() throws Exception {
        String id = createClaim();
        review(id, "off-c");
        decide(id, "REJECTED", null, "INR", "off-c");
        getJson(PATH).andExpect(jsonPath("$.officers.length()").value(1))
                .andExpect(jsonPath("$.officers[0].officerId").value("off-c"))
                .andExpect(jsonPath("$.officers[0].openClaimCount").value(0))
                .andExpect(jsonPath("$.officers[0].openClaimedAmountByCurrency.length()").value(0))
                .andExpect(jsonPath("$.officers[0].decidedCount").value(1))
                .andExpect(jsonPath("$.officers[0].rejectedCount").value(1))
                .andExpect(jsonPath("$.officers[0].approvedAmountByCurrency.length()").value(0));
    }

    @Test
    void currenciesAreNeverMixedWithinOneOfficer() throws Exception {
        assign(createClaim("SG", "MOTOR", "100.00", "INR"), "off-a");
        assign(createClaim("US", "MOTOR", "100.00", "USD"), "off-a");
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
