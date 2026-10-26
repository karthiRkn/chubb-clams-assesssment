package com.chubb.claims.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chubb.claims.common.ApiException;
import com.chubb.claims.common.security.Role;
import com.chubb.claims.testsupport.AccessMatrix;
import com.chubb.claims.testsupport.RouteCoverage;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Who may call the Assessment Service, which claims they may touch, and who an action is recorded against: officers act as
 * themselves, claimants see and answer only their own claim's requests.
 */
class AssessmentSecurityTest extends IntegrationTestBase {

    @Autowired
    ApplicationContext context;

    // ---- the role table ----

    @Test
    void everyPublicRouteIsAllowedToExactlyTheRolesItsRuleListsAndToNobodyElse() throws Exception {
        assertThat(AccessMatrix.deviations(mvc, jwt, context)).isEmpty();
    }

    /** Written by hand from the documented role matrix, not derived from the code (see the Claim Service's ClaimSecurityTest). */
    @Test
    void theRuleTableIsTheDocumentedRoleMatrix() {
        assertThat(AccessMatrix.table(context)).isEqualTo(Map.of(
                "POST /api/v1/officer/claims/{claimId}/review", "CLAIMS_OFFICER, ADMIN",
                "GET /api/v1/officer/claims/{claimId}/assessments", "CLAIMS_OFFICER, ADMIN",
                "POST /api/v1/officer/claims/{claimId}/decision", "CLAIMS_OFFICER, ADMIN",
                "POST /api/v1/officer/claims/{claimId}/settlement", "CLAIMS_OFFICER, ADMIN",
                "POST /api/v1/officer/claims/{claimId}/information-requests", "CLAIMS_OFFICER, ADMIN",
                "GET /api/v1/claims/{claimId}/information-requests", "CLAIMANT, CLAIMS_OFFICER, MANAGER, ADMIN",
                "POST /api/v1/claims/{claimId}/information", "CLAIMANT, ADMIN"));
    }

    @Test
    void everyApiRouteHasAnAccessRule() {
        RouteCoverage.assertEveryApiRouteHasExactlyOneRule(context);
    }

    @Test
    void thisServiceHasNoInternalRoutesOfItsOwn() throws Exception {
        String route = "/internal/v1/claims/" + CLAIM + "/information-requests/open"; // the route of the former Information Service
        getAs(as(Role.SERVICE), route).andExpect(status().isNotFound()); // allowed through the chain, but nothing serves it
        getAs(as(Role.ADMIN), route).andExpect(status().isForbidden()); // /internal is for services only, even an administrator
        getAs("", route).andExpect(status().isUnauthorized());
    }

    @Test
    void assessmentNotesAreReadableByOfficersOnlyNotByManagersOrClaimants() throws Exception {
        postAs(as(Role.CLAIMS_OFFICER, "OFF-1007"), "/api/v1/officer/claims/" + CLAIM + "/review",
                Map.of("assessmentNotes", "Looks like a duplicate of an earlier claim")).andExpect(status().isOk());

        String path = "/api/v1/officer/claims/" + CLAIM + "/assessments";
        getAs(as(Role.CLAIMS_OFFICER, "OFF-1007"), path).andExpect(status().isOk()).andExpect(jsonPath("$[0].notes").exists());
        for (Role role : new Role[] {Role.CLAIMANT, Role.MANAGER, Role.SERVICE}) {
            String body = getAs(as(role), path).andExpect(status().isForbidden()).andReturn().getResponse().getContentAsString();
            assertThat(body).doesNotContain("duplicate");
        }
    }

    @Test
    void aClaimantAndAManagerCannotReviewDecideSettleOrAskForInformation() throws Exception {
        for (Role role : new Role[] {Role.CLAIMANT, Role.MANAGER}) {
            for (String route : new String[] {"review", "decision", "settlement", "information-requests"}) {
                postAs(as(role), "/api/v1/officer/claims/" + CLAIM + "/" + route, Map.of()).andExpect(status().isForbidden());
            }
        }
        assertThat(rows()).isZero();
        assertThat(informationRows()).isZero();
        verifyNoInteractions(claims);
    }

