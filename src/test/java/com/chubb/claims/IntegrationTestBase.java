package com.chubb.claims;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Full-stack tests (controllers, services, JPA, Flyway) on the H2 test database. Tests are not wrapped in a transaction. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class IntegrationTestBase {

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected ObjectMapper json;
    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("delete from information_requests");
        jdbc.update("delete from claims");
    }

    protected Map<String, Object> claimBody(String market, String type, String amount, String currency) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("claimantName", "Mei Tan");
        b.put("claimantEmail", "mei.tan@example.com");
        b.put("market", market);
        b.put("claimType", type);
        b.put("description", "Rear bumper damaged in a low-speed collision.");
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
        return createClaim("SG", "MOTOR", "2400.00", "SGD");
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
        review(id, "OFF-1007");
        return id;
    }
}

