package com.chubb.claims.user;

import com.chubb.claims.common.security.AuthenticatedUser;
import com.chubb.claims.user.UserDtos.ChangeRoleRequest;
import com.chubb.claims.user.UserDtos.CreateUserRequest;
import com.chubb.claims.user.UserDtos.PageResponse;
import com.chubb.claims.user.UserDtos.SetActiveRequest;
import com.chubb.claims.user.UserDtos.UserResponse;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** User and role management. Everything except {@code /me} is for administrators (see {@link UserAccessRules}). */
@RestController
@RequestMapping("/api/v1/users")
@Validated
@Tag(name = "Users", description = "User and role management. Needs a Bearer token: `/me` for any logged-in person, the rest for an ADMIN.")
public class UserController {

    /** Documented maximum page size. */
    public static final int MAX_PAGE_SIZE = 100;

    private final UserService service;

    public UserController(UserService service) {
        this.service = service;
    }

    @GetMapping("/me")
    public UserResponse me(@Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUser caller) {
        return service.get(caller.username());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse create(@Valid @RequestBody CreateUserRequest request) {
        return service.create(request.username(), request.password(), request.role());
    }

    @GetMapping
    public PageResponse<UserResponse> list(@RequestParam(defaultValue = "0") @Min(0) int page,
                                           @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
        return service.list(page, size);
    }

    @GetMapping("/{username}")
    public UserResponse get(@PathVariable String username) {
        return service.get(username);
    }

    @PutMapping("/{username}/role")
    public UserResponse changeRole(@Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUser caller,
                                   @PathVariable String username, @Valid @RequestBody ChangeRoleRequest request) {
        return service.changeRole(caller, username, request.role());
    }

    @PutMapping("/{username}/active")
    public UserResponse setActive(@Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUser caller,
                                  @PathVariable String username, @Valid @RequestBody SetActiveRequest request) {
        return service.setActive(caller, username, request.active());
    }
}
