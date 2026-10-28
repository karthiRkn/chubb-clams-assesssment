package com.chubb.claims.claim;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chubb.claims.common.ApiException;
import com.chubb.claims.common.security.Role;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

/**
 * The management reports (outstanding liability, per-officer workload), now part of the Claim Service. They read the claims
 * table with GROUP BY queries, so these tests build real claims and check the numbers the database produces. The officer
 * report also lists the active claims officers the User Service knows, so one with no claims shows up with zeros; the User
 * Service is the mocked {@code users} client, which lists nobody unless a test says otherwise.
 */
class ManagementReportTest extends IntegrationTestBase {

    private static final String SUMMARY = "/api/v1/management/summary";
    private static final String OFFICERS = "/api/v1/management/officers";

    private void approve(String claimId, String amount, String currency, String officer) throws Exception {
        decide(claimId, decisionBody("APPROVED", amount, currency, officer)).andExpect(status().isOk());
    }

    private void reject(String claimId, String currency, String officer) throws Exception {
        decide(claimId, decisionBody("REJECTED", null, currency, officer)).andExpect(status().isOk());
    }

    // ---- summary ----

    @Test
    void emptyDatabaseGivesZeroCountsForEveryStatusAndNoLiability() throws Exception {
        getJson(SUMMARY)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generatedAt").exists())
                .andExpect(jsonPath("$.outstandingClaimCount").value(0))
                .andExpect(jsonPath("$.outstandingLiabilityByCurrency.length()").value(0))
                .andExpect(jsonPath("$.countsByStatus.SUBMITTED").value(0))
                .andExpect(jsonPath("$.countsByStatus.UNDER_REVIEW").value(0))
                .andExpect(jsonPath("$.countsByStatus.MORE_INFO_REQUIRED").value(0))
                .andExpect(jsonPath("$.countsByStatus.APPROVED").value(0))
                .andExpect(jsonPath("$.countsByStatus.REJECTED").value(0))
                .andExpect(jsonPath("$.countsByStatus.SETTLED").value(0))
                .andExpect(jsonPath("$.liabilityNote").value(org.hamcrest.Matchers.containsString("Not an actuarial reserve")));
    }

    @Test
    void outstandingExcludesSettledAndRejectedClaimsAndLiabilityStaysPerCurrency() throws Exception {
        createClaim("SG", "MOTOR", "1000.00", "INR");                         // SUBMITTED, counts at its claimed amount
        String inrReview = createClaim("SG", "MOTOR", "800.50", "INR");       // UNDER_REVIEW
        review(inrReview, "OFF-1");
        createClaim("AU", "PROPERTY", "5200.00", "AUD");                      // SUBMITTED
        String info = createClaim("SG", "MOTOR", "10.00", "INR");             // MORE_INFO_REQUIRED
        review(info, "OFF-1");
        requestInformation(info, "OFF-1").andExpect(status().isOk());

        String approved = createClaim("SG", "MOTOR", "3000.00", "INR");       // APPROVED, counts at the APPROVED amount
        review(approved, "OFF-1");
        approve(approved, "2500.00", "INR", "OFF-1");

        String settled = createClaim("SG", "MOTOR", "9999.00", "INR");        // SETTLED, left out
        review(settled, "OFF-1");
        approve(settled, "9000.00", "INR", "OFF-1");
        settle(settled, "OFF-1").andExpect(status().isOk());

        String rejected = createClaim("HK", "MOTOR", "7777.00", "HKD");       // REJECTED, left out
        review(rejected, "OFF-1");
        reject(rejected, "HKD", "OFF-1");

        getJson(SUMMARY)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outstandingClaimCount").value(5))
                .andExpect(jsonPath("$.outstandingLiabilityByCurrency.length()").value(2))
                // ordered by currency: AUD then INR; HKD is absent because its only claim is rejected
                .andExpect(jsonPath("$.outstandingLiabilityByCurrency[0].currency").value("AUD"))
                .andExpect(jsonPath("$.outstandingLiabilityByCurrency[0].claimCount").value(1))
                .andExpect(jsonPath("$.outstandingLiabilityByCurrency[0].estimatedLiability").value(5200.00))
                .andExpect(jsonPath("$.outstandingLiabilityByCurrency[1].currency").value("INR"))
                .andExpect(jsonPath("$.outstandingLiabilityByCurrency[1].claimCount").value(4))
                // 1000.00 + 800.50 + 10.00 claimed, plus 2500.00 approved (not the 3000.00 that was claimed)
                .andExpect(jsonPath("$.outstandingLiabilityByCurrency[1].estimatedLiability").value(4310.50))
                .andExpect(jsonPath("$.countsByStatus.SUBMITTED").value(2))
                .andExpect(jsonPath("$.countsByStatus.UNDER_REVIEW").value(1))
                .andExpect(jsonPath("$.countsByStatus.MORE_INFO_REQUIRED").value(1))
                .andExpect(jsonPath("$.countsByStatus.APPROVED").value(1))
                .andExpect(jsonPath("$.countsByStatus.SETTLED").value(1))
                .andExpect(jsonPath("$.countsByStatus.REJECTED").value(1))
                .andExpect(jsonPath("$.liabilityNote").value(org.hamcrest.Matchers.containsString("no exchange-rate conversion")));
    }

