package com.chubb.claims.user;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.chubb.claims.common.security.JwtUtil;
import com.chubb.claims.common.security.Role;
import com.chubb.claims.testsupport.DefaultTestTokens;
import com.chubb.claims.testsupport.TestAuth;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

/**
 * Full-stack tests of the User Service (controllers, services, JPA, Flyway, the shared security chain) on its own H2 test
 * database. Requests carry an ADMIN token unless a test says otherwise (see {@link DefaultTestTokens}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(DefaultTestTokens.class)
public abstract class IntegrationTestBase {

    protected static final String PASSWORD = "correct-horse-battery";

    @DynamicPropertySource
    static void jwtSecret(DynamicPropertyRegistry registry) {
        registry.add("claims.security.jwt.secret", TestAuth::secret);
    }

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected ObjectMapper json;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected JwtUtil jwt;

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("delete from users");
    }

    protected String as(Role role) {
        return TestAuth.bearer(jwt, role);
    }

    /** A request with no Authorization at all (an empty header, because a missing one would get the test default). */
    protected static final String ANONYMOUS = "";

    protected ResultActions register(String username, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/register").header(HttpHeaders.AUTHORIZATION, ANONYMOUS)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("username", username, "password", password))));
    }

    protected ResultActions login(String username, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").header(HttpHeaders.AUTHORIZATION, ANONYMOUS)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("username", username, "password", password))));
    }

    /** Creates a user as ADMIN through the API. */
    protected void createUser(String username, Role role) throws Exception {
        mvc.perform(post("/api/v1/users").header(HttpHeaders.AUTHORIZATION, as(Role.ADMIN)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("username", username, "password", PASSWORD, "role", role.name()))))
                .andExpect(MockMvcResultMatchers.status().isCreated());
    }

    /** Logs in through the API and returns the Authorization header value. */
    protected String tokenOf(String username) throws Exception {
        return "Bearer " + read(login(username, PASSWORD).andExpect(MockMvcResultMatchers.status().isOk())).get("accessToken").asText();
    }

    protected ResultActions getAs(String authorization, String path) throws Exception {
        return mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, authorization));
    }

    protected ResultActions putAs(String authorization, String path, Object body) throws Exception {
        return mvc.perform(put(path).header(HttpHeaders.AUTHORIZATION, authorization).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    protected ResultActions postAs(String authorization, String path, Object body) throws Exception {
        return mvc.perform(post(path).header(HttpHeaders.AUTHORIZATION, authorization).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    protected JsonNode read(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }
}
