package com.chubb.claims.claim.config;

import com.chubb.claims.common.security.AccessRule;
import com.chubb.claims.common.security.AccessRules;
import com.chubb.claims.common.security.Role;
import java.util.List;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

/**
 * Who may call the Claim Service's public routes (ADMIN is always included). What a person may see of the claims is further
 * limited in the service, because that needs the claim itself: a claimant sees only their own claims and a claims officer
 * only unassigned claims and their own (see {@code ClaimVisibility}); anything outside that answers like an unknown claim.
 * {@code /internal/v1/**} is SERVICE-only for every service and is not listed here.
 */
@Component
public class ClaimAccessRules implements AccessRules {

    @Override
    public List<AccessRule> rules() {
        return List.of(
                AccessRule.roles(HttpMethod.POST, "/api/v1/claims", Role.CLAIMANT),
                AccessRule.roles(HttpMethod.GET, "/api/v1/claims", Role.CLAIMANT, Role.CLAIMS_OFFICER, Role.MANAGER),
                AccessRule.roles(HttpMethod.GET, "/api/v1/claims/{claimId}", Role.CLAIMANT, Role.CLAIMS_OFFICER, Role.MANAGER),
                AccessRule.roles(HttpMethod.PATCH, "/api/v1/claims/{claimId}", Role.CLAIMANT),
                AccessRule.roles(HttpMethod.GET, "/api/v1/claims/{claimId}/history", Role.CLAIMANT, Role.CLAIMS_OFFICER, Role.MANAGER),
                AccessRule.roles(HttpMethod.GET, "/api/v1/claims/{claimId}/assignments", Role.CLAIMANT, Role.CLAIMS_OFFICER, Role.MANAGER),
                AccessRule.roles(HttpMethod.GET, "/api/v1/claims/{claimId}/updates", Role.CLAIMANT, Role.CLAIMS_OFFICER, Role.MANAGER),
                AccessRule.roles(HttpMethod.GET, "/api/v1/officer/claims", Role.CLAIMS_OFFICER, Role.MANAGER),
                AccessRule.roles(HttpMethod.POST, "/api/v1/officer/claims/{claimId}/assign", Role.CLAIMS_OFFICER, Role.MANAGER),
                AccessRule.roles(HttpMethod.GET, "/api/v1/management/summary", Role.MANAGER),
                AccessRule.roles(HttpMethod.GET, "/api/v1/management/officers", Role.MANAGER));
    }
}
