package com.chubb.claims.testsupport;

import com.chubb.claims.common.security.JwtUtil;
import com.chubb.claims.common.security.Role;
import java.util.Locale;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Tokens for tests. Every service's tests build real tokens with the service's own {@link JwtUtil} bean, so they travel
 * through the same filter chain as production traffic; nothing is stubbed.
 *
 * <p>This package is deliberately outside {@code com.chubb.claims.common}: the services component-scan that package, and
 * test helpers must never be picked up by a running service.
 */
public final class TestAuth {

    /** Random for every test JVM, so no signing key is ever written into a file. */
    private static final String SECRET = randomSecret();

    private TestAuth() {
    }

    /** The signing secret every test context of this JVM is started with (pass it as {@code claims.security.jwt.secret}). */
    public static String secret() {
        return SECRET;
    }

    private static String randomSecret() {
        byte[] bytes = new byte[48];
        new java.security.SecureRandom().nextBytes(bytes);
        return java.util.HexFormat.of().formatHex(bytes);
    }

    /** {@code Bearer <token>} for a person with the given role, named {@code test-<role>}. */
    public static String bearer(JwtUtil jwt, Role role) {
        return bearer(jwt, role, "test-" + role.name().toLowerCase(Locale.ROOT));
    }

    /** {@code Bearer <token>} for a named user, e.g. to tell two claimants apart. For SERVICE the name is the calling service. */
    public static String bearer(JwtUtil jwt, Role role, String username) {
        String token = role == Role.SERVICE ? jwt.generateServiceToken(username) : jwt.generate(username, role);
        return "Bearer " + token;
    }

    /**
     * Gives every request that carries no {@code Authorization} header a default identity: SERVICE on
     * {@code /internal/} routes (which only services may call), ADMIN everywhere else. A test that cares about who is
     * calling sets its own header, and one that wants to be anonymous sends an empty {@code Authorization} header.
     */
    public static RequestPostProcessor defaultTo(JwtUtil jwt) {
        return request -> {
            if (request.getHeader(HttpHeaders.AUTHORIZATION) == null) {
                Role role = request.getRequestURI().startsWith("/internal/") ? Role.SERVICE : Role.ADMIN;
                request.addHeader(HttpHeaders.AUTHORIZATION, bearer(jwt, role));
            }
            return request;
        };
    }
}
