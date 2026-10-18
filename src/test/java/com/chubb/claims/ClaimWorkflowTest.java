package com.chubb.claims;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ClaimWorkflowTest extends IntegrationTestBase {

    private static String officer(String claimId, String action) {
        return "/api/v1/officer/claims/" + claimId + "/" + action;
    }

    private Map<String, Object> settle(String amount, String currency) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("decision", "SETTLED");
        b.put("decisionReason", "Repair estimate approved.");
        b.put("settledAmount", new java.math.BigDecimal(amount));
        b.put("currency", currency);
        b.put("decidedByOfficerId", "OFF-1007");
        return b;
    }

    private Map<String, Object> reject() {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("decision", "REJECTED");
        b.put("decisionReason", "Evidence does not meet the policy requirements.");
        b.put("settledAmount", null);
        b.put("currency", "SGD");
        b.put("decidedByOfficerId", "OFF-1007");
        return b;
    }

    // ---- assignment and review ----

    @Test
    void assignKeepsStatusSubmitted() throws Exception {
        String id = createClaim();
        postJson(officer(id, "assign"), Map.of("officerId", "OFF-1007"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.claimId").value(id))
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.assignedOfficerId").value("OFF-1007"));
    }

    @Test
    void reviewMovesToUnderReviewAndAssignsOfficer() throws Exception {
        String id = createClaim();
        postJson(officer(id, "review"), Map.of("officerId", "OFF-1007"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNDER_REVIEW"))
                .andExpect(jsonPath("$.assignedOfficerId").value("OFF-1007"));
    }

    @Test
    void assignAndReviewValidateBodyAndClaimExistence() throws Exception {
        String id = createClaim();
        postJson(officer(id, "assign"), Map.of("officerId", " ")).andExpect(status().isBadRequest());
        postJson(officer(id, "review"), Map.of()).andExpect(status().isBadRequest());
        postJson(officer("CLM-2026-999999", "assign"), Map.of("officerId", "OFF-1")).andExpect(status().isNotFound());
        postJson(officer("CLM-2026-999999", "review"), Map.of("officerId", "OFF-1")).andExpect(status().isNotFound());
    }

    @Test
    void reviewTwiceIsConflict() throws Exception {
        String id = createClaimUnderReview();
        postJson(officer(id, "review"), Map.of("officerId", "OFF-1007"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_CLAIM_TRANSITION"));
    }

    @Test
    void reviewByDifferentOfficerThanAssignedIsConflict() throws Exception {
        String id = createClaim();
        assign(id, "OFF-1007");
        postJson(officer(id, "review"), Map.of("officerId", "OFF-2000"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OFFICER_MISMATCH"));
    }

    // ---- information requests ----

    @Test
    void informationRequestMovesClaimToAwaitingInformation() throws Exception {
        String id = createClaimUnderReview();
        postJson(officer(id, "information-requests"),
                Map.of("question", "Please provide the repair estimate.", "requestedByOfficerId", "OFF-1007"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.informationRequestId").value(org.hamcrest.Matchers.matchesPattern("IR-\\d{4}-\\d{6}")))
                .andExpect(jsonPath("$.claimId").value(id))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.answer").doesNotExist());
        getJson("/api/v1/claims/" + id).andExpect(jsonPath("$.status").value("AWAITING_INFORMATION"));
    }

    @Test
    void informationRequestRequiresUnderReviewAndValidBody() throws Exception {
        String submitted = createClaim();
        postJson(officer(submitted, "information-requests"), Map.of("question", "q", "requestedByOfficerId", "OFF-1"))
                .andExpect(status().isConflict());
        String id = createClaimUnderReview();
        postJson(officer(id, "information-requests"), Map.of("question", "", "requestedByOfficerId", "OFF-1"))
                .andExpect(status().isBadRequest());
        postJson(officer("CLM-2026-999999", "information-requests"), Map.of("question", "q", "requestedByOfficerId", "OFF-1"))
                .andExpect(status().isNotFound());
    }

    @Test
    void answeringResumesReviewAutomaticallyAndRequestsAreListed() throws Exception {
        String id = createClaimUnderReview();
        String irId = read(postJson(officer(id, "information-requests"),
                Map.of("question", "Photos please", "requestedByOfficerId", "OFF-1007"))).get("informationRequestId").asText();

        getJson("/api/v1/claims/" + id + "/information-requests")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].informationRequestId").value(irId))
                .andExpect(jsonPath("$[0].claimId").value(id))
                .andExpect(jsonPath("$[0].status").value("OPEN"));

        postJson("/api/v1/claims/" + id + "/information", Map.of("informationRequestId", irId, "answer", "Estimate is 2,200 SGD."))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ANSWERED"))
                .andExpect(jsonPath("$.answer").value("Estimate is 2,200 SGD."))
                .andExpect(jsonPath("$.answeredAt").exists());

        getJson("/api/v1/claims/" + id).andExpect(jsonPath("$.status").value("UNDER_REVIEW"));
        getJson("/api/v1/claims/" + id + "/information-requests").andExpect(jsonPath("$[0].status").value("ANSWERED"));
    }

    @Test
    void claimStaysAwaitingUntilAllRequestsAnswered() throws Exception {
        String id = createClaimUnderReview();
        String first = read(postJson(officer(id, "information-requests"),
                Map.of("question", "Q1", "requestedByOfficerId", "OFF-1007"))).get("informationRequestId").asText();
        // claim is now AWAITING_INFORMATION, so a second request is not allowed (needs UNDER_REVIEW)
        postJson(officer(id, "information-requests"), Map.of("question", "Q2", "requestedByOfficerId", "OFF-1007"))
                .andExpect(status().isConflict());
        // explicit review cannot resume while a request is OPEN
        postJson(officer(id, "review"), Map.of("officerId", "OFF-1007")).andExpect(status().isConflict());
        postJson("/api/v1/claims/" + id + "/information", Map.of("informationRequestId", first, "answer", "A1"))
                .andExpect(status().isOk());
        getJson("/api/v1/claims/" + id).andExpect(jsonPath("$.status").value("UNDER_REVIEW"));
    }

    @Test
    void answerErrors() throws Exception {
        String id = createClaimUnderReview();
        String irId = read(postJson(officer(id, "information-requests"),
                Map.of("question", "Q", "requestedByOfficerId", "OFF-1007"))).get("informationRequestId").asText();

        postJson("/api/v1/claims/" + id + "/information", Map.of("informationRequestId", "IR-2026-999999", "answer", "A"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("INFORMATION_REQUEST_NOT_FOUND"));
        postJson("/api/v1/claims/" + id + "/information", Map.of("informationRequestId", irId, "answer", ""))
                .andExpect(status().isBadRequest());
        postJson("/api/v1/claims/CLM-2026-999999/information", Map.of("informationRequestId", irId, "answer", "A"))
                .andExpect(status().isNotFound());

        // request belonging to a different claim is not found for this claim
        String other = createClaim();
        postJson("/api/v1/claims/" + other + "/information", Map.of("informationRequestId", irId, "answer", "A"))
                .andExpect(status().isNotFound());

        postJson("/api/v1/claims/" + id + "/information", Map.of("informationRequestId", irId, "answer", "A"))
                .andExpect(status().isOk());
        // answering again: claim already resumed, so it is a conflict
        postJson("/api/v1/claims/" + id + "/information", Map.of("informationRequestId", irId, "answer", "B"))
                .andExpect(status().isConflict());
    }

    @Test
    void listInformationRequestsForUnknownClaimIs404() throws Exception {
        getJson("/api/v1/claims/CLM-2026-999999/information-requests").andExpect(status().isNotFound());
    }

    // ---- decisions ----

    @Test
    void settleRecordsDecisionAndMakesClaimTerminal() throws Exception {
        String id = createClaimUnderReview();
        postJson(officer(id, "decision"), settle("2200.00", "SGD"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.claimId").value(id))
                .andExpect(jsonPath("$.status").value("SETTLED"))
                .andExpect(jsonPath("$.settledAmount").value(2200.00))
                .andExpect(jsonPath("$.decidedByOfficerId").value("OFF-1007"))
                .andExpect(jsonPath("$.decidedAt").exists());
        getJson("/api/v1/claims/" + id)
                .andExpect(jsonPath("$.status").value("SETTLED"))
                .andExpect(jsonPath("$.decisionReason").value("Repair estimate approved."))
                .andExpect(jsonPath("$.settledAmount").value(2200.00));
    }

    @Test
    void rejectRecordsDecision() throws Exception {
        String id = createClaimUnderReview();
        postJson(officer(id, "decision"), reject())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.settledAmount").doesNotExist());
    }

    @Test
    void duplicateDecisionIsConflictAndOriginalDecisionIsKept() throws Exception {
        String id = createClaimUnderReview();
        postJson(officer(id, "decision"), settle("2200.00", "SGD")).andExpect(status().isOk());
        postJson(officer(id, "decision"), reject()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_CLAIM_TRANSITION"));
        postJson(officer(id, "decision"), settle("1.00", "SGD")).andExpect(status().isConflict());
        getJson("/api/v1/claims/" + id).andExpect(jsonPath("$.status").value("SETTLED"))
                .andExpect(jsonPath("$.settledAmount").value(2200.00));
    }

    @Test
    void decisionNotAllowedUnlessUnderReview() throws Exception {
        String submitted = createClaim();
        postJson(officer(submitted, "decision"), settle("10.00", "SGD")).andExpect(status().isConflict());

        String awaiting = createClaimUnderReview();
        postJson(officer(awaiting, "information-requests"), Map.of("question", "Q", "requestedByOfficerId", "OFF-1"))
                .andExpect(status().isCreated());
        postJson(officer(awaiting, "decision"), reject()).andExpect(status().isConflict());
    }

    @Test
    void terminalClaimsCannotBeReassignedReviewedOrQueried() throws Exception {
        String id = createClaimUnderReview();
        postJson(officer(id, "decision"), reject()).andExpect(status().isOk());
        postJson(officer(id, "assign"), Map.of("officerId", "OFF-9")).andExpect(status().isConflict());
        postJson(officer(id, "review"), Map.of("officerId", "OFF-1007")).andExpect(status().isConflict());
        postJson(officer(id, "information-requests"), Map.of("question", "Q", "requestedByOfficerId", "OFF-1"))
                .andExpect(status().isConflict());
    }

    @Test
    void decisionValidation() throws Exception {
        String id = createClaimUnderReview();
        postJson(officer(id, "decision"), settle("0.00", "SGD")).andExpect(status().isBadRequest());
        postJson(officer(id, "decision"), settle("-5.00", "SGD")).andExpect(status().isBadRequest());
        postJson(officer(id, "decision"), settle("2400.01", "SGD")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SETTLED_AMOUNT"));
        postJson(officer(id, "decision"), settle("100.00", "USD")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CURRENCY_MISMATCH"));

        Map<String, Object> missingAmount = settle("1.00", "SGD");
        missingAmount.put("settledAmount", null);
        postJson(officer(id, "decision"), missingAmount).andExpect(status().isBadRequest());

        Map<String, Object> rejectWithAmount = reject();
        rejectWithAmount.put("settledAmount", new java.math.BigDecimal("5.00"));
        postJson(officer(id, "decision"), rejectWithAmount).andExpect(status().isBadRequest());

        Map<String, Object> badEnum = settle("1.00", "SGD");
        badEnum.put("decision", "UNDER_REVIEW");
        postJson(officer(id, "decision"), badEnum).andExpect(status().isBadRequest());

        Map<String, Object> noReason = settle("1.00", "SGD");
        noReason.put("decisionReason", "");
        postJson(officer(id, "decision"), noReason).andExpect(status().isBadRequest());

        postJson(officer("CLM-2026-999999", "decision"), settle("1.00", "SGD")).andExpect(status().isNotFound());
        // nothing above should have changed the claim
        getJson("/api/v1/claims/" + id).andExpect(jsonPath("$.status").value("UNDER_REVIEW"));
    }

    @Test
    void settlingExactlyTheClaimedAmountIsAllowed() throws Exception {
        String id = createClaimUnderReview();
        postJson(officer(id, "decision"), settle("2400.00", "SGD")).andExpect(status().isOk());
    }
}
