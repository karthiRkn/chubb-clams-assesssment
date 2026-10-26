package com.chubb.claims.system;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ManagementSummaryTest extends SystemTestBase {

    @Test
    void emptyDatabaseGivesZeroCountsForEveryStatus() throws Exception {
        getJson("/api/v1/management/summary")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outstandingClaimCount").value(0))
                .andExpect(jsonPath("$.outstandingLiabilityByCurrency.length()").value(0))
                .andExpect(jsonPath("$.countsByStatus.SUBMITTED").value(0))
                .andExpect(jsonPath("$.countsByStatus.UNDER_REVIEW").value(0))
                .andExpect(jsonPath("$.countsByStatus.MORE_INFO_REQUIRED").value(0))
                .andExpect(jsonPath("$.countsByStatus.APPROVED").value(0))
                .andExpect(jsonPath("$.countsByStatus.SETTLED").value(0))
                .andExpect(jsonPath("$.countsByStatus.REJECTED").value(0))
                .andExpect(jsonPath("$.liabilityNote").value(org.hamcrest.Matchers.containsString("Not an actuarial reserve")));
    }

    @Test
    void excludesTerminalClaimsAndGroupsLiabilityByCurrencyWithoutMixing() throws Exception {
        createClaim("SG", "MOTOR", "1000.00", "INR");                       // SUBMITTED, outstanding
        String inrReview = createClaim("SG", "MOTOR", "800.50", "INR");     // UNDER_REVIEW, outstanding
        review(inrReview, "off-1");
        createClaim("AU", "PROPERTY", "5200.00", "AUD");                    // SUBMITTED, outstanding

        String awaiting = createClaim("SG", "MOTOR", "10.00", "INR");       // MORE_INFO_REQUIRED, outstanding
        review(awaiting, "off-1");
        postJson("/api/v1/officer/claims/" + awaiting + "/information-requests",
                Map.of("question", "Q", "requestedByOfficerId", "off-1")).andExpect(status().isCreated());

        String settled = createClaim("SG", "MOTOR", "9999.00", "INR");      // SETTLED, excluded
        review(settled, "off-1");
        Map<String, Object> settle = new LinkedHashMap<>();
        settle.put("decision", "APPROVED");
        settle.put("decisionReason", "ok");
        settle.put("approvedAmount", new BigDecimal("9000.00"));
        settle.put("currency", "INR");
        settle.put("decidedByOfficerId", "off-1");
        postJson("/api/v1/officer/claims/" + settled + "/decision", settle).andExpect(status().isOk());
        postJson("/api/v1/officer/claims/" + settled + "/settlement", Map.of("settledByOfficerId", "off-1")).andExpect(status().isOk());

        String rejected = createClaim("HK", "MOTOR", "7777.00", "HKD");     // REJECTED, excluded
        review(rejected, "off-1");
        Map<String, Object> reject = new LinkedHashMap<>();
        reject.put("decision", "REJECTED");
        reject.put("decisionReason", "no");
        reject.put("approvedAmount", null);
        reject.put("currency", "HKD");
        reject.put("decidedByOfficerId", "off-1");
        postJson("/api/v1/officer/claims/" + rejected + "/decision", reject).andExpect(status().isOk());

        getJson("/api/v1/management/summary")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generatedAt").exists())
                .andExpect(jsonPath("$.outstandingClaimCount").value(4))
                .andExpect(jsonPath("$.outstandingLiabilityByCurrency.length()").value(2))
                // ordered by currency: AUD then INR; HKD absent because its only claim is terminal
                .andExpect(jsonPath("$.outstandingLiabilityByCurrency[0].currency").value("AUD"))
                .andExpect(jsonPath("$.outstandingLiabilityByCurrency[0].claimCount").value(1))
                .andExpect(jsonPath("$.outstandingLiabilityByCurrency[0].estimatedLiability").value(5200.00))
                .andExpect(jsonPath("$.outstandingLiabilityByCurrency[1].currency").value("INR"))
                .andExpect(jsonPath("$.outstandingLiabilityByCurrency[1].claimCount").value(3))
                .andExpect(jsonPath("$.outstandingLiabilityByCurrency[1].estimatedLiability").value(1810.50))
                .andExpect(jsonPath("$.countsByStatus.SUBMITTED").value(2))
                .andExpect(jsonPath("$.countsByStatus.UNDER_REVIEW").value(1))
                .andExpect(jsonPath("$.countsByStatus.MORE_INFO_REQUIRED").value(1))
                .andExpect(jsonPath("$.countsByStatus.SETTLED").value(1))
                .andExpect(jsonPath("$.countsByStatus.REJECTED").value(1));
    }
}
