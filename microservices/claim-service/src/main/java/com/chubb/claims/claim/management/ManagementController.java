package com.chubb.claims.claim.management;

import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Management workload and liability reports (managers, and administrators). */
@RestController
@RequestMapping("/api/v1/management")
public class ManagementController {

    private final ManagementService service;

    public ManagementController(ManagementService service) {
        this.service = service;
    }

    @GetMapping("/summary")
    @Operation(summary = "Claim counts per status and the outstanding liability per currency (a simple estimate, not a reserve)")
    public ManagementSummary summary() {
        return service.summary();
    }

    @GetMapping("/officers")
    @Operation(summary = "Open workload and decisions per officer")
    public OfficerMetrics officers() {
        return service.officerMetrics();
    }
}
