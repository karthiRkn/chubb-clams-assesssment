package com.chubb.claims.officer;

import com.chubb.claims.claim.Claim;
import com.chubb.claims.claim.ClaimRepository;
import com.chubb.claims.claim.ClaimService;
import com.chubb.claims.claim.ClaimStatus;
import com.chubb.claims.claim.ClaimType;
import com.chubb.claims.common.ApiException;
import com.chubb.claims.information.InformationRequestRepository;
import com.chubb.claims.information.InformationRequestStatus;
import com.chubb.claims.officer.OfficerDtos.ClaimSummary;
import com.chubb.claims.officer.OfficerDtos.Decision;
import com.chubb.claims.officer.OfficerDtos.DecisionRequest;
import com.chubb.claims.officer.OfficerDtos.DecisionResponse;
import com.chubb.claims.officer.OfficerDtos.PageResponse;
import com.chubb.claims.officer.OfficerDtos.WorkflowResponse;
import java.math.BigDecimal;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OfficerClaimService {

    private final ClaimService claimService;
    private final ClaimRepository claims;
    private final InformationRequestRepository requests;

    public OfficerClaimService(ClaimService claimService, ClaimRepository claims, InformationRequestRepository requests) {
        this.claimService = claimService;
        this.claims = claims;
        this.requests = requests;
    }

    /** Filtering, sorting and paging all happen in SQL (Specification + Pageable); nothing is filtered in memory. */
    @Transactional(readOnly = true)
    public PageResponse<ClaimSummary> search(ClaimStatus status, String market, ClaimType claimType,
                                             String assignedOfficerId, Boolean assigned, int page, int size) {
        if (Boolean.FALSE.equals(assigned) && assignedOfficerId != null && !assignedOfficerId.isBlank()) {
            throw ApiException.badRequest("CONFLICTING_FILTERS",
                    "assigned=false cannot be combined with assignedOfficerId, because an unassigned claim has no officer");
        }
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        return PageResponse.from(claims.findAll(ClaimSpecifications.filter(status, market, claimType, assignedOfficerId, assigned),
                pageable).map(ClaimSummary::from));
    }

    @Transactional
    public WorkflowResponse assign(String claimId, String officerId) {
        Claim claim = claimService.loadForUpdate(claimId);
        String previous = claim.getAssignedOfficerId();
        claim.assign(officerId);
        claimService.recordAssignment(claim, previous, null);
        return WorkflowResponse.from(claims.saveAndFlush(claim));
    }

    @Transactional
    public WorkflowResponse startReview(String claimId, String officerId) {
        Claim claim = claimService.loadForUpdate(claimId);
        boolean open = requests.existsByClaimAndStatus(claim, InformationRequestStatus.OPEN);
        ClaimStatus from = claim.getStatus();
        String previous = claim.getAssignedOfficerId();
        claim.startReview(officerId, open);
        claimService.recordAssignment(claim, previous, officerId);
        claimService.recordTransition(claim, from, officerId);
        return WorkflowResponse.from(claims.saveAndFlush(claim));
    }

    @Transactional
    public DecisionResponse decide(String claimId, DecisionRequest body) {
        Claim claim = claimService.loadForUpdate(claimId);
        claim.requireUnderReview();

        if (!claim.getCurrency().equals(body.currency())) {
            throw ApiException.badRequest("CURRENCY_MISMATCH",
                    "Decision currency " + body.currency() + " must match the claim currency " + claim.getCurrency());
        }
        BigDecimal settled = body.settledAmount() == null ? null : body.settledAmount().setScale(2);
        if (body.decision() == Decision.SETTLED) {
            if (settled == null || settled.signum() <= 0) {
                throw ApiException.badRequest("INVALID_SETTLED_AMOUNT", "A settlement requires a positive settledAmount");
            }
            if (settled.compareTo(claim.getClaimedAmount()) > 0) {
                throw ApiException.badRequest("INVALID_SETTLED_AMOUNT", "settledAmount cannot exceed the claimed amount");
            }
        } else if (settled != null) {
            throw ApiException.badRequest("INVALID_SETTLED_AMOUNT", "A rejection must not carry a settledAmount");
        }

        ClaimStatus from = claim.getStatus();
        claim.decide(body.decision().toStatus(), body.decisionReason().trim(), settled, body.decidedByOfficerId());
        claimService.recordTransition(claim, from, body.decidedByOfficerId());
        return DecisionResponse.from(claims.saveAndFlush(claim));
    }
}
