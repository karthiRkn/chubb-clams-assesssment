package com.chubb.claims.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The first thing a person does with a fresh system: log in as one of the documented default users and call protected
 * APIs on other services with the token. Everything here uses only what the README tells a newcomer (the usernames, the
 * demo password and the login route), not a test helper.
 */
class DemoUsersFlowTest extends SystemTestBase {

    private String login(String username) throws Exception {
        ResultActions result = mvc.perform(post("/api/v1/auth/login").header(HttpHeaders.AUTHORIZATION, "")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("username", username, "password", SystemUnderTest.DEMO_PASSWORD))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.tokenType").value("Bearer"));
        return "Bearer " + read(result).get("accessToken").asText();
    }

    private ResultActions as(String authorization, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, authorization));
    }

    private ResultActions postAs(String authorization, String path, Object body) throws Exception {
        return as(authorization, post(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    @Test
    void eachDemoUserLogsInWithTheDocumentedPasswordAndGetsTheirRole() throws Exception {
        String[][] users = {{"admin1", "ADMIN"}, {"officer1", "CLAIMS_OFFICER"}, {"manager1", "MANAGER"}, {"claimant1", "CLAIMANT"}};
        for (String[] user : users) {
            mvc.perform(post("/api/v1/auth/login").header(HttpHeaders.AUTHORIZATION, "").contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("username", user[0], "password", SystemUnderTest.DEMO_PASSWORD))))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.username").value(user[0])).andExpect(jsonPath("$.role").value(user[1]))
                    .andExpect(jsonPath("$.expiresIn").value(3600));
        }
    }

    @Test
    void aTokenFromTheUserServiceIsAcceptedByTheClaimAndAssessmentServices() throws Exception {
        String claimant = login("claimant1");
        String officer = login("officer1");
        String manager = login("manager1");

        // Claim Service
        String claimId = read(postAs(claimant, "/api/v1/claims", claimBody("SG", "MOTOR", "2400.00", "INR"))
                .andExpect(status().isCreated())).get("claimId").asText();
        as(claimant, get("/api/v1/claims/" + claimId)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.claimantId").value("claimant1"));
        as(claimant, get("/api/v1/claims")).andExpect(status().isOk()).andExpect(jsonPath("$.content[0].claimId").value(claimId));
        as(officer, get("/api/v1/officer/claims")).andExpect(status().isOk());

        // the manager assigns it to the demo officer (the Claim Service asks the User Service whether officer1 is an active claims officer)
        postAs(manager, "/api/v1/officer/claims/" + claimId + "/assign", Map.of("officerId", "officer1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedOfficerId").value("officer1"));

        // Assessment Service (which calls the Claim Service with its own service token)
        postAs(officer, "/api/v1/officer/claims/" + claimId + "/review", Map.of("assessmentNotes", "Looks fine."))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UNDER_REVIEW"));
        postAs(officer, "/api/v1/officer/claims/" + claimId + "/information-requests", Map.of("question", "Please send the repair estimate."))
                .andExpect(status().isCreated());
        as(claimant, get("/api/v1/claims/" + claimId + "/information-requests")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));

        // management reports, in the Claim Service
        as(manager, get("/api/v1/management/summary")).andExpect(status().isOk());
        as(manager, get("/api/v1/management/officers")).andExpect(status().isOk());
    }

    @Test
    void eachDemoUserIsStoppedAtTheRoutesOfOtherRoles() throws Exception {
        String claimant = login("claimant1");
        String officer = login("officer1");
        String manager = login("manager1");
        as(claimant, get("/api/v1/officer/claims")).andExpect(status().isForbidden());
        as(claimant, get("/api/v1/management/summary")).andExpect(status().isForbidden());
        as(officer, get("/api/v1/management/summary")).andExpect(status().isForbidden());
        postAs(manager, "/api/v1/officer/claims/CLM-2026-000101/review", Map.of("officerId", "manager1")).andExpect(status().isForbidden());
        as(manager, get("/api/v1/users")).andExpect(status().isForbidden());
    }

    @Test
    void theAdministratorSeesExactlyTheDemoUsersAmongTheUsers() throws Exception {
        JsonNode page = read(as(login("admin1"), get("/api/v1/users?size=100")).andExpect(status().isOk()));
        List<String> names = new ArrayList<>();
        page.get("content").forEach(u -> names.add(u.get("username").asText()));
        assertThat(names).contains("admin1", "officer1", "manager1", "claimant1");
        assertThat(page.toString()).doesNotContain(SystemUnderTest.DEMO_PASSWORD).doesNotContain("$2");
    }

    @Test
    void withoutLoggingInEveryProtectedApiIs401ThatNamesTheLoginRoute() throws Exception {
        as("", get("/api/v1/officer/claims")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("/api/v1/auth/login")));
    }
}
