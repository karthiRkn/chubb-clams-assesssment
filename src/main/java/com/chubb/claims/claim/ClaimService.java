package com.chubb.claims.claim;

import com.chubb.claims.common.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ClaimService {

    private final ClaimRepository claims;
    private final ReferenceGenerator references;

    public ClaimService(ClaimRepository claims, ReferenceGenerator references) {
        this.claims = claims;
        this.references = references;
    }

    @Transactional
    public ClaimResponse create(CreateClaimRequest r) {
        Claim claim = new Claim(references.nextClaimId(), r.claimantName().trim(), r.claimantEmail().trim(), r.market(),
                r.claimType(), r.description().trim(), r.incidentDate(), r.claimedAmount(), r.currency());
        return ClaimResponse.from(claims.saveAndFlush(claim));
    }

    @Transactional(readOnly = true)
    public ClaimResponse get(String claimId) {
        return ClaimResponse.from(claims.findByClaimId(claimId).orElseThrow(() -> notFound(claimId)));
    }

    /** Loads and row-locks a claim by public claimId; used by every state-changing workflow. */
    public Claim loadForUpdate(String claimId) {
        return claims.findByClaimIdForUpdate(claimId).orElseThrow(() -> notFound(claimId));
    }

    private static ApiException notFound(String claimId) {
        return ApiException.notFound("CLAIM_NOT_FOUND", "Claim " + claimId + " was not found");
    }
}
