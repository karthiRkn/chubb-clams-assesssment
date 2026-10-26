package com.chubb.claims.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import com.chubb.claims.common.security.AccessRule;
import com.chubb.claims.common.security.AccessRules;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpMethod;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Guards against the most likely way access control rots: somebody adds an endpoint and forgets its rule. Every
 * {@code /api/v1} route the service really serves must appear in its {@link AccessRules}, and every rule must belong to
 * a route that exists.
 */
public final class RouteCoverage {

    private RouteCoverage() {
    }

    public static void assertEveryApiRouteHasExactlyOneRule(ApplicationContext context) {
        Set<String> served = new TreeSet<>();
        Map<RequestMappingInfo, ?> handlers = context.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class)
                .getHandlerMethods();
        for (RequestMappingInfo info : handlers.keySet()) {
            if (info.getPathPatternsCondition() == null) {
                continue;
            }
            for (String pattern : info.getPathPatternsCondition().getPatternValues()) {
                if (!pattern.startsWith("/api/v1/")) {
                    continue;
                }
                for (RequestMethod method : info.getMethodsCondition().getMethods()) {
                    served.add(method + " " + pattern);
                }
            }
        }

        List<String> listed = new ArrayList<>();
        context.getBeansOfType(AccessRules.class).values()
                .forEach(rules -> rules.rules().forEach(rule -> listed.add(describe(rule))));

        assertThat(served).as("routes the service serves under /api/v1").isNotEmpty();
        assertThat(listed).as("no route is listed twice").doesNotHaveDuplicates();
        assertThat(listed).as("every served /api/v1 route has an access rule, and no rule is for a route that does not exist")
                .containsExactlyInAnyOrderElementsOf(served);
    }

    private static String describe(AccessRule rule) {
        HttpMethod method = rule.method();
        return (method == null ? "ANY" : method.name()) + " " + rule.pattern();
    }
}
