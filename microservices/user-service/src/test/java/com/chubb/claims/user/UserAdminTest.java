package com.chubb.claims.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chubb.claims.common.security.Role;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** User and role management by an administrator, and the signed-in user's own record. */
class UserAdminTest extends IntegrationTestBase {

    // ---- create ----

    @Test
    void anAdministratorCanCreateAUserWithAnyPersonRole() throws Exception {
        for (Role role : new Role[] {Role.CLAIMANT, Role.CLAIMS_OFFICER, Role.MANAGER, Role.ADMIN}) {
            String name = "user." + role.name().toLowerCase();
            postAs(as(Role.ADMIN), "/api/v1/users", Map.of("username", name, "password", PASSWORD, "role", role.name()))
                    .andExpect(status().isCreated()).andExpect(jsonPath("$.username").value(name))
                    .andExpect(jsonPath("$.role").value(role.name()));
            login(name, PASSWORD).andExpect(status().isOk()).andExpect(jsonPath("$.role").value(role.name()));
        }
    }

    @Test
    void theServiceRoleCannotBeGivenToAPerson() throws Exception {
        postAs(as(Role.ADMIN), "/api/v1/users", Map.of("username", "svc.user", "password", PASSWORD, "role", "SERVICE"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ROLE"));
        createUser("mei.tan", Role.CLAIMS_OFFICER);
        putAs(as(Role.ADMIN), "/api/v1/users/mei.tan/role", Map.of("role", "SERVICE"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ROLE"));
        assertThat(jdbc.queryForObject("select role from users where username = 'mei.tan'", String.class)).isEqualTo("CLAIMS_OFFICER");
    }

    @Test
    void createRejectsABadBodyADuplicateAndAnUnknownRole() throws Exception {
        postAs(as(Role.ADMIN), "/api/v1/users", Map.of("username", "mei.tan", "password", PASSWORD))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        postAs(as(Role.ADMIN), "/api/v1/users", Map.of("username", "mei.tan", "password", PASSWORD, "role", "SUPERUSER"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        createUser("mei.tan", Role.CLAIMS_OFFICER);
        postAs(as(Role.ADMIN), "/api/v1/users", Map.of("username", "mei.tan", "password", PASSWORD, "role", "MANAGER"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("USERNAME_TAKEN"));
        assertThat(jdbc.queryForObject("select role from users where username = 'mei.tan'", String.class)).isEqualTo("CLAIMS_OFFICER");
    }

    // ---- read ----

    @Test
    void anAdministratorCanReadOneUserAndNeverSeesAHash() throws Exception {
        createUser("mei.tan", Role.CLAIMS_OFFICER);
        String body = getAs(as(Role.ADMIN), "/api/v1/users/mei.tan").andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("mei.tan")).andExpect(jsonPath("$.role").value("CLAIMS_OFFICER"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("password").doesNotContain("$2");
    }

    @Test
    void anUnknownUserIs404() throws Exception {
        getAs(as(Role.ADMIN), "/api/v1/users/nobody.here").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
        putAs(as(Role.ADMIN), "/api/v1/users/nobody.here/role", Map.of("role", "CLAIMS_OFFICER")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
    }

    @Test
    void theListIsPagedInTheDatabaseAndOrderedByUsername() throws Exception {
        for (String name : new String[] {"carol", "alice", "dave", "bob", "erin"}) {
            createUser(name, Role.CLAIMANT);
        }
        JsonNode first = read(getAs(as(Role.ADMIN), "/api/v1/users?page=0&size=2").andExpect(status().isOk()));
        assertThat(first.get("totalElements").asLong()).isEqualTo(5);
        assertThat(first.get("totalPages").asInt()).isEqualTo(3);
        assertThat(first.get("content")).extracting(n -> n.get("username").asText()).containsExactly("alice", "bob");
        JsonNode last = read(getAs(as(Role.ADMIN), "/api/v1/users?page=2&size=2").andExpect(status().isOk()));
        assertThat(last.get("content")).extracting(n -> n.get("username").asText()).containsExactly("erin");
        assertThat(first.toString()).doesNotContain("password").doesNotContain("$2");
    }

    @Test
    void pagingParametersAreValidated() throws Exception {
        for (String query : new String[] {"page=-1", "size=0", "size=101", "page=abc", "size=x"}) {
            getAs(as(Role.ADMIN), "/api/v1/users?" + query).andExpect(status().isBadRequest());
        }
    }

    // ---- change role ----

    @Test
    void anAdministratorCanChangeARole() throws Exception {
        createUser("mei.tan", Role.CLAIMANT);
        putAs(as(Role.ADMIN), "/api/v1/users/mei.tan/role", Map.of("role", "CLAIMS_OFFICER")).andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("CLAIMS_OFFICER"));
        // the next login carries the new role
        login("mei.tan", PASSWORD).andExpect(jsonPath("$.role").value("CLAIMS_OFFICER"));
    }

    @Test
    void aTokenIssuedBeforeARoleChangeKeepsItsOldRoleUntilItExpires() throws Exception {
        createUser("mei.tan", Role.CLAIMS_OFFICER);
        String before = tokenOf("mei.tan");
        putAs(as(Role.ADMIN), "/api/v1/users/mei.tan/role", Map.of("role", "CLAIMANT")).andExpect(status().isOk());
        // documented limitation: there is no revocation; this test records the behaviour so a change is a conscious one
        assertThat(jwt.parse(before.substring("Bearer ".length())).orElseThrow().role()).isEqualTo(Role.CLAIMS_OFFICER);
        assertThat(jwt.parse(tokenOf("mei.tan").substring("Bearer ".length())).orElseThrow().role()).isEqualTo(Role.CLAIMANT);
    }

    @Test
    void changeRoleNeedsAValidRoleAndRefusesAnAdministratorChangingTheirOwn() throws Exception {
        createUser("boss", Role.ADMIN);
        createUser("mei.tan", Role.CLAIMS_OFFICER);
        putAs(as(Role.ADMIN), "/api/v1/users/mei.tan/role", Map.of()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        putAs(as(Role.ADMIN), "/api/v1/users/mei.tan/role", Map.of("role", "KING")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        // the caller is the token's subject, so an administrator named "boss" cannot demote "boss"
        String boss = tokenOf("boss");
        putAs(boss, "/api/v1/users/boss/role", Map.of("role", "CLAIMANT")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CANNOT_CHANGE_OWN_ROLE"));
        assertThat(jdbc.queryForObject("select role from users where username = 'boss'", String.class)).isEqualTo("ADMIN");
        // but one administrator can change another's
        createUser("other.admin", Role.ADMIN);
        putAs(boss, "/api/v1/users/other.admin/role", Map.of("role", "MANAGER")).andExpect(status().isOk());
    }

    // ---- /me ----

    @Test
    void meReturnsTheCallersOwnRecordForEveryPersonRole() throws Exception {
        for (Role role : new Role[] {Role.CLAIMANT, Role.CLAIMS_OFFICER, Role.MANAGER, Role.ADMIN}) {
            String name = "me." + role.name().toLowerCase();
            createUser(name, role);
            getAs(tokenOf(name), "/api/v1/users/me").andExpect(status().isOk()).andExpect(jsonPath("$.username").value(name))
                    .andExpect(jsonPath("$.role").value(role.name()));
        }
    }

    @Test
    void meNeedsAToken() throws Exception {
        getAs(ANONYMOUS, "/api/v1/users/me").andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        getAs(as(Role.SERVICE), "/api/v1/users/me").andExpect(status().isForbidden());
    }

    @Test
    void aValidTokenForAUserWhoNoLongerExistsIs404NotATokenForSomeoneElse() throws Exception {
        // the token is signed correctly, but there is no such user row
        getAs(as(Role.ADMIN), "/api/v1/users/me").andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
    }

    // ---- the other routes are administrator-only (see UserSecurityTest for the full matrix) ----

    @Test
    void nonAdministratorsCannotManageUsers() throws Exception {
        createUser("mei.tan", Role.CLAIMS_OFFICER);
        for (Role role : new Role[] {Role.CLAIMANT, Role.CLAIMS_OFFICER, Role.MANAGER, Role.SERVICE}) {
            getAs(as(role), "/api/v1/users").andExpect(status().isForbidden());
            getAs(as(role), "/api/v1/users/mei.tan").andExpect(status().isForbidden());
            postAs(as(role), "/api/v1/users", Map.of("username", "x.y.z", "password", PASSWORD, "role", "ADMIN"))
                    .andExpect(status().isForbidden());
            putAs(as(role), "/api/v1/users/mei.tan/role", Map.of("role", "ADMIN")).andExpect(status().isForbidden());
            putAs(as(role), "/api/v1/users/mei.tan/active", Map.of("active", false)).andExpect(status().isForbidden());
        }
        assertThat(jdbc.queryForObject("select count(*) from users", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select role from users where username = 'mei.tan'", String.class)).isEqualTo("CLAIMS_OFFICER");
    }

    @Test
    void aClaimantCannotPromoteThemselvesByRegisteringAgainOrEditingTheirRole() throws Exception {
        register("mei.tan", PASSWORD).andExpect(status().isCreated());
        String claimant = tokenOf("mei.tan");
        putAs(claimant, "/api/v1/users/mei.tan/role", Map.of("role", "ADMIN")).andExpect(status().isForbidden());
        postAs(claimant, "/api/v1/users", Map.of("username", "mei.two", "password", PASSWORD, "role", "ADMIN"))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("select role from users where username = 'mei.tan'", String.class)).isEqualTo("CLAIMANT");
    }

}
