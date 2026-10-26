package com.chubb.claims.claim.internal;

import com.chubb.claims.claim.Claim;
import com.chubb.claims.claim.ClaimRepository;
import com.chubb.claims.claim.ClaimService;
import com.chubb.claims.claim.ClaimStatus;
import com.chubb.claims.claim.WorkflowResponse;
import com.chubb.claims.claim.internal.InternalDtos.Decision;
import com.chubb.claims.claim.internal.InternalDtos.DecisionRequest;
import com.chubb.claims.claim.internal.InternalDtos.DecisionResponse;
import com.chubb.claims.claim.internal.InternalDtos.SettlementResponse;
import com.chubb.claims.common.ApiException;
import java.math.BigDecimal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The lifecycle transitions that the Assessment Service triggers. Each one loads the claim with a row lock, lets the
 * {@link Claim} entity enforce the rule, and appends its history row, all in one local transaction.
 */
@Service
public class ClaimWorkflowService {

    private final ClaimService claimService;
    private final ClaimRepository claims;

    public ClaimWorkflowService(ClaimService claimService, ClaimRepository claims) {
        this.claimService = claimService;
        this.claims = claims;
    }

    /** SUBMITTED (or MORE_INFO_REQUIRED with nothing open) to UNDER_REVIEW; also assigns the reviewer when unassigned. */
    @Transactional
    public WorkflowResponse startReview(String claimId, String officerId, boolean hasOpenInformationRequests) {
        Claim claim = claimService.loadForUpdate(claimId);
        ClaimStatus from = claim.getStatus();
        String previous = claim.getAssignedOfficerId();
        claim.startReview(officerId, hasOpenInformationRequests);
        claimService.recordAssignment(claim, previous, officerId);
        claimService.recordTransition(claim, from, officerId);
        return WorkflowResponse.from(claims.saveAndFlush(claim));
    }

    /** UNDER_REVIEW to MORE_INFO_REQUIRED; the Assessment Service stores the request itself. */
    @Transactional
    public WorkflowResponse requestInformation(String claimId, String requestedByOfficerId) {
        Claim claim = claimService.loadForUpdate(claimId);
        ClaimStatus from = claim.getStatus();
        claim.requestInformation(requestedByOfficerId);
        claimService.recordTransition(claim, from, requestedByOfficerId);
        return WorkflowResponse.from(claims.saveAndFlush(claim));
    }

    /** MORE_INFO_REQUIRED to UNDER_REVIEW once the Assessment Service reports that no request is still OPEN. */
    @Transactional
    public WorkflowResponse resumeAfterInformation(String claimId, String answeredBy) {
        Claim claim = claimService.loadForUpdate(claimId);
        ClaimStatus from = claim.getStatus();
        claim.resumeAfterInformation();
        claimService.recordTransition(claim, from, answeredBy == null || answeredBy.isBlank() ? ClaimService.CLAIMANT : answeredBy);
        return WorkflowResponse.from(claims.saveAndFlush(claim));
    }

    /** UNDER_REVIEW to APPROVED or REJECTED. The amount and currency rules need the claim, so they are checked here. */
    @Transactional
    public DecisionResponse decide(String claimId, DecisionRequest body) {
        Claim claim = claimService.loadForUpdate(claimId);
        claim.requireUnderReview();

        if (!claim.getCurrency().equals(body.currency())) {
            throw ApiException.badRequest("CURRENCY_MISMATCH",
                    "Decision currency " + body.currency() + " must match the claim currency " + claim.getCurrency());
        }
        BigDecimal approved = body.approvedAmount() == null ? null : body.approvedAmount().setScale(2);
        if (body.decision() == Decision.APPROVED) {
            if (approved == null || approved.signum() <= 0) {
                throw ApiException.badRequest("INVALID_APPROVED_AMOUNT", "An approval requires a positive approvedAmount");
            }
            if (approved.compareTo(claim.getClaimedAmount()) > 0) {
                throw ApiException.badRequest("INVALID_APPROVED_AMOUNT", "approvedAmount cannot exceed the claimed amount");
            }
        } else if (approved != null) {
            throw ApiException.badRequest("INVALID_APPROVED_AMOUNT", "A rejection must not carry an approvedAmount");
        }

        ClaimStatus from = claim.getStatus();
        claim.decide(body.decision().toStatus(), body.decisionReason().trim(), approved, body.decidedByOfficerId());
        claimService.recordTransition(claim, from, body.decidedByOfficerId());
        return DecisionResponse.from(claims.saveAndFlush(claim));
    }

    /** APPROVED to SETTLED (terminal). A rejected claim, or one not yet approved, is a 409. */
    @Transactional
    public SettlementResponse settle(String claimId, String settledByOfficerId) {
        Claim claim = claimService.loadForUpdate(claimId);
        claim.requireApproved();
        ClaimStatus from = claim.getStatus();
        claim.settle(settledByOfficerId);
        claimService.recordTransition(claim, from, settledByOfficerId);
        return SettlementResponse.from(claims.saveAndFlush(claim));
    }
}
