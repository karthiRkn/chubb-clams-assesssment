package com.chubb.claims.claim;

import java.time.Instant;

public record ClaimHistoryResponse(String claimId, ClaimStatus fromStatus, ClaimStatus toStatus, Instant changedAt,
                                   String actor) {

    public static ClaimHistoryResponse from(ClaimStatusHistory h) {
        return new ClaimHistoryResponse(h.getClaim().getClaimId(), h.getFromStatus(), h.getToStatus(), h.getChangedAt(),
                h.getActor());
    }
}
