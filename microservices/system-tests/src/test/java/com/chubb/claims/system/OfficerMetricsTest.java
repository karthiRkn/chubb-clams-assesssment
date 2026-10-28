package com.chubb.claims.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * The officer report over real HTTP. The Claim Service asks the real User Service for the active claims officers, so every
 * officer account in {@link SystemUnderTest} (the {@code off-*} accounts and the demo {@code officer1}) is in the report
 * whether or not they have a claim, and the tests look an officer up by name instead of by position.
 */
class OfficerMetricsTest extends SystemTestBase {

    private static final String PATH = "/api/v1/management/officers";

    /** Every account the User Service holds with the CLAIMS_OFFICER role in these tests (all active unless a test switches one off). */
    private static final List<String> ACTIVE_OFFICERS;

    static {
        ACTIVE_OFFICERS = new ArrayList<>(SystemUnderTest.OFFICERS);
        ACTIVE_OFFICERS.add("officer1"); // the demo user
    }

    /** A JsonPath that selects one officer's entry, whatever its position in the list. */
    private static String officer(String id) {
        return "$.officers[?(@.officerId=='" + id + "')]";
    }

    private void decide(String claimId, String decision, String settled, String currency, String officer) throws Exception {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("decision", decision);
        b.put("decisionReason", "Reason");
        if (settled != null) {
            b.put("approvedAmount", new BigDecimal(settled));
        }
        b.put("currency", currency);
        b.put("decidedByOfficerId", officer);
        postJson("/api/v1/officer/claims/" + claimId + "/decision", b).andExpect(status().isOk());
    }

    private List<String> officerIds() throws Exception {
        List<String> ids = new ArrayList<>();
        read(getJson(PATH).andExpect(status().isOk())).get("officers").forEach(o -> ids.add(o.get("officerId").asText()));
        return ids;
    }

    /** Every officer in the report except these has nothing open and has decided nothing. */
    private void assertNobodyElseHasWork(Set<String> exceptions) throws Exception {
        for (JsonNode o : read(getJson(PATH).andExpect(status().isOk())).get("officers")) {
            if (!exceptions.contains(o.get("officerId").asText())) {
                assertThat(o.get("openClaimCount").asLong()).as(o.get("officerId").asText()).isZero();
                assertThat(o.get("decidedCount").asLong()).as(o.get("officerId").asText()).isZero();
            }
        }
    }

    // ---- who is in the report: the users table, not the claims table ----

    @Test
    void everyActiveClaimsOfficerIsListedWithZerosWhenThereAreNoClaimsAndNobodyElseIs() throws Exception {
        getJson(PATH).andExpect(status().isOk())
                .andExpect(jsonPath("$.generatedAt").exists())
                .andExpect(jsonPath("$.note").value(org.hamcrest.Matchers.containsString("CURRENT assignee")));
        List<String> ids = officerIds();
        assertThat(ids).containsAll(ACTIVE_OFFICERS).doesNotHaveDuplicates();
        // managers, administrators and claimants are users too, but not officers
        assertThat(ids).doesNotContain("manager1", "admin1", "claimant1", SystemUnderTest.ADMIN_USERNAME);
        assertThat(ids).as("sorted by officer id").isSorted();

        assertNobodyElseHasWork(Set.of());
        // the shape the report promises for an officer with no work at all
        getJson(PATH).andExpect(jsonPath(officer("off-b") + ".openClaimCount").value(0))
                .andExpect(jsonPath(officer("off-b") + ".openClaimsByStatus.SUBMITTED").value(0))
                .andExpect(jsonPath(officer("off-b") + ".openClaimsByStatus.UNDER_REVIEW").value(0))
                .andExpect(jsonPath(officer("off-b") + ".openClaimsByStatus.MORE_INFO_REQUIRED").value(0))
                .andExpect(jsonPath(officer("off-b") + ".openClaimsByStatus.APPROVED").value(0))
                .andExpect(jsonPath(officer("off-b") + ".openClaimedAmountByCurrency[*]", hasSize(0)))
                .andExpect(jsonPath(officer("off-b") + ".decidedCount").value(0))
                .andExpect(jsonPath(officer("off-b") + ".approvedCount").value(0))
                .andExpect(jsonPath(officer("off-b") + ".settledCount").value(0))
                .andExpect(jsonPath(officer("off-b") + ".rejectedCount").value(0))
                .andExpect(jsonPath(officer("off-b") + ".approvedAmountByCurrency[*]", hasSize(0)));
    }

