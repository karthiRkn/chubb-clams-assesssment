package com.chubb.claims.user;

import com.chubb.claims.common.security.Role;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Default users for trying the system, so a fresh start can log in straight away: one per role, all with the same
 * well-known demo password. <b>For demonstration only.</b> Anyone who reads this file or the README can log in as an
 * administrator while this is on, so it must be switched off ({@code DEMO_USERS=false}) for anything that is not a demo.
 *
 * <p>It only creates: a user that already exists is left exactly as it is (the password is never reset), and the
 * password is never written to the log. Off by default in code; the committed {@code application.properties} turns it on
 * for the packaged demo, and the tests turn it off except for the test that checks it.
 */
@Component
@Order(2) // after AdminBootstrap
public class DemoUsers implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoUsers.class);

    /** The usernames and roles created, in this order. */
    static final Map<String, Role> USERS = demoUsers();

    private final UserService service;
    private final UserRepository users;
    private final boolean enabled;
    private final String password;

    public DemoUsers(UserService service, UserRepository users,
                     @Value("${claims.auth.demo-users.enabled:false}") boolean enabled,
                     @Value("${claims.auth.demo-users.password:}") String password) {
        this.service = service;
        this.users = users;
        this.enabled = enabled;
        this.password = password == null ? "" : password;
    }

    private static Map<String, Role> demoUsers() {
        Map<String, Role> m = new LinkedHashMap<>();
        m.put("admin1", Role.ADMIN);
        m.put("officer1", Role.CLAIMS_OFFICER);
        m.put("manager1", Role.MANAGER);
        m.put("claimant1", Role.CLAIMANT);
        return Collections.unmodifiableMap(m);
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        if (password.length() < 8) {
            throw new IllegalStateException("claims.auth.demo-users.password (environment variable DEMO_USERS_PASSWORD) "
                    + "must be at least 8 characters when demo users are enabled");
        }
        int created = 0;
        for (Map.Entry<String, Role> user : USERS.entrySet()) {
            if (!users.existsByUsername(user.getKey())) {
                service.create(user.getKey(), password, user.getValue());
                created++;
            }
        }
        log.warn("DEMO USERS ARE ENABLED ({} created, {} already existed): {}. They share a well-known demo password, so "
                + "anyone can log in as an administrator. Set DEMO_USERS=false for anything that is not a demo.",
                created, USERS.size() - created, String.join(", ", USERS.keySet()));
    }
}
