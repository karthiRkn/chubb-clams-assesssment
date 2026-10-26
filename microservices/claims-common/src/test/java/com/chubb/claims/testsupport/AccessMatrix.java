package com.chubb.claims.testsupport;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import com.chubb.claims.common.security.AccessRule;
import com.chubb.claims.common.security.AccessRules;
import com.chubb.claims.common.security.JwtUtil;
import com.chubb.claims.common.security.Role;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Calls every route in a service's {@link AccessRules} as every role and as nobody, and reports each place where the
 * answer is not the one the table promises. Because it reads the same rules the service enforces, a new route with a
 * rule is covered automatically.
 *
 * <p>Only the security verdict is checked, not the business outcome: an allowed caller may still get a 400 or a 404
 * (the body is empty and the id does not exist). What must never happen is a 401 or 403 for an allowed role, a 200-ish
 * answer for a forbidden one, or anything but 401 for a missing token on a protected route.
 */
public final class AccessMatrix {

    private AccessMatrix() {
    }

    /**
     * The service's rule table as readable text, {@code "POST /api/v1/claims" -> "CLAIMANT, ADMIN"} (or {@code "OPEN"}),
     * to be compared with a hand-written expectation in the test. {@link #deviations} proves the service enforces its
     * table; only this proves the table says what the documented role matrix says.
     */
    public static Map<String, String> table(ApplicationContext context) {
        Map<String, String> table = new TreeMap<>();
        for (AccessRules rules : context.getBeansOfType(AccessRules.class).values()) {
            for (AccessRule rule : rules.rules()) {
                String key = (rule.method() == null ? "ANY" : rule.method().name()) + " " + rule.pattern();
                String roles = rule.open() ? "OPEN" : EnumSet.copyOf(rule.roles()).stream().map(Role::name)
                        .collect(java.util.stream.Collectors.joining(", "));
                table.put(key, roles);
            }
        }
        return table;
    }

    /** Every deviation from the table; empty means the service enforces exactly what it lists. */
    public static List<String> deviations(MockMvc mvc, JwtUtil jwt, ApplicationContext context) throws Exception {
        List<String> problems = new ArrayList<>();
        for (AccessRules rules : context.getBeansOfType(AccessRules.class).values()) {
            for (AccessRule rule : rules.rules()) {
                String path = rule.pattern().replaceAll("\\{[^/]+}", "x");
                HttpMethod method = rule.method() == null ? HttpMethod.GET : rule.method();

                // an empty Authorization header is "anonymous" (a missing header would get the test default identity)
                int anonymous = status(mvc, method, path, "");
                if (rule.open() ? denied(anonymous) : anonymous != 401) {
                    problems.add(method + " " + path + " anonymous -> " + anonymous + (rule.open() ? " (should be open)" : " (should be 401)"));
                }
                for (Role role : EnumSet.allOf(Role.class)) {
                    int status = status(mvc, method, path, TestAuth.bearer(jwt, role));
                    if (rule.allows(role) ? denied(status) : status != 403) {
                        problems.add(method + " " + path + " as " + role + " -> " + status
                                + (rule.allows(role) ? " (should be allowed)" : " (should be 403)"));
                    }
                }
            }
        }
        return problems;
    }

    private static boolean denied(int status) {
        return status == 401 || status == 403;
    }

    private static int status(MockMvc mvc, HttpMethod method, String path, String authorization) throws Exception {
        return mvc.perform(request(method, path).header(HttpHeaders.AUTHORIZATION, authorization)
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn().getResponse().getStatus();
    }
}
