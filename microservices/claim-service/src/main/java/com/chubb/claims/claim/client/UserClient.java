package com.chubb.claims.claim.client;

import com.chubb.claims.common.ApiException;
import com.chubb.claims.common.DownstreamClient;
import org.springframework.http.HttpStatus;

/**
 * What the Claim Service needs from the User Service: whether a person is an active claims officer, so a manager cannot
 * assign a claim to somebody who is not (a claimant, a typo, an account that has been switched off). It is the only call
 * the Claim Service makes. Only the fields used here are declared, and the role stays text so a new role cannot break parsing.
 */
public class UserClient {

    private static final String CLAIMS_OFFICER = "CLAIMS_OFFICER";

    private record UserView(String username, String role, boolean active) {
    }

    private final DownstreamClient http;

    public UserClient(DownstreamClient http) {
        this.http = http;
    }

    /**
     * True only for an existing, active user whose role is CLAIMS_OFFICER. An unknown user is {@code false}, not an error.
     * The User Service being unreachable is an error (503 {@code DOWNSTREAM_UNAVAILABLE}); the assignment is not guessed.
     */
    public boolean isActiveClaimsOfficer(String username) {
        try {
            UserView user = http.get(UserView.class, "/internal/v1/users/{username}", username);
            return user != null && user.active() && CLAIMS_OFFICER.equals(user.role());
        } catch (ApiException e) {
            if (e.getStatus() == HttpStatus.NOT_FOUND && "USER_NOT_FOUND".equals(e.getCode())) {
                return false;
            }
            throw e;
        }
    }
}
