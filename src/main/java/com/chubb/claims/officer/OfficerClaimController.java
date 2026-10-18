package com.chubb.claims.officer;

import com.chubb.claims.claim.ClaimStatus;
import com.chubb.claims.claim.ClaimType;
import com.chubb.claims.officer.OfficerDtos.ClaimSummary;
import com.chubb.claims.officer.OfficerDtos.DecisionRequest;
import com.chubb.claims.officer.OfficerDtos.DecisionResponse;
import com.chubb.claims.officer.OfficerDtos.OfficerRequest;
import com.chubb.claims.officer.OfficerDtos.PageResponse;
import com.chubb.claims.officer.OfficerDtos.WorkflowResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/officer/claims")
@Validated
public class OfficerClaimController {

    /** Documented maximum page size. */
    public static final int MAX_PAGE_SIZE = 100;

    private final OfficerClaimService service;

    public OfficerClaimController(OfficerClaimService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<ClaimSummary> search(
            @RequestParam(required = false) ClaimStatus status,
            @RequestParam(required = false) String market,
            @RequestParam(required = false) ClaimType claimType,
            @RequestParam(required = false) String assignedOfficerId,
            @RequestParam(required = false) Boolean assigned,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
        return service.search(status, market, claimType, assignedOfficerId, assigned, page, size);
    }

    @PostMapping("/{claimId}/assign")
    public WorkflowResponse assign(@PathVariable String claimId, @Valid @RequestBody OfficerRequest body) {
        return service.assign(claimId, body.officerId());
    }

    @PostMapping("/{claimId}/review")
    public WorkflowResponse review(@PathVariable String claimId, @Valid @RequestBody OfficerRequest body) {
        return service.startReview(claimId, body.officerId());
    }

    @PostMapping("/{claimId}/decision")
    public DecisionResponse decision(@PathVariable String claimId, @Valid @RequestBody DecisionRequest body) {
        return service.decide(claimId, body);
    }
}
