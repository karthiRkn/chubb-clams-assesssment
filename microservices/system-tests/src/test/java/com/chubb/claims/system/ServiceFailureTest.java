package com.chubb.claims.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chubb.claims.assessment.AssessmentServiceApplication;
import com.chubb.claims.claim.ClaimServiceApplication;
import com.chubb.claims.common.security.JwtProperties;
import com.chubb.claims.common.security.JwtUtil;
import com.chubb.claims.common.security.Role;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * What a caller sees when a service it depends on is down or misconfigured. A second copy of the service under test is
 * started with the dependency's URL pointing at a port where nothing listens (or with another JWT secret), so the failure is
 * real. The healthy services from the main system are reused to check that nothing was changed behind the failed call.
 * The dependencies are Assessment to Claim and Claim to User, and nothing else.
 */
class ServiceFailureTest {

    private static final SystemUnderTest SYSTEM = SystemUnderTest.get();
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    private static final String OTHER_SECRET = "a-different-secret-for-the-misconfigured-service-0123456789";

    private static ConfigurableApplicationContext assessmentWithoutClaim;
    private static ConfigurableApplicationContext assessmentWithOtherSecret;
    private static ConfigurableApplicationContext claimWithoutUser;
    private static ConfigurableApplicationContext claimWithOtherSecret;

    @BeforeAll
    static void startDegradedServices() {
        assessmentWithoutClaim = SYSTEM.startExtra(AssessmentServiceApplication.class, "assessment-service",
                "sys_assessment_no_claim", "assessment", Map.of("claims.services.claim.url", SystemUnderTest.deadUrl()));
        assessmentWithOtherSecret = SYSTEM.startExtra(AssessmentServiceApplication.class, "assessment-service",
                "sys_assessment_other_secret", "assessment",
                Map.of("claims.services.claim.url", SYSTEM.claimUrl, "claims.security.jwt.secret", OTHER_SECRET));
        claimWithoutUser = SYSTEM.startExtra(ClaimServiceApplication.class, "claim-service", "sys_claim_no_user", "claim",
                Map.of("claims.services.user.url", SystemUnderTest.deadUrl()));
        claimWithOtherSecret = SYSTEM.startExtra(ClaimServiceApplication.class, "claim-service", "sys_claim_other_secret", "claim",
                Map.of("claims.services.user.url", SYSTEM.userUrl, "claims.security.jwt.secret", OTHER_SECRET));
    }

    @AfterAll
    static void stopDegradedServices() {
        for (ConfigurableApplicationContext context : new ConfigurableApplicationContext[] {assessmentWithoutClaim,
                assessmentWithOtherSecret, claimWithoutUser, claimWithOtherSecret}) {
            if (context != null) {
                context.close();
            }
        }
    }

    private static HttpMvc to(ConfigurableApplicationContext service) {
        String url = SystemUnderTest.urlOf(service);
        return new HttpMvc(path -> url);
    }

    private static HttpMvc live() {
        return new HttpMvc(SYSTEM::baseUrlFor);
    }

