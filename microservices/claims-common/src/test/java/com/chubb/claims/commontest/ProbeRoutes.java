package com.chubb.claims.commontest;

import com.chubb.claims.common.security.AccessRule;
import com.chubb.claims.common.security.AccessRules;
import com.chubb.claims.common.security.AuthenticatedUser;
import com.chubb.claims.common.security.Role;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpMethod;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ProbeController {

    @GetMapping("/api/v1/probe/officer")
    Map<String, String> officerOnly() {
        return Map.of("ok", "officer");
    }

    @PostMapping("/api/v1/probe/manager")
    Map<String, String> managerOnly() {
        return Map.of("ok", "manager");
    }

    @GetMapping("/api/v1/probe/open")
    Map<String, String> open() {
        return Map.of("ok", "open");
    }

    @GetMapping("/api/v1/probe/unlisted")
    Map<String, String> unlisted() {
        return Map.of("ok", "unlisted");
    }

    @GetMapping("/internal/v1/probe/{id}")
    Map<String, String> internal(@PathVariable String id) {
        return Map.of("ok", id);
    }

    @GetMapping("/api/v1/probe/whoami")
    Map<String, String> whoami(@AuthenticationPrincipal AuthenticatedUser user) {
        return Map.of("username", user.username(), "role", user.role().name());
    }

    /** A service that decides inside its own code that the caller may not do this (for example ownership). */
    @GetMapping("/api/v1/probe/denied-in-code")
    Map<String, String> deniedInCode() {
        throw new AccessDeniedException("not yours");
    }
}

@Component
class ProbeAccessRules implements AccessRules {

    @Override
    public List<AccessRule> rules() {
        return List.of(
                AccessRule.roles(HttpMethod.GET, "/api/v1/probe/officer", Role.CLAIMS_OFFICER),
                AccessRule.roles(HttpMethod.POST, "/api/v1/probe/manager", Role.MANAGER),
                AccessRule.open(HttpMethod.GET, "/api/v1/probe/open"),
                AccessRule.roles(HttpMethod.GET, "/api/v1/probe/whoami", Role.CLAIMANT, Role.CLAIMS_OFFICER, Role.MANAGER),
                AccessRule.roles(HttpMethod.GET, "/api/v1/probe/denied-in-code", Role.CLAIMANT));
    }
}
