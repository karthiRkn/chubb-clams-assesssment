package com.chubb.claims.commontest;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

/**
 * A tiny application that scans the shared package exactly as a real service does, with a few probe routes, so the
 * security chain (not any one service's business rules) can be tested here once. Lives outside
 * {@code com.chubb.claims.common} so no service ever scans it.
 */
@SpringBootApplication(scanBasePackages = {"com.chubb.claims.common", "com.chubb.claims.commontest"},
        exclude = UserDetailsServiceAutoConfiguration.class)
public class SecurityTestApp {
}
