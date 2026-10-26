package com.chubb.claims.system;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.junit.jupiter.api.Test;

class AssignmentHistoryTest extends SystemTestBase {

    private String assignments(String claimId) {
        return "/api/v1/claims/" + claimId + "/assignments";
    }

    @Test
    void newClaimHasNoAssignments() throws Exception {
        getJson(assignments(createClaim()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void firstAssignmentAndReassignmentRecordPreviousAndNewOfficerInOrder() throws Exception {
        String id = createClaim();
        assign(id, "off-1");
        assign(id, "off-2");
        getJson(assignments(id))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].claimId").value(id))
                .andExpect(jsonPath("$[0].previousOfficerId").doesNotExist())
                .andExpect(jsonPath("$[0].newOfficerId").value("off-1"))
                .andExpect(jsonPath("$[0].changedAt").isNotEmpty())
                .andExpect(jsonPath("$[1].previousOfficerId").value("off-1"))
                .andExpect(jsonPath("$[1].newOfficerId").value("off-2"));
    }

    @Test
    void assignActorIsTheCallerWhoMadeTheAssignment() throws Exception {
        String id = createClaim();
        assign(id, "off-1");
        getJson(assignments(id)).andExpect(jsonPath("$[0].actor").value("test-admin"));
    }

    @Test
    void assigningTheSameOfficerAgainAddsNoRow() throws Exception {
        String id = createClaim();
        assign(id, "off-1");
        assign(id, "off-1");
        getJson(assignments(id)).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void reviewOfAnUnassignedClaimRecordsTheAutomaticAssignmentWithTheReviewerAsActor() throws Exception {
        String id = createClaim();
        review(id, "off-7");
        getJson(assignments(id))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].previousOfficerId").doesNotExist())
                .andExpect(jsonPath("$[0].newOfficerId").value("off-7"))
                .andExpect(jsonPath("$[0].actor").value("off-7"));
    }

    @Test
    void reviewByTheAlreadyAssignedOfficerAddsNoRow() throws Exception {
        String id = createClaim();
        assign(id, "off-1");
        review(id, "off-1");
        getJson(assignments(id)).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void rejectedAssignmentsWriteNothing() throws Exception {
        String id = createClaimUnderReview();
        postJson("/api/v1/officer/claims/" + id + "/decision", Map.of("decision", "REJECTED",
                "decisionReason", "No cover.", "currency", "INR", "decidedByOfficerId", "off-1007"))
                .andExpect(status().isOk());
        int before = read(getJson(assignments(id))).size();
        postJson("/api/v1/officer/claims/" + id + "/assign", Map.of("officerId", "off-9")).andExpect(status().isConflict());
        postJson("/api/v1/officer/claims/" + id + "/assign", Map.of("officerId", "")).andExpect(status().isBadRequest());
        getJson(assignments(id)).andExpect(jsonPath("$.length()").value(before));
    }

    @Test
    void assignmentHistoryDoesNotChangeStatusHistory() throws Exception {
        String id = createClaim();
        assign(id, "off-1");
        getJson("/api/v1/claims/" + id + "/history").andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void unknownClaimIs404() throws Exception {
        getJson(assignments("CLM-2026-999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CLAIM_NOT_FOUND"));
    }

    @Test
    void endpointIsGetOnly() throws Exception {
        postJson(assignments(createClaim()), Map.of()).andExpect(status().isMethodNotAllowed());
    }
}
