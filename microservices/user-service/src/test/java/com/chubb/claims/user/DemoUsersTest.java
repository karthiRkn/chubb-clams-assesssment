package com.chubb.claims.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chubb.claims.common.security.AuthenticatedUser;
import com.chubb.claims.common.security.JwtUtil;
import com.chubb.claims.common.security.Role;
import com.chubb.claims.testsupport.TestAuth;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The default users, in a real application context with the feature switched on (the committed configuration has it on,
 * the ordinary tests switch it off so they start from an empty users table). It has its own in-memory database so it
 * cannot leak users into the other test classes.
 */
@SpringBootTest(properties = {
        "claims.auth.demo-users.enabled=true",
        "claims.auth.demo-users.password=demo-test-password",
        "spring.datasource.url=jdbc:h2:mem:user_demo_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DemoUsersTest {

    private static final String PASSWORD = "demo-test-password";

    @DynamicPropertySource
    static void jwtSecret(DynamicPropertyRegistry registry) {
        registry.add("claims.security.jwt.secret", TestAuth::secret);
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    JwtUtil jwt;
    @Autowired
    DemoUsers demoUsers;

    private ResultActions login(String username, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").header(HttpHeaders.AUTHORIZATION, "").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("username", username, "password", password))));
    }

    private String bearerFor(String username) throws Exception {
        JsonNode body = json.readTree(login(username, PASSWORD).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        return "Bearer " + body.get("accessToken").asText();
    }

    @Test
    void oneUserPerRoleExistsAfterStartUp() {
        Map<String, String> roles = jdbc.queryForList("select username, role from users").stream()
                .collect(Collectors.toMap(r -> (String) r.get("username"), r -> (String) r.get("role")));
        assertThat(roles).containsExactlyInAnyOrderEntriesOf(Map.of(
                "admin1", "ADMIN", "officer1", "CLAIMS_OFFICER", "manager1", "MANAGER", "claimant1", "CLAIMANT"));
    }

    @Test
    void eachDemoUserCanLogInAndGetsATokenWithTheirRole() throws Exception {
        for (Map.Entry<String, Role> user : DemoUsers.USERS.entrySet()) {
            JsonNode body = json.readTree(login(user.getKey(), PASSWORD).andExpect(status().isOk())
                    .andExpect(jsonPath("$.tokenType").value("Bearer")).andReturn().getResponse().getContentAsString());
            assertThat(body.get("role").asText()).isEqualTo(user.getValue().name());
            assertThat(jwt.parse(body.get("accessToken").asText())).contains(new AuthenticatedUser(user.getKey(), user.getValue()));
        }
    }

    @Test
    void theTokenOpensTheProtectedRoutesOfTheRoleAndNoOthers() throws Exception {
        mvc.perform(get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, bearerFor("officer1")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.username").value("officer1")).andExpect(jsonPath("$.role").value("CLAIMS_OFFICER"));
        // only the administrator may list users, and the list holds exactly the demo users
        mvc.perform(get("/api/v1/users").header(HttpHeaders.AUTHORIZATION, bearerFor("admin1")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(4));
        for (String other : new String[] {"officer1", "manager1", "claimant1"}) {
            mvc.perform(get("/api/v1/users").header(HttpHeaders.AUTHORIZATION, bearerFor(other))).andExpect(status().isForbidden());
        }
    }

    @Test
    void theWrongPasswordAndTheDocumentedDefaultWhenAnotherIsConfiguredAreRefused() throws Exception {
        login("officer1", "not-the-password").andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        login("officer1", "password123").andExpect(status().isUnauthorized());
    }

    @Test
    void thePasswordIsStoredOnlyAsAHash() {
        String stored = jdbc.queryForObject("select password_hash from users where username = 'officer1'", String.class);
        assertThat(stored).startsWith("$2").doesNotContain(PASSWORD);
    }

    @Test
    void runningAgainNeitherDuplicatesNorResetsAnExistingUser() throws Exception {
        jdbc.update("update users set password_hash = ? where username = 'manager1'", "changed-by-someone-else");
        demoUsers.run(null);
        assertThat(jdbc.queryForObject("select count(*) from users", Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("select password_hash from users where username = 'manager1'", String.class))
                .isEqualTo("changed-by-someone-else");
        // put it back so the other tests in this class still see a working login
        jdbc.update("update users set password_hash = (select password_hash from users where username = 'officer1') where username = 'manager1'");
    }
}
