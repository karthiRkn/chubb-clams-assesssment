package com.chubb.claims.assessment;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.chubb.claims.assessment.AssessmentDtos.DecisionResponse;
import com.chubb.claims.assessment.AssessmentDtos.SettlementResponse;
import com.chubb.claims.assessment.AssessmentDtos.WorkflowResponse;
import com.chubb.claims.assessment.client.ClaimClient;
import com.chubb.claims.assessment.client.ClaimClient.ClaimStatusView;
import com.chubb.claims.common.ApiException;
import com.chubb.claims.common.security.JwtUtil;
import com.chubb.claims.common.security.Role;
import com.chubb.claims.testsupport.DefaultTestTokens;
import com.chubb.claims.testsupport.TestAuth;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
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

/**
 * Full-stack tests of the Assessment Service on its own H2 database. The Claim Service, the only service it calls, is
 * replaced by a mock client. By default every claim except {@link #UNKNOWN} exists, is UNDER_REVIEW, was submitted by
 * {@link #OWNER} and is unassigned, and accepts every transition; individual tests change that to simulate the Claim
 * Service refusing or being down.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(DefaultTestTokens.class)
public abstract class IntegrationTestBase {

    protected static final String CLAIM = "CLM-2026-000101";
    protected static final String UNKNOWN = "CLM-2026-999999";
    /** The claimant who submitted every claim in these tests, as the Claim Service would report it. */
    protected static final String OWNER = "claimant.owner";
    private static final Instant NOW = Instant.parse("2026-10-22T04:00:00Z");

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
    protected ClaimClient claims;

    /** {@code Authorization} value for a person (or service). Requests without one are sent as ADMIN. */
    protected String as(Role role) {
        return TestAuth.bearer(jwt, role);
    }

    protected String as(Role role, String username) {
        return TestAuth.bearer(jwt, role, username);
    }

    @BeforeEach
    void cleanDatabaseAndStubTheClaimService() {
        jdbc.update("delete from information_requests");
        jdbc.update("delete from claim_assessments");
        claimIs(CLAIM, "UNDER_REVIEW");
    }

    /** Every claim except {@link #UNKNOWN} exists with this status, submitted by {@link #OWNER} and not assigned. */
    protected void claimIs(String claimId, String status) {
        claimIs(claimId, status, OWNER, null);
    }

    /** Every claim except {@link #UNKNOWN} exists with this status, submitter ({@code null}: none recorded) and assignee ({@code null}: nobody). */
    protected void claimIs(String claimId, String status, String owner, String assignee) {
        when(claims.status(anyString())).thenAnswer(inv -> {
            String id = inv.getArgument(0);
            requireKnown(id);
            return new ClaimStatusView(id, status, owner, assignee);
        });
        when(claims.startReview(anyString(), anyString(), anyBoolean(), anyBoolean())).thenAnswer(inv -> {
            String id = inv.getArgument(0);
            requireKnown(id);
            return new WorkflowResponse(id, "UNDER_REVIEW", inv.getArgument(1), NOW);
        });
        when(claims.decide(anyString(), anyString(), anyString(), any(), anyString(), anyString())).thenAnswer(inv -> {
            String id = inv.getArgument(0);
            requireKnown(id);
            String decision = inv.getArgument(1);
            return new DecisionResponse(id, decision, inv.getArgument(2), inv.getArgument(3), inv.getArgument(4),
                    inv.getArgument(5), NOW, NOW);
        });
        when(claims.settle(anyString(), anyString())).thenAnswer(inv -> {
            String id = inv.getArgument(0);
            requireKnown(id);
            return new SettlementResponse(id, "SETTLED", new BigDecimal("2200.00"), "INR", inv.getArgument(1), NOW, NOW);
        });
        doAnswer(inv -> {
            requireKnown(inv.getArgument(0));
            return null;
        }).when(claims).requestInformation(anyString(), any());
        doAnswer(inv -> {
            requireKnown(inv.getArgument(0));
            return null;
        }).when(claims).resumeAfterInformation(anyString(), any());
    }

    private static void requireKnown(String claimId) {
        if (UNKNOWN.equals(claimId)) {
            throw ApiException.notFound("CLAIM_NOT_FOUND", "Claim " + claimId + " was not found");
        }
    }

    protected ResultActions postJson(String path, Object body) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    protected ResultActions postAs(String authorization, String path, Object body) throws Exception {
        return mvc.perform(post(path).header(org.springframework.http.HttpHeaders.AUTHORIZATION, authorization)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    protected ResultActions getJson(String path) throws Exception {
        return mvc.perform(get(path));
    }

    protected ResultActions getAs(String authorization, String path) throws Exception {
        return mvc.perform(get(path).header(org.springframework.http.HttpHeaders.AUTHORIZATION, authorization));
    }

    protected JsonNode read(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    // ---- review, decision, settlement ----

    protected Map<String, Object> reviewBody(String officer, String notes) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("officerId", officer);
        b.put("assessmentNotes", notes);
        return b;
    }

    protected ResultActions review(String claimId, String officer, String notes) throws Exception {
        return postJson("/api/v1/officer/claims/" + claimId + "/review", reviewBody(officer, notes));
    }

    protected Map<String, Object> decisionBody(String decision, String approvedAmount, String currency, String officer) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("decision", decision);
        b.put("decisionReason", "Reason");
        b.put("approvedAmount", approvedAmount == null ? null : new BigDecimal(approvedAmount));
        b.put("currency", currency);
        b.put("decidedByOfficerId", officer);
        return b;
    }

    protected ResultActions decide(String claimId, Map<String, Object> body) throws Exception {
        return postJson("/api/v1/officer/claims/" + claimId + "/decision", body);
    }

    protected ResultActions settle(String claimId, String officer, String note) throws Exception {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("settledByOfficerId", officer);
        b.put("settlementNote", note);
        return postJson("/api/v1/officer/claims/" + claimId + "/settlement", b);
    }

    protected int rows() {
        return jdbc.queryForObject("select count(*) from claim_assessments", Integer.class);
    }

    // ---- information requests ----

    protected ResultActions ask(String claimId, String question, String officer) throws Exception {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("question", question);
        b.put("requestedByOfficerId", officer);
        return postJson("/api/v1/officer/claims/" + claimId + "/information-requests", b);
    }

    /** Asks a question through the API and returns the generated information request ID. */
    protected String askAndGetId(String claimId) throws Exception {
        return read(ask(claimId, "Please provide the repair estimate.", "OFF-1007")).get("informationRequestId").asText();
    }

    protected ResultActions answer(String claimId, String irId, String text) throws Exception {
        return postJson("/api/v1/claims/" + claimId + "/information", Map.of("informationRequestId", irId, "answer", text));
    }

    protected String statusOf(String irId) {
        return jdbc.queryForObject("select status from information_requests where information_request_id = ?", String.class, irId);
    }

    protected int informationRows() {
        return jdbc.queryForObject("select count(*) from information_requests", Integer.class);
    }
}
