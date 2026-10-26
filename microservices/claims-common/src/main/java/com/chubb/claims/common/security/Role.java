package com.chubb.claims.common.security;

/**
 * The roles carried in a token. {@code CLAIMANT}, {@code CLAIMS_OFFICER}, {@code MANAGER} and {@code ADMIN} belong to
 * people and are the only ones the User Service will assign. {@code SERVICE} is the identity one service uses when it
 * calls another; it is never stored for a user and is only accepted on {@code /internal/v1} routes.
 */
public enum Role {
    CLAIMANT,
    CLAIMS_OFFICER,
    MANAGER,
    ADMIN,
    SERVICE;

    /** True for the roles a person can hold (everything except {@link #SERVICE}). */
    public boolean isUserRole() {
        return this != SERVICE;
    }
}
