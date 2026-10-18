package com.chubb.claims.claim;

import com.chubb.claims.common.ApiException;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ClaimService {

    /** History actor for steps taken by the claimant, who has no identity in this application. */
    public static final String CLAIMANT = "CLAIMANT";

    private final ClaimRepository claims;
    private final ClaimStatusHistoryRepository history;
    private final ClaimAssignmentHistoryRepository assignmentHistory;
    private final ReferenceGenerator references;
    private final MarketPolicy markets;

    public ClaimService(ClaimRepository claims, ClaimStatusHistoryRepository history,
                        ClaimAssignmentHistoryRepository assignmentHistory, ReferenceGenerator references,
                        MarketPolicy markets) {
        this.claims = claims;
        this.history = history;
        this.assignmentHistory = assignmentHistory;
        this.references = references;
        this.markets = markets;
    }

    @Transactional
    public ClaimResponse create(CreateClaimRequest r) {
        markets.requireAllowed(r.market());
        Claim claim = new Claim(references.nextClaimId(), r.claimantName().trim(), r.claimantEmail().trim(), r.market(),
                r.claimType(), r.description().trim(), r.incidentDate(), r.claimedAmount(), r.currency());
        claims.saveAndFlush(claim);
        recordTransition(claim, null, CLAIMANT);
        return ClaimResponse.from(claim);
    }

    @Transactional(readOnly = true)
    public ClaimResponse get(String claimId) {
        return ClaimResponse.from(claims.findByClaimId(claimId).orElseThrow(() -> notFound(claimId)));
    }

    @Transactional(readOnly = true)
    public List<ClaimHistoryResponse> history(String claimId) {
        Claim claim = claims.findByClaimId(claimId).orElseThrow(() -> notFound(claimId));
        return history.findAllForClaim(claim).stream().map(ClaimHistoryResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<ClaimAssignmentResponse> assignments(String claimId) {
        Claim claim = claims.findByClaimId(claimId).orElseThrow(() -> notFound(claimId));
        return assignmentHistory.findAllForClaim(claim).stream().map(ClaimAssignmentResponse::from).toList();
    }

    /** Loads and row-locks a claim by public claimId; used by every state-changing workflow. */
    public Claim loadForUpdate(String claimId) {
        return claims.findByClaimIdForUpdate(claimId).orElseThrow(() -> notFound(claimId));
    }

    /**
     * Appends a history row when the claim's status differs from {@code from}. Call it inside the same transaction as
     * the transition, after the claim has been changed, so the change and its history commit or roll back together.
     */
    public void recordTransition(Claim claim, ClaimStatus from, String actor) {
        if (from != claim.getStatus()) {
            history.save(new ClaimStatusHistory(claim, from, claim.getStatus(), actor));
        }
    }

    /**
     * Appends an assignment-history row when the assignee differs from {@code previousOfficerId}. Same transaction as the
     * change, called after the claim has been changed. {@code actor} may be null when the request does not say who acted.
     */
    public void recordAssignment(Claim claim, String previousOfficerId, String actor) {
        if (!java.util.Objects.equals(previousOfficerId, claim.getAssignedOfficerId())) {
            assignmentHistory.save(new ClaimAssignmentHistory(claim, previousOfficerId, claim.getAssignedOfficerId(), actor));
        }
    }

    private static ApiException notFound(String claimId) {
        return ApiException.notFound("CLAIM_NOT_FOUND", "Claim " + claimId + " was not found");
    }
}
