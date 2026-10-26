package com.chubb.claims.claim;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** Status history and assignment history, written by the Claim Service in the same transaction as each change. */
class HistoryEndpointsTest extends IntegrationTestBase {

    private static final String UNKNOWN = "CLM-2026-999999";

    private static String history(String claimId) {
        return "/api/v1/claims/" + claimId + "/history";
    }

    private static String assignments(String claimId) {
        return "/api/v1/claims/" + claimId + "/assignments";
    }

    @Test
    void newClaimHasOneCreationRowAndNoAssignments() throws Exception {
        String id = createClaim();
        getJson(history(id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].claimId").value(id))
                .andExpect(jsonPath("$[0].fromStatus").doesNotExist())
                .andExpect(jsonPath("$[0].toStatus").value("SUBMITTED"))
                .andExpect(jsonPath("$[0].actor").value("test-admin"))
                .andExpect(jsonPath("$[0].changedAt").isNotEmpty());
        getJson(assignments(id)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void fullLifecycleIsRecordedInOrderWithActors() throws Exception {
        String id = createClaim();
        assign(id, "OFF-1007");
        review(id, "OFF-1007");
        requestInformation(id, "OFF-1007").andExpect(status().isOk());
        resume(id).andExpect(status().isOk());
        decide(id, decisionBody("REJECTED", null, "INR", "OFF-1007")).andExpect(status().isOk());

        getJson(history(id))
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$[0].toStatus").value("SUBMITTED"))
                .andExpect(jsonPath("$[1].fromStatus").value("SUBMITTED"))
                .andExpect(jsonPath("$[1].toStatus").value("UNDER_REVIEW"))
                .andExpect(jsonPath("$[1].actor").value("OFF-1007"))
                .andExpect(jsonPath("$[2].toStatus").value("MORE_INFO_REQUIRED"))
                .andExpect(jsonPath("$[2].actor").value("OFF-1007"))
                .andExpect(jsonPath("$[3].fromStatus").value("MORE_INFO_REQUIRED"))
                .andExpect(jsonPath("$[3].toStatus").value("UNDER_REVIEW"))
                .andExpect(jsonPath("$[3].actor").value("CLAIMANT"))
                .andExpect(jsonPath("$[4].toStatus").value("REJECTED"))
                .andExpect(jsonPath("$[4].actor").value("OFF-1007"));
    }

    @Test
    void assignmentRecordsPreviousNewOfficerAndTheCallerAsActorAndAddsNoStatusRow() throws Exception {
        String id = createClaim();
        assign(id, "OFF-1");
        assign(id, "OFF-2");
        assign(id, "OFF-2");
        getJson(assignments(id))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].claimId").value(id))
                .andExpect(jsonPath("$[0].previousOfficerId").doesNotExist())
                .andExpect(jsonPath("$[0].newOfficerId").value("OFF-1"))
                .andExpect(jsonPath("$[0].actor").value("test-admin"))
                .andExpect(jsonPath("$[1].previousOfficerId").value("OFF-1"))
                .andExpect(jsonPath("$[1].newOfficerId").value("OFF-2"));
        getJson(history(id)).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void reviewByTheAlreadyAssignedOfficerAddsNoAssignmentRow() throws Exception {
        String id = createClaim();
        assign(id, "OFF-1");
        review(id, "OFF-1");
        getJson(assignments(id)).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void rejectedTransitionsWriteNoHistory() throws Exception {
        String id = createClaimUnderReview();
        reviewRequest(id, "OFF-1007", false).andExpect(status().isConflict());
        decide(id, decisionBody("APPROVED", "999999.00", "INR", "OFF-1007")).andExpect(status().isBadRequest());
        postJson("/api/v1/officer/claims/" + id + "/assign", Map.of("officerId", "")).andExpect(status().isBadRequest());
        getJson(history(id)).andExpect(jsonPath("$.length()").value(2));
        getJson(assignments(id)).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void unknownClaimIs404AndBothEndpointsAreGetOnly() throws Exception {
        getJson(history(UNKNOWN)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CLAIM_NOT_FOUND"));
        getJson(assignments(UNKNOWN)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CLAIM_NOT_FOUND"));
        String id = createClaim();
        postJson(history(id), Map.of()).andExpect(status().isMethodNotAllowed());
        postJson(assignments(id), Map.of()).andExpect(status().isMethodNotAllowed());
    }
}
