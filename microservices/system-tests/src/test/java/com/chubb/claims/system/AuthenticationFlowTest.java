package com.chubb.claims.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chubb.claims.testsupport.TestAuth;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Real people, real logins, all three services. Tokens come from the running User Service (not from a test helper) and are
 * presented to the Claim and Assessment services, which also call the others with service tokens. Everything else in this
 * module runs with a default identity; this class is where "who is calling" is the point.
 */
class AuthenticationFlowTest extends SystemTestBase {

    private static final String PASSWORD = "journey-pass-123";
    private static final String OFFICER = "journey.officer";
    private static final String OTHER_OFFICER = "journey.officer.two";
    private static final String MANAGER = "journey.manager";
    private static final String CLAIMANT_A = "journey.claimant.a";
    private static final String CLAIMANT_B = "journey.claimant.b";

    private String admin;
    private String officer;
    private String otherOfficer;
    private String manager;
    private String claimantA;
    private String claimantB;

    @BeforeEach
    void peopleExistAndAreLoggedIn() throws Exception {
        admin = login(SystemUnderTest.ADMIN_USERNAME, SystemUnderTest.ADMIN_PASSWORD);
        ensureUser(OFFICER, "CLAIMS_OFFICER");
        ensureUser(OTHER_OFFICER, "CLAIMS_OFFICER");
        ensureUser(MANAGER, "MANAGER");
        ensureUser(CLAIMANT_A, "CLAIMANT");
        ensureUser(CLAIMANT_B, "CLAIMANT");
        officer = login(OFFICER, PASSWORD);
        otherOfficer = login(OTHER_OFFICER, PASSWORD);
        manager = login(MANAGER, PASSWORD);
        claimantA = login(CLAIMANT_A, PASSWORD);
        claimantB = login(CLAIMANT_B, PASSWORD);
    }

    // ---- helpers: every call states who is calling ----