    @Test
    void anApprovedClaimLeavesTheLiabilityWhenItIsSettled() throws Exception {
        String id = createClaim("SG", "MOTOR", "1000.00", "INR");
        review(id, "OFF-1");
        approve(id, "800.00", "INR", "OFF-1");
        getJson(SUMMARY).andExpect(jsonPath("$.outstandingLiabilityByCurrency[0].estimatedLiability").value(800.00))
                .andExpect(jsonPath("$.outstandingClaimCount").value(1));
        settle(id, "OFF-1").andExpect(status().isOk());
        getJson(SUMMARY).andExpect(jsonPath("$.outstandingLiabilityByCurrency.length()").value(0))
                .andExpect(jsonPath("$.outstandingClaimCount").value(0));
    }

    // ---- officers ----

    @Test
    void emptyDatabaseGivesNoOfficers() throws Exception {
        getJson(OFFICERS).andExpect(status().isOk())
                .andExpect(jsonPath("$.generatedAt").exists())
                .andExpect(jsonPath("$.note").value(org.hamcrest.Matchers.containsString("CURRENT assignee")))
                .andExpect(jsonPath("$.officers.length()").value(0));
    }

    @Test
    void unassignedClaimsAreNotAttributedToAnyOfficer() throws Exception {
        createClaim();
        getJson(OFFICERS).andExpect(jsonPath("$.officers.length()").value(0));
    }

