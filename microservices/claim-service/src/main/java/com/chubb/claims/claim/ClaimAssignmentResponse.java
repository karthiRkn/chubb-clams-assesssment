package com.chubb.claims.claim;

import java.time.Instant;

/** {@code actor} is null when the request does not say who acted; it is never a verified identity. */
public record ClaimAssignmentResponse(String claimId, String previousOfficerId, String newOfficerId, Instant changedAt,
                                      String actor) {

    public static ClaimAssignmentResponse from(ClaimAssignmentHistory h) {
        return new ClaimAssignmentResponse(h.getClaim().getClaimId(), h.getPreviousOfficerId(), h.getNewOfficerId(),
                h.getChangedAt(), h.getActor());
    }
}
