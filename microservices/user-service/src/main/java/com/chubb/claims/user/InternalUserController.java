package com.chubb.claims.user;

import com.chubb.claims.common.security.Role;
import com.chubb.claims.user.UserDtos.UserView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * For the Claim Service, which asks whether an officer a claim is about to be assigned to really is an active claims
 * officer, and which active claims officers exist (so the officer workload report lists officers who have no claims yet).
 * Only a SERVICE token is accepted (the shared security chain enforces that for every {@code /internal/v1}
 * route); a person's token, an administrator's included, gets 403.
 */
@RestController
@RequestMapping("/internal/v1/users")
@Tag(name = "Internal (service-to-service)", description = "Used by the other services. Not for claimants or officers.")
public class InternalUserController {

    private final UserService service;

    public InternalUserController(UserService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Every user with this role and active flag, by username (no password, no hash); both parameters are required")
    public List<UserView> list(@RequestParam Role role, @RequestParam boolean active) {
        return service.viewAll(role, active);
    }

    @GetMapping("/{username}")
    @Operation(summary = "A user's role and active flag (no password, no hash); 404 USER_NOT_FOUND when there is no such user")
    public UserView get(@PathVariable String username) {
        return service.view(username);
    }
}
