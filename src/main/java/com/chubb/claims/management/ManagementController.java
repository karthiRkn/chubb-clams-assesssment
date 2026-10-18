package com.chubb.claims.management;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/management")
public class ManagementController {

    private final ManagementService service;

    public ManagementController(ManagementService service) {
        this.service = service;
    }

    @GetMapping("/summary")
    public ManagementSummary summary() {
        return service.summary();
    }
}
