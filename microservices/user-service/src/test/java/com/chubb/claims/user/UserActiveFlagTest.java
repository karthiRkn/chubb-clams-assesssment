package com.chubb.claims.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chubb.claims.common.security.Role;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.security.crypto.password.PasswordEncoder;

/** The active flag (an administrator can stop a person logging in) and the SERVICE-only lookup the Claim Service uses. */
class UserActiveFlagTest extends IntegrationTestBase {

    @SpyBean
    PasswordEncoder encoder;

    // ---- the flag ----

    @Test
    void newUsersAreActive() throws Exception {
        createUser("mei.tan", Role.CLAIMS_OFFICER);
        getAs(as(Role.ADMIN), "/api/v1/users/mei.tan").andExpect(status().isOk()).andExpect(jsonPath("$.active").value(true));
        register("amy.lim", PASSWORD).andExpect(status().isCreated()).andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void aDeactivatedUserCannotLogInAndTheAnswerIsTheSameAsForAWrongPassword() throws Exception {
        createUser("mei.tan", Role.CLAIMS_OFFICER);
        putAs(as(Role.ADMIN), "/api/v1/users/mei.tan/active", Map.of("active", false)).andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("mei.tan")).andExpect(jsonPath("$.active").value(false));

        String disabled = login("mei.tan", PASSWORD).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS")).andReturn().getResponse().getContentAsString();
        String wrongPassword = login("mei.tan", "not-the-password").andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
        // nothing in the answer reveals that the account exists but is switched off (timestamps differ, so compare the rest)
        assertThat(json.readTree(disabled).get("code")).isEqualTo(json.readTree(wrongPassword).get("code"));
        assertThat(json.readTree(disabled).get("message")).isEqualTo(json.readTree(wrongPassword).get("message"));
        assertThat(disabled).doesNotContain("disabled").doesNotContain("inactive").doesNotContain("active");
    }

    @Test
    void aDeactivatedAccountStillCostsAPasswordCheckSoItDoesNotAnswerFasterThanAWrongPassword() throws Exception {
        createUser("mei.tan", Role.CLAIMS_OFFICER);
        putAs(as(Role.ADMIN), "/api/v1/users/mei.tan/active", Map.of("active", false)).andExpect(status().isOk());
        clearInvocations(encoder);

        login("mei.tan", PASSWORD).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));

        // the BCrypt comparison ran (timing is the same as for a wrong password); only afterwards was the account refused
        verify(encoder).matches(eq(PASSWORD), anyString());
        // and an unknown user costs one as well (Spring compares against a dummy hash), so the three cases cannot be told apart by time
        clearInvocations(encoder);
        login("nobody.here", PASSWORD).andExpect(status().isUnauthorized());
        verify(encoder).matches(eq(PASSWORD), anyString());
    }

    @Test
    void reactivatingLetsThePersonLogInAgain() throws Exception {
        createUser("mei.tan", Role.CLAIMS_OFFICER);
        putAs(as(Role.ADMIN), "/api/v1/users/mei.tan/active", Map.of("active", false)).andExpect(status().isOk());
        login("mei.tan", PASSWORD).andExpect(status().isUnauthorized());
        putAs(as(Role.ADMIN), "/api/v1/users/mei.tan/active", Map.of("active", true)).andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));
        login("mei.tan", PASSWORD).andExpect(status().isOk()).andExpect(jsonPath("$.role").value("CLAIMS_OFFICER"));
    }

    @Test
    void aTokenIssuedBeforeDeactivationWorksUntilItExpires() throws Exception {
        createUser("mei.tan", Role.CLAIMS_OFFICER);
        String token = tokenOf("mei.tan");
        putAs(as(Role.ADMIN), "/api/v1/users/mei.tan/active", Map.of("active", false)).andExpect(status().isOk());
        // documented limitation: there is no revocation; this test records the behaviour so a change is a conscious one
        getAs(token, "/api/v1/users/me").andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
    }

    @Test
    void anAdministratorCannotDeactivateThemselvesButCanDeactivateAnotherAdministrator() throws Exception {
        createUser("boss", Role.ADMIN);
        createUser("other.admin", Role.ADMIN);
        String boss = tokenOf("boss");
        putAs(boss, "/api/v1/users/boss/active", Map.of("active", false)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CANNOT_DEACTIVATE_SELF"));
        assertThat(jdbc.queryForObject("select active from users where username = 'boss'", Boolean.class)).isTrue();
        putAs(boss, "/api/v1/users/other.admin/active", Map.of("active", false)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select active from users where username = 'other.admin'", Boolean.class)).isFalse();
    }

    @Test
    void theFlagNeedsABooleanAndAKnownUser() throws Exception {
        createUser("mei.tan", Role.CLAIMANT);
        putAs(as(Role.ADMIN), "/api/v1/users/mei.tan/active", Map.of()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        putAs(as(Role.ADMIN), "/api/v1/users/mei.tan/active", Map.of("active", "maybe")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        putAs(as(Role.ADMIN), "/api/v1/users/nobody.here/active", Map.of("active", false)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
    }

    @Test
    void onlyAnAdministratorCanSwitchAnAccountOnOrOff() throws Exception {
        createUser("mei.tan", Role.CLAIMANT);
        for (Role role : new Role[] {Role.CLAIMANT, Role.CLAIMS_OFFICER, Role.MANAGER, Role.SERVICE}) {
            putAs(as(role), "/api/v1/users/mei.tan/active", Map.of("active", false)).andExpect(status().isForbidden());
        }
        putAs(ANONYMOUS, "/api/v1/users/mei.tan/active", Map.of("active", false)).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("select active from users where username = 'mei.tan'", Boolean.class)).isTrue();
    }

    // ---- the lookup for other services ----

    @Test
    void aServiceCanLookUpARoleAndTheActiveFlagAndNothingElse() throws Exception {
        createUser("mei.tan", Role.CLAIMS_OFFICER);
        String body = getAs(as(Role.SERVICE), "/internal/v1/users/mei.tan").andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("mei.tan")).andExpect(jsonPath("$.role").value("CLAIMS_OFFICER"))
                .andExpect(jsonPath("$.active").value(true)).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(body).fieldNames()).toIterable().containsExactlyInAnyOrder("username", "role", "active");
        assertThat(body).doesNotContain("password").doesNotContain("$2");

        putAs(as(Role.ADMIN), "/api/v1/users/mei.tan/active", Map.of("active", false)).andExpect(status().isOk());
        getAs(as(Role.SERVICE), "/internal/v1/users/mei.tan").andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
    }

    @Test
    void theLookupAnswers404ForAnUnknownUser() throws Exception {
        getAs(as(Role.SERVICE), "/internal/v1/users/nobody.here").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
    }

    @Test
    void aPersonsTokenCannotUseTheInternalLookupNotEvenAnAdministrators() throws Exception {
        createUser("mei.tan", Role.CLAIMS_OFFICER);
        getAs(ANONYMOUS, "/internal/v1/users/mei.tan").andExpect(status().isUnauthorized());
        for (Role role : new Role[] {Role.CLAIMANT, Role.CLAIMS_OFFICER, Role.MANAGER, Role.ADMIN}) {
            getAs(as(role), "/internal/v1/users/mei.tan").andExpect(status().isForbidden());
        }
    }
}