    @Test
    void unassignedClaimsAreNotAttributedToAnyOfficer() throws Exception {
        createClaim();
        assertNobodyElseHasWork(Set.of());
    }

    @Test
    void anOfficerWithNoClaimsIsListedBesideAnOfficerWhoHasOne() throws Exception {
        review(createClaim("SG", "MOTOR", "1000.00", "INR"), "off-a");
        getJson(PATH).andExpect(jsonPath(officer("off-a") + ".openClaimCount").value(1))
                .andExpect(jsonPath(officer("off-a") + ".openClaimsByStatus.UNDER_REVIEW").value(1))
                .andExpect(jsonPath(officer("off-b") + ".openClaimCount").value(0))
                .andExpect(jsonPath(officer("off-b") + ".decidedCount").value(0));
        assertThat(officerIds()).contains("off-a", "off-b");
        assertNobodyElseHasWork(Set.of("off-a"));
    }

    @Test
    void anOfficerSwitchedOffByAnAdministratorLeavesTheReportUntilReactivatedUnlessTheyHoldClaims() throws Exception {
        String officer = "off-9"; // an account no other test class needs while this one runs; restored below
        try {
            switchOff(officer);
            assertThat(officerIds()).as("switched off, no claims").doesNotContain(officer).contains("off-a", "off-b");
        } finally {
            switchOn(officer);
        }
        assertThat(officerIds()).as("switched back on").contains(officer);

        // switched off while they hold a claim: their workload must stay visible to the manager
        String claim = createClaim("SG", "MOTOR", "300.00", "INR");
        assign(claim, officer);
        try {
            switchOff(officer);
            getJson(PATH).andExpect(jsonPath(officer(officer) + ".openClaimCount").value(1));
        } finally {
            switchOn(officer);
        }
    }

    private void switchOff(String username) throws Exception {
        setActive(username, false);
    }

    private void switchOn(String username) throws Exception {
        setActive(username, true);
    }

