package com.chubb.claims.common.security;

import java.util.EnumSet;
import java.util.Set;
import org.springframework.http.HttpMethod;

/**
 * One line of a service's access table: which roles may call a public route. The table is plain data, so the same rows
 * that configure Spring Security are what the tests iterate over and what the documentation lists. Service-to-service
 * routes are not listed here: {@code /internal/v1/**} is SERVICE-only for every service (see {@link SecurityConfig}).
 *
 * @param method  the HTTP method, or {@code null} for every method
 * @param pattern an MVC path pattern such as {@code /api/v1/claims/{claimId}}
 * @param open    true when no token is needed (login, registration)
 * @param roles   roles allowed when {@code open} is false
 */
public record AccessRule(HttpMethod method, String pattern, boolean open, Set<Role> roles) {

    /** No token needed. */
    public static AccessRule open(HttpMethod method, String pattern) {
        return new AccessRule(method, pattern, true, Set.of());
    }

    /** The listed roles, plus ADMIN, who has full access to the public API. */
    public static AccessRule roles(HttpMethod method, String pattern, Role... allowed) {
        EnumSet<Role> roles = EnumSet.of(Role.ADMIN, allowed);
        if (roles.contains(Role.SERVICE)) {
            throw new IllegalArgumentException("SERVICE tokens are for /internal/v1 only: " + pattern);
        }
        return new AccessRule(method, pattern, false, Set.copyOf(roles));
    }

    public boolean allows(Role role) {
        return open || roles.contains(role);
    }
}
