package com.chubb.claims.claim.client;

import com.chubb.claims.common.ApiException;
import com.chubb.claims.common.DownstreamClient;
import java.util.List;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;

/**
 * What the Claim Service needs from the User Service: whether a person is an active claims officer, so a manager cannot
 * assign a claim to somebody who is not (a claimant, a typo, an account that has been switched off), and which active claims
 * officers exist, so the officer workload report lists an officer who has no claims. These are the only calls the Claim
 * Service makes. Only the fields used here are declared, and the role stays text so a new role cannot break parsing.
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

    /**
     * The login names of every active claims officer, as the User Service lists them. The role and flag are checked again here
     * (as in {@link #isActiveClaimsOfficer}), so the answer stays right whatever the other service does with the query. An
     * unreachable User Service is an error (503 {@code DOWNSTREAM_UNAVAILABLE}), never an empty list: a report that silently
     * lacks its officers is the fault this call exists to prevent.
     */
    public List<String> activeClaimsOfficers() {
        List<UserView> found = http.get(new ParameterizedTypeReference<List<UserView>>() { },
                "/internal/v1/users?role={role}&active={active}", CLAIMS_OFFICER, true);
        if (found == null) {
            return List.of();
        }
        return found.stream().filter(u -> u.active() && CLAIMS_OFFICER.equals(u.role())).map(UserView::username).toList();
    }
}
