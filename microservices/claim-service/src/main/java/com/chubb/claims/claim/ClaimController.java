package com.chubb.claims.claim;

import com.chubb.claims.claim.officer.OfficerDtos.ClaimSummary;
import com.chubb.claims.claim.officer.OfficerDtos.PageResponse;
import com.chubb.claims.common.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/claims")
@Validated
public class ClaimController {

    /** Documented maximum page size. */
    public static final int MAX_PAGE_SIZE = 100;

    private final ClaimService service;

    public ClaimController(ClaimService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Submit a claim (claimant)",
            description = "The claim number (claimId) and the claimant (the logged-in user) are never taken from the request.")
    public ClaimResponse create(@Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUser caller,
                                @Valid @RequestBody CreateClaimRequest request) {
        return service.create(request, caller);
    }

    @GetMapping
    @Operation(summary = "List claims, newest first, with filters and paging",
            description = "A claimant sees their own claims, a claims officer the unassigned claims and their own, a manager every claim.")
    public PageResponse<ClaimSummary> list(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUser caller,
            @RequestParam(required = false) ClaimStatus status,
            @RequestParam(required = false) String market,
            @RequestParam(required = false) ClaimType claimType,
            @RequestParam(required = false) String assignedOfficerId,
            @RequestParam(required = false) Boolean assigned,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
        return service.search(caller, status, market, claimType, assignedOfficerId, assigned, page, size);
    }

    @GetMapping("/{claimId}/assignments")
    public List<ClaimAssignmentResponse> assignments(@Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUser caller,
                                                     @PathVariable String claimId) {
        return service.assignments(claimId, caller);
    }

    @GetMapping("/{claimId}/history")
    public List<ClaimHistoryResponse> history(@Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUser caller,
                                              @PathVariable String claimId) {
        return service.history(claimId, caller);
    }

    @GetMapping("/{claimId}/updates")
    @Operation(summary = "The edits the claimant made to the claim (who, when, which fields)")
    public List<ClaimUpdateResponse> updates(@Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUser caller,
                                             @PathVariable String claimId) {
        return service.updates(claimId, caller);
    }

    @GetMapping("/{claimId}")
    public ClaimResponse get(@Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUser caller,
                             @PathVariable String claimId) {
        return service.get(claimId, caller);
    }

    @PatchMapping("/{claimId}")
    @Operation(summary = "Correct the details of your own claim while it is still SUBMITTED (claimant)",
            description = "Every field is optional; send only what changes. 409 CLAIM_NOT_EDITABLE once an officer has started on the claim.")
    public ClaimResponse update(@Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUser caller,
                                @PathVariable String claimId, @Valid @RequestBody UpdateClaimRequest request) {
        return service.update(claimId, request, caller);
    }
}