    private void setActive(String username, boolean active) throws Exception {
        mvc.perform(put("/api/v1/users/" + username + "/active").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("active", active)))).andExpect(status().isOk());
    }

    // ---- the numbers ----

    @Test
    void workloadAndPerformanceArePerOfficerAndPerCurrency() throws Exception {
        // off-a: one SUBMITTED (assigned only), one UNDER_REVIEW, both INR; one AUD claim approved but not settled
        String aSubmitted = createClaim("SG", "MOTOR", "1000.00", "INR");
        assign(aSubmitted, "off-a");
        String aReview = createClaim("SG", "MOTOR", "500.00", "INR");
        review(aReview, "off-a");
        String aSettled = createClaim("AU", "PROPERTY", "700.00", "AUD");
        review(aSettled, "off-a");
        decide(aSettled, "APPROVED", "600.00", "AUD", "off-a"); // approved but not yet settled: still open work

        // off-b: one MORE_INFO_REQUIRED (USD), one approved and settled INR, one rejected INR
        String bAwaiting = createClaim("US", "MOTOR", "150.00", "USD");
        review(bAwaiting, "off-b");
        postJson("/api/v1/officer/claims/" + bAwaiting + "/information-requests",
                Map.of("question", "Q", "requestedByOfficerId", "off-b")).andExpect(status().isCreated());
        String bSettled = createClaim("SG", "MOTOR", "300.00", "INR");
        review(bSettled, "off-b");
        decide(bSettled, "APPROVED", "250.00", "INR", "off-b");
        postJson("/api/v1/officer/claims/" + bSettled + "/settlement", Map.of("settledByOfficerId", "off-b")).andExpect(status().isOk());
        String bRejected = createClaim("SG", "MOTOR", "200.00", "INR");
        review(bRejected, "off-b");
        decide(bRejected, "REJECTED", null, "INR", "off-b");

        // unassigned claim must not appear anywhere
        createClaim("SG", "MOTOR", "9999.00", "INR");

        String a = officer("off-a");
        String b = officer("off-b");
        getJson(PATH).andExpect(status().isOk())
                .andExpect(jsonPath(a + ".openClaimCount").value(3))
                .andExpect(jsonPath(a + ".openClaimsByStatus.APPROVED").value(1))
                .andExpect(jsonPath(a + ".openClaimsByStatus.SUBMITTED").value(1))
                .andExpect(jsonPath(a + ".openClaimsByStatus.UNDER_REVIEW").value(1))
                .andExpect(jsonPath(a + ".openClaimsByStatus.MORE_INFO_REQUIRED").value(0))
                .andExpect(jsonPath(a + ".openClaimedAmountByCurrency[*]", hasSize(2)))
                .andExpect(jsonPath(a + ".openClaimedAmountByCurrency[0].currency").value("AUD"))
                .andExpect(jsonPath(a + ".openClaimedAmountByCurrency[0].amount").value(700.00))
                .andExpect(jsonPath(a + ".openClaimedAmountByCurrency[1].currency").value("INR"))
                .andExpect(jsonPath(a + ".openClaimedAmountByCurrency[1].claimCount").value(2))
                .andExpect(jsonPath(a + ".openClaimedAmountByCurrency[1].amount").value(1500.00))
                .andExpect(jsonPath(a + ".decidedCount").value(1))
                .andExpect(jsonPath(a + ".approvedCount").value(1))
                .andExpect(jsonPath(a + ".settledCount").value(0))
                .andExpect(jsonPath(a + ".rejectedCount").value(0))
                .andExpect(jsonPath(a + ".approvedAmountByCurrency[*]", hasSize(1)))
                .andExpect(jsonPath(a + ".approvedAmountByCurrency[0].currency").value("AUD"))
                .andExpect(jsonPath(a + ".approvedAmountByCurrency[0].amount").value(600.00))
                .andExpect(jsonPath(b + ".openClaimCount").value(1))
                .andExpect(jsonPath(b + ".openClaimsByStatus.MORE_INFO_REQUIRED").value(1))
                .andExpect(jsonPath(b + ".openClaimsByStatus.UNDER_REVIEW").value(0))
                .andExpect(jsonPath(b + ".openClaimedAmountByCurrency[0].currency").value("USD"))
                .andExpect(jsonPath(b + ".openClaimedAmountByCurrency[0].amount").value(150.00))
                .andExpect(jsonPath(b + ".decidedCount").value(2))
                .andExpect(jsonPath(b + ".approvedCount").value(1))
                .andExpect(jsonPath(b + ".settledCount").value(1))
                .andExpect(jsonPath(b + ".rejectedCount").value(1))
                .andExpect(jsonPath(b + ".approvedAmountByCurrency[*]", hasSize(1)))
                .andExpect(jsonPath(b + ".approvedAmountByCurrency[0].currency").value("INR"))
                .andExpect(jsonPath(b + ".approvedAmountByCurrency[0].amount").value(250.00));
        // nobody but these two has any work: the unassigned claim is attributed to no one
        assertNobodyElseHasWork(Set.of("off-a", "off-b"));
    }

    @Test
    void officerWithOnlyDecisionsStillAppearsWithZeroOpenWork() throws Exception {
        String id = createClaim();
        review(id, "off-c");
        decide(id, "REJECTED", null, "INR", "off-c");
        getJson(PATH).andExpect(jsonPath(officer("off-c") + ".officerId").value("off-c"))
                .andExpect(jsonPath(officer("off-c") + ".openClaimCount").value(0))
                .andExpect(jsonPath(officer("off-c") + ".openClaimedAmountByCurrency[*]", hasSize(0)))
                .andExpect(jsonPath(officer("off-c") + ".decidedCount").value(1))
                .andExpect(jsonPath(officer("off-c") + ".rejectedCount").value(1))
                .andExpect(jsonPath(officer("off-c") + ".approvedAmountByCurrency[*]", hasSize(0)));
        assertNobodyElseHasWork(Set.of("off-c"));
    }

    @Test
    void currenciesAreNeverMixedWithinOneOfficer() throws Exception {
        assign(createClaim("SG", "MOTOR", "100.00", "INR"), "off-a");
        assign(createClaim("US", "MOTOR", "100.00", "USD"), "off-a");
        String a = officer("off-a");
        getJson(PATH).andExpect(jsonPath(a + ".openClaimCount").value(2))
                .andExpect(jsonPath(a + ".openClaimedAmountByCurrency[*]", hasSize(2)))
                .andExpect(jsonPath(a + ".openClaimedAmountByCurrency[0].currency").value("INR"))
                .andExpect(jsonPath(a + ".openClaimedAmountByCurrency[0].amount").value(100.00))
                .andExpect(jsonPath(a + ".openClaimedAmountByCurrency[1].currency").value("USD"))
                .andExpect(jsonPath(a + ".openClaimedAmountByCurrency[1].amount").value(100.00));
    }

    @Test
    void onlyGetIsAllowed() throws Exception {
        mvc.perform(post(PATH)).andExpect(status().isMethodNotAllowed());
    }
}
