package com.chubb.claims.officer;

import com.chubb.claims.claim.Claim;
import com.chubb.claims.claim.ClaimStatus;
import com.chubb.claims.claim.ClaimType;
import org.springframework.data.jpa.domain.Specification;

/** Optional filters; absent filters are simply not added to the SQL WHERE clause. */
final class ClaimSpecifications {

    private ClaimSpecifications() {
    }

    static Specification<Claim> filter(ClaimStatus status, String market, ClaimType claimType, String assignedOfficerId,
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
}