    private static org.springframework.test.web.servlet.ResultActions postJson(HttpMvc mvc, String path, Object body) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body)));
    }

    private static final Map<String, Object> CLAIM_BODY = Map.of("claimantName", "Mei Tan", "claimantEmail", "mei.tan@example.com",
            "policyNumber", "POL-2026-0042", "market", "SG", "claimType", "MOTOR", "incidentDescription", "Bumper.",
            "incidentDate", "2026-10-12", "claimedAmount", 2400.00, "currency", "INR");

    private static String newClaim(HttpMvc claimService) throws Exception {
        var created = postJson(claimService, "/api/v1/claims", CLAIM_BODY).andExpect(status().isCreated());
        return JSON.readTree(created.andReturn().getResponse().getContentAsString()).get("claimId").asText();
    }

    private static String claimStatus(String claimId) throws Exception {
        var response = live().perform(get("/api/v1/claims/" + claimId)).andReturn().getResponse().getContentAsString();
        return JSON.readTree(response).get("status").asText();
    }

    // ---- Assessment to Claim ----

    @Test
    void everyAssessmentActionFailsWith503WhenTheClaimServiceIsDownAndNothingIsKept() throws Exception {
        HttpMvc assessment = to(assessmentWithoutClaim);
        String claimId = "CLM-2026-000101";

        postJson(assessment, "/api/v1/officer/claims/" + claimId + "/review", Map.of("officerId", "off-1", "assessmentNotes", "Note."))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DOWNSTREAM_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Claim Service")))
                .andExpect(jsonPath("$.path").value("/api/v1/officer/claims/" + claimId + "/review"));
        postJson(assessment, "/api/v1/officer/claims/" + claimId + "/decision", Map.of("decision", "REJECTED",
                "decisionReason", "No cover.", "currency", "INR", "decidedByOfficerId", "off-1"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("DOWNSTREAM_UNAVAILABLE"));
        postJson(assessment, "/api/v1/officer/claims/" + claimId + "/settlement", Map.of("settledByOfficerId", "off-1"))
                .andExpect(status().isServiceUnavailable());
        postJson(assessment, "/api/v1/officer/claims/" + claimId + "/information-requests",
                Map.of("question", "Photos please", "requestedByOfficerId", "off-1"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("DOWNSTREAM_UNAVAILABLE"));
        assessment.perform(get("/api/v1/officer/claims/" + claimId + "/assessments")).andExpect(status().isServiceUnavailable());
        assessment.perform(get("/api/v1/claims/" + claimId + "/information-requests")).andExpect(status().isServiceUnavailable());

        JdbcTemplate jdbc = assessmentWithoutClaim.getBean(JdbcTemplate.class);
        assertThat(jdbc.queryForObject("select count(*) from claim_assessments", Integer.class))
                .as("no entry was kept for a failed review, decision or settlement").isZero();
        assertThat(jdbc.queryForObject("select count(*) from information_requests", Integer.class))
                .as("the request was never stored").isZero();
    }

    @Test
    void anAnswerIsNotSavedWhenTheClaimServiceCannotBeAskedAboutTheClaim() throws Exception {
        JdbcTemplate jdbc = assessmentWithoutClaim.getBean(JdbcTemplate.class);
        jdbc.update("""
                insert into information_requests (information_request_id, claim_id, question, status, requested_by_officer_id, created_at)
                values ('IR-2026-900001', 'CLM-2026-000101', 'Q', 'OPEN', 'off-1', current_timestamp)""");
        try {
            postJson(to(assessmentWithoutClaim), "/api/v1/claims/CLM-2026-000101/information",
                    Map.of("informationRequestId", "IR-2026-900001", "answer", "Attached."))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("DOWNSTREAM_UNAVAILABLE"));
            assertThat(jdbc.queryForObject("select status from information_requests where information_request_id = 'IR-2026-900001'",
                    String.class)).isEqualTo("OPEN");
        } finally {
            jdbc.update("delete from information_requests");
        }
    }

    @Test
    void aServiceWhoseJwtSecretDiffersCannotCallTheClaimServiceAndTheCallerIsToldNothingAboutTokens() throws Exception {
        // This Assessment Service signs its service tokens with another secret, so the Claim Service refuses the token with
        // 401. That is a configuration fault between two services, not something the officer did: it surfaces as 502, naming
        // the service but giving no hint about tokens, and the Claim Service is left exactly as it was.
        JwtUtil sameSecretAsTheMisconfiguredService = new JwtUtil(new JwtProperties(OTHER_SECRET, "chubb-claims",
                Duration.ofMinutes(5), Duration.ofSeconds(60)));
        String officer = "Bearer " + sameSecretAsTheMisconfiguredService.generate("some.officer", Role.CLAIMS_OFFICER);
        String claimId = newClaim(live());

        to(assessmentWithOtherSecret).perform(post("/api/v1/officer/claims/" + claimId + "/review")
                        .header("Authorization", officer).contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("assessmentNotes", "Note."))))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("DOWNSTREAM_ERROR"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.matchesPattern("Claim Service .*")))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Bearer"))))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret"))));

        assertThat(claimStatus(claimId)).isEqualTo("SUBMITTED");
        assertThat(assessmentWithOtherSecret.getBean(JdbcTemplate.class)
                .queryForObject("select count(*) from claim_assessments", Integer.class)).isZero();
        // the same person's token is, correctly, worthless to a service with the system's secret
        live().perform(get("/api/v1/officer/claims/" + claimId + "/assessments").header("Authorization", officer))
                .andExpect(status().isUnauthorized());
    }

    // ---- Claim to User ----

    @Test
    void aManagerCannotAssignWhenTheUserServiceIsDownButEverythingElseStillWorks() throws Exception {
        HttpMvc claims = to(claimWithoutUser);
        String claimId = newClaim(claims);

        // the officer cannot be checked, so the assignment is refused rather than guessed
        postJson(claims, "/api/v1/officer/claims/" + claimId + "/assign", Map.of("officerId", "off-1"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DOWNSTREAM_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("User Service")))
                .andExpect(jsonPath("$.path").value("/api/v1/officer/claims/" + claimId + "/assign"));
        JdbcTemplate jdbc = claimWithoutUser.getBean(JdbcTemplate.class);
        assertThat(jdbc.queryForObject("select assigned_officer_id from claims where claim_id = ?", String.class, claimId)).isNull();
        assertThat(jdbc.queryForObject("select count(*) from claim_assignment_history", Integer.class)).isZero();

        // an officer taking a claim for themselves needs no lookup (their role is in their token), and reads still work
        claims.perform(post("/api/v1/officer/claims/" + claimId + "/assign").header("Authorization",
                        SYSTEM.authorization(Role.CLAIMS_OFFICER, "off-1")).contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("officerId", "off-1"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.assignedOfficerId").value("off-1"));
        claims.perform(get("/api/v1/claims/" + claimId)).andExpect(status().isOk());
        claims.perform(get("/api/v1/management/summary")).andExpect(status().isOk());
    }

    @Test
    void aClaimServiceWhoseJwtSecretDiffersFromTheUserServicesGets502NotAGuessAboutTheOfficer() throws Exception {
        // A manager's token signed with the misconfigured Claim Service's own secret is fine there, but the service token it
        // sends on to the User Service is signed with that same wrong secret, so the User Service refuses it.
        JwtUtil other = new JwtUtil(new JwtProperties(OTHER_SECRET, "chubb-claims", Duration.ofMinutes(5), Duration.ofSeconds(60)));
        String manager = "Bearer " + other.generate("some.manager", Role.MANAGER);
        String claimant = "Bearer " + other.generate("some.claimant", Role.CLAIMANT);
        HttpMvc claims = to(claimWithOtherSecret);
        var created = claims.perform(post("/api/v1/claims").header("Authorization", claimant).contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(CLAIM_BODY))).andExpect(status().isCreated());
        String claimId = JSON.readTree(created.andReturn().getResponse().getContentAsString()).get("claimId").asText();

        claims.perform(post("/api/v1/officer/claims/" + claimId + "/assign").header("Authorization", manager)
                        .contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(Map.of("officerId", "off-1"))))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("DOWNSTREAM_ERROR"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.matchesPattern("User Service .*")))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Bearer"))));
        assertThat(claimWithOtherSecret.getBean(JdbcTemplate.class)
                .queryForObject("select count(*) from claim_assignment_history", Integer.class)).isZero();
    }

    // ---- validation comes before any call ----

    @Test
    void requestValidationStillAnswers400EvenWhenTheDependencyIsDown() throws Exception {
        // validation runs before any call to another service, so a bad request is a 400, not a 503
        postJson(to(assessmentWithoutClaim), "/api/v1/officer/claims/CLM-2026-000101/review", Map.of("officerId", "string"))
                .andExpect(status().isBadRequest());
        postJson(to(assessmentWithoutClaim), "/api/v1/officer/claims/CLM-2026-000101/information-requests",
                Map.of("question", "", "requestedByOfficerId", "off-1"))
                .andExpect(status().isBadRequest());
        postJson(to(claimWithoutUser), "/api/v1/officer/claims/CLM-2026-000101/assign", Map.of("officerId", "string"))
                .andExpect(status().isBadRequest());
    }
}
