package com.chubb.claims.claim;

/**
 * The claim lifecycle. The allowed moves, all enforced inside {@link Claim}:
 * <pre>
 * SUBMITTED          -> UNDER_REVIEW         an officer starts review (assigns themselves when unassigned)
 * UNDER_REVIEW       -> MORE_INFO_REQUIRED   the officer asks the claimant for information
 * MORE_INFO_REQUIRED -> UNDER_REVIEW         the claimant answers every open request (automatic), or the officer resumes
 * UNDER_REVIEW       -> APPROVED             the officer approves, with an approved amount
 * UNDER_REVIEW       -> REJECTED             the officer rejects, with a reason
 * APPROVED           -> SETTLED              the officer records the settlement
 * </pre>
 * {@code REJECTED} and {@code SETTLED} are terminal, so a rejected claim can never be settled.
 */
public enum ClaimStatus {
    SUBMITTED,
    UNDER_REVIEW,
    MORE_INFO_REQUIRED,
    APPROVED,
    REJECTED,
    SETTLED;

    public boolean isTerminal() {
        return this == SETTLED || this == REJECTED;
    }
}
