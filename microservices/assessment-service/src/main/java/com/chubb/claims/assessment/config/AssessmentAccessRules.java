package com.chubb.claims.assessment.config;

import com.chubb.claims.common.security.AccessRule;
import com.chubb.claims.common.security.AccessRules;
import com.chubb.claims.common.security.Role;
import java.util.List;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

/**
 * Who may call the Assessment Service (ADMIN is always included). Review, assessment history, decision, settlement and
 * asking for information are claims-officer work; the assessment history is internal, so a manager and a claimant cannot
 * read it either. Information requests can be read by the claimant (their own claim's), an officer and a manager, and
 * answered only by a claimant. Which claims a person may see is further limited in the service (see {@code ClaimVisibility}).
 */
@Component
public class AssessmentAccessRules implements AccessRules {

    @Override
    public List<AccessRule> rules() {
        return List.of(
                AccessRule.roles(HttpMethod.POST, "/api/v1/officer/claims/{claimId}/review", Role.CLAIMS_OFFICER),
                AccessRule.roles(HttpMethod.GET, "/api/v1/officer/claims/{claimId}/assessments", Role.CLAIMS_OFFICER),
                AccessRule.roles(HttpMethod.POST, "/api/v1/officer/claims/{claimId}/decision", Role.CLAIMS_OFFICER),
                AccessRule.roles(HttpMethod.POST, "/api/v1/officer/claims/{claimId}/settlement", Role.CLAIMS_OFFICER),
                AccessRule.roles(HttpMethod.POST, "/api/v1/officer/claims/{claimId}/information-requests", Role.CLAIMS_OFFICER),
                AccessRule.roles(HttpMethod.GET, "/api/v1/claims/{claimId}/information-requests", Role.CLAIMANT, Role.CLAIMS_OFFICER, Role.MANAGER),
                AccessRule.roles(HttpMethod.POST, "/api/v1/claims/{claimId}/information", Role.CLAIMANT));
    }
}
