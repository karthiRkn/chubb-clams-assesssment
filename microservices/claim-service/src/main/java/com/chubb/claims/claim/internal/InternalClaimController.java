package com.chubb.claims.claim.internal;

import com.chubb.claims.claim.Claim;
import com.chubb.claims.claim.ClaimService;
import com.chubb.claims.claim.WorkflowResponse;
import com.chubb.claims.claim.client.UserClient;
import com.chubb.claims.common.ApiException;
import com.chubb.claims.claim.internal.InternalDtos.ClaimStatusView;
import com.chubb.claims.claim.internal.InternalDtos.DecisionRequest;
import com.chubb.claims.claim.internal.InternalDtos.DecisionResponse;
import com.chubb.claims.claim.internal.InternalDtos.RequestInformationCommand;
import com.chubb.claims.claim.internal.InternalDtos.ResumeAfterInformationCommand;
import com.chubb.claims.claim.internal.InternalDtos.SettlementCommand;
import com.chubb.claims.claim.internal.InternalDtos.SettlementResponse;
import com.chubb.claims.claim.internal.InternalDtos.StartReviewCommand;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Service-to-service API for the Assessment Service: the lifecycle transitions that need the claim's rules and row lock.
 * Only a SERVICE token is accepted here (not even an administrator's): the calling service has already checked the person's
 * role and which claims they may touch, and this API trusts it. Anyone holding the shared JWT secret can mint a SERVICE
 * token, so keep the port on a trusted network as well.
 */
@RestController
@RequestMapping("/internal/v1/claims")
@Tag(name = "Internal (service-to-service)", description = "Used by the other services. Not for claimants or officers.")
public class InternalClaimController {

    private final ClaimService claimService;
    private final ClaimWorkflowService workflow;
    private final UserClient users;

    public InternalClaimController(ClaimService claimService, ClaimWorkflowService workflow, UserClient users) {
        this.claimService = claimService;
        this.workflow = workflow;
        this.users = users;
    }

    @GetMapping("/{claimId}/status")
    @Operation(summary = "Claim status, submitter and assigned officer only (no claimant data); 404 when the claim does not exist")
    public ClaimStatusView status(@PathVariable String claimId) {
        Claim claim = claimService.getForService(claimId);
        return new ClaimStatusView(claim.getClaimId(), claim.getStatus(), claim.getClaimantId(), claim.getAssignedOfficerId());
    }

    @PostMapping("/{claimId}/review")
    @Operation(summary = "Start or resume review (SUBMITTED or MORE_INFO_REQUIRED to UNDER_REVIEW)")
    public WorkflowResponse review(@PathVariable String claimId, @Valid @RequestBody StartReviewCommand body) {
        if (!Boolean.TRUE.equals(body.officerVerified())) {
            // Starting the review of an unassigned claim assigns it to this officer, so the name must be a real, active claims
            // officer. The question is put here, before the claim is locked, so no row lock is held across the call.
            Claim claim = claimService.getForService(claimId); // 404 CLAIM_NOT_FOUND for an unknown claim, as before
            if (claim.getAssignedOfficerId() == null && !users.isActiveClaimsOfficer(body.officerId())) {
                throw ApiException.badRequest("INVALID_OFFICER", body.officerId() + " is not an active claims officer");
            }
        }
        return workflow.startReview(claimId, body.officerId(), body.hasOpenInformationRequests());
    }

    @PostMapping("/{claimId}/request-information")
    @Operation(summary = "UNDER_REVIEW to MORE_INFO_REQUIRED (only by the assigned officer)")
    public WorkflowResponse requestInformation(@PathVariable String claimId,
                                               @Valid @RequestBody RequestInformationCommand body) {
        return workflow.requestInformation(claimId, body.requestedByOfficerId());
    }

    @PostMapping("/{claimId}/resume-after-information")
    @Operation(summary = "MORE_INFO_REQUIRED to UNDER_REVIEW once no information request is still OPEN")
    public WorkflowResponse resumeAfterInformation(@PathVariable String claimId,
                                                   @Valid @RequestBody(required = false) ResumeAfterInformationCommand body) {
        return workflow.resumeAfterInformation(claimId, body == null ? null : body.answeredBy());
    }

    @PostMapping("/{claimId}/decision")
    @Operation(summary = "UNDER_REVIEW to APPROVED (with an approved amount) or REJECTED, with the amount and currency rules")
    public DecisionResponse decision(@PathVariable String claimId, @Valid @RequestBody DecisionRequest body) {
        return workflow.decide(claimId, body);
    }

    @PostMapping("/{claimId}/settlement")
    @Operation(summary = "APPROVED to SETTLED (a rejected claim cannot be settled)")
    public SettlementResponse settlement(@PathVariable String claimId, @Valid @RequestBody SettlementCommand body) {
        return workflow.settle(claimId, body.settledByOfficerId());
    }
}
