package com.chubb.claims.assessment;

import com.chubb.claims.assessment.AssessmentDtos.AssessmentResponse;
import com.chubb.claims.assessment.AssessmentDtos.DecisionRequest;
import com.chubb.claims.assessment.AssessmentDtos.DecisionResponse;
import com.chubb.claims.assessment.AssessmentDtos.ReviewRequest;
import com.chubb.claims.assessment.AssessmentDtos.SettlementRequest;
import com.chubb.claims.assessment.AssessmentDtos.SettlementResponse;
import com.chubb.claims.assessment.AssessmentDtos.WorkflowResponse;
import com.chubb.claims.assessment.client.ClaimClient;
import com.chubb.claims.assessment.client.ClaimClient.ClaimStatusView;
import com.chubb.claims.assessment.information.InformationService;
import com.chubb.claims.common.security.AuthenticatedUser;
import com.chubb.claims.common.security.ClaimVisibility;
import com.chubb.claims.common.security.Role;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The officer's assessment of a claim: starting (or resuming) review with optional notes, the approve/reject decision, and
 * the settlement of an approved claim. The claim's lifecycle and the amount rules belong to the Claim Service and are
 * enforced there in one transaction under the claim row lock; this service records the assessment history and coordinates.
 *
 * <p>Every action first asks the Claim Service where the claim is and who has it, and refuses (404, as for an unknown claim)
 * a claim the caller may not see: a claims officer sees only unassigned claims and their own. The officer an action is
 * recorded against comes from the token (see {@link AuthenticatedUser#actingOfficerId}).
 *
 * <p>Each action that changes the claim writes its history entry before the Claim Service is called and commits it only after
 * the Claim Service accepts, so a refused action (404, 409, 400, 503) rolls the entry back: a refused action saves nothing.
 * If the commit itself fails after the Claim Service accepted, the claim has moved without its entry; that is the one
 * inconsistency this design cannot rule out (see the README).
 */
@Service
public class AssessmentService {

    static final String SETTLED_NOTE = "Claim settled";

    private final ClaimAssessmentRepository assessments;
    private final ClaimClient claims;
    private final InformationService information;

    public AssessmentService(ClaimAssessmentRepository assessments, ClaimClient claims, InformationService information) {
        this.assessments = assessments;
        this.claims = claims;
        this.information = information;
    }

    /**
     * Review may only resume from MORE_INFO_REQUIRED when no information request is OPEN. Those requests live in this
     * service, so it looks them up and passes the fact to the Claim Service, which makes the decision.
     */
    @Transactional
    public WorkflowResponse startReview(AuthenticatedUser caller, String claimId, ReviewRequest body) {
        String officerId = ActingAs.officer(caller, body.officerId(), "review", claimId);
        requireVisible(caller, claimId);
        boolean hasOpenInformationRequests = information.hasOpenRequests(claimId);
        if (body.assessmentNotes() != null && !body.assessmentNotes().isBlank()) {
            assessments.saveAndFlush(new ClaimAssessment(claimId, officerId, AssessmentKind.NOTE, body.assessmentNotes().trim()));
        }
        // A claims officer is the person whose token was just checked, so the Claim Service need not check the name. An
        // administrator names an officer, and the Claim Service checks that name is an active claims officer before it assigns
        // an unassigned claim to it (the same rule as for the assign route).
        return claims.startReview(claimId, officerId, hasOpenInformationRequests, caller.is(Role.CLAIMS_OFFICER));
    }

    @Transactional(readOnly = true)
    public List<AssessmentResponse> assessments(AuthenticatedUser caller, String claimId) {
        requireVisible(caller, claimId);
        return assessments.findByClaimIdOrderByCreatedAtAscIdAsc(claimId).stream().map(AssessmentResponse::from).toList();
    }

    /** APPROVED (with an approved amount) or REJECTED. The decision and its reason become an entry of the assessment history. */
    @Transactional
    public DecisionResponse decide(AuthenticatedUser caller, String claimId, DecisionRequest body) {
        String officerId = ActingAs.officer(caller, body.decidedByOfficerId(), "decision " + body.decision(), claimId);
        requireVisible(caller, claimId);
        AssessmentKind kind = body.decision() == AssessmentDtos.Decision.APPROVED ? AssessmentKind.APPROVED : AssessmentKind.REJECTED;
        assessments.saveAndFlush(new ClaimAssessment(claimId, officerId, kind, body.decisionReason().trim()));
        return claims.decide(claimId, body.decision().name(), body.decisionReason(), body.approvedAmount(), body.currency(), officerId);
    }

    /** APPROVED to SETTLED. A rejected claim, or one not yet approved, is refused by the Claim Service with 409. */
    @Transactional
    public SettlementResponse settle(AuthenticatedUser caller, String claimId, SettlementRequest body) {
        String officerId = ActingAs.officer(caller, body.settledByOfficerId(), "settlement", claimId);
        requireVisible(caller, claimId);
        String note = body.settlementNote() == null || body.settlementNote().isBlank() ? SETTLED_NOTE : body.settlementNote().trim();
        assessments.saveAndFlush(new ClaimAssessment(claimId, officerId, AssessmentKind.SETTLED, note));
        return claims.settle(claimId, officerId);
    }

    /** 404 {@code CLAIM_NOT_FOUND} for an unknown claim and for one the caller may not see. */
    private void requireVisible(AuthenticatedUser caller, String claimId) {
        ClaimStatusView claim = claims.status(claimId);
        ClaimVisibility.require(caller, claimId, claim.claimantId(), claim.assignedOfficerId());
    }
}
