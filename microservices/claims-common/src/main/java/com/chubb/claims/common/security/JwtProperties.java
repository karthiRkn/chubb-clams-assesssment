package com.chubb.claims.common.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code claims.security.jwt.*}. The secret has no default on purpose: it comes from the {@code JWT_SECRET} environment
 * variable, is shared by every service that must accept the same tokens, and a service refuses to start without it.
 */
@ConfigurationProperties(prefix = "claims.security.jwt")
public record JwtProperties(
        String secret,
        @DefaultValue("chubb-claims") String issuer,
        @DefaultValue("60m") Duration expiry,
        @DefaultValue("60s") Duration serviceTokenLifetime) {
}
