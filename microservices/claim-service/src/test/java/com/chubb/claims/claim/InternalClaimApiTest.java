package com.chubb.claims.claim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.chubb.claims.common.ApiException;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The service-to-service transitions the Assessment Service triggers, reached through /internal/v1. */
class InternalClaimApiTest extends IntegrationTestBase {

    private static final String UNKNOWN = "CLM-2026-999999";

    // ---- status view ----

    @Test
    void statusViewHasClaimIdStatusClaimantAndOfficerOnly() throws Exception {
        String id = createClaim();
        getJson(INTERNAL + id + "/status")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.claimId").value(id))
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.claimantId").value("test-admin"))
                .andExpect(jsonPath("$.assignedOfficerId").doesNotExist())
                .andExpect(jsonPath("$.claimantEmail").doesNotExist())
                .andExpect(jsonPath("$.claimedAmount").doesNotExist());
    }

    @Test
    void statusOfUnknownClaimIs404() throws Exception {
        getJson(INTERNAL + UNKNOWN + "/status")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CLAIM_NOT_FOUND"));
    }

    // ---- review ----

    @Test
    void reviewMovesToUnderReviewAssignsTheReviewerAndRecordsBothHistories() throws Exception {
        String id = createClaim();
        reviewRequest(id, "OFF-1007", false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.claimId").value(id))
                .andExpect(jsonPath("$.status").value("UNDER_REVIEW"))
                .andExpect(jsonPath("$.assignedOfficerId").value("OFF-1007"))
                .andExpect(jsonPath("$.updatedAt").exists());
        getJson("/api/v1/claims/" + id + "/history")
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].toStatus").value("UNDER_REVIEW"))
                .andExpect(jsonPath("$[1].actor").value("OFF-1007"));
        getJson("/api/v1/claims/" + id + "/assignments")
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].newOfficerId").value("OFF-1007"))
                .andExpect(jsonPath("$[0].actor").value("OFF-1007"));
    }

    @Test
    void reviewValidatesTheBody() throws Exception {
        String id = createClaim();
        postJson(INTERNAL + id + "/review", Map.of()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        postJson(INTERNAL + id + "/review", Map.of("officerId", "OFF-1")).andExpect(status().isBadRequest());
        postJson(INTERNAL + id + "/review", Map.of("officerId", " ", "hasOpenInformationRequests", false))
                .andExpect(status().isBadRequest());
        postJson(INTERNAL + id + "/review", Map.of("officerId", "string", "hasOpenInformationRequests", false))
                .andExpect(status().isBadRequest());
        getJson("/api/v1/claims/" + id).andExpect(jsonPath("$.status").value("SUBMITTED"));
    }

    @Test
    void reviewOfUnknownClaimIs404() throws Exception {
        reviewRequest(UNKNOWN, "OFF-1", false)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CLAIM_NOT_FOUND"));
    }

    @Test
    void reviewTwiceIsConflict() throws Exception {
        String id = createClaimUnderReview();
        reviewRequest(id, "OFF-1007", false)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_CLAIM_TRANSITION"));
    }

    @Test
    void reviewByAnotherOfficerThanAssignedIsConflictAndChangesNothing() throws Exception {
        String id = createClaim();
        assign(id, "OFF-1007");
        reviewRequest(id, "OFF-2000", false)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OFFICER_MISMATCH"));
        getJson("/api/v1/claims/" + id).andExpect(jsonPath("$.status").value("SUBMITTED"));
        getJson("/api/v1/claims/" + id + "/history").andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void reviewCannotResumeWhileTheCallerReportsOpenInformationRequests() throws Exception {
        String id = createClaimUnderReview();
        requestInformation(id, "OFF-1007").andExpect(status().isOk());
        reviewRequest(id, "OFF-1007", true).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_CLAIM_TRANSITION"));
        getJson("/api/v1/claims/" + id).andExpect(jsonPath("$.status").value("MORE_INFO_REQUIRED"));
        // nothing open any more: the explicit review path resumes the claim
        reviewRequest(id, "OFF-1007", false).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNDER_REVIEW"));
    }

    // ---- request information ----

    @Test
    void requestInformationMovesToAwaitingInformationAndRecordsTheOfficerAsActor() throws Exception {
        String id = createClaimUnderReview();
        requestInformation(id, "OFF-1007")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.claimId").value(id))
                .andExpect(jsonPath("$.status").value("MORE_INFO_REQUIRED"));
        getJson("/api/v1/claims/" + id + "/history")
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[2].fromStatus").value("UNDER_REVIEW"))
                .andExpect(jsonPath("$[2].toStatus").value("MORE_INFO_REQUIRED"))
                .andExpect(jsonPath("$[2].actor").value("OFF-1007"));
    }

    @Test
    void requestInformationErrors() throws Exception {
        String submitted = createClaim();
        requestInformation(submitted, "OFF-1").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_CLAIM_TRANSITION"));
        postJson(INTERNAL + submitted + "/request-information", Map.of()).andExpect(status().isBadRequest());
        postJson(INTERNAL + submitted + "/request-information", Map.of("requestedByOfficerId", "string"))
                .andExpect(status().isBadRequest());
        requestInformation(UNKNOWN, "OFF-1").andExpect(status().isNotFound());
        getJson("/api/v1/claims/" + submitted + "/history").andExpect(jsonPath("$.length()").value(1));
    }

    // ---- resume after information ----

    @Test
    void resumeMovesBackToUnderReviewWithTheClaimantAsActor() throws Exception {
        String id = createClaimUnderReview();
        requestInformation(id, "OFF-1007").andExpect(status().isOk());
        resume(id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNDER_REVIEW"));
        getJson("/api/v1/claims/" + id + "/history")
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[3].fromStatus").value("MORE_INFO_REQUIRED"))
                .andExpect(jsonPath("$[3].toStatus").value("UNDER_REVIEW"))
                .andExpect(jsonPath("$[3].actor").value("CLAIMANT"));
    }

    @Test
    void resumeErrors() throws Exception {
        String id = createClaimUnderReview();
        resume(id).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_CLAIM_TRANSITION"));
        resume(UNKNOWN).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CLAIM_NOT_FOUND"));
        getJson("/api/v1/claims/" + id + "/history").andExpect(jsonPath("$.length()").value(2));
    }

    // ---- decision ----

    @Test
    void approveRecordsTheDecisionAndTheClaimWaitsForSettlement() throws Exception {
        String id = createClaimUnderReview();
        decide(id, decisionBody("APPROVED", "2200.00", "INR", "OFF-1007"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.claimId").value(id))
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.decisionReason").value("Reason"))
                .andExpect(jsonPath("$.approvedAmount").value(2200.00))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.decidedByOfficerId").value("OFF-1007"))
                .andExpect(jsonPath("$.decidedAt").exists())
                .andExpect(jsonPath("$.updatedAt").exists());
        getJson("/api/v1/claims/" + id)
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.approvedAmount").value(2200.00));
    }

    @Test
    void rejectRecordsTheDecisionWithoutAnAmount() throws Exception {
        String id = createClaimUnderReview();
        decide(id, decisionBody("REJECTED", null, "INR", "OFF-1007"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.approvedAmount").doesNotExist());
    }

    @Test
    void approvingExactlyTheClaimedAmountIsAllowedAndScaleIsTwoDecimals() throws Exception {
        String exact = createClaimUnderReview();
        decide(exact, decisionBody("APPROVED", "2400.00", "INR", "OFF-1007")).andExpect(status().isOk());
        String scaled = createClaimUnderReview();
        decide(scaled, decisionBody("APPROVED", "1000.5", "INR", "OFF-1007"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvedAmount").value(1000.50));
        assertThat(jdbc.queryForObject("select approved_amount from claims where claim_id = ?", BigDecimal.class, scaled))
                .isEqualByComparingTo("1000.50");
    }

    @Test
    void duplicateDecisionIsConflictAndTheFirstIsKept() throws Exception {
        String id = createClaimUnderReview();
        decide(id, decisionBody("APPROVED", "2200.00", "INR", "OFF-1007")).andExpect(status().isOk());
        decide(id, decisionBody("REJECTED", null, "INR", "OFF-1007")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_CLAIM_TRANSITION"));
        getJson("/api/v1/claims/" + id).andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.approvedAmount").value(2200.00));
    }

    @Test
    void decisionIsOnlyAllowedFromUnderReview() throws Exception {
        String submitted = createClaim();
        decide(submitted, decisionBody("REJECTED", null, "INR", "OFF-1")).andExpect(status().isConflict());
        String awaiting = createClaimUnderReview();
        requestInformation(awaiting, "OFF-1007").andExpect(status().isOk());
        decide(awaiting, decisionBody("REJECTED", null, "INR", "OFF-1")).andExpect(status().isConflict());
    }

    @Test
    void decisionValidationAndBusinessRules() throws Exception {
        String id = createClaimUnderReview();
        decide(id, decisionBody("APPROVED", "0.00", "INR", "OFF-1")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_APPROVED_AMOUNT"));
        decide(id, decisionBody("APPROVED", "-5.00", "INR", "OFF-1")).andExpect(status().isBadRequest());
        decide(id, decisionBody("APPROVED", "2400.01", "INR", "OFF-1")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_APPROVED_AMOUNT"));
        decide(id, decisionBody("APPROVED", "100.00", "AUD", "OFF-1")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CURRENCY_MISMATCH"));
        decide(id, decisionBody("APPROVED", null, "INR", "OFF-1")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_APPROVED_AMOUNT"));
        decide(id, decisionBody("REJECTED", "5.00", "INR", "OFF-1")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_APPROVED_AMOUNT"));
        decide(id, decisionBody("APPROVED", "1.00", "INR", "string")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        Map<String, Object> badEnum = decisionBody("APPROVED", "1.00", "INR", "OFF-1");
        badEnum.put("decision", "SETTLED");
        decide(id, badEnum).andExpect(status().isBadRequest());
        Map<String, Object> noReason = decisionBody("APPROVED", "1.00", "INR", "OFF-1");
        noReason.put("decisionReason", "");
        decide(id, noReason).andExpect(status().isBadRequest());

        decide(UNKNOWN, decisionBody("APPROVED", "1.00", "INR", "OFF-1")).andExpect(status().isNotFound());
        // nothing above changed the claim or its history
        getJson("/api/v1/claims/" + id).andExpect(jsonPath("$.status").value("UNDER_REVIEW"));
        getJson("/api/v1/claims/" + id + "/history").andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void decisionOnAnUnknownClaimWithAnInvalidBodyPrefersTheValidationError() throws Exception {
        postJson(INTERNAL + UNKNOWN + "/decision", Map.of("decision", "APPROVED")).andExpect(status().isBadRequest());
    }

    @Test
    void terminalClaimsCannotBeAssignedReviewedOrQueried() throws Exception {
        String id = createClaimUnderReview();
        decide(id, decisionBody("REJECTED", null, "INR", "OFF-1007")).andExpect(status().isOk());
        postJson("/api/v1/officer/claims/" + id + "/assign", Map.of("officerId", "OFF-9")).andExpect(status().isConflict());
        reviewRequest(id, "OFF-1007", false).andExpect(status().isConflict());
        requestInformation(id, "OFF-1007").andExpect(status().isConflict());
        resume(id).andExpect(status().isConflict());
    }

    // ---- starting a review assigns the claim, so the name must be a real officer unless the caller has proved it ----

    @Test
    void anUnverifiedReviewOfAnUnassignedClaimNeedsAnActiveClaimsOfficer() throws Exception {
        String id = createClaim();
        when(users.isActiveClaimsOfficer("typo.name")).thenReturn(false);
        reviewRequest(id, "typo.name", false).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_OFFICER"))
                .andExpect(jsonPath("$.message").value("typo.name is not an active claims officer"));
        getJson("/api/v1/claims/" + id).andExpect(jsonPath("$.status").value("SUBMITTED")).andExpect(jsonPath("$.assignedOfficerId").doesNotExist());
        getJson("/api/v1/claims/" + id + "/assignments").andExpect(jsonPath("$.length()").value(0));
        getJson("/api/v1/claims/" + id + "/history").andExpect(jsonPath("$.length()").value(1));
        verify(users).isActiveClaimsOfficer("typo.name");
    }

    @Test
    void anUnverifiedReviewByARealOfficerAssignsThemAsBefore() throws Exception {
        String id = createClaim();
        reviewRequest(id, "OFF-1007", false).andExpect(status().isOk()).andExpect(jsonPath("$.assignedOfficerId").value("OFF-1007"));
        verify(users).isActiveClaimsOfficer("OFF-1007");
    }

    @Test
    void aVerifiedReviewNeedsNoLookupBecauseTheCallerHasAlreadyProvedWhoTheOfficerIs() throws Exception {
        String id = createClaim();
        when(users.isActiveClaimsOfficer(anyString())).thenReturn(false); // would refuse anybody, if it were asked
        postJson(INTERNAL + id + "/review", Map.of("officerId", "officer.a", "hasOpenInformationRequests", false, "officerVerified", true))
                .andExpect(status().isOk()).andExpect(jsonPath("$.assignedOfficerId").value("officer.a"));
        verifyNoInteractions(users);
    }

    @Test
    void anExplicitlyUnverifiedReviewIsCheckedLikeAnAbsentFlag() throws Exception {
        String id = createClaim();
        when(users.isActiveClaimsOfficer("nobody")).thenReturn(false);
        postJson(INTERNAL + id + "/review", Map.of("officerId", "nobody", "hasOpenInformationRequests", false, "officerVerified", false))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_OFFICER"));
    }

    @Test
    void aClaimThatAlreadyHasThisOfficerIsNotCheckedAgain() throws Exception {
        String id = createClaim();
        assign(id, "OFF-1");
        clearInvocations(users);
        when(users.isActiveClaimsOfficer(anyString())).thenReturn(false);
        reviewRequest(id, "OFF-1", false).andExpect(status().isOk());
        verifyNoInteractions(users);
    }

    @Test
    void aReviewByTheWrongOfficerOfAnAssignedClaimIsAMismatchNotAnEligibilityQuestion() throws Exception {
        String id = createClaim();
        assign(id, "OFF-1");
        clearInvocations(users);
        reviewRequest(id, "OFF-2", false).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OFFICER_MISMATCH"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("OFF-1"))));
        verifyNoInteractions(users);
    }

    @Test
    void anUnknownClaimIs404EvenWhenTheOfficerIsNotEligible() throws Exception {
        when(users.isActiveClaimsOfficer("nobody")).thenReturn(false);
        reviewRequest(UNKNOWN, "nobody", false).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CLAIM_NOT_FOUND"));
    }

    @Test
    void ifTheUserServiceIsDownAnUnverifiedReviewOfAnUnassignedClaimIs503AndChangesNothing() throws Exception {
        String id = createClaim();
        when(users.isActiveClaimsOfficer(anyString())).thenThrow(
                ApiException.unavailable("DOWNSTREAM_UNAVAILABLE", "User Service is unavailable or did not answer in time"));
        reviewRequest(id, "OFF-1", false).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("DOWNSTREAM_UNAVAILABLE"));
        getJson("/api/v1/claims/" + id).andExpect(jsonPath("$.status").value("SUBMITTED"));
    }

    // ---- who may act: only the officer the claim is assigned to ----

    @Test
    void onlyTheAssignedOfficerCanRequestInformationDecideOrSettle() throws Exception {
        String id = createClaimUnderReview(); // assigned to OFF-1007 by the review
        requestInformation(id, "OFF-2000").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OFFICER_MISMATCH"));
        decide(id, decisionBody("APPROVED", "100.00", "INR", "OFF-2000")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OFFICER_MISMATCH"));
        decide(id, decisionBody("REJECTED", null, "INR", "OFF-2000")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OFFICER_MISMATCH"));
        getJson("/api/v1/claims/" + id).andExpect(jsonPath("$.status").value("UNDER_REVIEW"));

        decide(id, decisionBody("APPROVED", "100.00", "INR", "OFF-1007")).andExpect(status().isOk());
        settle(id, "OFF-2000").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OFFICER_MISMATCH"));
        getJson("/api/v1/claims/" + id).andExpect(jsonPath("$.status").value("APPROVED"));
        settle(id, "OFF-1007").andExpect(status().isOk());
        getJson("/api/v1/claims/" + id + "/history").andExpect(jsonPath("$.length()").value(4));
    }

    @Test
    void resumeRecordsTheNamedClaimantAsActorWhenTheCallerSaysWho() throws Exception {
        String id = createClaimUnderReview();
        requestInformation(id, "OFF-1007").andExpect(status().isOk());
        postJson(INTERNAL + id + "/resume-after-information", Map.of("answeredBy", "claimant.a")).andExpect(status().isOk());
        getJson("/api/v1/claims/" + id + "/history").andExpect(jsonPath("$[3].actor").value("claimant.a"));
    }

    // ---- approval and settlement ----

    @Test
    void approvedIsNotFinalAndSettlementCompletesTheClaim() throws Exception {
        String id = createClaimUnderReview();
        decide(id, decisionBody("APPROVED", "2000.00", "INR", "OFF-1007")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED")).andExpect(jsonPath("$.approvedAmount").value(2000.00));
        getJson("/api/v1/claims/" + id).andExpect(jsonPath("$.settledAt").doesNotExist());
        // an approved claim can still be assigned to someone else: it is not terminal
        postJson("/api/v1/officer/claims/" + id + "/assign", Map.of("officerId", "OFF-2000")).andExpect(status().isOk());
        settle(id, "OFF-1007").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OFFICER_MISMATCH"));
        postJson("/api/v1/officer/claims/" + id + "/assign", Map.of("officerId", "OFF-1007")).andExpect(status().isOk());

        settle(id, "OFF-1007").andExpect(status().isOk())
                .andExpect(jsonPath("$.claimId").value(id)).andExpect(jsonPath("$.status").value("SETTLED"))
                .andExpect(jsonPath("$.approvedAmount").value(2000.00)).andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.settledByOfficerId").value("OFF-1007")).andExpect(jsonPath("$.settledAt").exists());
        getJson("/api/v1/claims/" + id).andExpect(jsonPath("$.status").value("SETTLED")).andExpect(jsonPath("$.settledAt").exists())
                .andExpect(jsonPath("$.approvedAmount").value(2000.00));
        getJson("/api/v1/claims/" + id + "/history")
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[2].fromStatus").value("UNDER_REVIEW")).andExpect(jsonPath("$[2].toStatus").value("APPROVED"))
                .andExpect(jsonPath("$[3].fromStatus").value("APPROVED")).andExpect(jsonPath("$[3].toStatus").value("SETTLED"))
                .andExpect(jsonPath("$[3].actor").value("OFF-1007"));
    }

    @Test
    void settlementIsOnlyAllowedForAnApprovedClaim() throws Exception {
        String submitted = createClaim();
        settle(submitted, "OFF-1").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_CLAIM_TRANSITION"));
        String underReview = createClaimUnderReview();
        settle(underReview, "OFF-1007").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_CLAIM_TRANSITION"));
        String info = createClaimUnderReview();
        requestInformation(info, "OFF-1007").andExpect(status().isOk());
        settle(info, "OFF-1007").andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("select count(*) from claims where status = 'SETTLED'", Integer.class)).isZero();
    }

    @Test
    void aRejectedClaimCanNeverBeSettled() throws Exception {
        String id = createClaimUnderReview();
        decide(id, decisionBody("REJECTED", null, "INR", "OFF-1007")).andExpect(status().isOk());
        settle(id, "OFF-1007").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_CLAIM_TRANSITION"))
                .andExpect(jsonPath("$.message").value("A rejected claim cannot be settled"));
        getJson("/api/v1/claims/" + id).andExpect(jsonPath("$.status").value("REJECTED")).andExpect(jsonPath("$.settledAt").doesNotExist());
    }

    @Test
    void aSecondSettlementIsAConflictAndTheFirstIsKept() throws Exception {
        String id = createClaimUnderReview();
        decide(id, decisionBody("APPROVED", "100.00", "INR", "OFF-1007")).andExpect(status().isOk());
        settle(id, "OFF-1007").andExpect(status().isOk());
        String settledAt = jdbc.queryForObject("select cast(settled_at as varchar) from claims where claim_id = ?", String.class, id);
        settle(id, "OFF-1007").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_CLAIM_TRANSITION"));
        assertThat(jdbc.queryForObject("select cast(settled_at as varchar) from claims where claim_id = ?", String.class, id))
                .isEqualTo(settledAt);
        getJson("/api/v1/claims/" + id + "/history").andExpect(jsonPath("$.length()").value(4));
    }

    @Test
    void settlementValidatesTheBodyAndTheClaimExists() throws Exception {
        String id = createClaimUnderReview();
        decide(id, decisionBody("APPROVED", "100.00", "INR", "OFF-1007")).andExpect(status().isOk());
        postJson(INTERNAL + id + "/settlement", Map.of()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        postJson(INTERNAL + id + "/settlement", Map.of("settledByOfficerId", "string")).andExpect(status().isBadRequest());
        postJson(INTERNAL + id + "/settlement", Map.of("settledByOfficerId", " ")).andExpect(status().isBadRequest());
        settle(UNKNOWN, "OFF-1").andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CLAIM_NOT_FOUND"));
        getJson("/api/v1/claims/" + id).andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    void onlyApprovedAndRejectedAreDecisions() throws Exception {
        String id = createClaimUnderReview();
        decide(id, decisionBody("SETTLED", "100.00", "INR", "OFF-1007")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        getJson("/api/v1/claims/" + id).andExpect(jsonPath("$.status").value("UNDER_REVIEW"));
    }

    @Test
    void internalRoutesAreNotPubliclyRoutedAsApiV1() throws Exception {
        String id = createClaim();
        mvc.perform(get("/api/v1/claims/" + id + "/review")).andExpect(status().isNotFound());
    }
}
