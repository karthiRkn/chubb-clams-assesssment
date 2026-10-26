package com.chubb.claims.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;

class JwtUtilTest {

    private static final String SECRET = "unit-test-secret-of-at-least-thirty-two-bytes!";
    private static final String OTHER_SECRET = "a-completely-different-secret-for-the-forgery-test";
    private static final Instant NOW = Instant.parse("2026-10-26T10:00:00Z");

    private static JwtUtil util(String secret, String issuer, Instant now) {
        return new JwtUtil(new JwtProperties(secret, issuer, Duration.ofMinutes(60), Duration.ofSeconds(60)),
                Clock.fixed(now, ZoneOffset.UTC));
    }

    private final JwtUtil jwt = util(SECRET, "chubb-claims", NOW);

    @Test
    void aTokenRoundTripsItsSubjectAndRole() {
        for (Role role : Role.values()) {
            if (role.isUserRole()) {
                AuthenticatedUser user = jwt.parse(jwt.generate("someone", role)).orElseThrow();
                assertThat(user).isEqualTo(new AuthenticatedUser("someone", role));
            }
        }
    }

    @Test
    void aServiceTokenCarriesTheServiceRoleAndTheServiceName() {
        assertThat(jwt.parse(jwt.generateServiceToken("assessment-service")))
                .contains(new AuthenticatedUser("assessment-service", Role.SERVICE));
    }

    @Test
    void thePersonTokenMethodCannotMintAServiceToken() {
        assertThatThrownBy(() -> jwt.generate("someone", Role.SERVICE)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aTokenIsRejectedOnceItIsPastItsExpiryPlusTheClockSkew() {
        String token = jwt.generate("someone", Role.CLAIMS_OFFICER);
        assertThat(util(SECRET, "chubb-claims", NOW.plus(Duration.ofMinutes(59))).parse(token)).isPresent();
        // a few seconds late is tolerated for clock differences between machines, a minute late is not
        assertThat(util(SECRET, "chubb-claims", NOW.plus(Duration.ofMinutes(60)).plusSeconds(10)).parse(token)).isPresent();
        assertThat(util(SECRET, "chubb-claims", NOW.plus(Duration.ofMinutes(61))).parse(token)).isEmpty();
    }

    @Test
    void aServiceTokenIsOnlyValidForAboutAMinute() {
        String token = jwt.generateServiceToken("assessment-service");
        assertThat(util(SECRET, "chubb-claims", NOW.plusSeconds(30)).parse(token)).isPresent();
        assertThat(util(SECRET, "chubb-claims", NOW.plusSeconds(120)).parse(token)).isEmpty();
    }

    @Test
    void aTokenSignedWithAnotherHmacAlgorithmIsRejectedEvenWithTheRightKey() {
        // 64 bytes is long enough to sign with HS512; the system only ever issues HS256
        String longSecret = SECRET + SECRET;
        SecretKey key = Keys.hmacShaKeyFor(longSecret.getBytes(StandardCharsets.UTF_8));
        JwtUtil longKeyUtil = util(longSecret, "chubb-claims", NOW);
        String hs512 = Jwts.builder().subject("someone").issuer("chubb-claims").claim("role", "ADMIN")
                .expiration(Date.from(NOW.plusSeconds(600))).signWith(key, Jwts.SIG.HS512).compact();
        assertThat(longKeyUtil.parse(hs512)).isEmpty();
        assertThat(longKeyUtil.parse(longKeyUtil.generate("someone", Role.ADMIN))).isPresent();
    }

    @Test
    void aTokenSignedWithAnotherSecretIsRejected() {
        assertThat(util(OTHER_SECRET, "chubb-claims", NOW).parse(jwt.generate("someone", Role.ADMIN))).isEmpty();
    }

    @Test
    void aTokenFromAnotherIssuerIsRejected() {
        assertThat(util(SECRET, "someone-else", NOW).parse(jwt.generate("someone", Role.ADMIN))).isEmpty();
    }

    @Test
    void aTamperedPayloadIsRejected() {
        String[] parts = jwt.generate("someone", Role.CLAIMANT).split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
                .replace("CLAIMANT", "ADMIN");
        String forged = parts[0] + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8))
                + "." + parts[2];
        assertThat(jwt.parse(forged)).isEmpty();
    }

    @Test
    void anUnsignedTokenWithAlgNoneIsRejected() {
        String unsigned = Jwts.builder().subject("someone").issuer("chubb-claims").claim("role", "ADMIN")
                .expiration(Date.from(NOW.plusSeconds(600))).compact();
        assertThat(unsigned.endsWith(".")).isTrue();
        assertThat(jwt.parse(unsigned)).isEmpty();
    }

    @Test
    void aTokenWithAnUnknownRoleOrNoRoleIsRejected() {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        String unknownRole = Jwts.builder().subject("someone").issuer("chubb-claims").claim("role", "SUPERUSER")
                .expiration(Date.from(NOW.plusSeconds(600))).signWith(key).compact();
        String noRole = Jwts.builder().subject("someone").issuer("chubb-claims")
                .expiration(Date.from(NOW.plusSeconds(600))).signWith(key).compact();
        assertThat(jwt.parse(unknownRole)).isEmpty();
        assertThat(jwt.parse(noRole)).isEmpty();
    }

    @Test
    void aTokenWithoutAnExpiryIsRejected() {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        String forever = Jwts.builder().subject("someone").issuer("chubb-claims").claim("role", "ADMIN").signWith(key).compact();
        // jjwt accepts a token with no exp claim, so the filter must not treat "no expiry" as valid
        assertThat(jwt.parse(forever)).isEmpty();
    }

    @Test
    void garbageIsNotAToken() {
        for (String garbage : new String[] {null, "", "   ", "abc", "a.b.c", "Bearer x"}) {
            assertThat(jwt.parse(garbage)).as("input: %s", garbage).isEmpty();
        }
    }

    @Test
    void aMissingOrShortSecretStopsTheServiceFromStarting() {
        for (String secret : new String[] {null, "", "too-short"}) {
            assertThatThrownBy(() -> util(secret, "chubb-claims", NOW))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("JWT_SECRET");
        }
    }
}