    @Test
    void workloadAndDecisionsArePerOfficerAndPerCurrency() throws Exception {
        // OFF-A: one SUBMITTED (assigned only), one UNDER_REVIEW (both INR), and one AUD claim approved, not yet settled
        assign(createClaim("SG", "MOTOR", "1000.00", "INR"), "OFF-A");
        String aReview = createClaim("SG", "MOTOR", "500.00", "INR");
        review(aReview, "OFF-A");
        String aApproved = createClaim("AU", "PROPERTY", "700.00", "AUD");
        review(aApproved, "OFF-A");
        approve(aApproved, "600.00", "AUD", "OFF-A");

        // OFF-B: one MORE_INFO_REQUIRED (USD), one approved and settled INR, one rejected INR
        String bInfo = createClaim("US", "MOTOR", "150.00", "USD");
        review(bInfo, "OFF-B");
        requestInformation(bInfo, "OFF-B").andExpect(status().isOk());
        String bSettled = createClaim("SG", "MOTOR", "300.00", "INR");
        review(bSettled, "OFF-B");
        approve(bSettled, "250.00", "INR", "OFF-B");
        settle(bSettled, "OFF-B").andExpect(status().isOk());
        String bRejected = createClaim("SG", "MOTOR", "200.00", "INR");
        review(bRejected, "OFF-B");
        reject(bRejected, "INR", "OFF-B");

        createClaim("SG", "MOTOR", "9999.00", "INR"); // unassigned: must not appear anywhere

        getJson(OFFICERS).andExpect(status().isOk())
                .andExpect(jsonPath("$.officers.length()").value(2))
                // OFF-A (sorted first): an approved claim is still open work until it is settled
                .andExpect(jsonPath("$.officers[0].officerId").value("OFF-A"))
                .andExpect(jsonPath("$.officers[0].openClaimCount").value(3))
                .andExpect(jsonPath("$.officers[0].openClaimsByStatus.SUBMITTED").value(1))
                .andExpect(jsonPath("$.officers[0].openClaimsByStatus.UNDER_REVIEW").value(1))
                .andExpect(jsonPath("$.officers[0].openClaimsByStatus.MORE_INFO_REQUIRED").value(0))
                .andExpect(jsonPath("$.officers[0].openClaimsByStatus.APPROVED").value(1))
                .andExpect(jsonPath("$.officers[0].openClaimedAmountByCurrency.length()").value(2))
                .andExpect(jsonPath("$.officers[0].openClaimedAmountByCurrency[0].currency").value("AUD"))
                .andExpect(jsonPath("$.officers[0].openClaimedAmountByCurrency[0].amount").value(700.00))
                .andExpect(jsonPath("$.officers[0].openClaimedAmountByCurrency[1].currency").value("INR"))
                .andExpect(jsonPath("$.officers[0].openClaimedAmountByCurrency[1].claimCount").value(2))
                .andExpect(jsonPath("$.officers[0].openClaimedAmountByCurrency[1].amount").value(1500.00))
                .andExpect(jsonPath("$.officers[0].decidedCount").value(1))
                .andExpect(jsonPath("$.officers[0].approvedCount").value(1))
                .andExpect(jsonPath("$.officers[0].settledCount").value(0))
                .andExpect(jsonPath("$.officers[0].rejectedCount").value(0))
                .andExpect(jsonPath("$.officers[0].approvedAmountByCurrency.length()").value(1))
                .andExpect(jsonPath("$.officers[0].approvedAmountByCurrency[0].currency").value("AUD"))
                .andExpect(jsonPath("$.officers[0].approvedAmountByCurrency[0].amount").value(600.00))
                // OFF-B
                .andExpect(jsonPath("$.officers[1].officerId").value("OFF-B"))
                .andExpect(jsonPath("$.officers[1].openClaimCount").value(1))
                .andExpect(jsonPath("$.officers[1].openClaimsByStatus.MORE_INFO_REQUIRED").value(1))
                .andExpect(jsonPath("$.officers[1].openClaimsByStatus.UNDER_REVIEW").value(0))
                .andExpect(jsonPath("$.officers[1].openClaimedAmountByCurrency[0].currency").value("USD"))
                .andExpect(jsonPath("$.officers[1].openClaimedAmountByCurrency[0].amount").value(150.00))
                .andExpect(jsonPath("$.officers[1].decidedCount").value(2))
                .andExpect(jsonPath("$.officers[1].approvedCount").value(1))
                .andExpect(jsonPath("$.officers[1].settledCount").value(1))
                .andExpect(jsonPath("$.officers[1].rejectedCount").value(1))
                .andExpect(jsonPath("$.officers[1].approvedAmountByCurrency.length()").value(1))
                .andExpect(jsonPath("$.officers[1].approvedAmountByCurrency[0].currency").value("INR"))
                .andExpect(jsonPath("$.officers[1].approvedAmountByCurrency[0].amount").value(250.00));
    }

    // ---- officers with no claims: the list of officers comes from the User Service, not from the claims table ----

