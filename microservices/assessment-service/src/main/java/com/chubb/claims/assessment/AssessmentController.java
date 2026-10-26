package com.chubb.claims.assessment;

import com.chubb.claims.assessment.AssessmentDtos.AssessmentResponse;
import com.chubb.claims.assessment.AssessmentDtos.DecisionRequest;
import com.chubb.claims.assessment.AssessmentDtos.DecisionResponse;
import com.chubb.claims.assessment.AssessmentDtos.ReviewRequest;
import com.chubb.claims.assessment.AssessmentDtos.SettlementRequest;
import com.chubb.claims.assessment.AssessmentDtos.SettlementResponse;
import com.chubb.claims.assessment.AssessmentDtos.WorkflowResponse;
import com.chubb.claims.common.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Officer review, assessment history, decision and settlement. Workload search and assignment are served by the Claim Service. */
@RestController
@RequestMapping("/api/v1/officer/claims")
public class AssessmentController {

    private final AssessmentService service;

    public AssessmentController(AssessmentService service) {
        this.service = service;
    }

    @PostMapping("/{claimId}/review")
    @Operation(summary = "Start (or resume) review of a claim, with optional assessment notes",
            description = "SUBMITTED or MORE_INFO_REQUIRED to UNDER_REVIEW; an unassigned claim is assigned to the officer. "
                    + "A claims officer reviews as themselves; an administrator names the officer in officerId.")
    public WorkflowResponse review(@Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUser caller,
                                   @PathVariable String claimId, @Valid @RequestBody ReviewRequest body) {
        return service.startReview(caller, claimId, body);
    }

    @GetMapping("/{claimId}/assessments")
    @Operation(summary = "The assessment history of a claim: notes, decision and settlement, oldest first (never shown to claimants)")
    public List<AssessmentResponse> assessments(@Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUser caller,
                                                @PathVariable String claimId) {
        return service.assessments(caller, claimId);
    }

    @PostMapping("/{claimId}/decision")
    @Operation(summary = "Approve a claim (with an approved amount) or reject it (with a reason)",
            description = "UNDER_REVIEW to APPROVED or REJECTED, only by the officer the claim is assigned to. An approval needs a positive "
                    + "approvedAmount not above the claimed amount, in the claim's currency; a rejection carries no amount.")
    public DecisionResponse decision(@Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUser caller,
                                     @PathVariable String claimId, @Valid @RequestBody DecisionRequest body) {
        return service.decide(caller, claimId, body);
    }

    @PostMapping("/{claimId}/settlement")
    @Operation(summary = "Record the settlement of an approved claim",
            description = "APPROVED to SETTLED, only by the officer the claim is assigned to. A rejected claim can never be settled (409).")
    public SettlementResponse settlement(@Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUser caller,
                                         @PathVariable String claimId, @Valid @RequestBody SettlementRequest body) {
        return service.settle(caller, claimId, body);
    }
}
