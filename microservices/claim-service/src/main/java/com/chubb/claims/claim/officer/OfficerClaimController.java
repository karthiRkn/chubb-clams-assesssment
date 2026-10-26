package com.chubb.claims.claim.officer;

import com.chubb.claims.claim.ClaimService;
import com.chubb.claims.claim.ClaimStatus;
import com.chubb.claims.claim.ClaimType;
import com.chubb.claims.claim.WorkflowResponse;
import com.chubb.claims.claim.officer.OfficerDtos.ClaimSummary;
import com.chubb.claims.claim.officer.OfficerDtos.OfficerRequest;
import com.chubb.claims.claim.officer.OfficerDtos.PageResponse;
import com.chubb.claims.common.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Staff workload and assignment. Review, decision, settlement and information requests are served by the Assessment Service. */
@RestController
@RequestMapping("/api/v1/officer/claims")
@Validated
public class OfficerClaimController {

    /** Documented maximum page size. */
    public static final int MAX_PAGE_SIZE = 100;

    private final ClaimService claimService;
    private final OfficerClaimService assignments;

    public OfficerClaimController(ClaimService claimService, OfficerClaimService assignments) {
        this.claimService = claimService;
        this.assignments = assignments;
    }

    @GetMapping
    @Operation(summary = "Staff view of the claim list (the same list as GET /api/v1/claims, for officers and managers)")
    public PageResponse<ClaimSummary> search(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUser caller,
            @RequestParam(required = false) ClaimStatus status,
            @RequestParam(required = false) String market,
            @RequestParam(required = false) ClaimType claimType,
            @RequestParam(required = false) String assignedOfficerId,
            @RequestParam(required = false) Boolean assigned,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
        return claimService.search(caller, status, market, claimType, assignedOfficerId, assigned, page, size);
    }

    @PostMapping("/{claimId}/assign")
    @Operation(summary = "Assign a claim to an officer",
            description = "A manager assigns it to any active claims officer. A claims officer can only take a claim for themselves "
                    + "(403 ASSIGNMENT_NOT_PERMITTED when naming someone else). 400 INVALID_OFFICER for a name that is not an active officer.")
    public WorkflowResponse assign(@Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUser caller,
                                   @PathVariable String claimId, @Valid @RequestBody OfficerRequest body) {
        return assignments.assign(caller, claimId, body.officerId());
    }
}