    @Test
    void noTokenIs401() throws Exception {
        postAs("", "/api/v1/officer/claims/" + CLAIM + "/review", Map.of("officerId", "OFF-1"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        verifyNoInteractions(claims);
    }

    // ---- an officer acts as themselves ----

    private static final String OFFICER_A = "officer.a";
    private static final String OFFICER_B = "officer.b";

    private ResultActions asOfficerA(String route, Map<String, Object> body) throws Exception {
        return postAs(as(Role.CLAIMS_OFFICER, OFFICER_A), "/api/v1/officer/claims/" + CLAIM + "/" + route, body);
    }

    private static Map<String, Object> with(String key, Object value, Object... more) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(key, value);
        for (int i = 0; i < more.length; i += 2) {
            m.put((String) more[i], more[i + 1]);
        }
        return m;
    }

    @Test
    void anOfficerWhoOmitsTheOfficerIdActsAsTheTokensUser() throws Exception {
        asOfficerA("review", with("assessmentNotes", "Mine.")).andExpect(status().isOk());
        verify(claims).startReview(CLAIM, OFFICER_A, false, true);
        assertThat(jdbc.queryForObject("select officer_id from claim_assessments", String.class)).isEqualTo(OFFICER_A);

        claimIs(CLAIM, "UNDER_REVIEW", OWNER, OFFICER_A);
        asOfficerA("information-requests", with("question", "Estimate please?")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.requestedByOfficerId").value(OFFICER_A));
        verify(claims).requestInformation(CLAIM, OFFICER_A);

        asOfficerA("decision", with("decision", "REJECTED", "decisionReason", "Not covered", "currency", "INR")).andExpect(status().isOk())
                .andExpect(jsonPath("$.decidedByOfficerId").value(OFFICER_A));

        claimIs(CLAIM, "APPROVED", OWNER, OFFICER_A);
        asOfficerA("settlement", with("settlementNote", "Paid")).andExpect(status().isOk()).andExpect(jsonPath("$.settledByOfficerId").value(OFFICER_A));
        verify(claims).settle(CLAIM, OFFICER_A);
    }

    @Test
    void anOfficerWhoRepeatsTheirOwnNameIsFine() throws Exception {
        asOfficerA("review", with("officerId", OFFICER_A)).andExpect(status().isOk());
        verify(claims).startReview(CLAIM, OFFICER_A, false, true);
    }

    @Test
    void anOfficerCannotActInAnotherOfficersNameOnAnyRouteAndNothingIsCalledOrStored() throws Exception {
        asOfficerA("review", with("officerId", OFFICER_B)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("OFFICER_IDENTITY_MISMATCH"));
        asOfficerA("decision", with("decision", "REJECTED", "decisionReason", "r", "currency", "INR", "decidedByOfficerId", OFFICER_B))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("OFFICER_IDENTITY_MISMATCH"));
        asOfficerA("settlement", with("settledByOfficerId", OFFICER_B)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("OFFICER_IDENTITY_MISMATCH"));
        asOfficerA("information-requests", with("question", "Q", "requestedByOfficerId", OFFICER_B)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("OFFICER_IDENTITY_MISMATCH"));
        verifyNoInteractions(claims);
        assertThat(rows()).isZero();
        assertThat(informationRows()).isZero();
    }

    @Test
    void anAdministratorMustNameTheOfficerTheyActForAndThenActsAsThatOfficer() throws Exception {
        postJson("/api/v1/officer/claims/" + CLAIM + "/review", Map.of()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED")).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("officer ID")));
        postJson("/api/v1/officer/claims/" + CLAIM + "/settlement", Map.of()).andExpect(status().isBadRequest());
        verifyNoInteractions(claims);
        postJson("/api/v1/officer/claims/" + CLAIM + "/review", Map.of("officerId", "officer.c")).andExpect(status().isOk());
        verify(claims).startReview(CLAIM, "officer.c", false, false);
    }

    @Test
    void whenAnAdministratorNamesAnOfficerTheClaimServiceIsToldItIsUnverifiedSoItCanCheckTheName() throws Exception {
        // the Claim Service refuses a name that is not an active claims officer; the refusal reaches the administrator unchanged
        when(claims.startReview(CLAIM, "typo.name", false, false)).thenThrow(
                ApiException.badRequest("INVALID_OFFICER", "typo.name is not an active claims officer"));
        postJson("/api/v1/officer/claims/" + CLAIM + "/review", Map.of("officerId", "typo.name", "assessmentNotes", "n"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_OFFICER"));
        assertThat(rows()).as("the note is rolled back with the refused review").isZero();
        verify(claims).startReview(CLAIM, "typo.name", false, false);
    }

    // ---- an officer sees only the queue and their own claims ----

    @Test
    void anOfficerCannotTouchAClaimAssignedToAnotherOfficerAndItLooksLikeAnUnknownClaim() throws Exception {
        claimIs(CLAIM, "UNDER_REVIEW", OWNER, OFFICER_B);
        String hidden = asOfficerA("review", with("assessmentNotes", "Sneaky.")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CLAIM_NOT_FOUND")).andReturn().getResponse().getContentAsString();
        assertThat(hidden).doesNotContain(OFFICER_B).doesNotContain(OWNER);
        asOfficerA("decision", with("decision", "REJECTED", "decisionReason", "r", "currency", "INR")).andExpect(status().isNotFound());
        asOfficerA("settlement", with("settlementNote", "n")).andExpect(status().isNotFound());
        asOfficerA("information-requests", with("question", "Q")).andExpect(status().isNotFound());
        getAs(as(Role.CLAIMS_OFFICER, OFFICER_A), "/api/v1/officer/claims/" + CLAIM + "/assessments").andExpect(status().isNotFound());
        getAs(as(Role.CLAIMS_OFFICER, OFFICER_A), "/api/v1/claims/" + CLAIM + "/information-requests").andExpect(status().isNotFound());

        verify(claims, never()).startReview(anyString(), anyString(), anyBoolean(), anyBoolean());
        verify(claims, never()).decide(anyString(), anyString(), anyString(), any(), anyString(), anyString());
        verify(claims, never()).settle(anyString(), anyString());
        verify(claims, never()).requestInformation(anyString(), any());
        assertThat(rows()).isZero();
        assertThat(informationRows()).isZero();

        // the officer it IS assigned to can read it
        getAs(as(Role.CLAIMS_OFFICER, OFFICER_B), "/api/v1/officer/claims/" + CLAIM + "/assessments").andExpect(status().isOk());
    }

    @Test
    void anOfficerMayStartOnAnUnassignedClaimOrOneAssignedToThem() throws Exception {
        claimIs(CLAIM, "SUBMITTED", OWNER, null);
        asOfficerA("review", with("assessmentNotes", "Taking it.")).andExpect(status().isOk());
        claimIs(CLAIM, "UNDER_REVIEW", OWNER, OFFICER_A);
        asOfficerA("review", with("assessmentNotes", "Again.")).andExpect(status().isOk());
        getAs(as(Role.CLAIMS_OFFICER, OFFICER_A), "/api/v1/officer/claims/" + CLAIM + "/assessments").andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    // ---- a claimant sees and answers only their own claim's requests ----

    private ResultActions list(String authorization, String claimId) throws Exception {
        return getAs(authorization, "/api/v1/claims/" + claimId + "/information-requests");
    }

    private ResultActions answerAs(String authorization, String claimId, String irId) throws Exception {
        return postAs(authorization, "/api/v1/claims/" + claimId + "/information",
                Map.of("informationRequestId", irId, "answer", "Here is the estimate."));
    }

    @Test
    void theOwnerListsTheRequestsOfTheirClaim() throws Exception {
        askAndGetId(CLAIM);
        list(as(Role.CLAIMANT, OWNER), CLAIM).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void anotherClaimantGetsTheSameAnswerAsForAClaimThatDoesNotExist() throws Exception {
        askAndGetId(CLAIM);
        String foreign = list(as(Role.CLAIMANT, "claimant.other"), CLAIM).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CLAIM_NOT_FOUND")).andReturn().getResponse().getContentAsString();
        String unknown = list(as(Role.CLAIMANT, "claimant.other"), UNKNOWN).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CLAIM_NOT_FOUND")).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(foreign).get("message").asText()).isEqualTo("Claim " + CLAIM + " was not found");
        assertThat(json.readTree(unknown).get("message").asText()).isEqualTo("Claim " + UNKNOWN + " was not found");
        assertThat(foreign).doesNotContain(OWNER).doesNotContain("repair estimate");
    }

    @Test
    void aClaimWithNoRecordedOwnerIsVisibleToStaffOnly() throws Exception {
        askAndGetId(CLAIM);
        claimIs(CLAIM, "MORE_INFO_REQUIRED", null, null);
        list(as(Role.CLAIMANT, OWNER), CLAIM).andExpect(status().isNotFound());
        for (Role staff : new Role[] {Role.CLAIMS_OFFICER, Role.MANAGER, Role.ADMIN}) {
            list(as(staff), CLAIM).andExpect(status().isOk());
        }
    }

    @Test
    void managersAndAdministratorsListTheRequestsOfAnyClaimAndAnOfficerOnlyOfVisibleOnes() throws Exception {
        askAndGetId(CLAIM);
        for (Role staff : new Role[] {Role.MANAGER, Role.ADMIN}) {
            list(as(staff, "someone.else"), CLAIM).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        }
        list(as(Role.CLAIMS_OFFICER, "someone.else"), CLAIM).andExpect(status().isOk()); // unassigned: part of the queue
        claimIs(CLAIM, "UNDER_REVIEW", OWNER, OFFICER_B);
        list(as(Role.CLAIMS_OFFICER, "someone.else"), CLAIM).andExpect(status().isNotFound());
        list(as(Role.MANAGER, "someone.else"), CLAIM).andExpect(status().isOk());
    }

    @Test
    void theOwnerAnswersAndAnotherClaimantCannot() throws Exception {
        String irId = askAndGetId(CLAIM);
        claimIs(CLAIM, "MORE_INFO_REQUIRED");

        answerAs(as(Role.CLAIMANT, "claimant.other"), CLAIM, irId).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CLAIM_NOT_FOUND"));
        assertThat(statusOf(irId)).isEqualTo("OPEN");
        verify(claims, never()).resumeAfterInformation(anyString(), any());

        answerAs(as(Role.CLAIMANT, OWNER), CLAIM, irId).andExpect(status().isOk());
        assertThat(statusOf(irId)).isEqualTo("ANSWERED");
        verify(claims).resumeAfterInformation(CLAIM, OWNER);
    }

    @Test
    void aForeignClaimantLearnsNothingAboutTheClaimsStatusOrTheRequestsExistence() throws Exception {
        String irId = askAndGetId(CLAIM);
        // the claim is UNDER_REVIEW, so the owner would get 409; a stranger must get the same 404 whatever the request id is
        for (String id : new String[] {irId, "IR-2026-999999"}) {
            answerAs(as(Role.CLAIMANT, "claimant.other"), CLAIM, id).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("CLAIM_NOT_FOUND"));
        }
        answerAs(as(Role.CLAIMANT, OWNER), CLAIM, irId).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_CLAIM_TRANSITION"));
    }

    @Test
    void anAdministratorMayAnswerOnBehalfOfAClaimant() throws Exception {
        String irId = askAndGetId(CLAIM);
        claimIs(CLAIM, "MORE_INFO_REQUIRED");
        answerAs(as(Role.ADMIN), CLAIM, irId).andExpect(status().isOk());
    }

    @Test
    void anOfficerCannotAnswerOnTheClaimantsBehalf() throws Exception {
        String irId = askAndGetId(CLAIM);
        claimIs(CLAIM, "MORE_INFO_REQUIRED");
        answerAs(as(Role.CLAIMS_OFFICER), CLAIM, irId).andExpect(status().isForbidden());
        assertThat(statusOf(irId)).isEqualTo("OPEN");
    }

    @Test
    void aClaimantCannotAskQuestions() throws Exception {
        postAs(as(Role.CLAIMANT, OWNER), "/api/v1/officer/claims/" + CLAIM + "/information-requests",
                Map.of("question", "Am I done?", "requestedByOfficerId", "OFF-1")).andExpect(status().isForbidden());
        assertThat(informationRows()).isZero();
    }
}
