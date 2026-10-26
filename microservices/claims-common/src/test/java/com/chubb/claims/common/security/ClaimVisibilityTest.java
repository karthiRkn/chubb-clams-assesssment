package com.chubb.claims.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chubb.claims.common.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** The visibility rule and the officer-identity rule, written out from the documented role matrix, not from the code. */
class ClaimVisibilityTest {

    private static AuthenticatedUser as(Role role, String name) {
        return new AuthenticatedUser(name, role);
    }

    @Test
    void aClaimantSeesOnlyTheirOwnClaims() {
        AuthenticatedUser alice = as(Role.CLAIMANT, "alice");
        assertThat(ClaimVisibility.canSee(alice, "alice", null)).isTrue();
        assertThat(ClaimVisibility.canSee(alice, "alice", "officer1")).isTrue();
        assertThat(ClaimVisibility.canSee(alice, "bob", null)).isFalse();
        assertThat(ClaimVisibility.canSee(alice, null, null)).as("a claim with no recorded owner").isFalse();
    }

    @Test
    void anOfficerSeesTheUnassignedQueueAndTheirOwnClaimsOnly() {
        AuthenticatedUser officer = as(Role.CLAIMS_OFFICER, "officer1");
        assertThat(ClaimVisibility.canSee(officer, "alice", null)).isTrue();
        assertThat(ClaimVisibility.canSee(officer, "alice", "officer1")).isTrue();
        assertThat(ClaimVisibility.canSee(officer, "alice", "officer2")).isFalse();
        assertThat(ClaimVisibility.canSee(officer, null, null)).as("legacy claim, unassigned").isTrue();
    }

    @Test
    void managersAndAdministratorsSeeEveryClaim() {
        for (Role role : new Role[] {Role.MANAGER, Role.ADMIN}) {
            AuthenticatedUser user = as(role, "someone");
            assertThat(ClaimVisibility.canSee(user, "alice", "officer2")).as(role.name()).isTrue();
            assertThat(ClaimVisibility.canSee(user, null, null)).as(role.name()).isTrue();
        }
    }

    @Test
    void aClaimOutsideTheCallersScopeIsExactlyAnUnknownClaim() {
        assertThatThrownBy(() -> ClaimVisibility.require(as(Role.CLAIMANT, "bob"), "CLM-2026-000101", "alice", null))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(e.getCode()).isEqualTo("CLAIM_NOT_FOUND");
                    assertThat(e.getMessage()).isEqualTo("Claim CLM-2026-000101 was not found");
                });
        assertThatCode(() -> ClaimVisibility.require(as(Role.CLAIMANT, "alice"), "CLM-2026-000101", "alice", null))
                .doesNotThrowAnyException();
    }

    // ---- who an action is recorded against ----

    @Test
    void anOfficerCanOnlyActAsThemselves() {
        AuthenticatedUser officer = as(Role.CLAIMS_OFFICER, "officer1");
        assertThat(officer.actingOfficerId(null)).isEqualTo("officer1");
        assertThat(officer.actingOfficerId("  ")).isEqualTo("officer1");
        assertThat(officer.actingOfficerId("officer1")).isEqualTo("officer1");
        assertThatThrownBy(() -> officer.actingOfficerId("officer2"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(e.getCode()).isEqualTo("OFFICER_IDENTITY_MISMATCH");
                });
    }

    @Test
    void anAdministratorMayActForTheOfficerTheyName() {
        AuthenticatedUser admin = as(Role.ADMIN, "admin1");
        assertThat(admin.actingOfficerId("officer2")).isEqualTo("officer2");
        assertThat(admin.actingOfficerId(" officer2 ")).isEqualTo("officer2");
    }

    @Test
    void anAdministratorWhoNamesNobodyIsRefusedSoTheyNeverBecomeTheOfficerOfAClaim() {
        AuthenticatedUser admin = as(Role.ADMIN, "admin1");
        for (String missing : new String[] {null, "", "   "}) {
            assertThatThrownBy(() -> admin.actingOfficerId(missing)).isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(e.getCode()).isEqualTo("VALIDATION_FAILED");
            });
        }
    }
}
