package com.chubb.claims.system;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.junit.jupiter.api.Test;

class StatusHistoryTest extends SystemTestBase {

    private String history(String claimId) {
        return "/api/v1/claims/" + claimId + "/history";
    }

    @Test
    void newClaimHasOneCreationRow() throws Exception {
        String id = createClaim();
        getJson(history(id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].claimId").value(id))
                .andExpect(jsonPath("$[0].fromStatus").doesNotExist())
                .andExpect(jsonPath("$[0].toStatus").value("SUBMITTED"))
                .andExpect(jsonPath("$[0].actor").value("test-admin"))
                .andExpect(jsonPath("$[0].changedAt").isNotEmpty());
    }

    @Test
    void fullLifecycleIsRecordedInOrderWithActors() throws Exception {
        String id = createClaim();
        assign(id, "off-1007");
        review(id, "off-1007");
        String irId = read(postJson("/api/v1/officer/claims/" + id + "/information-requests",
                Map.of("question", "Photos?", "requestedByOfficerId", "off-1007"))).get("informationRequestId").asText();
        postJson("/api/v1/claims/" + id + "/information", Map.of("informationRequestId", irId, "answer", "Attached."))
                .andExpect(status().isOk());
        postJson("/api/v1/officer/claims/" + id + "/decision", Map.of("decision", "REJECTED",
                "decisionReason", "Not covered.", "currency", "INR", "decidedByOfficerId", "off-1007"))
                .andExpect(status().isOk());

        getJson(history(id))
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$[0].toStatus").value("SUBMITTED"))
                .andExpect(jsonPath("$[1].fromStatus").value("SUBMITTED"))
                .andExpect(jsonPath("$[1].toStatus").value("UNDER_REVIEW"))
                .andExpect(jsonPath("$[1].actor").value("off-1007"))
                .andExpect(jsonPath("$[2].fromStatus").value("UNDER_REVIEW"))
                .andExpect(jsonPath("$[2].toStatus").value("MORE_INFO_REQUIRED"))
                .andExpect(jsonPath("$[2].actor").value("off-1007"))
                .andExpect(jsonPath("$[3].fromStatus").value("MORE_INFO_REQUIRED"))
                .andExpect(jsonPath("$[3].toStatus").value("UNDER_REVIEW"))
                .andExpect(jsonPath("$[3].actor").value("test-admin"))
                .andExpect(jsonPath("$[4].fromStatus").value("UNDER_REVIEW"))
                .andExpect(jsonPath("$[4].toStatus").value("REJECTED"))
                .andExpect(jsonPath("$[4].actor").value("off-1007"));
    }

    @Test
    void assignmentAloneAddsNoHistoryRow() throws Exception {
        String id = createClaim();
        assign(id, "off-1");
        getJson(history(id)).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void rejectedTransitionsWriteNoHistory() throws Exception {
        String id = createClaimUnderReview();
        postJson("/api/v1/officer/claims/" + id + "/review", Map.of("officerId", "off-1007"))
                .andExpect(status().isConflict());
        postJson("/api/v1/officer/claims/" + id + "/decision", Map.of("decision", "APPROVED",
                "decisionReason", "Too much.", "approvedAmount", "999999.00", "currency", "INR",
                "decidedByOfficerId", "off-1007")).andExpect(status().isBadRequest());
        getJson(history(id)).andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void unknownClaimHistoryIs404() throws Exception {
        getJson(history("CLM-2026-999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CLAIM_NOT_FOUND"));
    }

    @Test
    void historyEndpointIsGetOnly() throws Exception {
        postJson(history(createClaim()), Map.of()).andExpect(status().isMethodNotAllowed());
    }
}
