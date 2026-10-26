package com.chubb.claims.user;

import com.chubb.claims.common.security.Role;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Creates the first administrator at start-up from {@code BOOTSTRAP_ADMIN_USERNAME} and {@code BOOTSTRAP_ADMIN_PASSWORD},
 * so a fresh system can be used without anyone editing the database. It only ever creates: an administrator who already
 * exists is left exactly as it is (the password is never reset from the environment), and the password is never logged.
 */
@Component
@Order(1) // before DemoUsers
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);
    private static final Pattern USERNAME = Pattern.compile(UserDtos.USERNAME_PATTERN);

    private final UserService service;
    private final UserRepository users;
    private final String username;
    private final String password;

    public AdminBootstrap(UserService service, UserRepository users,
                          @Value("${claims.auth.bootstrap-admin.username:}") String username,
                          @Value("${claims.auth.bootstrap-admin.password:}") String password) {
        this.service = service;
        this.users = users;
        this.username = username == null ? "" : username.trim();
        this.password = password == null ? "" : password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (username.isEmpty() && password.isEmpty()) {
            log.info("No bootstrap administrator configured (BOOTSTRAP_ADMIN_USERNAME / BOOTSTRAP_ADMIN_PASSWORD)");
            return;
        }
        if (!USERNAME.matcher(username).matches() || password.length() < 8) {
            throw new IllegalStateException("BOOTSTRAP_ADMIN_USERNAME must be 3-50 lower-case characters and "
                    + "BOOTSTRAP_ADMIN_PASSWORD at least 8 characters; set both or neither");
        }
        if (users.existsByUsername(username)) {
            log.info("Bootstrap administrator '{}' already exists and is left unchanged", username);
            return;
        }
        service.create(username, password, Role.ADMIN);
        log.info("Created bootstrap administrator '{}'", username);
    }
}
