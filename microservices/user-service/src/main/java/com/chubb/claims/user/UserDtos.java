package com.chubb.claims.user;

import com.chubb.claims.common.security.Role;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;

public final class UserDtos {

    /** Lower-case only, so "Admin" and "admin" can never be two people; also safe to use in a URL path. */
    public static final String USERNAME_PATTERN = "^[a-z0-9][a-z0-9._-]{2,49}$";
    public static final String USERNAME_MESSAGE =
            "must be 3-50 characters: lower-case letters, digits, '.', '_' or '-', starting with a letter or digit";

    private UserDtos() {
    }

    public record RegisterRequest(
            @NotBlank @Pattern(regexp = USERNAME_PATTERN, message = USERNAME_MESSAGE) String username,
            @NotBlank @Size(min = 8, max = 72) String password) {
    }

    public record CreateUserRequest(
            @NotBlank @Pattern(regexp = USERNAME_PATTERN, message = USERNAME_MESSAGE) String username,
            @NotBlank @Size(min = 8, max = 72) String password,
            @NotNull Role role) {
    }

    public record ChangeRoleRequest(@NotNull Role role) {
    }

    public record SetActiveRequest(@NotNull Boolean active) {
    }

    /** No length rule beyond a sane upper bound: a wrong password must be a 401, not a different kind of error. */
    @Schema(description = "Credentials to log in with")
    public record LoginRequest(
            @Schema(description = "Login name (not case sensitive)", example = "officer1") @NotBlank @Size(max = 100) String username,
            @Schema(description = "Password", example = "password123", format = "password") @NotBlank @Size(max = 128) String password) {
    }

    @Schema(description = "The token to send on every other call")
    public record LoginResponse(
            @Schema(description = "Signed JWT. Send as `Authorization: Bearer <accessToken>`") String accessToken,
            @Schema(description = "Always Bearer", example = "Bearer") String tokenType,
            @Schema(description = "Seconds until the token expires", example = "3600") long expiresIn,
            @Schema(description = "The logged-in user", example = "officer1") String username,
            @Schema(description = "The role the token carries", example = "CLAIMS_OFFICER") Role role) {
    }

    /** Never carries the password or its hash. */
    public record UserResponse(String username, Role role, boolean active, Instant createdAt) {
        public static UserResponse from(UserAccount u) {
            return new UserResponse(u.getUsername(), u.getRole(), u.isActive(), u.getCreatedAt());
        }
    }

    /**
     * What another service may ask about a person ({@code /internal/v1/users/{username}}): who they are and whether they
     * can act. The Claim Service uses it to check that an officer a claim is assigned to is a real, active claims officer.
     */
    public record UserView(String username, Role role, boolean active) {
        public static UserView from(UserAccount u) {
            return new UserView(u.getUsername(), u.getRole(), u.isActive());
        }
    }

    public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
        public static <T> PageResponse<T> from(Page<T> p) {
            return new PageResponse<>(p.getContent(), p.getNumber(), p.getSize(), p.getTotalElements(), p.getTotalPages());
        }
    }
}
