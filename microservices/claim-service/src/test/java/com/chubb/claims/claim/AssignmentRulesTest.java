package com.chubb.claims.claim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chubb.claims.common.ApiException;
import com.chubb.claims.common.security.Role;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** Who may assign a claim to whom, and what is recorded. */
class AssignmentRulesTest extends IntegrationTestBase {

    private ResultActions assignAs(String authorization, String claimId, String officerId) throws Exception {
        return mvc.perform(post("/api/v1/officer/claims/" + claimId + "/assign").header(HttpHeaders.AUTHORIZATION, authorization)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("officerId", officerId))));
    }

    private String assignee(String claimId) {
        return jdbc.queryForObject("select assigned_officer_id from claims where claim_id = ?", String.class, claimId);
    }

    private int assignmentRows(String claimId) {
        return jdbc.queryForObject("""
                select count(*) from claim_assignment_history h join claims c on c.id = h.claim_pk where c.claim_id = ?""",
                Integer.class, claimId);
    }

    // ---- a manager ----

    @Test
    void aManagerAssignsAClaimToAnActiveClaimsOfficerAndIsRecordedAsWhoDidIt() throws Exception {
        String id = createClaim();
        assignAs(as(Role.MANAGER, "meera"), id, "officer.a").andExpect(status().isOk())
                .andExpect(jsonPath("$.claimId").value(id)).andExpect(jsonPath("$.assignedOfficerId").value("officer.a"))
                .andExpect(jsonPath("$.status").value("SUBMITTED"));
        verify(users).isActiveClaimsOfficer("officer.a");
        getJson("/api/v1/claims/" + id + "/assignments").andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].newOfficerId").value("officer.a"))
                .andExpect(jsonPath("$[0].previousOfficerId").doesNotExist())
                .andExpect(jsonPath("$[0].actor").value("meera"));
        getJson("/api/v1/claims/" + id + "/history").andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void aManagerCanReassignAndTheHistoryKeepsBothOfficers() throws Exception {
        String id = createClaim();
        assignAs(as(Role.MANAGER, "meera"), id, "officer.a").andExpect(status().isOk());
        assignAs(as(Role.MANAGER, "meera"), id, "officer.b").andExpect(status().isOk());
        assignAs(as(Role.MANAGER, "meera"), id, "officer.b").andExpect(status().isOk()); // no change, no row
        assertThat(assignee(id)).isEqualTo("officer.b");
        getJson("/api/v1/claims/" + id + "/assignments").andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].previousOfficerId").value("officer.a")).andExpect(jsonPath("$[1].newOfficerId").value("officer.b"))
                .andExpect(jsonPath("$[1].actor").value("meera"));
    }

    @Test
    void aManagerCannotAssignToSomebodyWhoIsNotAnActiveClaimsOfficer() throws Exception {
        String id = createClaim();
        when(users.isActiveClaimsOfficer("a.claimant")).thenReturn(false);
        assignAs(as(Role.MANAGER, "meera"), id, "a.claimant").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_OFFICER"))
                .andExpect(jsonPath("$.message").value("a.claimant is not an active claims officer"));
        assertThat(assignee(id)).isNull();
        assertThat(assignmentRows(id)).isZero();
    }

    @Test
    void anAdministratorIsHeldToTheSameEligibilityRule() throws Exception {
        String id = createClaim();
        when(users.isActiveClaimsOfficer("typo.name")).thenReturn(false);
        assignAs(as(Role.ADMIN), id, "typo.name").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_OFFICER"));
        assertThat(assignee(id)).isNull();
    }

    @Test
    void ifTheUserServiceIsDownNothingIsAssignedAndTheAnswerIs503() throws Exception {
        String id = createClaim();
        when(users.isActiveClaimsOfficer(anyString())).thenThrow(
                ApiException.unavailable("DOWNSTREAM_UNAVAILABLE", "User Service is unavailable or did not answer in time"));
        assignAs(as(Role.MANAGER, "meera"), id, "officer.a").andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DOWNSTREAM_UNAVAILABLE"));
        assertThat(assignee(id)).isNull();
        assertThat(assignmentRows(id)).isZero();
    }

    @Test
    void theOfficerIsCheckedBeforeTheClaimSoNoRowLockIsHeldDuringTheCall() throws Exception {
        // an unknown claim with an ineligible officer reports the officer: the User Service was asked first
        when(users.isActiveClaimsOfficer("nobody")).thenReturn(false);
        assignAs(as(Role.MANAGER, "meera"), "CLM-2026-999999", "nobody").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_OFFICER"));
        // with an eligible officer the unknown claim is a plain 404
        assignAs(as(Role.MANAGER, "meera"), "CLM-2026-999999", "officer.a").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CLAIM_NOT_FOUND"));
    }

    @Test
    void theNameIsTrimmedBeforeItIsCheckedAndStored() throws Exception {
        String id = createClaim();
        assignAs(as(Role.MANAGER, "meera"), id, "  officer.a  ").andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedOfficerId").value("officer.a"));
        verify(users).isActiveClaimsOfficer("officer.a");
    }

    // ---- a claims officer ----

    @Test
    void aClaimsOfficerCanTakeAnUnassignedClaimForThemselvesWithoutAskingTheUserService() throws Exception {
        String id = createClaim();
        assignAs(as(Role.CLAIMS_OFFICER, "officer.a"), id, "officer.a").andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedOfficerId").value("officer.a"));
        verifyNoInteractions(users); // their own role and active state are already proven by the token
        getJson("/api/v1/claims/" + id + "/assignments").andExpect(jsonPath("$[0].actor").value("officer.a"));
    }

    @Test
    void aClaimsOfficerCannotGiveAClaimToSomeoneElse() throws Exception {
        String id = createClaim();
        assignAs(as(Role.CLAIMS_OFFICER, "officer.a"), id, "officer.b").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ASSIGNMENT_NOT_PERMITTED"));
        assertThat(assignee(id)).isNull();
        assertThat(assignmentRows(id)).isZero();
        verify(users, never()).isActiveClaimsOfficer(anyString());
    }

    @Test
    void anOfficerCannotTakeAClaimThatIsAssignedToAnotherOfficerAndItLooksLikeAnUnknownClaim() throws Exception {
        String id = createClaim();
        assignAs(as(Role.MANAGER, "meera"), id, "officer.b").andExpect(status().isOk());
        assignAs(as(Role.CLAIMS_OFFICER, "officer.a"), id, "officer.a").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CLAIM_NOT_FOUND"));
        assertThat(assignee(id)).isEqualTo("officer.b");
        assertThat(assignmentRows(id)).isEqualTo(1);
    }

    @Test
    void anOfficerTakingTheirOwnClaimAgainChangesNothing() throws Exception {
        String id = createClaim();
        assignAs(as(Role.CLAIMS_OFFICER, "officer.a"), id, "officer.a").andExpect(status().isOk());
        assignAs(as(Role.CLAIMS_OFFICER, "officer.a"), id, "officer.a").andExpect(status().isOk());
        assertThat(assignmentRows(id)).isEqualTo(1);
    }

    // ---- nobody else, and claims that cannot be assigned ----

    @Test
    void claimantsAndServicesCannotAssignAndAnonymousIsUnauthenticated() throws Exception {
        String id = createClaim();
        assignAs(as(Role.CLAIMANT, "claimant.a"), id, "officer.a").andExpect(status().isForbidden());
        assignAs(as(Role.SERVICE), id, "officer.a").andExpect(status().isForbidden());
        assignAs("", id, "officer.a").andExpect(status().isUnauthorized());
        assertThat(assignee(id)).isNull();
        verifyNoInteractions(users);
    }

    @Test
    void aSettledOrRejectedClaimCannotBeAssigned() throws Exception {
        String rejected = createClaimUnderReview();
        decide(rejected, decisionBody("REJECTED", null, "INR", "OFF-1007")).andExpect(status().isOk());
        assignAs(as(Role.MANAGER, "meera"), rejected, "officer.a").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_CLAIM_TRANSITION"));
        assertThat(assignee(rejected)).isEqualTo("OFF-1007");
    }

    @Test
    void anApprovedClaimCanStillBeHandedToAnotherOfficerToSettle() throws Exception {
        String id = createClaimUnderReview();
        decide(id, decisionBody("APPROVED", "100.00", "INR", "OFF-1007")).andExpect(status().isOk());
        assignAs(as(Role.MANAGER, "meera"), id, "officer.a").andExpect(status().isOk());
        settle(id, "OFF-1007").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OFFICER_MISMATCH"));
        settle(id, "officer.a").andExpect(status().isOk());
    }
}
