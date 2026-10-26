package com.chubb.claims.system;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Base class of the system tests. It offers the same helpers as the monolith's {@code IntegrationTestBase} (so the
 * original test classes need almost no change) but every call goes over real HTTP to the running services, and
 * {@code jdbc} reaches the database of whichever service owns the table.
 */
public abstract class SystemTestBase {

    protected final SystemUnderTest system = SystemUnderTest.get();
    protected final HttpMvc mvc = new HttpMvc(system::baseUrlFor);
    protected final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    protected final SystemJdbc jdbc = system.jdbc();

    @BeforeEach
    void cleanDatabases() {
        jdbc.update("delete from claim_assessments");
        jdbc.update("delete from claim_update_history");
        jdbc.update("delete from claim_assignment_history");
        jdbc.update("delete from claim_status_history");
        jdbc.update("delete from information_requests");
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
        r.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated());
        return read(r).get("claimId").asText();
    }

    protected String createClaim() throws Exception {
        return createClaim("SG", "MOTOR", "2400.00", "INR");
    }

    protected void assign(String claimId, String officer) throws Exception {
        postJson("/api/v1/officer/claims/" + claimId + "/assign", Map.of("officerId", officer))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    }

    protected void review(String claimId, String officer) throws Exception {
        postJson("/api/v1/officer/claims/" + claimId + "/review", Map.of("officerId", officer))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    }

    /** Creates a claim and moves it to UNDER_REVIEW. */
    protected String createClaimUnderReview() throws Exception {
        String id = createClaim();
        review(id, "off-1007");
        return id;
    }
}
