package com.chubb.claims.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.chubb.claims.testsupport.AccessMatrix;
import com.chubb.claims.testsupport.RouteCoverage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/** Every route against every role, driven by the same table the service enforces. */
class UserSecurityTest extends IntegrationTestBase {

    @Autowired
    ApplicationContext context;

    @Test
    void everyRouteIsAllowedToExactlyTheRolesItsRuleListsAndToNobodyElse() throws Exception {
        assertThat(AccessMatrix.deviations(mvc, jwt, context)).isEmpty();
    }

    /** Written by hand from the documented role matrix, not derived from the code (see ClaimSecurityTest). */
    @Test
    void theRuleTableIsTheDocumentedRoleMatrix() {
        assertThat(AccessMatrix.table(context)).isEqualTo(java.util.Map.of(
                "POST /api/v1/auth/register", "OPEN",
                "POST /api/v1/auth/login", "OPEN",
                "GET /api/v1/users/me", "CLAIMANT, CLAIMS_OFFICER, MANAGER, ADMIN",
                "POST /api/v1/users", "ADMIN",
                "GET /api/v1/users", "ADMIN",
                "GET /api/v1/users/{username}", "ADMIN",
                "PUT /api/v1/users/{username}/role", "ADMIN",
                "PUT /api/v1/users/{username}/active", "ADMIN"));
    }

    @Test
    void everyApiRouteHasAnAccessRule() {
        RouteCoverage.assertEveryApiRouteHasExactlyOneRule(context);
    }
}
