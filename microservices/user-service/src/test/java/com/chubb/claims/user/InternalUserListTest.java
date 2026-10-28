package com.chubb.claims.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chubb.claims.common.security.Role;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

/**
 * {@code GET /internal/v1/users?role=&active=}: the list the Claim Service uses so its officer report includes officers who
 * have no claims yet. SERVICE only, like every {@code /internal/v1} route.
 */
class InternalUserListTest extends IntegrationTestBase {

    private static final String LIST = "/internal/v1/users?role=CLAIMS_OFFICER&active=true";

    private void deactivate(String username) throws Exception {
        putAs(as(Role.ADMIN), "/api/v1/users/" + username + "/active", Map.of("active", false)).andExpect(status().isOk());
    }

    @Test
    void listsEveryActiveClaimsOfficerByUsernameWithNoClaimsRequired() throws Exception {
        createUser("officer2", Role.CLAIMS_OFFICER);
        createUser("officer1", Role.CLAIMS_OFFICER);

        String body = getAs(as(Role.SERVICE), LIST).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].username").value("officer1"))
                .andExpect(jsonPath("$[0].role").value("CLAIMS_OFFICER"))
                .andExpect(jsonPath("$[0].active").value(true))
                .andExpect(jsonPath("$[1].username").value("officer2"))
                .andReturn().getResponse().getContentAsString();
        // the same three fields as the single-user lookup, and nothing secret
        assertThat(json.readTree(body).get(0).fieldNames()).toIterable().containsExactlyInAnyOrder("username", "role", "active");
        assertThat(body).doesNotContain("password").doesNotContain("$2");
    }

    @Test
    void anOfficerWhoHasBeenSwitchedOffIsNotInTheActiveListButIsInTheInactiveOne() throws Exception {
        createUser("officer1", Role.CLAIMS_OFFICER);
        createUser("officer2", Role.CLAIMS_OFFICER);
        deactivate("officer2");

        getAs(as(Role.SERVICE), LIST).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].username").value("officer1"));
        getAs(as(Role.SERVICE), "/internal/v1/users?role=CLAIMS_OFFICER&active=false").andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].username").value("officer2"))
                .andExpect(jsonPath("$[0].active").value(false));
    }

    @Test
    void onlyTheRequestedRoleIsListed() throws Exception {
        createUser("officer1", Role.CLAIMS_OFFICER);
        createUser("manager1", Role.MANAGER);
        createUser("admin2", Role.ADMIN);
        createUser("claimant1", Role.CLAIMANT);

        getAs(as(Role.SERVICE), LIST).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].username").value("officer1"));
        getAs(as(Role.SERVICE), "/internal/v1/users?role=MANAGER&active=true").andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].username").value("manager1"));
    }

    @Test
    void noMatchIsAnEmptyListNotAnError() throws Exception {
        createUser("claimant1", Role.CLAIMANT);
        getAs(as(Role.SERVICE), LIST).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        // SERVICE is not a role a user can have, so nobody matches it
        getAs(as(Role.SERVICE), "/internal/v1/users?role=SERVICE&active=true").andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void bothParametersAreRequiredAndMustBeValid() throws Exception {
        for (String query : new String[] {"", "?role=CLAIMS_OFFICER", "?active=true", "?role=NOT_A_ROLE&active=true",
                "?role=CLAIMS_OFFICER&active=maybe"}) {
            getAs(as(Role.SERVICE), "/internal/v1/users" + query).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
        }
    }

    @Test
    void aPersonsTokenCannotUseTheListNotEvenAnAdministratorsAndOnlyGetIsAllowed() throws Exception {
        createUser("officer1", Role.CLAIMS_OFFICER);
        getAs(ANONYMOUS, LIST).andExpect(status().isUnauthorized());
        for (Role role : new Role[] {Role.CLAIMANT, Role.CLAIMS_OFFICER, Role.MANAGER, Role.ADMIN}) {
            getAs(as(role), LIST).andExpect(status().isForbidden());
        }
        mvc.perform(post("/internal/v1/users").header(HttpHeaders.AUTHORIZATION, as(Role.SERVICE)))
                .andExpect(status().isMethodNotAllowed());
    }
}
