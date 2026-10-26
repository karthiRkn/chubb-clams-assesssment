package com.chubb.claims.user;

import com.chubb.claims.common.security.AccessRule;
import com.chubb.claims.common.security.AccessRules;
import com.chubb.claims.common.security.Role;
import java.util.List;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

/** Who may call the User Service's routes. A rule with no roles listed is ADMIN-only. */
@Component
public class UserAccessRules implements AccessRules {

    @Override
    public List<AccessRule> rules() {
        return List.of(
                AccessRule.open(HttpMethod.POST, "/api/v1/auth/register"),
                AccessRule.open(HttpMethod.POST, "/api/v1/auth/login"),
                AccessRule.roles(HttpMethod.GET, "/api/v1/users/me", Role.CLAIMANT, Role.CLAIMS_OFFICER, Role.MANAGER),
                AccessRule.roles(HttpMethod.POST, "/api/v1/users"),
                AccessRule.roles(HttpMethod.GET, "/api/v1/users"),
                AccessRule.roles(HttpMethod.GET, "/api/v1/users/{username}"),
                AccessRule.roles(HttpMethod.PUT, "/api/v1/users/{username}/role"),
                AccessRule.roles(HttpMethod.PUT, "/api/v1/users/{username}/active"));
    }
}
