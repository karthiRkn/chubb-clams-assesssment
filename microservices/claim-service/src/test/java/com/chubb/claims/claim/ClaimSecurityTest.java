package com.chubb.claims.claim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chubb.claims.common.security.Role;
import com.chubb.claims.testsupport.AccessMatrix;
import com.chubb.claims.testsupport.RouteCoverage;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** Who may call the Claim Service, and which claims a claimant and a claims officer may see. */
class ClaimSecurityTest extends IntegrationTestBase {

    @Autowired
    ApplicationContext context;

    // ---- the role table ----

    @Test
    void everyPublicRouteIsAllowedToExactlyTheRolesItsRuleListsAndToNobodyElse() throws Exception {
        assertThat(AccessMatrix.deviations(mvc, jwt, context)).isEmpty();
    }

    /**
     * Written by hand from the documented role matrix (API_TEST_CASES.md), deliberately not derived from the code: the
     * test above proves the service enforces its table, this proves the table is the documented one. Roles are listed in
     * the enum's order and ADMIN is in every public route.
     */
    @Test
    void theRuleTableIsTheDocumentedRoleMatrix() {
        assertThat(AccessMatrix.table(context)).isEqualTo(Map.ofEntries(
                Map.entry("POST /api/v1/claims", "CLAIMANT, ADMIN"),
                Map.entry("GET /api/v1/claims", "CLAIMANT, CLAIMS_OFFICER, MANAGER, ADMIN"),
                Map.entry("GET /api/v1/claims/{claimId}", "CLAIMANT, CLAIMS_OFFICER, MANAGER, ADMIN"),
                Map.entry("PATCH /api/v1/claims/{claimId}", "CLAIMANT, ADMIN"),
                Map.entry("GET /api/v1/claims/{claimId}/history", "CLAIMANT, CLAIMS_OFFICER, MANAGER, ADMIN"),
                Map.entry("GET /api/v1/claims/{claimId}/assignments", "CLAIMANT, CLAIMS_OFFICER, MANAGER, ADMIN"),
                Map.entry("GET /api/v1/claims/{claimId}/updates", "CLAIMANT, CLAIMS_OFFICER, MANAGER, ADMIN"),
                Map.entry("GET /api/v1/officer/claims", "CLAIMS_OFFICER, MANAGER, ADMIN"),
                Map.entry("POST /api/v1/officer/claims/{claimId}/assign", "CLAIMS_OFFICER, MANAGER, ADMIN"),
                Map.entry("GET /api/v1/management/summary", "MANAGER, ADMIN"),
                Map.entry("GET /api/v1/management/officers", "MANAGER, ADMIN")));
    }

    @Test
    void everyApiRouteHasAnAccessRule() {
        RouteCoverage.assertEveryApiRouteHasExactlyOneRule(context);
    }

    @Test
    void internalRoutesAcceptOnlyAServiceToken() throws Exception {
        String id = createClaim();
        mvc.perform(get(INTERNAL + id + "/status").header(HttpHeaders.AUTHORIZATION, "")).andExpect(status().isUnauthorized());
        for (Role role : new Role[] {Role.CLAIMANT, Role.CLAIMS_OFFICER, Role.MANAGER, Role.ADMIN}) {
            mvc.perform(get(INTERNAL + id + "/status").header(HttpHeaders.AUTHORIZATION, as(role))).andExpect(status().isForbidden());
        }
        mvc.perform(get(INTERNAL + id + "/status").header(HttpHeaders.AUTHORIZATION, as(Role.SERVICE))).andExpect(status().isOk());

        // every state-changing internal route as well: nobody's own token can move a claim behind the Assessment Service's back
        Map<String, String> posts = Map.of(
                "/review", "{\"officerId\":\"OFF-1\",\"hasOpenInformationRequests\":false}",
                "/request-information", "{\"requestedByOfficerId\":\"OFF-1\"}",
                "/resume-after-information", "{}",
                "/decision", "{\"decision\":\"APPROVED\",\"decisionReason\":\"r\",\"approvedAmount\":1.00,\"currency\":\"INR\",\"decidedByOfficerId\":\"OFF-1\"}",
                "/settlement", "{\"settledByOfficerId\":\"OFF-1\"}");
        for (Map.Entry<String, String> route : posts.entrySet()) {
            mvc.perform(post(INTERNAL + id + route.getKey()).header(HttpHeaders.AUTHORIZATION, "")
                    .contentType(MediaType.APPLICATION_JSON).content(route.getValue())).andExpect(status().isUnauthorized());
            for (Role role : new Role[] {Role.CLAIMANT, Role.CLAIMS_OFFICER, Role.MANAGER, Role.ADMIN}) {
                mvc.perform(post(INTERNAL + id + route.getKey()).header(HttpHeaders.AUTHORIZATION, as(role))
                        .contentType(MediaType.APPLICATION_JSON).content(route.getValue())).andExpect(status().isForbidden());
            }
        }
        assertThat(jdbc.queryForObject("select status from claims where claim_id = ?", String.class, id)).isEqualTo("SUBMITTED");
    }

