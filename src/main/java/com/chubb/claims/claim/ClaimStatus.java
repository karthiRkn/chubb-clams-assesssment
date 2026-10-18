package com.chubb.claims.claim;

public enum ClaimStatus {
    SUBMITTED,
    UNDER_REVIEW,
    AWAITING_INFORMATION,
    SETTLED,
    REJECTED;

    public boolean isTerminal() {
        return this == SETTLED || this == REJECTED;
    }
}
