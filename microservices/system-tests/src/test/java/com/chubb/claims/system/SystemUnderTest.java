package com.chubb.claims.system;

import com.chubb.claims.assessment.AssessmentServiceApplication;
import com.chubb.claims.claim.ClaimServiceApplication;
import com.chubb.claims.common.security.JwtUtil;
import com.chubb.claims.common.security.Role;
import com.chubb.claims.testsupport.TestAuth;
import com.chubb.claims.user.UserService;
import com.chubb.claims.user.UserServiceApplication;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The three services running together in one JVM, started once for the whole test run. Each one is a separate Spring
 * application context with its own embedded Tomcat on a random port, its own in-memory H2 database and its own Flyway
 * folder, and they call each other over real HTTP exactly as separate processes would: Assessment calls Claim, and Claim
 * calls User (to check that an officer a manager assigns a claim to is an active claims officer).
 *
 * <p>No {@code application.properties} is read (all the modules ship one with the same name, so on a shared classpath
 * only one could be seen); every setting is passed explicitly instead. The services' real configuration files are
 * exercised by starting the actual jars, see the README.
 *
 * <p>All services share one randomly generated JWT secret for the run, and the User Service starts with a bootstrap
 * administrator whose password is also random, so no credential is written into the tests.
 */
final class SystemUnderTest {

    /** The administrator the User Service creates at start-up; tests that need real users log in as this one. */
    static final String ADMIN_USERNAME = "system.admin";
    static final String ADMIN_PASSWORD = java.util.UUID.randomUUID().toString();

    /** The documented demo password (README, Swagger examples): demo users admin1, officer1, manager1 and claimant1 use it. */
    static final String DEMO_PASSWORD = "password123";

    /**
     * The officers the replayed tests assign claims to. A manager (or an administrator) can only assign a claim to a real,
     * active claims officer, so these exist as accounts; they get a random password nobody knows, because the tests act as
     * them with tokens, not by logging in. Login names are lower-case, which is why the tests say {@code off-1}, not {@code OFF-1}.
     */
    static final List<String> OFFICERS = List.of("off-1", "off-2", "off-3", "off-7", "off-9", "off-a", "off-b", "off-c",
            "off-1007", "off-2000", "string-officer-1");

    private static final class Holder {
        static final SystemUnderTest INSTANCE = new SystemUnderTest();
    }

    static SystemUnderTest get() {
        return Holder.INSTANCE;
    }

    final ConfigurableApplicationContext user;
    final ConfigurableApplicationContext claim;
    final ConfigurableApplicationContext assessment;

    final String userUrl;
    final String claimUrl;
    final String assessmentUrl;

    private final List<ConfigurableApplicationContext> started = new ArrayList<>();

    private SystemUnderTest() {
        Runtime.getRuntime().addShutdownHook(new Thread(this::stopAll, "system-under-test-shutdown"));
        user = startWithDatabase(UserServiceApplication.class, "user-service", "sys_user", "user",
                Map.of("claims.auth.bootstrap-admin.username", ADMIN_USERNAME, "claims.auth.bootstrap-admin.password", ADMIN_PASSWORD,
                        "claims.auth.bcrypt-strength", "4",
                        // the packaged defaults: one demo user per role, with the password the README documents
                        "claims.auth.demo-users.enabled", "true", "claims.auth.demo-users.password", DEMO_PASSWORD));
        userUrl = urlOf(user);
        UserService users = user.getBean(UserService.class);
        String unguessable = java.util.UUID.randomUUID().toString();
        OFFICERS.forEach(name -> users.create(name, unguessable, Role.CLAIMS_OFFICER));
        claim = startWithDatabase(ClaimServiceApplication.class, "claim-service", "sys_claim", "claim",
                Map.of("claims.services.user.url", userUrl));
        claimUrl = urlOf(claim);
        assessment = startWithDatabase(AssessmentServiceApplication.class, "assessment-service", "sys_assessment",
                "assessment", Map.of("claims.services.claim.url", claimUrl));
        assessmentUrl = urlOf(assessment);
    }

    /** Tokens for tests, signed with the secret every service of this run shares. */
    JwtUtil jwt() {
        return claim.getBean(JwtUtil.class);
    }