    private ResultActions as(String authorization, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, authorization));
    }

    private ResultActions postAs(String authorization, String path, Object body) throws Exception {
        return as(authorization, post(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private ResultActions patchAs(String authorization, String path, Object body) throws Exception {
        return as(authorization, patch(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private ResultActions putAs(String authorization, String path, Object body) throws Exception {
        return as(authorization, put(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private String login(String username, String password) throws Exception {
        ResultActions result = mvc.perform(post("/api/v1/auth/login").header(HttpHeaders.AUTHORIZATION, "")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("username", username, "password", password))))
                .andExpect(status().isOk());
        return "Bearer " + read(result).get("accessToken").asText();
    }

    /** Creates a person through the real User Service (as the bootstrap administrator) unless they already exist. */
    private void ensureUser(String username, String role) throws Exception {
        int existing = as(admin, get("/api/v1/users/" + username)).andReturn().getResponse().getStatus();
        if (existing == 404) {
            postAs(admin, "/api/v1/users", Map.of("username", username, "password", PASSWORD, "role", role)).andExpect(status().isCreated());
        }
    }

    private String claimStatusAs(String authorization, String claimId) throws Exception {
        return read(as(authorization, get("/api/v1/claims/" + claimId)).andExpect(status().isOk())).get("status").asText();
    }

    private String submit(String claimant) throws Exception {
        return read(postAs(claimant, "/api/v1/claims", claimBody("SG", "MOTOR", "2400.00", "INR"))
                .andExpect(status().isCreated())).get("claimId").asText();
    }

    // ---- the whole journey, each step by the person whose job it is ----

    @Test
    void aClaimGoesFromSubmissionToSettlementWithEachStepDoneByTheRightRole() throws Exception {
        // the claimant submits; the claim is theirs, and the claimant is the token's user, not anything in the body
        String claimId = submit(claimantA);
        as(claimantA, get("/api/v1/claims/" + claimId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.claimantId").value(CLAIMANT_A)).andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.policyNumber").value("POL-2026-0042")).andExpect(jsonPath("$.approvedAmount").doesNotExist());

        // another claimant cannot see it, and is told nothing more than for a claim that does not exist
        as(claimantB, get("/api/v1/claims/" + claimId)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CLAIM_NOT_FOUND"));
        as(claimantB, get("/api/v1/claims/" + claimId + "/history")).andExpect(status().isNotFound());
        as(claimantB, get("/api/v1/claims/" + claimId + "/updates")).andExpect(status().isNotFound());
        as(claimantB, get("/api/v1/claims/" + claimId + "/information-requests")).andExpect(status().isNotFound());
        patchAs(claimantB, "/api/v1/claims/" + claimId, Map.of("claimedAmount", 1.00)).andExpect(status().isNotFound());
        as(claimantB, get("/api/v1/claims")).andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(org.hamcrest.Matchers.not(1)));

        // the claimant corrects their own claim while it is still SUBMITTED
        patchAs(claimantA, "/api/v1/claims/" + claimId, Map.of("claimedAmount", 2500.00, "incidentDescription", "Bumper and boot lid."))
                .andExpect(status().isOk()).andExpect(jsonPath("$.claimedAmount").value(2500.00));
        as(claimantA, get("/api/v1/claims/" + claimId + "/updates")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].actor").value(CLAIMANT_A));

        // a claimant cannot do staff work; nothing changes
        postAs(claimantA, "/api/v1/officer/claims/" + claimId + "/assign", Map.of("officerId", OFFICER)).andExpect(status().isForbidden());
        postAs(claimantA, "/api/v1/officer/claims/" + claimId + "/review", Map.of()).andExpect(status().isForbidden());
        postAs(manager, "/api/v1/officer/claims/" + claimId + "/review", Map.of("officerId", OFFICER)).andExpect(status().isForbidden());
        assertThat(claimStatusAs(manager, claimId)).isEqualTo("SUBMITTED");

        // the manager assigns it to a real, active claims officer, and cannot assign it to somebody who is not one
        postAs(manager, "/api/v1/officer/claims/" + claimId + "/assign", Map.of("officerId", CLAIMANT_B)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_OFFICER"));
        postAs(manager, "/api/v1/officer/claims/" + claimId + "/assign", Map.of("officerId", "nobody.at.all")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_OFFICER"));
        postAs(manager, "/api/v1/officer/claims/" + claimId + "/assign", Map.of("officerId", OFFICER)).andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedOfficerId").value(OFFICER));
        as(manager, get("/api/v1/claims/" + claimId + "/assignments")).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].newOfficerId").value(OFFICER)).andExpect(jsonPath("$[0].actor").value(MANAGER));

        // another officer cannot see or touch a claim assigned to this one; an officer cannot give it away either
        as(otherOfficer, get("/api/v1/claims/" + claimId)).andExpect(status().isNotFound());
        postAs(otherOfficer, "/api/v1/officer/claims/" + claimId + "/review", Map.of()).andExpect(status().isNotFound());
        postAs(officer, "/api/v1/officer/claims/" + claimId + "/assign", Map.of("officerId", OTHER_OFFICER)).andExpect(status().isForbidden());

        // the officer reviews as themselves (the Assessment Service calls the Claim Service with a service token)
        postAs(officer, "/api/v1/officer/claims/" + claimId + "/review", Map.of("assessmentNotes", "Journey note"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UNDER_REVIEW")).andExpect(jsonPath("$.assignedOfficerId").value(OFFICER));
        // an officer cannot act in another officer's name
        postAs(officer, "/api/v1/officer/claims/" + claimId + "/information-requests", Map.of("question", "Q", "requestedByOfficerId", OTHER_OFFICER))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("OFFICER_IDENTITY_MISMATCH"));
        // and the claimant can no longer rewrite the claim
        patchAs(claimantA, "/api/v1/claims/" + claimId, Map.of("claimedAmount", 1.00)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CLAIM_NOT_EDITABLE"));

        // the assessment history is for the claims officer: not for the claimant, not for the manager
        as(officer, get("/api/v1/officer/claims/" + claimId + "/assessments")).andExpect(status().isOk()).andExpect(jsonPath("$[0].notes").value("Journey note"));
        as(claimantA, get("/api/v1/officer/claims/" + claimId + "/assessments")).andExpect(status().isForbidden());
        as(manager, get("/api/v1/officer/claims/" + claimId + "/assessments")).andExpect(status().isForbidden());

        // the officer asks, the owner answers, a stranger cannot, and review resumes by itself
        String irId = read(postAs(officer, "/api/v1/officer/claims/" + claimId + "/information-requests", Map.of("question", "Photos please"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.requestedByOfficerId").value(OFFICER))).get("informationRequestId").asText();
        assertThat(claimStatusAs(officer, claimId)).isEqualTo("MORE_INFO_REQUIRED");
        postAs(claimantB, "/api/v1/claims/" + claimId + "/information", Map.of("informationRequestId", irId, "answer", "Not mine"))
                .andExpect(status().isNotFound());
        postAs(officer, "/api/v1/claims/" + claimId + "/information", Map.of("informationRequestId", irId, "answer", "Not the claimant"))
                .andExpect(status().isForbidden());
        postAs(claimantA, "/api/v1/claims/" + claimId + "/information", Map.of("informationRequestId", irId, "answer", "Photos attached"))
                .andExpect(status().isOk());
        assertThat(claimStatusAs(claimantA, claimId)).isEqualTo("UNDER_REVIEW");
        as(claimantA, get("/api/v1/claims/" + claimId + "/history")).andExpect(jsonPath("$[3].toStatus").value("UNDER_REVIEW"))
                .andExpect(jsonPath("$[3].actor").value(CLAIMANT_A));

        // the officer approves with an amount; the claimant and the other officer cannot decide
        Map<String, Object> approval = Map.of("decision", "APPROVED", "decisionReason", "Covered", "approvedAmount", 2000.00, "currency", "INR");
        postAs(claimantA, "/api/v1/officer/claims/" + claimId + "/decision", approval).andExpect(status().isForbidden());
        postAs(otherOfficer, "/api/v1/officer/claims/" + claimId + "/decision", approval).andExpect(status().isNotFound());
        postAs(officer, "/api/v1/officer/claims/" + claimId + "/decision", approval).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED")).andExpect(jsonPath("$.approvedAmount").value(2000.00))
                .andExpect(jsonPath("$.decidedByOfficerId").value(OFFICER));
        as(claimantA, get("/api/v1/claims/" + claimId)).andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.approvedAmount").value(2000.00)).andExpect(jsonPath("$.decisionReason").value("Covered"));

        // approval is not settlement: the manager's summary still counts it, at the approved amount
        as(manager, get("/api/v1/management/summary")).andExpect(jsonPath("$.countsByStatus.APPROVED").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));

        // the officer settles; a claimant cannot, and a second settlement is refused
        postAs(claimantA, "/api/v1/officer/claims/" + claimId + "/settlement", Map.of()).andExpect(status().isForbidden());
        postAs(officer, "/api/v1/officer/claims/" + claimId + "/settlement", Map.of("settlementNote", "Paid by transfer")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SETTLED")).andExpect(jsonPath("$.settledByOfficerId").value(OFFICER));
        postAs(officer, "/api/v1/officer/claims/" + claimId + "/settlement", Map.of()).andExpect(status().isConflict());
        assertThat(claimStatusAs(claimantA, claimId)).isEqualTo("SETTLED");

        // the status history is readable by the owner and by staff, and the assessment history holds the whole story
        as(claimantA, get("/api/v1/claims/" + claimId + "/history")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(6));
        as(manager, get("/api/v1/claims/" + claimId + "/history")).andExpect(status().isOk());
        as(officer, get("/api/v1/officer/claims/" + claimId + "/assessments")).andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].kind").value("NOTE")).andExpect(jsonPath("$[1].kind").value("APPROVED"))
                .andExpect(jsonPath("$[2].kind").value("SETTLED"));
    }

    @Test
    void aRejectedClaimCanNeverBeSettled() throws Exception {
        String claimId = submit(claimantA);
        postAs(manager, "/api/v1/officer/claims/" + claimId + "/assign", Map.of("officerId", OFFICER)).andExpect(status().isOk());
        postAs(officer, "/api/v1/officer/claims/" + claimId + "/review", Map.of()).andExpect(status().isOk());
        postAs(officer, "/api/v1/officer/claims/" + claimId + "/decision", Map.of("decision", "REJECTED", "decisionReason", "Not covered", "currency", "INR"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED")).andExpect(jsonPath("$.approvedAmount").doesNotExist());
        postAs(officer, "/api/v1/officer/claims/" + claimId + "/settlement", Map.of()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("A rejected claim cannot be settled"));
        assertThat(claimStatusAs(claimantA, claimId)).isEqualTo("REJECTED");
    }

    @Test
    void aClaimantListsOnlyTheirOwnClaimsAndAnOfficerTheQueueAndTheirOwn() throws Exception {
        String mine = submit(claimantA);
        String theirs = submit(claimantB);
        String queued = submit(claimantB);
        postAs(manager, "/api/v1/officer/claims/" + theirs + "/assign", Map.of("officerId", OTHER_OFFICER)).andExpect(status().isOk());

        String listA = as(claimantA, get("/api/v1/claims")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(listA).contains(mine).doesNotContain(theirs).doesNotContain(queued);
        String listOfficer = as(officer, get("/api/v1/claims?size=100")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(listOfficer).contains(queued).doesNotContain(theirs);
        String listManager = as(manager, get("/api/v1/claims?size=100")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(listManager).contains(mine, theirs, queued);
    }

    @Test
    void aDeactivatedPersonCannotLogInAndCannotBeAssignedAClaim() throws Exception {
        String temp = "journey.temp.officer";
        ensureUser(temp, "CLAIMS_OFFICER");
        try {
            String claimId = submit(claimantA);
            postAs(manager, "/api/v1/officer/claims/" + claimId + "/assign", Map.of("officerId", temp)).andExpect(status().isOk());

            putAs(admin, "/api/v1/users/" + temp + "/active", Map.of("active", false)).andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
            mvc.perform(post("/api/v1/auth/login").header(HttpHeaders.AUTHORIZATION, "").contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("username", temp, "password", PASSWORD))))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
            String another = submit(claimantA);
            postAs(manager, "/api/v1/officer/claims/" + another + "/assign", Map.of("officerId", temp)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_OFFICER"));

            putAs(admin, "/api/v1/users/" + temp + "/active", Map.of("active", true)).andExpect(status().isOk());
            login(temp, PASSWORD);
            postAs(manager, "/api/v1/officer/claims/" + another + "/assign", Map.of("officerId", temp)).andExpect(status().isOk());
        } finally {
            jdbc.update("delete from users where username = ?", temp);
        }
    }

    @Test
    void theManagementReportsAreForManagersAndAdministratorsOnly() throws Exception {
        submit(claimantA);
        for (String path : new String[] {"/api/v1/management/summary", "/api/v1/management/officers"}) {
            as(manager, get(path)).andExpect(status().isOk());
            as(admin, get(path)).andExpect(status().isOk());
            as(claimantA, get(path)).andExpect(status().isForbidden());
            as(officer, get(path)).andExpect(status().isForbidden());
            as("", get(path)).andExpect(status().isUnauthorized());
        }
        as(manager, get("/api/v1/management/summary")).andExpect(jsonPath("$.countsByStatus.SUBMITTED").value(1));
    }

    @Test
    void aPersonsTokenNeverWorksOnAServiceToServiceRoute() throws Exception {
        String claimId = createClaim();
        for (String token : new String[] {claimantA, officer, manager, admin}) {
            as(token, get("/internal/v1/claims/" + claimId + "/status")).andExpect(status().isForbidden());
            as(token, get("/internal/v1/users/" + OFFICER)).andExpect(status().isForbidden());
        }
        as("", get("/internal/v1/claims/" + claimId + "/status")).andExpect(status().isUnauthorized());
        as("", get("/internal/v1/users/" + OFFICER)).andExpect(status().isUnauthorized());
        as(system.authorization(com.chubb.claims.common.security.Role.SERVICE, "somebody"), get("/internal/v1/claims/" + claimId + "/status"))
                .andExpect(status().isOk());
        as(system.authorization(com.chubb.claims.common.security.Role.SERVICE, "somebody"), get("/internal/v1/users/" + OFFICER))
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("CLAIMS_OFFICER")).andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void aTokenFromAnotherSecretOrAnExpiredOneIsRefusedByEveryService() throws Exception {
        String claimId = createClaim();
        String foreign = bearer(Jwts.builder().subject(OFFICER).issuer("chubb-claims").claim("role", "ADMIN")
                .expiration(Date.from(Instant.now().plusSeconds(600)))
                .signWith(Keys.hmacShaKeyFor("a-secret-the-services-do-not-share-0123456789".getBytes(StandardCharsets.UTF_8))).compact());
        String expired = bearer(Jwts.builder().subject(OFFICER).issuer("chubb-claims").claim("role", "ADMIN")
                .expiration(Date.from(Instant.now().minusSeconds(3600)))
                .signWith(Keys.hmacShaKeyFor(TestAuth.secret().getBytes(StandardCharsets.UTF_8))).compact());
        // a token from before the role was renamed (the old name is no longer a role)
        String oldRoleName = bearer(Jwts.builder().subject(OFFICER).issuer("chubb-claims").claim("role", "OFFICER")
                .expiration(Date.from(Instant.now().plusSeconds(600)))
                .signWith(Keys.hmacShaKeyFor(TestAuth.secret().getBytes(StandardCharsets.UTF_8))).compact());
        String[] routes = {"/api/v1/claims/" + claimId, "/api/v1/claims/" + claimId + "/information-requests",
                "/api/v1/officer/claims/" + claimId + "/assessments", "/api/v1/management/summary", "/api/v1/users/me"};
        for (String token : new String[] {foreign, expired, oldRoleName}) {
            for (String route : routes) {
                as(token, get(route)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
            }
        }
    }

    @Test
    void aWrongPasswordGivesNoTokenAndLoginNeedsNoToken() throws Exception {
        mvc.perform(post("/api/v1/auth/login").header(HttpHeaders.AUTHORIZATION, "").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("username", OFFICER, "password", "not-the-password"))))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.accessToken").doesNotExist());
    }

    @Test
    void aSelfRegisteredClaimantCanLogInAndSubmitButCannotReachStaffRoutesOrUserManagement() throws Exception {
        String name = "journey.newcomer";
        mvc.perform(post("/api/v1/auth/register").header(HttpHeaders.AUTHORIZATION, "").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("username", name, "password", PASSWORD)))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("CLAIMANT")).andExpect(jsonPath("$.active").value(true));
        try {
            String token = login(name, PASSWORD);
            postAs(token, "/api/v1/claims", claimBody("HK", "PROPERTY", "900.00", "HKD")).andExpect(status().isCreated());
            as(token, get("/api/v1/officer/claims")).andExpect(status().isForbidden());
            as(token, get("/api/v1/users")).andExpect(status().isForbidden());
            postAs(token, "/api/v1/users", Map.of("username", "journey.sneaky", "password", PASSWORD, "role", "ADMIN")).andExpect(status().isForbidden());
        } finally {
            jdbc.update("delete from users where username = ?", name);
        }
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
