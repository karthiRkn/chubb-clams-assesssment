package com.chubb.claims.system;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** The README demo flow, end to end: one claim travelling through the Claim and Assessment services. */
class DemoFlowTest extends SystemTestBase {

    @Test
    void aClaimGoesFromSubmissionToSettlementAcrossTheServices() throws Exception {
        // Claim Service
        String id = createClaim("SG", "MOTOR", "2400.00", "INR");
        getJson("/api/v1/claims/" + id).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUBMITTED"));
        getJson("/api/v1/officer/claims?status=SUBMITTED&market=SG").andExpect(jsonPath("$.totalElements").value(1));
        assign(id, "off-1007"); // the Claim Service asks the User Service that off-1007 is an active claims officer

        // Assessment Service -> Claim Service (transition)
        postJson("/api/v1/officer/claims/" + id + "/review", Map.of("officerId", "off-1007",
                "assessmentNotes", "Photos match; need the repair estimate."))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UNDER_REVIEW"));

        // Assessment Service (information requests) -> Claim Service
        String irId = read(postJson("/api/v1/officer/claims/" + id + "/information-requests",
                Map.of("question", "Please provide the repair estimate.", "requestedByOfficerId", "off-1007")))
                .get("informationRequestId").asText();
        getJson("/api/v1/claims/" + id).andExpect(jsonPath("$.status").value("MORE_INFO_REQUIRED"));
        getJson("/api/v1/claims/" + id + "/information-requests")
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].status").value("OPEN"));
        postJson("/api/v1/claims/" + id + "/information", Map.of("informationRequestId", irId, "answer", "Estimate is 2,200 INR."))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ANSWERED"));
        getJson("/api/v1/claims/" + id).andExpect(jsonPath("$.status").value("UNDER_REVIEW"));

        // the assessment history, kept out of the claimant's view
        getJson("/api/v1/officer/claims/" + id + "/assessments")
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].notes").value("Photos match; need the repair estimate."));
        getJson("/api/v1/claims/" + id).andExpect(jsonPath("$.assessmentNotes").doesNotExist());

        // the management report (Claim Service) sees the open claim at its claimed amount
        getJson("/api/v1/management/summary")
                .andExpect(jsonPath("$.outstandingClaimCount").value(1))
                .andExpect(jsonPath("$.outstandingLiabilityByCurrency[0].estimatedLiability").value(2400.00));

        // approval, with an amount (the decision rules are enforced by the Claim Service)
        postJson("/api/v1/officer/claims/" + id + "/decision", Map.of("decision", "APPROVED", "decisionReason", "Estimate approved.",
                "approvedAmount", 2200.00, "currency", "INR", "decidedByOfficerId", "off-1007"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.approvedAmount").value(2200.00));
        postJson("/api/v1/officer/claims/" + id + "/decision", Map.of("decision", "REJECTED", "decisionReason", "x",
                "currency", "INR", "decidedByOfficerId", "off-1007"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_CLAIM_TRANSITION"));
        // approved is not settled: still outstanding, now at the approved amount
        getJson("/api/v1/management/summary").andExpect(jsonPath("$.outstandingClaimCount").value(1))
                .andExpect(jsonPath("$.outstandingLiabilityByCurrency[0].estimatedLiability").value(2200.00))
                .andExpect(jsonPath("$.countsByStatus.APPROVED").value(1));

        // settlement
        postJson("/api/v1/officer/claims/" + id + "/settlement", Map.of("settledByOfficerId", "off-1007", "settlementNote", "Paid."))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SETTLED"));
        getJson("/api/v1/management/summary").andExpect(jsonPath("$.outstandingClaimCount").value(0))
                .andExpect(jsonPath("$.countsByStatus.SETTLED").value(1));
        getJson("/api/v1/management/officers").andExpect(jsonPath("$.officers[0].officerId").value("off-1007"))
                .andExpect(jsonPath("$.officers[0].settledCount").value(1))
                .andExpect(jsonPath("$.officers[0].approvedAmountByCurrency[0].amount").value(2200.00));
        getJson("/api/v1/claims/" + id + "/history").andExpect(jsonPath("$.length()").value(6));
        getJson("/api/v1/officer/claims/" + id + "/assessments").andExpect(jsonPath("$.length()").value(3));
        getJson("/api/v1/claims/CLM-2026-999999").andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CLAIM_NOT_FOUND"));
    }
}