    /** The {@code Authorization} header value for a person with this role (or, for SERVICE, a service token). */
    String authorization(Role role) {
        return TestAuth.bearer(jwt(), role);
    }

    String authorization(Role role, String username) {
        return TestAuth.bearer(jwt(), role, username);
    }

    /**
     * What a request that carries no {@code Authorization} header is sent with: SERVICE on {@code /internal} routes
     * (which only services may call), ADMIN everywhere else. That is what lets the original monolith tests, which know
     * nothing about login, run with little change; tests about who is calling set their own header.
     */
    String defaultAuthorization(String path) {
        return authorization(path.startsWith("/internal/") ? Role.SERVICE : Role.ADMIN);
    }

    /** Where a request path is served, as a gateway in front of the three services would route it (there is none). */
    String baseUrlFor(String path) {
        if (path.startsWith("/api/v1/auth/") || path.equals("/api/v1/users") || path.startsWith("/api/v1/users/")
                || path.equals("/internal/v1/users") || path.startsWith("/internal/v1/users/")) {
            return userUrl;
        }
        if (path.matches("/api/v1/officer/claims/[^/]+/(review|decision|settlement|assessments|information-requests)")
                || path.matches("/api/v1/claims/[^/]+/information-requests")
                || path.matches("/api/v1/claims/[^/]+/information")) {
            return assessmentUrl;
        }
        return claimUrl; // claims, assignment, history, the management reports, /internal/v1/claims
    }

    SystemJdbc jdbc() {
        return new SystemJdbc(claim.getBean(JdbcTemplate.class), assessment.getBean(JdbcTemplate.class),
                user.getBean(JdbcTemplate.class));
    }

    /** A second instance of one service (for failure tests), pointed at the given URLs, owning a private database. */
    ConfigurableApplicationContext startExtra(Class<?> application, String name, String databaseName, String migrations,
                                              Map<String, String> urls) {
        return migrations == null ? start(application, name, urls) : startWithDatabase(application, name, databaseName, migrations, urls);
    }

    /** A URL on localhost where nothing is listening, for simulating a service that is down. */
    static String deadUrl() {
        try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            return "http://localhost:" + socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static String urlOf(ConfigurableApplicationContext context) {
        return "http://localhost:" + ((ServletWebServerApplicationContext) context).getWebServer().getPort();
    }

    private ConfigurableApplicationContext startWithDatabase(Class<?> application, String name, String databaseName,
                                                             String migrations, Map<String, String> extra) {
        Map<String, String> props = new LinkedHashMap<>(extra);
        props.put("spring.datasource.url", "jdbc:h2:mem:" + databaseName
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        props.put("spring.datasource.username", "sa");
        props.put("spring.datasource.password", "");
        props.put("spring.jpa.hibernate.ddl-auto", "validate");
        props.put("spring.jpa.open-in-view", "false");
        props.put("spring.flyway.enabled", "true");
        props.put("spring.flyway.locations", "classpath:db/migration/" + migrations);
        return start(application, name, props);
    }

    private ConfigurableApplicationContext start(Class<?> application, String name, Map<String, String> extra) {
        Map<String, String> props = new LinkedHashMap<>(extra);
        props.put("spring.application.name", name);
        props.put("server.port", "0");
        // do not read any application*.properties from the shared classpath
        props.put("spring.config.name", "none-" + name);
        props.putIfAbsent("claims.downstream.connect-timeout", "2s");
        props.putIfAbsent("claims.downstream.read-timeout", "10s");
        // one secret for the whole run, so a token from one service is accepted by all (a test may override it to simulate a mismatch)
        props.putIfAbsent("claims.security.jwt.secret", TestAuth.secret());
        String[] args = props.entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue()).toArray(String[]::new);
        ConfigurableApplicationContext context = new SpringApplicationBuilder(application).run(args);
        started.add(context);
        return context;
    }

    private void stopAll() {
        List<ConfigurableApplicationContext> copy = new ArrayList<>(started);
        for (int i = copy.size() - 1; i >= 0; i--) {
            try {
                copy.get(i).close();
            } catch (RuntimeException ignored) {
                // shutting down
            }
        }
    }
}