    @Test
    void anActiveOfficerWithNoClaimsIsReturnedWithZeroWorkload() throws Exception {
        // users table: officer1 and officer2 are both active claims officers; only officer1 has a claim
        when(users.activeClaimsOfficers()).thenReturn(List.of("officer1", "officer2"));
        review(createClaim("SG", "MOTOR", "1000.00", "INR"), "officer1");

        getJson(OFFICERS).andExpect(status().isOk())
                .andExpect(jsonPath("$.officers.length()").value(2))
                .andExpect(jsonPath("$.officers[0].officerId").value("officer1"))
                .andExpect(jsonPath("$.officers[0].openClaimCount").value(1))
                .andExpect(jsonPath("$.officers[0].openClaimsByStatus.UNDER_REVIEW").value(1))
                .andExpect(jsonPath("$.officers[0].openClaimedAmountByCurrency[0].amount").value(1000.00))
                // officer2 has nothing assigned and has decided nothing, and is still reported, in full
                .andExpect(jsonPath("$.officers[1].officerId").value("officer2"))
                .andExpect(jsonPath("$.officers[1].openClaimCount").value(0))
                .andExpect(jsonPath("$.officers[1].openClaimsByStatus.length()").value(4))
                .andExpect(jsonPath("$.officers[1].openClaimsByStatus.SUBMITTED").value(0))
                .andExpect(jsonPath("$.officers[1].openClaimsByStatus.UNDER_REVIEW").value(0))
                .andExpect(jsonPath("$.officers[1].openClaimsByStatus.MORE_INFO_REQUIRED").value(0))
                .andExpect(jsonPath("$.officers[1].openClaimsByStatus.APPROVED").value(0))
                .andExpect(jsonPath("$.officers[1].openClaimedAmountByCurrency").isArray())
                .andExpect(jsonPath("$.officers[1].openClaimedAmountByCurrency.length()").value(0))
                .andExpect(jsonPath("$.officers[1].decidedCount").value(0))
                .andExpect(jsonPath("$.officers[1].approvedCount").value(0))
                .andExpect(jsonPath("$.officers[1].settledCount").value(0))
                .andExpect(jsonPath("$.officers[1].rejectedCount").value(0))
                .andExpect(jsonPath("$.officers[1].approvedAmountByCurrency").isArray())
                .andExpect(jsonPath("$.officers[1].approvedAmountByCurrency.length()").value(0));
    }

    @Test
    void activeOfficersAreListedEvenWhenThereIsNoClaimAtAll() throws Exception {
        when(users.activeClaimsOfficers()).thenReturn(List.of("officer1", "officer2"));
        getJson(OFFICERS).andExpect(status().isOk())
                .andExpect(jsonPath("$.officers.length()").value(2))
                .andExpect(jsonPath("$.officers[0].officerId").value("officer1"))
                .andExpect(jsonPath("$.officers[0].openClaimCount").value(0))
                .andExpect(jsonPath("$.officers[1].officerId").value("officer2"))
                .andExpect(jsonPath("$.officers[1].openClaimCount").value(0));
    }

    @Test
    void anOfficerWhoIsActiveAndHasClaimsIsListedOnce() throws Exception {
        when(users.activeClaimsOfficers()).thenReturn(List.of("officer1"));
        assign(createClaim(), "officer1");
        String id = createClaim();
        review(id, "officer1");
        reject(id, "INR", "officer1"); // officer1 appears as an assignee and as a decider
        getJson(OFFICERS).andExpect(jsonPath("$.officers.length()").value(1))
                .andExpect(jsonPath("$.officers[0].officerId").value("officer1"))
                .andExpect(jsonPath("$.officers[0].openClaimCount").value(1))
                .andExpect(jsonPath("$.officers[0].rejectedCount").value(1));
    }

    @Test
    void anOfficerSwitchedOffSinceBeingAssignedWorkIsStillShownWhileTheyHaveClaims() throws Exception {
        // officer2 is no longer in the active list, but still holds an open claim: that workload must not disappear
        when(users.activeClaimsOfficers()).thenReturn(List.of("officer1"));
        assign(createClaim("SG", "MOTOR", "300.00", "INR"), "officer2");
        getJson(OFFICERS).andExpect(jsonPath("$.officers.length()").value(2))
                .andExpect(jsonPath("$.officers[0].officerId").value("officer1"))
                .andExpect(jsonPath("$.officers[0].openClaimCount").value(0))
                .andExpect(jsonPath("$.officers[1].officerId").value("officer2"))
                .andExpect(jsonPath("$.officers[1].openClaimCount").value(1));
    }

