package com.chubb.claims.system;

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
 * Two competing requests on the same claim. The claim row lock serialises them: one wins, the other sees the new state
 * and gets a 409, and the stored decision is the winner's. Run on H2 only; this is not proof for PostgreSQL.
 */
class ConcurrentTransitionTest extends SystemTestBase {

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
        String id = createClaimUnderReview();
        String path = "/api/v1/officer/claims/" + id + "/decision";
        Map<String, Object> settle = Map.of("decision", "APPROVED", "decisionReason", "Approved.",
                "approvedAmount", "1000.00", "currency", "INR", "decidedByOfficerId", "off-1007");
        Map<String, Object> reject = Map.of("decision", "REJECTED", "decisionReason", "Declined.",
                "currency", "INR", "decidedByOfficerId", "off-1007");

        List<Integer> statuses = race(() -> status(path, settle), () -> status(path, reject));

        assertEquals(1, statuses.stream().filter(s -> s == 200).count(), "statuses: " + statuses);
        assertEquals(1, statuses.stream().filter(s -> s == 409).count(), "statuses: " + statuses);

        Map<String, Object> row = jdbc.queryForMap(
                "select status, decided_by_officer_id, approved_amount from claims where claim_id = ?", id);
        boolean approvedWon = "APPROVED".equals(row.get("status"));
        assertEquals("off-1007", row.get("decided_by_officer_id"));
        assertEquals(approvedWon, row.get("approved_amount") != null, "a rejection must not keep an approved amount");
        assertEquals(1, jdbc.queryForObject("""
                select count(*) from claim_status_history h join claims c on c.id = h.claim_pk
                where c.claim_id = ? and h.to_status in ('APPROVED', 'REJECTED')""", Integer.class, id));
    }

    @Test
    void decisionRacingInformationRequestLeavesOneConsistentOutcome() throws Exception {
        String id = createClaimUnderReview();
        Map<String, Object> reject = Map.of("decision", "REJECTED", "decisionReason", "Declined.",
                "currency", "INR", "decidedByOfficerId", "off-1007");
        Map<String, Object> ask = Map.of("question", "More photos?", "requestedByOfficerId", "off-1007");

        List<Integer> statuses = race(() -> status("/api/v1/officer/claims/" + id + "/decision", reject),
                () -> status("/api/v1/officer/claims/" + id + "/information-requests", ask));

        // The two orders are both legal: decision first leaves the request a 409; request first leaves the decision a 409.
        assertEquals(1, statuses.stream().filter(s -> s == 200 || s == 201).count(), "statuses: " + statuses);
        assertEquals(1, statuses.stream().filter(s -> s == 409).count(), "statuses: " + statuses);
        String finalStatus = jdbc.queryForObject("select status from claims where claim_id = ?", String.class, id);
        int requests = jdbc.queryForObject("select count(*) from information_requests", Integer.class);
        if ("REJECTED".equals(finalStatus)) {
            assertEquals(0, requests);
        } else {
            assertEquals("MORE_INFO_REQUIRED", finalStatus);
            assertEquals(1, requests);
        }
    }

    @Test
    void twoOfficersRacingToReviewTheSameUnassignedClaimOnlyOneWins() throws Exception {
        String id = createClaim();
        String path = "/api/v1/officer/claims/" + id + "/review";

        List<Integer> statuses = race(() -> status(path, Map.of("officerId", "off-a")),
                () -> status(path, Map.of("officerId", "off-b")));

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
    void twoAnswersToTheSameInformationRequestOnlyOneIsAccepted() throws Exception {
        String id = createClaimUnderReview();
        String irId = read(postJson("/api/v1/officer/claims/" + id + "/information-requests",
                Map.of("question", "Photos?", "requestedByOfficerId", "off-1007"))).get("informationRequestId").asText();
        String path = "/api/v1/claims/" + id + "/information";

        List<Integer> statuses = race(
                () -> status(path, Map.of("informationRequestId", irId, "answer", "First answer")),
                () -> status(path, Map.of("informationRequestId", irId, "answer", "Second answer")));

        assertEquals(1, statuses.stream().filter(s -> s == 200).count(), "statuses: " + statuses);
        assertEquals(1, statuses.stream().filter(s -> s == 409).count(), "statuses: " + statuses);
        assertEquals("UNDER_REVIEW", jdbc.queryForObject("select status from claims where claim_id = ?", String.class, id));
        assertEquals(1, jdbc.queryForObject("""
                select count(*) from claim_status_history h join claims c on c.id = h.claim_pk
                where c.claim_id = ? and h.from_status = 'MORE_INFO_REQUIRED'""", Integer.class, id));
    }

    @Test
    void staleActionsAfterTheClaimMovedOnAreRejectedAndWriteNothing() throws Exception {
        String id = createClaimUnderReview();
        postJson("/api/v1/officer/claims/" + id + "/decision", Map.of("decision", "APPROVED", "decisionReason", "OK.",
                "approvedAmount", "100.00", "currency", "INR", "decidedByOfficerId", "off-1007")).andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        int historyRows = jdbc.queryForObject("select count(*) from claim_status_history", Integer.class);

        assertEquals(409, status("/api/v1/officer/claims/" + id + "/review", Map.of("officerId", "off-1007")));
        assertEquals(409, status("/api/v1/officer/claims/" + id + "/information-requests",
                Map.of("question", "Late?", "requestedByOfficerId", "off-1007")));
        assertEquals(409, status("/api/v1/officer/claims/" + id + "/decision", Map.of("decision", "REJECTED",
                "decisionReason", "Changed mind.", "currency", "INR", "decidedByOfficerId", "off-1007")));

        Map<String, Object> row = jdbc.queryForMap(
                "select status, decided_by_officer_id, approved_amount from claims where claim_id = ?", id);
        assertEquals("APPROVED", row.get("status"));
        assertEquals("off-1007", row.get("decided_by_officer_id"));
        assertEquals(historyRows, jdbc.queryForObject("select count(*) from claim_status_history", Integer.class));
    }
}
