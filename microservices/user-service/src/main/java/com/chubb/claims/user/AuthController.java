package com.chubb.claims.user;

import com.chubb.claims.common.ApiError;
import com.chubb.claims.user.UserDtos.LoginRequest;
import com.chubb.claims.user.UserDtos.LoginResponse;
import com.chubb.claims.user.UserDtos.RegisterRequest;
import com.chubb.claims.user.UserDtos.UserResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The two routes that need no token: log in and create a claimant account. */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "Log in to get a JWT, or register as a claimant. These two routes need no token.")
public class AuthController {

    private final UserService service;

    public AuthController(UserService service) {
        this.service = service;
    }

    @PostMapping("/login")
    @Operation(
            summary = "Log in: exchange a username and password for a JWT",
            description = """
                    Returns a signed token. Send it on every other call as `Authorization: Bearer <accessToken>`, or paste \
                    it into **Authorize** (the padlock at the top of this page, and on each service's Swagger UI). The token \
                    holds the username and one role and expires after `expiresIn` seconds (3600 by default); log in again after that.

                    **Demo users** (created at start-up unless `DEMO_USERS=false`; password `password123` unless `DEMO_USERS_PASSWORD` is set): \
                    `admin1` (ADMIN), `officer1` (CLAIMS_OFFICER), `manager1` (MANAGER), `claimant1` (CLAIMANT). \
                    Anyone can also create a CLAIMANT with `POST /api/v1/auth/register`.

                    A wrong password and an unknown user give the same 401, so the answer does not reveal which usernames exist.""",
            security = {},
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = LoginRequest.class),
                    examples = {
                            @ExampleObject(name = "Officer", value = "{\"username\": \"officer1\", \"password\": \"password123\"}"),
                            @ExampleObject(name = "Manager", value = "{\"username\": \"manager1\", \"password\": \"password123\"}"),
                            @ExampleObject(name = "Claimant", value = "{\"username\": \"claimant1\", \"password\": \"password123\"}"),
                            @ExampleObject(name = "Administrator", value = "{\"username\": \"admin1\", \"password\": \"password123\"}")})),
            responses = {
                    @ApiResponse(responseCode = "200", description = "Logged in", content = @Content(
                            mediaType = "application/json", schema = @Schema(implementation = LoginResponse.class),
                            examples = @ExampleObject(value = "{\"accessToken\": \"eyJhbGciOiJIUzI1NiJ9.<payload>.<signature>\", "
                                    + "\"tokenType\": \"Bearer\", \"expiresIn\": 3600, \"username\": \"officer1\", \"role\": \"CLAIMS_OFFICER\"}"))),
                    @ApiResponse(responseCode = "400", description = "Username or password missing or blank (VALIDATION_FAILED), or the body is not valid JSON (MALFORMED_REQUEST)",
                            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
                    @ApiResponse(responseCode = "401", description = "Wrong password or unknown user (INVALID_CREDENTIALS)",
                            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class),
                                    examples = @ExampleObject(value = "{\"status\": 401, \"error\": \"Unauthorized\", \"code\": \"INVALID_CREDENTIALS\", "
                                            + "\"message\": \"Invalid username or password\", \"path\": \"/api/v1/auth/login\"}")))})
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return service.login(request);
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "Register: create a CLAIMANT account",
            description = "Always creates a CLAIMANT (a `role` in the body is ignored). Officers, managers and administrators are "
                    + "created by an administrator with `POST /api/v1/users`. Username: 3 to 50 characters, lower-case letters, digits, "
                    + "`.`, `_` or `-`, starting with a letter or digit. Password: 8 to 72 bytes.",
            security = {},
            responses = {
                    @ApiResponse(responseCode = "201", description = "Account created (the password is never returned)"),
                    @ApiResponse(responseCode = "400", description = "Invalid username or password (VALIDATION_FAILED, PASSWORD_TOO_LONG)",
                            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
                    @ApiResponse(responseCode = "409", description = "The username is taken (USERNAME_TAKEN)",
                            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))})
    public UserResponse register(@Valid @RequestBody RegisterRequest request) {
        return service.register(request.username(), request.password());
    }
}
