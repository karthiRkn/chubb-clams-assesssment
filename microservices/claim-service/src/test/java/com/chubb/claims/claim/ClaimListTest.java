package com.chubb.claims.claim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chubb.claims.common.security.Role;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** GET /api/v1/claims: the same list for everyone, narrowed in SQL to what the caller may see. */
class ClaimListTest extends IntegrationTestBase {

    private static final String LIST = "/api/v1/claims";

    private String aliceOld;
    private String aliceNew;
    private String bobClaim;
    private String queued;

    private String createAs(String username, String market, String type, String amount, String currency) throws Exception {
        ResultActions r = mvc.perform(post("/api/v1/claims").header(HttpHeaders.AUTHORIZATION, as(Role.CLAIMANT, username))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(claimBody(market, type, amount, currency))))
                .andExpect(status().isCreated());
        return read(r).get("claimId").asText();
    }

    private ResultActions list(Role role, String username, String query) throws Exception {
        return mvc.perform(get(LIST + query).header(HttpHeaders.AUTHORIZATION, as(role, username)));
    }

    private static List<String> ids(JsonNode page) {
        List<String> ids = new ArrayList<>();
        page.get("content").forEach(c -> ids.add(c.get("claimId").asText()));
        return ids;
    }

    @BeforeEach
    void seed() throws Exception {
        aliceOld = createAs("claimant.alice", "SG", "MOTOR", "100.00", "INR");
        bobClaim = createAs("claimant.bob", "SG", "PROPERTY", "200.00", "INR");
        aliceNew = createAs("claimant.alice", "AU", "MOTOR", "300.00", "AUD");
        queued = createAs("claimant.carol", "HK", "MOTOR", "400.00", "HKD");
        assign(aliceOld, "officer.a");
        assign(bobClaim, "officer.b");
    }

    // ---- a claimant ----

    @Test
    void aClaimantListsTheirOwnClaimsNewestFirstAndNobodyElses() throws Exception {
        JsonNode page = read(list(Role.CLAIMANT, "claimant.alice", "").andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2)).andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20)).andExpect(jsonPath("$.totalPages").value(1)));
        assertThat(ids(page)).containsExactly(aliceNew, aliceOld);
        assertThat(ids(read(list(Role.CLAIMANT, "claimant.bob", "")))).containsExactly(bobClaim);
        list(Role.CLAIMANT, "claimant.nobody", "").andExpect(jsonPath("$.totalElements").value(0)).andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    void aClaimantsFiltersAndPagingApplyInsideTheirOwnClaims() throws Exception {
        list(Role.CLAIMANT, "claimant.alice", "?market=AU").andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].claimId").value(aliceNew));
        list(Role.CLAIMANT, "claimant.alice", "?market=HK").andExpect(jsonPath("$.totalElements").value(0));
        list(Role.CLAIMANT, "claimant.alice", "?claimType=PROPERTY").andExpect(jsonPath("$.totalElements").value(0));
        list(Role.CLAIMANT, "claimant.alice", "?page=0&size=1").andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.totalElements").value(2)).andExpect(jsonPath("$.totalPages").value(2));
        list(Role.CLAIMANT, "claimant.alice", "?page=1&size=1").andExpect(jsonPath("$.content[0].claimId").value(aliceOld));
    }

    @Test
    void askingForAnotherClaimantsClaimsThroughAFilterShowsNothingOfThem() throws Exception {
        list(Role.CLAIMANT, "claimant.alice", "?assignedOfficerId=officer.b").andExpect(jsonPath("$.totalElements").value(0));
        list(Role.CLAIMANT, "claimant.alice", "?assigned=false").andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].claimId").value(aliceNew));
    }

    @Test
    void aStatusFilterFollowsTheLifecycle() throws Exception {
        review(aliceOld, "officer.a");
        list(Role.CLAIMANT, "claimant.alice", "?status=UNDER_REVIEW").andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].claimId").value(aliceOld));
        list(Role.CLAIMANT, "claimant.alice", "?status=SUBMITTED").andExpect(jsonPath("$.content[0].claimId").value(aliceNew));
    }

    @Test
    void listItemsCarryTheClaimantTheirPolicyAndTheStatusButNotThePrivateTextOfTheClaim() throws Exception {
        String body = list(Role.CLAIMANT, "claimant.alice", "").andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].claimantId").value("claimant.alice"))
                .andExpect(jsonPath("$.content[0].claimantName").value("Mei Tan"))
                .andExpect(jsonPath("$.content[0].policyNumber").value("POL-2026-0042"))
                .andExpect(jsonPath("$.content[0].status").value("SUBMITTED"))
                .andExpect(jsonPath("$.content[0].currency").value("AUD"))
                .andExpect(jsonPath("$.content[0].incidentDescription").doesNotExist())
                .andExpect(jsonPath("$.content[0].claimantEmail").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("claimant.bob").doesNotContain("claimant.carol");
    }

    // ---- a claims officer ----

    @Test
    void aClaimsOfficerSeesTheUnassignedQueueAndTheirOwnClaimsButNotAnotherOfficers() throws Exception {
        assertThat(ids(read(list(Role.CLAIMS_OFFICER, "officer.a", "").andExpect(status().isOk()))))
                .containsExactlyInAnyOrder(aliceOld, aliceNew, queued);
        assertThat(ids(read(list(Role.CLAIMS_OFFICER, "officer.b", "")))).containsExactlyInAnyOrder(bobClaim, aliceNew, queued);
        assertThat(ids(read(list(Role.CLAIMS_OFFICER, "officer.nobody", "")))).containsExactlyInAnyOrder(aliceNew, queued);
    }

    @Test
    void theOfficerRouteGivesTheSameScopedListAsTheClaimsRoute() throws Exception {
        String viaOfficerRoute = mvc.perform(get("/api/v1/officer/claims").header(HttpHeaders.AUTHORIZATION, as(Role.CLAIMS_OFFICER, "officer.a")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(ids(json.readTree(viaOfficerRoute))).containsExactlyInAnyOrder(aliceOld, aliceNew, queued);
    }

    @Test
    void anOfficerCannotWidenTheirScopeWithAFilter() throws Exception {
        // another officer's claims stay hidden whatever the filter says
        list(Role.CLAIMS_OFFICER, "officer.a", "?assignedOfficerId=officer.b").andExpect(jsonPath("$.totalElements").value(0));
        list(Role.CLAIMS_OFFICER, "officer.a", "?assigned=true").andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].claimId").value(aliceOld));
        list(Role.CLAIMS_OFFICER, "officer.a", "?assigned=false").andExpect(jsonPath("$.totalElements").value(2));
        list(Role.CLAIMS_OFFICER, "officer.a", "?assignedOfficerId=officer.a").andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void aClaimDisappearsFromOtherOfficersListsOnceItIsAssigned() throws Exception {
        assertThat(ids(read(list(Role.CLAIMS_OFFICER, "officer.b", "")))).contains(queued);
        assign(queued, "officer.a");
        assertThat(ids(read(list(Role.CLAIMS_OFFICER, "officer.b", "")))).doesNotContain(queued);
        assertThat(ids(read(list(Role.CLAIMS_OFFICER, "officer.a", "")))).contains(queued);
    }

    // ---- managers and administrators ----

    @Test
    void managersAndAdministratorsListEveryClaim() throws Exception {
        for (Role role : new Role[] {Role.MANAGER, Role.ADMIN}) {
            assertThat(ids(read(list(role, "someone", "")))).containsExactlyInAnyOrder(aliceOld, bobClaim, aliceNew, queued);
        }
        list(Role.MANAGER, "meera", "?assignedOfficerId=officer.b").andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].claimId").value(bobClaim));
        list(Role.MANAGER, "meera", "?status=SUBMITTED&market=SG&claimType=PROPERTY").andExpect(jsonPath("$.totalElements").value(1));
    }

    // ---- the contract ----

    @Test
    void invalidParametersAre400AndUnknownFiltersAreIgnored() throws Exception {
        for (String bad : new String[] {"?status=NOPE", "?claimType=BOAT", "?page=-1", "?size=0", "?size=101", "?page=abc", "?assigned=maybe"}) {
            list(Role.MANAGER, "meera", bad).andExpect(status().isBadRequest());
        }
        list(Role.MANAGER, "meera", "?size=100").andExpect(status().isOk());
        list(Role.MANAGER, "meera", "?assigned=false&assignedOfficerId=officer.a").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CONFLICTING_FILTERS"));
    }

    @Test
    void theListNeedsAToken() throws Exception {
        mvc.perform(get(LIST).header(HttpHeaders.AUTHORIZATION, "")).andExpect(status().isUnauthorized());
        mvc.perform(get(LIST).header(HttpHeaders.AUTHORIZATION, as(Role.SERVICE))).andExpect(status().isForbidden());
    }
}
