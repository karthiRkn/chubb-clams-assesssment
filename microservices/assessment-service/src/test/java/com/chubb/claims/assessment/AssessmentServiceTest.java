package com.chubb.claims.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chubb.claims.common.ApiException;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AssessmentServiceTest extends IntegrationTestBase {

    private static String assessments(String claimId) {
        return "/api/v1/officer/claims/" + claimId + "/assessments";
    }

    // ---- POST .../review ----

    @Test
    void reviewWithNotesSavesThemTrimmedAndTheyCanBeRetrieved() throws Exception {
        review(CLAIM, "OFF-1", "  Photos match the described damage.  ")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.claimId").value(CLAIM))
                .andExpect(jsonPath("$.status").value("UNDER_REVIEW"))
                .andExpect(jsonPath("$.assignedOfficerId").value("OFF-1"))
                .andExpect(jsonPath("$.updatedAt").exists());
        verify(claims).startReview(CLAIM, "OFF-1", false, false);
        getJson(assessments(CLAIM))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].claimId").value(CLAIM))
                .andExpect(jsonPath("$[0].officerId").value("OFF-1"))
                .andExpect(jsonPath("$[0].kind").value("NOTE"))
                .andExpect(jsonPath("$[0].notes").value("Photos match the described damage."))
                .andExpect(jsonPath("$[0].createdAt").isNotEmpty());
    }

    @Test
    void reviewWithoutNotesOrWithBlankNotesStillWorksAndStoresNothing() throws Exception {
        postJson("/api/v1/officer/claims/" + CLAIM + "/review", Map.of("officerId", "OFF-1")).andExpect(status().isOk());
        review(CLAIM, "OFF-1", "   ").andExpect(status().isOk());
        review(CLAIM, "OFF-1", null).andExpect(status().isOk());
        assertThat(rows()).isZero();
        getJson(assessments(CLAIM)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void notesOverTheLimitAreRejectedBeforeAnyOtherServiceIsCalled() throws Exception {
        review(CLAIM, "OFF-1", "x".repeat(2001))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        verifyNoInteractions(claims);
        assertThat(rows()).isZero();
        review(CLAIM, "OFF-1", "x".repeat(2000)).andExpect(status().isOk());
        assertThat(rows()).isEqualTo(1);
    }

    @Test
    void placeholderAndOverlongOfficerIdsAreRejectedAndNothingIsCalledOrStored() throws Exception {
        review(CLAIM, "string", "Notes.").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("must be a real officer ID")));
        review(CLAIM, "O".repeat(51), "Notes.").andExpect(status().isBadRequest());
        verifyNoInteractions(claims);
        assertThat(rows()).isZero();
    }

    @Test
    void aReviewTheClaimServiceRefusesSavesNoNoteAndKeepsTheClaimServicesError() throws Exception {
        when(claims.startReview(eq(CLAIM), eq("OFF-1007"), anyBoolean(), anyBoolean())).thenThrow(
                ApiException.conflict("INVALID_CLAIM_TRANSITION",
                        "Review can only be started for a SUBMITTED or MORE_INFO_REQUIRED claim, but claim is UNDER_REVIEW"));
        review(CLAIM, "OFF-1007", "Second look.")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_CLAIM_TRANSITION"))
                .andExpect(jsonPath("$.path").value("/api/v1/officer/claims/" + CLAIM + "/review"));
        assertThat(rows()).isZero();

        when(claims.startReview(eq(CLAIM), eq("OFF-2"), anyBoolean(), anyBoolean())).thenThrow(
                ApiException.conflict("OFFICER_MISMATCH", "Claim is assigned to OFF-1, not OFF-2"));
        review(CLAIM, "OFF-2", "Not my claim.")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OFFICER_MISMATCH"));
        assertThat(rows()).isZero();
    }

    @Test
    void reviewOfAnUnknownClaimIs404AndSavesNoNote() throws Exception {
        review(UNKNOWN, "OFF-1", "Notes.").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CLAIM_NOT_FOUND"));
        assertThat(rows()).isZero();
    }

    @Test
    void anOpenInformationRequestIsPassedOnToTheClaimServiceAsAFact() throws Exception {
        review(CLAIM, "OFF-1", null).andExpect(status().isOk());
        verify(claims).startReview(CLAIM, "OFF-1", false, false);

        claimIs(CLAIM, "UNDER_REVIEW", OWNER, "OFF-1");
        ask(CLAIM, "Photos please", "OFF-1").andExpect(status().isCreated());
        review(CLAIM, "OFF-1", null).andExpect(status().isOk());
        verify(claims).startReview(CLAIM, "OFF-1", true, false);

        // answered: nothing open any more
        claimIs(CLAIM, "MORE_INFO_REQUIRED", OWNER, "OFF-1");
        String irId = jdbc.queryForObject("select information_request_id from information_requests", String.class);
        answer(CLAIM, irId, "Here.").andExpect(status().isOk());
        review(CLAIM, "OFF-1", null).andExpect(status().isOk());
        // the first and the third review saw nothing open, the second saw the OPEN request
        verify(claims, org.mockito.Mockito.times(2)).startReview(CLAIM, "OFF-1", false, false);
        verify(claims, org.mockito.Mockito.times(1)).startReview(CLAIM, "OFF-1", true, false);
    }

    @Test
    void ifTheClaimServiceIsDownReviewFailsWith503AndTheNoteIsRolledBack() throws Exception {
        when(claims.startReview(eq(CLAIM), anyString(), anyBoolean(), anyBoolean())).thenThrow(
                ApiException.unavailable("DOWNSTREAM_UNAVAILABLE", "Claim Service is unavailable or did not answer in time"));
        review(CLAIM, "OFF-1", "Notes.")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DOWNSTREAM_UNAVAILABLE"));
        assertThat(rows()).isZero();
    }

    @Test
    void everyAcceptedReviewAddsItsOwnNoteInOrder() throws Exception {
        review(CLAIM, "OFF-1", "First pass.").andExpect(status().isOk());
        review(CLAIM, "OFF-1", "Resumed.").andExpect(status().isOk());
        getJson(assessments(CLAIM))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].notes").value("First pass."))
                .andExpect(jsonPath("$[1].notes").value("Resumed."));
    }

    // ---- GET .../assessments ----

    @Test
    void assessmentsOfAnUnknownClaimIs404() throws Exception {
        getJson(assessments(UNKNOWN)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CLAIM_NOT_FOUND"));
    }

    @Test
    void assessmentsAreScopedToTheirClaim() throws Exception {
        review(CLAIM, "OFF-1", "For 101.").andExpect(status().isOk());
        review("CLM-2026-000102", "OFF-1", "For 102.").andExpect(status().isOk());
        getJson(assessments(CLAIM)).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].notes").value("For 101."));
        getJson(assessments("CLM-2026-000102")).andExpect(jsonPath("$.length()").value(1));
    }

    // ---- POST .../decision ----

    @Test
    void anApprovalIsForwardedAndTheClaimServicesAnswerIsReturned() throws Exception {
        decide(CLAIM, decisionBody("APPROVED", "2200.00", "INR", "OFF-1007"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.claimId").value(CLAIM))
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.decisionReason").value("Reason"))
                .andExpect(jsonPath("$.approvedAmount").value(2200.00))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.decidedByOfficerId").value("OFF-1007"))
                .andExpect(jsonPath("$.decidedAt").exists());
        verify(claims).decide(eq(CLAIM), eq("APPROVED"), eq("Reason"), org.mockito.ArgumentMatchers.argThat(
                (BigDecimal a) -> a != null && a.compareTo(new BigDecimal("2200.00")) == 0), eq("INR"), eq("OFF-1007"));
    }

    @Test
    void aRejectionCarriesNoAmount() throws Exception {
        decide(CLAIM, decisionBody("REJECTED", null, "INR", "OFF-1007"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.approvedAmount").doesNotExist());
        verify(claims).decide(eq(CLAIM), eq("REJECTED"), eq("Reason"), eq(null), eq("INR"), eq("OFF-1007"));
    }

    @Test
    void theDecisionAndItsReasonBecomeAnEntryOfTheAssessmentHistory() throws Exception {
        review(CLAIM, "OFF-1007", "Looks genuine.").andExpect(status().isOk());
        decide(CLAIM, decisionBody("APPROVED", "100.00", "INR", "OFF-1007")).andExpect(status().isOk());
        getJson(assessments(CLAIM)).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].kind").value("NOTE")).andExpect(jsonPath("$[0].notes").value("Looks genuine."))
                .andExpect(jsonPath("$[1].kind").value("APPROVED")).andExpect(jsonPath("$[1].notes").value("Reason"))
                .andExpect(jsonPath("$[1].officerId").value("OFF-1007"));

        String rejected = "CLM-2026-000102";
        claimIs(rejected, "UNDER_REVIEW");
        decide(rejected, decisionBody("REJECTED", null, "INR", "OFF-1007")).andExpect(status().isOk());
        getJson(assessments(rejected)).andExpect(jsonPath("$[0].kind").value("REJECTED"));
    }

    @Test
    void aDecisionTheClaimServiceRefusesLeavesNoEntryInTheAssessmentHistory() throws Exception {
        when(claims.decide(eq(CLAIM), anyString(), anyString(), any(), anyString(), anyString())).thenThrow(
                ApiException.conflict("INVALID_CLAIM_TRANSITION", "A decision can only be recorded for a claim under review, but claim is APPROVED"));
        decide(CLAIM, decisionBody("REJECTED", null, "INR", "OFF-1")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_CLAIM_TRANSITION"));
        assertThat(rows()).isZero();
    }

    @Test
    void theClaimServicesBusinessErrorsReachTheClientWithTheirOwnStatusAndCode() throws Exception {
        when(claims.decide(eq(CLAIM), anyString(), anyString(), any(), anyString(), anyString())).thenThrow(
                ApiException.conflict("OFFICER_MISMATCH", "Claim is assigned to OFF-1007, not OFF-1"));
        decide(CLAIM, decisionBody("REJECTED", null, "INR", "OFF-1")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OFFICER_MISMATCH"));

        when(claims.decide(eq(CLAIM), anyString(), anyString(), any(), anyString(), anyString())).thenThrow(
                ApiException.badRequest("CURRENCY_MISMATCH", "Decision currency AUD must match the claim currency INR"));
        decide(CLAIM, decisionBody("APPROVED", "100.00", "AUD", "OFF-1")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CURRENCY_MISMATCH"));

        when(claims.decide(eq(CLAIM), anyString(), anyString(), any(), anyString(), anyString())).thenThrow(
                ApiException.badRequest("INVALID_APPROVED_AMOUNT", "approvedAmount cannot exceed the claimed amount"));
        decide(CLAIM, decisionBody("APPROVED", "2400.01", "INR", "OFF-1")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_APPROVED_AMOUNT"));

        decide(UNKNOWN, decisionBody("APPROVED", "1.00", "INR", "OFF-1")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CLAIM_NOT_FOUND"));
        assertThat(rows()).isZero();
    }

    @Test
    void invalidDecisionBodiesAre400AndTheClaimServiceIsNotCalled() throws Exception {
        decide(CLAIM, Map.of("decision", "APPROVED")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        decide(CLAIM, decisionBody("APPROVED", "1.00", "inr", "OFF-1")).andExpect(status().isBadRequest());
        decide(CLAIM, decisionBody("APPROVED", "1.001", "INR", "OFF-1")).andExpect(status().isBadRequest());
        decide(CLAIM, decisionBody("APPROVED", "1.00", "INR", "string")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("must be a real officer ID")));
        for (String notADecision : new String[] {"SETTLED", "UNDER_REVIEW", "MAYBE"}) {
            Map<String, Object> badEnum = decisionBody("APPROVED", "1.00", "INR", "OFF-1");
            badEnum.put("decision", notADecision);
            decide(CLAIM, badEnum).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        }
        Map<String, Object> noReason = decisionBody("APPROVED", "1.00", "INR", "OFF-1");
        noReason.put("decisionReason", "");
        decide(CLAIM, noReason).andExpect(status().isBadRequest());
        verifyNoInteractions(claims);
        assertThat(rows()).isZero();
    }

    @Test
    void ifTheClaimServiceIsDownTheDecisionFailsWith503AndLeavesNoEntry() throws Exception {
        when(claims.decide(eq(CLAIM), anyString(), anyString(), any(), anyString(), anyString())).thenThrow(
                ApiException.unavailable("DOWNSTREAM_UNAVAILABLE", "Claim Service is unavailable or did not answer in time"));
        decide(CLAIM, decisionBody("REJECTED", null, "INR", "OFF-1")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DOWNSTREAM_UNAVAILABLE"));
        assertThat(rows()).isZero();
    }

    @Test
    void aClaimServiceFailureThatIsNotABusinessAnswerBecomes502WithoutLeakingDetail() throws Exception {
        when(claims.decide(eq(CLAIM), anyString(), anyString(), any(), anyString(), anyString())).thenThrow(
                ApiException.badGateway("DOWNSTREAM_ERROR", "Claim Service answered with HTTP 500 which this service cannot handle"));
        decide(CLAIM, decisionBody("REJECTED", null, "INR", "OFF-1")).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("DOWNSTREAM_ERROR"))
                .andExpect(jsonPath("$.trace").doesNotExist());
    }

    // ---- POST .../settlement ----

    @Test
    void settlingAnApprovedClaimIsForwardedAndRecordedInTheHistory() throws Exception {
        claimIs(CLAIM, "APPROVED", OWNER, "OFF-1007");
        settle(CLAIM, "OFF-1007", "  Paid by bank transfer on 2026-10-30.  ")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.claimId").value(CLAIM))
                .andExpect(jsonPath("$.status").value("SETTLED"))
                .andExpect(jsonPath("$.approvedAmount").value(2200.00))
                .andExpect(jsonPath("$.settledByOfficerId").value("OFF-1007"))
                .andExpect(jsonPath("$.settledAt").exists());
        verify(claims).settle(CLAIM, "OFF-1007");
        getJson(assessments(CLAIM)).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].kind").value("SETTLED"))
                .andExpect(jsonPath("$[0].notes").value("Paid by bank transfer on 2026-10-30."))
                .andExpect(jsonPath("$[0].officerId").value("OFF-1007"));
    }

    @Test
    void aSettlementWithoutANoteRecordsADefaultOne() throws Exception {
        claimIs(CLAIM, "APPROVED", OWNER, "OFF-1007");
        postJson("/api/v1/officer/claims/" + CLAIM + "/settlement", Map.of("settledByOfficerId", "OFF-1007")).andExpect(status().isOk());
        postJson("/api/v1/officer/claims/" + CLAIM + "/settlement", Map.of("settledByOfficerId", "OFF-1007", "settlementNote", "  "))
                .andExpect(status().isOk());
        getJson(assessments(CLAIM)).andExpect(jsonPath("$[0].notes").value("Claim settled"))
                .andExpect(jsonPath("$[1].notes").value("Claim settled"));
    }

    @Test
    void aSettlementTheClaimServiceRefusesLeavesNoEntry() throws Exception {
        when(claims.settle(eq(CLAIM), anyString())).thenThrow(
                ApiException.conflict("INVALID_CLAIM_TRANSITION", "A rejected claim cannot be settled"));
        settle(CLAIM, "OFF-1007", "Paid.").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_CLAIM_TRANSITION"))
                .andExpect(jsonPath("$.message").value("A rejected claim cannot be settled"));
        assertThat(rows()).isZero();

        when(claims.settle(eq(CLAIM), anyString())).thenThrow(
                ApiException.conflict("OFFICER_MISMATCH", "Claim is assigned to OFF-1, not OFF-1007"));
        settle(CLAIM, "OFF-1007", "Paid.").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OFFICER_MISMATCH"));
        assertThat(rows()).isZero();
    }

    @Test
    void settlementOfAnUnknownClaimIs404AndAnInvalidBodyIs400WithoutCallingTheClaimService() throws Exception {
        settle(UNKNOWN, "OFF-1", "n").andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CLAIM_NOT_FOUND"));
        verify(claims, never()).settle(anyString(), anyString());
        postJson("/api/v1/officer/claims/" + CLAIM + "/settlement", Map.of("settledByOfficerId", "string")).andExpect(status().isBadRequest());
        postJson("/api/v1/officer/claims/" + CLAIM + "/settlement", Map.of("settlementNote", "x".repeat(2001))).andExpect(status().isBadRequest());
        verify(claims, never()).settle(anyString(), anyString());
        assertThat(rows()).isZero();
    }
}
