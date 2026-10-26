package com.chubb.claims.claim;

import com.chubb.claims.common.security.AuthenticatedUser;
import org.springframework.data.jpa.domain.Specification;

/** Search conditions for the claim list; absent filters are simply not added to the SQL WHERE clause. */
public final class ClaimSpecifications {

    private ClaimSpecifications() {
    }

    public static Specification<Claim> filter(ClaimStatus status, String market, ClaimType claimType, String assignedOfficerId,
                                              Boolean assigned) {
        Specification<Claim> spec = Specification.where(null);
        if (status != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("status"), status));
        }
        if (market != null && !market.isBlank()) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("market"), market));
        }
        if (claimType != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("claimType"), claimType));
        }
        if (assignedOfficerId != null && !assignedOfficerId.isBlank()) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("assignedOfficerId"), assignedOfficerId));
        }
        if (assigned != null) {
            spec = spec.and((root, q, cb) -> assigned ? cb.isNotNull(root.get("assignedOfficerId"))
                    : cb.isNull(root.get("assignedOfficerId")));
        }
        return spec;
    }

    /**
     * The claims this caller may see, as a SQL condition (the same rule as
     * {@link com.chubb.claims.common.security.ClaimVisibility}, so a list can never show what a single read would hide):
     * a claimant's own claims; a claims officer's unassigned and own claims; everything for a manager or administrator.
     */
    public static Specification<Claim> visibleTo(AuthenticatedUser caller) {
        return switch (caller.role()) {
            case CLAIMANT -> (root, q, cb) -> cb.equal(root.get("claimantId"), caller.username());
            case CLAIMS_OFFICER -> (root, q, cb) -> cb.or(cb.isNull(root.get("assignedOfficerId")),
                    cb.equal(root.get("assignedOfficerId"), caller.username()));
            case MANAGER, ADMIN, SERVICE -> Specification.where(null);
        };
    }
}
