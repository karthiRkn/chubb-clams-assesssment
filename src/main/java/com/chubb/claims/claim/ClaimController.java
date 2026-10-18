package com.chubb.claims.claim;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/claims")
public class ClaimController {

    private final ClaimService service;

    public ClaimController(ClaimService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ClaimResponse create(@Valid @RequestBody CreateClaimRequest request) {
        return service.create(request);
    }

    @GetMapping("/{claimId}/assignments")
    public List<ClaimAssignmentResponse> assignments(@PathVariable String claimId) {
        return service.assignments(claimId);
    }

    @GetMapping("/{claimId}/history")
    public List<ClaimHistoryResponse> history(@PathVariable String claimId) {
        return service.history(claimId);
    }

    @GetMapping("/{claimId}")
    public ClaimResponse get(@PathVariable String claimId) {
        return service.get(claimId);
    }
}
