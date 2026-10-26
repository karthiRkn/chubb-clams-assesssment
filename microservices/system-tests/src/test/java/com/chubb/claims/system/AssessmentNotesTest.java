package com.chubb.claims.system;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AssessmentNotesTest extends SystemTestBase {

    private String assessments(String claimId) {
        return "/api/v1/officer/claims/" + claimId + "/assessments";
    }

    private Map<String, Object> reviewBody(String officer, String notes) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("officerId", officer);
        b.put("assessmentNotes", notes);
        return b;
    }

    private int rows() {
        return jdbc.queryForObject("select count(*) from claim_assessments", Integer.class);
    }

    @Test
    void reviewWithNotesSavesThemAndTheyCanBeRetrieved() throws Exception {
        String id = createClaim();
        postJson("/api/v1/officer/claims/" + id + "/review", reviewBody("off-1", "  Photos match the described damage.  "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNDER_REVIEW"));
        getJson(assessments(id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].claimId").value(id))
                .andExpect(jsonPath("$[0].officerId").value("off-1"))
                .andExpect(jsonPath("$[0].notes").value("Photos match the described damage."))
                .andExpect(jsonPath("$[0].createdAt").isNotEmpty());
    }

    @Test
    void reviewWithoutNotesStillWorksAndStoresNothing() throws Exception {
        String id = createClaim();
        postJson("/api/v1/officer/claims/" + id + "/review", Map.of("officerId", "off-1")).andExpect(status().isOk());
        getJson(assessments(id)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        assertEquals(0, rows());
    }

    @Test
    void blankOrNullNotesStoreNothing() throws Exception {
        String a = createClaim();
        postJson("/api/v1/officer/claims/" + a + "/review", reviewBody("off-1", "   ")).andExpect(status().isOk());
        String b = createClaim();
        postJson("/api/v1/officer/claims/" + b + "/review", reviewBody("off-1", null)).andExpect(status().isOk());
        assertEquals(0, rows());
    }

    @Test
    void notesOverTheLimitAreRejectedAndNothingChanges() throws Exception {
        String id = createClaim();
        postJson("/api/v1/officer/claims/" + id + "/review", reviewBody("off-1", "x".repeat(2001)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        getJson("/api/v1/claims/" + id).andExpect(jsonPath("$.status").value("SUBMITTED"));
        assertEquals(0, rows());
        postJson("/api/v1/officer/claims/" + id + "/review", reviewBody("off-1", "x".repeat(2000))).andExpect(status().isOk());
        assertEquals(1, rows());
    }

    @Test
    void rejectedReviewDoesNotSaveItsNotes() throws Exception {
        String id = createClaimUnderReview();
        postJson("/api/v1/officer/claims/" + id + "/review", reviewBody("off-1007", "Second look."))
                .andExpect(status().isConflict());
        String assigned = createClaim();
        assign(assigned, "off-1");
        postJson("/api/v1/officer/claims/" + assigned + "/review", reviewBody("off-2", "Not my claim."))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OFFICER_MISMATCH"));
        assertEquals(0, rows());
    }

    @Test
    void placeholderOfficerIdIsStillRejectedWhenNotesAreSent() throws Exception {
        String id = createClaim();
        postJson("/api/v1/officer/claims/" + id + "/review", reviewBody("string", "Notes."))
                .andExpect(status().isBadRequest());
        assertEquals(0, rows());
    }

    @Test
    void aSecondReviewAfterAnInformationAnswerIsAConflictAndAddsNoNote() throws Exception {
        String id = createClaim();
        postJson("/api/v1/officer/claims/" + id + "/review", reviewBody("off-1", "First pass: need the repair estimate."))
                .andExpect(status().isOk());
        String irId = read(postJson("/api/v1/officer/claims/" + id + "/information-requests",
                Map.of("question", "Repair estimate?", "requestedByOfficerId", "off-1")))
                .get("informationRequestId").asText();
        postJson("/api/v1/claims/" + id + "/information", Map.of("informationRequestId", irId, "answer", "Attached."))
                .andExpect(status().isOk());
        // The answer already resumed review, so a further review call is a 409 and must not add a note.
        postJson("/api/v1/officer/claims/" + id + "/review", reviewBody("off-1", "Second pass.")).andExpect(status().isConflict());
        getJson(assessments(id))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].notes").value("First pass: need the repair estimate."));
    }

    @Test
    void resumingReviewFromAwaitingInformationWithNoOpenRequestSavesTheNote() throws Exception {
        String id = createClaim();
        postJson("/api/v1/officer/claims/" + id + "/review", reviewBody("off-1", "First pass.")).andExpect(status().isOk());
        // Put the claim in MORE_INFO_REQUIRED with no OPEN request, which only a direct data change can do here.
        jdbc.update("update claims set status = 'MORE_INFO_REQUIRED' where claim_id = ?", id);
        postJson("/api/v1/officer/claims/" + id + "/review", reviewBody("off-1", "Resumed."))
                .andExpect(status().isOk());
        getJson(assessments(id))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].notes").value("First pass."))
                .andExpect(jsonPath("$[1].notes").value("Resumed."));
    }

    @Test
    void notesAreNotExposedOnTheClaimantClaimResponse() throws Exception {
        String id = createClaim();
        postJson("/api/v1/officer/claims/" + id + "/review", reviewBody("off-1", "Internal view only.")).andExpect(status().isOk());
        getJson("/api/v1/claims/" + id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assessmentNotes").doesNotExist());
    }

    @Test
    void unknownClaimIs404AndPostIs405() throws Exception {
        getJson(assessments("CLM-2026-999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CLAIM_NOT_FOUND"));
        postJson(assessments(createClaim()), Map.of()).andExpect(status().isMethodNotAllowed());
    }
}