    @Test
    void theStatsRoutesAreGoneBecauseTheReportsNowLiveInThisService() throws Exception {
        for (String route : new String[] {INTERNAL + "stats/summary", INTERNAL + "stats/officers"}) {
            mvc.perform(get(route).header(HttpHeaders.AUTHORIZATION, as(Role.SERVICE))).andExpect(status().isNotFound());
        }
    }

    @Test
    void aMissingExpiredOrTamperedTokenIs401OnEveryPublicRoute() throws Exception {
        String token = as(Role.ADMIN);
        String tampered = token.substring(0, token.length() - 3) + (token.endsWith("abc") ? "xyz" : "abc");
        for (String auth : new String[] {"", "Bearer nonsense", tampered}) {
            mvc.perform(get("/api/v1/claims/CLM-2026-000101").header(HttpHeaders.AUTHORIZATION, auth))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
            mvc.perform(post("/api/v1/claims").header(HttpHeaders.AUTHORIZATION, auth).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(claimBody("SG", "MOTOR", "10.00", "INR")))).andExpect(status().isUnauthorized());
        }
        assertThat(jdbc.queryForObject("select count(*) from claims", Integer.class)).isZero();
    }

    // ---- a claimant sees only their own claims ----

    private String createAs(String authorization, String claimantNameInBody) throws Exception {
        var body = claimBody("SG", "MOTOR", "2400.00", "INR");
        body.put("claimantName", claimantNameInBody);
        ResultActions created = mvc.perform(post("/api/v1/claims").header(HttpHeaders.AUTHORIZATION, authorization)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andExpect(status().isCreated());
        return read(created).get("claimId").asText();
    }

    private ResultActions fetch(String authorization, String path) throws Exception {
        return mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, authorization));
    }

    private static final String[] CLAIM_READS = {"", "/history", "/assignments", "/updates"};

    @Test
    void theClaimIsRecordedAsSubmittedByTheTokensUsernameNotByAnyNameInTheBody() throws Exception {
        String id = createAs(as(Role.CLAIMANT, "claimant.a"), "Somebody Else Entirely");
        assertThat(jdbc.queryForObject("select claimant_id from claims where claim_id = ?", String.class, id)).isEqualTo("claimant.a");
        fetch(as(Role.CLAIMANT, "claimant.a"), "/api/v1/claims/" + id).andExpect(status().isOk())
                .andExpect(jsonPath("$.claimantId").value("claimant.a"))
                .andExpect(jsonPath("$.claimantName").value("Somebody Else Entirely"));
    }

    @Test
    void aClaimantReadsTheirOwnClaimHistoryAssignmentsAndUpdates() throws Exception {
        String id = createAs(as(Role.CLAIMANT, "claimant.a"), "Mei Tan");
        for (String suffix : CLAIM_READS) {
            fetch(as(Role.CLAIMANT, "claimant.a"), "/api/v1/claims/" + id + suffix).andExpect(status().isOk());
        }
    }

    @Test
    void anotherClaimantGetsTheSameAnswerAsForAClaimThatDoesNotExist() throws Exception {
        String id = createAs(as(Role.CLAIMANT, "claimant.a"), "Mei Tan");
        for (String suffix : CLAIM_READS) {
            String foreign = fetch(as(Role.CLAIMANT, "claimant.b"), "/api/v1/claims/" + id + suffix).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("CLAIM_NOT_FOUND")).andReturn().getResponse().getContentAsString();
            String unknown = fetch(as(Role.CLAIMANT, "claimant.b"), "/api/v1/claims/CLM-2099-999999" + suffix).andExpect(status().isNotFound())
                    .andReturn().getResponse().getContentAsString();
            JsonNode a = json.readTree(foreign);
            JsonNode b = json.readTree(unknown);
            assertThat(a.get("message").asText()).isEqualTo("Claim " + id + " was not found");
            assertThat(a.get("code")).isEqualTo(b.get("code"));
            assertThat(a.get("status")).isEqualTo(b.get("status"));
            assertThat(foreign).doesNotContain("claimant.a").doesNotContain("Mei Tan");
        }
    }

    @Test
    void managersAndAdministratorsReadAnyClaim() throws Exception {
        String id = createAs(as(Role.CLAIMANT, "claimant.a"), "Mei Tan");
        for (Role role : new Role[] {Role.MANAGER, Role.ADMIN}) {
            for (String suffix : CLAIM_READS) {
                fetch(as(role, "staff.member"), "/api/v1/claims/" + id + suffix).andExpect(status().isOk());
            }
        }
    }

    @Test
    void aClaimWithNoRecordedOwnerIsVisibleToStaffOnly() throws Exception {
        // a claim from before claimants were recorded
        jdbc.update("insert into claims (claim_id, claimant_name, claimant_email, market, claim_type, incident_description, incident_date, "
                + "claimed_amount, currency, status, created_at, updated_at) values ('CLM-2025-000001', 'Old Claimant', 'old@example.com', "
                + "'SG', 'MOTOR', 'old claim', date '2025-01-01', 10.00, 'INR', 'SUBMITTED', current_timestamp, current_timestamp)");
        fetch(as(Role.CLAIMANT, "claimant.a"), "/api/v1/claims/CLM-2025-000001").andExpect(status().isNotFound());
        fetch(as(Role.CLAIMS_OFFICER), "/api/v1/claims/CLM-2025-000001").andExpect(status().isOk());
        fetch(as(Role.MANAGER), "/api/v1/claims/CLM-2025-000001").andExpect(status().isOk());
    }

    @Test
    void theOwnerIsComparedExactlyNotByPrefixOrCase() throws Exception {
        String id = createAs(as(Role.CLAIMANT, "claimant.a"), "Mei Tan");
        for (String other : new String[] {"claimant", "claimant.a2", "Claimant.A", "claimant.", "claimant.a "}) {
            fetch(as(Role.CLAIMANT, other), "/api/v1/claims/" + id).andExpect(status().isNotFound());
        }
    }

    // ---- a claims officer sees the unassigned queue and their own claims only ----

    @Test
    void aClaimsOfficerReadsUnassignedClaimsAndTheirOwnButNotAnotherOfficersClaims() throws Exception {
        String queued = createAs(as(Role.CLAIMANT, "claimant.a"), "Mei Tan");
        String mine = createAs(as(Role.CLAIMANT, "claimant.a"), "Mei Tan");
        String theirs = createAs(as(Role.CLAIMANT, "claimant.b"), "Lee Wong");
        assign(mine, "officer.a");
        assign(theirs, "officer.b");

        for (String suffix : CLAIM_READS) {
            fetch(as(Role.CLAIMS_OFFICER, "officer.a"), "/api/v1/claims/" + queued + suffix).andExpect(status().isOk());
            fetch(as(Role.CLAIMS_OFFICER, "officer.a"), "/api/v1/claims/" + mine + suffix).andExpect(status().isOk());
            // the same 404 an unknown claim gets, and nothing about who has it
            String hidden = fetch(as(Role.CLAIMS_OFFICER, "officer.a"), "/api/v1/claims/" + theirs + suffix)
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CLAIM_NOT_FOUND"))
                    .andReturn().getResponse().getContentAsString();
            assertThat(hidden).doesNotContain("officer.b").doesNotContain("Lee Wong");
        }
        // the other officer reads theirs
        fetch(as(Role.CLAIMS_OFFICER, "officer.b"), "/api/v1/claims/" + theirs).andExpect(status().isOk());
    }

    @Test
    void theInternalStatusViewTellsOtherServicesWhoSubmittedAndWhoHasTheClaimAndNothingElseAboutThem() throws Exception {
        String id = createAs(as(Role.CLAIMANT, "claimant.a"), "Mei Tan");
        assign(id, "officer.a");
        String body = mvc.perform(get(INTERNAL + id + "/status").header(HttpHeaders.AUTHORIZATION, as(Role.SERVICE)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.claimId").value(id)).andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.claimantId").value("claimant.a")).andExpect(jsonPath("$.assignedOfficerId").value("officer.a"))
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(body).fieldNames()).toIterable()
                .containsExactlyInAnyOrder("claimId", "status", "claimantId", "assignedOfficerId");
        assertThat(body).doesNotContain("Mei Tan").doesNotContain("example.com");
    }
}
