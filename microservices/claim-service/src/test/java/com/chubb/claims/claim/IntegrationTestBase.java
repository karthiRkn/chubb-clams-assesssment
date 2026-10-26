package com.chubb.claims.claim;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.chubb.claims.claim.client.UserClient;
import com.chubb.claims.common.security.JwtUtil;
import com.chubb.claims.common.security.Role;
import com.chubb.claims.testsupport.DefaultTestTokens;
import com.chubb.claims.testsupport.TestAuth;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

/**
 * Full-stack tests of the Claim Service (controllers, services, JPA, Flyway) on its own H2 test database. The one service
 * it calls, the User Service (to check that an officer a manager assigns to is a real, active claims officer), is replaced
 * by a mock that says yes unless a test says otherwise. Tests are not wrapped in a transaction.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(DefaultTestTokens.class)
public abstract class IntegrationTestBase {

    protected static final String INTERNAL = "/internal/v1/claims/";

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
    @MockBean
    protected UserClient users;

    /** {@code Authorization} value for a person (or service) with this role. Requests without one are sent as ADMIN, or SERVICE on /internal. */
    protected String as(Role role) {
        return TestAuth.bearer(jwt, role);
    }

    protected String as(Role role, String username) {
        return TestAuth.bearer(jwt, role, username);
    }

    @BeforeEach
    void cleanDatabase() {
        org.mockito.Mockito.when(users.isActiveClaimsOfficer(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        jdbc.update("delete from claim_update_history");
        jdbc.update("delete from claim_assignment_history");
        jdbc.update("delete from claim_status_history");
        jdbc.update("delete from claims");
    }

    protected Map<String, Object> claimBody(String market, String type, String amount, String currency) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("claimantName", "Mei Tan");
        b.put("claimantEmail", "mei.tan@example.com");
        b.put("policyNumber", "POL-2026-0042");
        b.put("market", market);
        b.put("claimType", type);
        b.put("incidentDescription", "Rear bumper damaged in a low-speed collision.");
        b.put("incidentDate", "2026-10-12");
        b.put("claimedAmount", new java.math.BigDecimal(amount));
        b.put("currency", currency);
        return b;
    }

    protected ResultActions postJson(String path, Object body) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    protected ResultActions getJson(String path) throws Exception {
        return mvc.perform(get(path));
    }

    protected JsonNode read(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    /** Creates a claim through the API and returns the generated public claimId. */
    protected String createClaim(String market, String type, String amount, String currency) throws Exception {
        ResultActions r = postJson("/api/v1/claims", claimBody(market, type, amount, currency));
        r.andExpect(MockMvcResultMatchers.status().isCreated());
        return read(r).get("claimId").asText();
    }

    protected String createClaim() throws Exception {
        return createClaim("SG", "MOTOR", "2400.00", "INR");
    }

    protected void assign(String claimId, String officer) throws Exception {
        postJson("/api/v1/officer/claims/" + claimId + "/assign", Map.of("officerId", officer))
                .andExpect(MockMvcResultMatchers.status().isOk());
    }

    /** Starts review the way the Assessment Service does: through the internal endpoint, nothing OPEN. */
    protected void review(String claimId, String officer) throws Exception {
        reviewRequest(claimId, officer, false).andExpect(MockMvcResultMatchers.status().isOk());
    }

    protected ResultActions reviewRequest(String claimId, String officer, boolean hasOpenInformationRequests) throws Exception {
        return postJson(INTERNAL + claimId + "/review",
                Map.of("officerId", officer, "hasOpenInformationRequests", hasOpenInformationRequests));
    }

    protected ResultActions requestInformation(String claimId, String officer) throws Exception {
        return postJson(INTERNAL + claimId + "/request-information", Map.of("requestedByOfficerId", officer));
    }

    protected ResultActions resume(String claimId) throws Exception {
        return mvc.perform(post(INTERNAL + claimId + "/resume-after-information"));
    }

    protected Map<String, Object> decisionBody(String decision, String approvedAmount, String currency, String officer) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("decision", decision);
        b.put("decisionReason", "Reason");
        b.put("approvedAmount", approvedAmount == null ? null : new java.math.BigDecimal(approvedAmount));
        b.put("currency", currency);
        b.put("decidedByOfficerId", officer);
        return b;
    }

    protected ResultActions decide(String claimId, Map<String, Object> body) throws Exception {
        return postJson(INTERNAL + claimId + "/decision", body);
    }

    protected ResultActions settle(String claimId, String officer) throws Exception {
        return postJson(INTERNAL + claimId + "/settlement", Map.of("settledByOfficerId", officer));
    }

    /** Creates a claim and moves it to UNDER_REVIEW. */
    protected String createClaimUnderReview() throws Exception {
        String id = createClaim();
        review(id, "OFF-1007");
        return id;
    }
}
