package com.chubb.claims.claim;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Competing requests on the same claim. The claim row lock (in this service's database) serialises them: one wins, the
 * other sees the new state and gets a 409, and the stored result is the winner's. Run on H2 only; this is not proof for
 * PostgreSQL.
 */
class ConcurrentTransitionTest extends IntegrationTestBase {

    private List<Integer> race(Callable<Integer> a, Callable<Integer> b) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (Callable<Integer> task : List.of(a, b)) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return task.call();
                }));
            }
            go.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : futures) {
                statuses.add(f.get(30, TimeUnit.SECONDS));
            }
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }

    private int status(String path, Object body) throws Exception {
        return postJson(path, body).andReturn().getResponse().getStatus();
    }

    @Test
    void twoCompetingDecisionsOnlyOneWins() throws Exception {
        String id = createClaim();
        review(id, "OFF-A"); // the claim is assigned to OFF-A, who is the only officer who may decide it
        String path = INTERNAL + id + "/decision";
        Map<String, Object> approve = decisionBody("APPROVED", "1000.00", "INR", "OFF-A");
        Map<String, Object> reject = decisionBody("REJECTED", null, "INR", "OFF-A");

        List<Integer> statuses = race(() -> status(path, approve), () -> status(path, reject));

        assertEquals(1, statuses.stream().filter(s -> s == 200).count(), "statuses: " + statuses);
        assertEquals(1, statuses.stream().filter(s -> s == 409).count(), "statuses: " + statuses);

        Map<String, Object> row = jdbc.queryForMap(
                "select status, decided_by_officer_id, approved_amount from claims where claim_id = ?", id);
        boolean approvedWon = "APPROVED".equals(row.get("status"));
        assertEquals("OFF-A", row.get("decided_by_officer_id"));
        assertEquals(approvedWon, row.get("approved_amount") != null, "a rejection must not keep an approved amount");
        assertEquals(1, jdbc.queryForObject("""
                select count(*) from claim_status_history h join claims c on c.id = h.claim_pk
                where c.claim_id = ? and h.to_status in ('APPROVED', 'REJECTED')""", Integer.class, id));
    }

    @Test
    void decisionRacingAnInformationRequestLeavesOneConsistentOutcome() throws Exception {
        String id = createClaimUnderReview(); // assigned to OFF-1007, who both competing calls come from
        Map<String, Object> reject = decisionBody("REJECTED", null, "INR", "OFF-1007");

        List<Integer> statuses = race(() -> status(INTERNAL + id + "/decision", reject),
                () -> status(INTERNAL + id + "/request-information", Map.of("requestedByOfficerId", "OFF-1007")));

        // Both orders are legal: decision first leaves the request a 409; request first leaves the decision a 409.
        assertEquals(1, statuses.stream().filter(s -> s == 200).count(), "statuses: " + statuses);
        assertEquals(1, statuses.stream().filter(s -> s == 409).count(), "statuses: " + statuses);
        String finalStatus = jdbc.queryForObject("select status from claims where claim_id = ?", String.class, id);
        int toTerminalOrAwaiting = jdbc.queryForObject("""
                select count(*) from claim_status_history h join claims c on c.id = h.claim_pk
                where c.claim_id = ? and h.to_status = ?""", Integer.class, id, finalStatus);
        assertEquals(1, toTerminalOrAwaiting);
    }

    @Test
    void twoOfficersRacingToReviewTheSameUnassignedClaimOnlyOneWins() throws Exception {
        String id = createClaim();
        String path = INTERNAL + id + "/review";

        List<Integer> statuses = race(
                () -> status(path, Map.of("officerId", "OFF-A", "hasOpenInformationRequests", false)),
                () -> status(path, Map.of("officerId", "OFF-B", "hasOpenInformationRequests", false)));

        assertEquals(1, statuses.stream().filter(s -> s == 200).count(), "statuses: " + statuses);
        assertEquals(1, statuses.stream().filter(s -> s == 409).count(), "statuses: " + statuses);
        String assignee = jdbc.queryForObject("select assigned_officer_id from claims where claim_id = ?", String.class, id);
        assertEquals("UNDER_REVIEW", jdbc.queryForObject("select status from claims where claim_id = ?", String.class, id));
        // The loser must not have overwritten the assignee, and exactly one assignment and one review row exist.
        assertEquals(1, jdbc.queryForObject("""
                select count(*) from claim_assignment_history h join claims c on c.id = h.claim_pk
                where c.claim_id = ? and h.new_officer_id = ?""", Integer.class, id, assignee));
        assertEquals(1, jdbc.queryForObject("select count(*) from claim_assignment_history", Integer.class));
        assertEquals(1, jdbc.queryForObject("""
                select count(*) from claim_status_history h join claims c on c.id = h.claim_pk
                where c.claim_id = ? and h.to_status = 'UNDER_REVIEW'""", Integer.class, id));
    }

    @Test
    void twoResumesOnlyOneIsAccepted() throws Exception {
        String id = createClaimUnderReview();
        requestInformation(id, "OFF-1007").andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        String path = INTERNAL + id + "/resume-after-information";

        List<Integer> statuses = race(
                () -> mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path))
                        .andReturn().getResponse().getStatus(),
                () -> mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path))
                        .andReturn().getResponse().getStatus());

        assertEquals(1, statuses.stream().filter(s -> s == 200).count(), "statuses: " + statuses);
        assertEquals(1, statuses.stream().filter(s -> s == 409).count(), "statuses: " + statuses);
        assertEquals(1, jdbc.queryForObject("""
                select count(*) from claim_status_history h join claims c on c.id = h.claim_pk
                where c.claim_id = ? and h.from_status = 'MORE_INFO_REQUIRED'""", Integer.class, id));
    }

    @Test
    void twoSettlementsOnlyOneIsAccepted() throws Exception {
        String id = createClaimUnderReview();
        decide(id, decisionBody("APPROVED", "100.00", "INR", "OFF-1007")).andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        String path = INTERNAL + id + "/settlement";

        List<Integer> statuses = race(() -> status(path, Map.of("settledByOfficerId", "OFF-1007")),
                () -> status(path, Map.of("settledByOfficerId", "OFF-1007")));

        assertEquals(1, statuses.stream().filter(s -> s == 200).count(), "statuses: " + statuses);
        assertEquals(1, statuses.stream().filter(s -> s == 409).count(), "statuses: " + statuses);
        assertEquals(1, jdbc.queryForObject("""
                select count(*) from claim_status_history h join claims c on c.id = h.claim_pk
                where c.claim_id = ? and h.to_status = 'SETTLED'""", Integer.class, id));
    }

    @Test
    void afterAnApprovalAndARejectionRaceASettlementOnlyFollowsAnApproval() throws Exception {
        // an approval and a rejection cannot both win: whichever does, a settlement works only if it was the approval
        String id = createClaim();
        review(id, "OFF-A");
        Map<String, Object> approve = decisionBody("APPROVED", "100.00", "INR", "OFF-A");
        Map<String, Object> reject = decisionBody("REJECTED", null, "INR", "OFF-A");
        race(() -> status(INTERNAL + id + "/decision", approve), () -> status(INTERNAL + id + "/decision", reject));
        int settle = status(INTERNAL + id + "/settlement", Map.of("settledByOfficerId", "OFF-A"));
        String finalStatus = jdbc.queryForObject("select status from claims where claim_id = ?", String.class, id);
        assertEquals("SETTLED".equals(finalStatus) ? 200 : 409, settle, "a settlement is accepted exactly when the claim was approved");
        assertEquals(1, jdbc.queryForObject("""
                select count(*) from claim_status_history h join claims c on c.id = h.claim_pk
                where c.claim_id = ? and h.to_status in ('APPROVED', 'REJECTED')""", Integer.class, id));
    }

    @Test
    void staleActionsAfterTheClaimMovedOnAreRejectedAndWriteNothing() throws Exception {
        String id = createClaimUnderReview();
        decide(id, decisionBody("APPROVED", "100.00", "INR", "OFF-1007")).andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        int historyRows = jdbc.queryForObject("select count(*) from claim_status_history", Integer.class);

        assertEquals(409, status(INTERNAL + id + "/review", Map.of("officerId", "OFF-1007", "hasOpenInformationRequests", false)));
        assertEquals(409, status(INTERNAL + id + "/request-information", Map.of("requestedByOfficerId", "OFF-1007")));
        assertEquals(409, status(INTERNAL + id + "/decision", decisionBody("REJECTED", null, "INR", "OFF-1007")));

        Map<String, Object> row = jdbc.queryForMap(
                "select status, decided_by_officer_id, approved_amount from claims where claim_id = ?", id);
        assertEquals("APPROVED", row.get("status"));
        assertEquals("OFF-1007", row.get("decided_by_officer_id"));
        assertEquals(historyRows, jdbc.queryForObject("select count(*) from claim_status_history", Integer.class));
    }
}
