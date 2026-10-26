package com.chubb.claims.common.security;

import com.chubb.claims.common.ApiException;

/**
 * Which claims a person may see and act on, in one place so the Claim and Assessment services cannot drift apart:
 * <ul>
 *   <li>a claimant: only the claims they submitted;</li>
 *   <li>a claims officer: unassigned claims (the queue they can pick up from) and the claims assigned to them;</li>
 *   <li>a manager and an administrator: every claim.</li>
 * </ul>
 * A claim outside that answers exactly as a claim that does not exist, so claim numbers cannot be probed. A claim with
 * no recorded owner (created before owners were recorded) is visible to staff only.
 */
public final class ClaimVisibility {

    private ClaimVisibility() {
    }

    public static boolean canSee(AuthenticatedUser caller, String claimantId, String assignedOfficerId) {
        return switch (caller.role()) {
            case CLAIMANT -> claimantId != null && claimantId.equals(caller.username());
            case CLAIMS_OFFICER -> assignedOfficerId == null || assignedOfficerId.equals(caller.username());
            case MANAGER, ADMIN, SERVICE -> true;
        };
    }

    /** @throws ApiException 404 {@code CLAIM_NOT_FOUND} when the caller may not see the claim */
    public static void require(AuthenticatedUser caller, String claimId, String claimantId, String assignedOfficerId) {
        if (!canSee(caller, claimantId, assignedOfficerId)) {
            throw ApiException.notFound("CLAIM_NOT_FOUND", "Claim " + claimId + " was not found");
        }
    }
}
