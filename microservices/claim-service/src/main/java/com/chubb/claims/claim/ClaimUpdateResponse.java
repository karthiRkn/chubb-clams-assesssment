package com.chubb.claims.claim;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/** One edit of the claim by its claimant: when, by whom, and one line per changed field (free text is only reported as changed). */
public record ClaimUpdateResponse(String claimId, Instant changedAt, String actor, List<String> changes) {

    public static ClaimUpdateResponse from(ClaimUpdateHistory h) {
        return new ClaimUpdateResponse(h.getClaim().getClaimId(), h.getChangedAt(), h.getActor(),
                Arrays.asList(h.getChangeSummary().split("\n")));
    }
}
