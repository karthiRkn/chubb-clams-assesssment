package com.chubb.claims.common.security;

import com.chubb.claims.common.ApiException;

/** Who is calling, as proven by a valid token: the token's subject and its role. Available to controllers as the principal. */
public record AuthenticatedUser(String username, Role role) {

    public boolean is(Role candidate) {
        return role == candidate;
    }

    /**
     * The officer an action is recorded against. A claims officer is always themselves: a request that names a different
     * officer is refused with 403, so an officer cannot act in another officer's name. An administrator, who may act for
     * an officer, must say which officer in the request (400 otherwise, so an administrator can never become the officer
     * of a claim by leaving the field out).
     *
     * @param requestedOfficerId the officer ID the request body carries, or {@code null} when it carries none
     */
    public String actingOfficerId(String requestedOfficerId) {
        boolean named = requestedOfficerId != null && !requestedOfficerId.isBlank();
        if (role == Role.CLAIMS_OFFICER) {
            if (named && !requestedOfficerId.trim().equals(username)) {
                throw ApiException.forbidden("OFFICER_IDENTITY_MISMATCH",
                        "A claims officer can only act as themselves; the officer ID in the request does not match the token");
            }
            return username;
        }
        if (!named) {
            throw ApiException.badRequest("VALIDATION_FAILED", "The officer ID is required when you act for an officer");
        }
        return requestedOfficerId.trim();
    }
}