    @Test
    void theOfficerReportIsRefusedNotShortenedWhenTheUserServiceCannotBeReached() throws Exception {
        when(users.activeClaimsOfficers())
                .thenThrow(ApiException.unavailable("DOWNSTREAM_UNAVAILABLE", "User Service is unavailable or did not answer in time"));
        assign(createClaim(), "officer1");
        getJson(OFFICERS).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DOWNSTREAM_UNAVAILABLE"))
                .andExpect(jsonPath("$.officers").doesNotExist());
        // the summary reads only claims, so it does not depend on the User Service
        getJson(SUMMARY).andExpect(status().isOk());

        // doThrow, not when(): the stub above already throws, so calling it inside when(...) would throw here
        doThrow(ApiException.badGateway("DOWNSTREAM_ERROR", "User Service answered with HTTP 403")).when(users).activeClaimsOfficers();
        getJson(OFFICERS).andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("DOWNSTREAM_ERROR"));
    }

    @Test
    void settlingAnApprovedClaimMovesItFromOpenWorkToSettled() throws Exception {
        String id = createClaim("SG", "MOTOR", "400.00", "INR");
        review(id, "OFF-A");
        approve(id, "400.00", "INR", "OFF-A");
        getJson(OFFICERS).andExpect(jsonPath("$.officers[0].openClaimCount").value(1))
                .andExpect(jsonPath("$.officers[0].settledCount").value(0));
        settle(id, "OFF-A").andExpect(status().isOk());
        getJson(OFFICERS).andExpect(jsonPath("$.officers[0].openClaimCount").value(0))
                .andExpect(jsonPath("$.officers[0].approvedCount").value(1))
                .andExpect(jsonPath("$.officers[0].settledCount").value(1))
                .andExpect(jsonPath("$.officers[0].approvedAmountByCurrency[0].amount").value(400.00));
    }

    @Test
    void officerWithOnlyDecisionsStillAppearsWithZeroOpenWork() throws Exception {
        String id = createClaim();
        review(id, "OFF-C");
        reject(id, "INR", "OFF-C");
        getJson(OFFICERS).andExpect(jsonPath("$.officers.length()").value(1))
                .andExpect(jsonPath("$.officers[0].officerId").value("OFF-C"))
                .andExpect(jsonPath("$.officers[0].openClaimCount").value(0))
                .andExpect(jsonPath("$.officers[0].openClaimedAmountByCurrency.length()").value(0))
                .andExpect(jsonPath("$.officers[0].decidedCount").value(1))
                .andExpect(jsonPath("$.officers[0].rejectedCount").value(1))
                .andExpect(jsonPath("$.officers[0].approvedAmountByCurrency.length()").value(0));
    }

    @Test
    void currenciesAreNeverMixedWithinOneOfficer() throws Exception {
        assign(createClaim("SG", "MOTOR", "100.00", "INR"), "OFF-A");
        assign(createClaim("US", "MOTOR", "100.00", "USD"), "OFF-A");
        getJson(OFFICERS).andExpect(jsonPath("$.officers[0].openClaimCount").value(2))
                .andExpect(jsonPath("$.officers[0].openClaimedAmountByCurrency.length()").value(2))
                .andExpect(jsonPath("$.officers[0].openClaimedAmountByCurrency[0].currency").value("INR"))
                .andExpect(jsonPath("$.officers[0].openClaimedAmountByCurrency[0].amount").value(100.00))
                .andExpect(jsonPath("$.officers[0].openClaimedAmountByCurrency[1].currency").value("USD"))
                .andExpect(jsonPath("$.officers[0].openClaimedAmountByCurrency[1].amount").value(100.00));
    }

    // ---- who may read them, and the HTTP contract ----

    @Test
    void onlyManagersAndAdministratorsMayReadTheReports() throws Exception {
        for (String path : new String[] {SUMMARY, OFFICERS}) {
            mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "")).andExpect(status().isUnauthorized());
            mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, as(Role.MANAGER))).andExpect(status().isOk());
            mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, as(Role.ADMIN))).andExpect(status().isOk());
            for (Role role : new Role[] {Role.CLAIMANT, Role.CLAIMS_OFFICER, Role.SERVICE}) {
                mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, as(role))).andExpect(status().isForbidden());
            }
        }
    }

    @Test
    void onlyGetIsAllowedAndUnknownRoutesAre404() throws Exception {
        mvc.perform(post(SUMMARY)).andExpect(status().isMethodNotAllowed()).andExpect(jsonPath("$.status").value(405));
        mvc.perform(post(OFFICERS)).andExpect(status().isMethodNotAllowed());
        mvc.perform(put(SUMMARY)).andExpect(status().isMethodNotAllowed());
        mvc.perform(delete(OFFICERS)).andExpect(status().isMethodNotAllowed());
        mvc.perform(get("/api/v1/management/nothing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.path").value("/api/v1/management/nothing"));
    }
}
