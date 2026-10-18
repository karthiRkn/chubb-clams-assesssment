package com.chubb.claims;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UnassignedFilterTest extends IntegrationTestBase {

    private String unassignedOld;
    private String unassignedNew;
    private String assignedOff1;

    @BeforeEach
    void seed() throws Exception {
        unassignedOld = createClaim("SG", "MOTOR", "100.00", "INR");
        assignedOff1 = createClaim("SG", "MOTOR", "200.00", "INR");
        assign(assignedOff1, "OFF-1");
        unassignedNew = createClaim("AU", "PROPERTY", "300.00", "AUD");
    }

    @Test
    void assignedFalseListsOnlyUnassignedClaimsNewestFirst() throws Exception {
        getJson("/api/v1/officer/claims?assigned=false")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].claimId").value(unassignedNew))
                .andExpect(jsonPath("$.content[1].claimId").value(unassignedOld))
                .andExpect(jsonPath("$.content[0].assignedOfficerId").doesNotExist());
    }

    @Test
    void assignedTrueListsOnlyAssignedClaims() throws Exception {
        getJson("/api/v1/officer/claims?assigned=true")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].claimId").value(assignedOff1))
                .andExpect(jsonPath("$.content[0].assignedOfficerId").value("OFF-1"));
    }

    @Test
    void assignedFilterCombinesWithOtherFiltersAndPaging() throws Exception {
        getJson("/api/v1/officer/claims?assigned=false&market=AU")
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].claimId").value(unassignedNew));
        getJson("/api/v1/officer/claims?assigned=false&status=UNDER_REVIEW")
                .andExpect(jsonPath("$.totalElements").value(0));
        getJson("/api/v1/officer/claims?assigned=false&page=0&size=1")
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    void claimDisappearsFromUnassignedOnceAssigned() throws Exception {
        assign(unassignedOld, "OFF-2");
        getJson("/api/v1/officer/claims?assigned=false")
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].claimId").value(unassignedNew));
    }

    @Test
    void assignedTrueWithMatchingOfficerIsAllowed() throws Exception {
        getJson("/api/v1/officer/claims?assigned=true&assignedOfficerId=OFF-1")
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void invalidAssignedValueIs400() throws Exception {
        getJson("/api/v1/officer/claims?assigned=maybe")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    @Test
    void assignedFalseWithOfficerIdIsConflictingFilters400() throws Exception {
        getJson("/api/v1/officer/claims?assigned=false&assignedOfficerId=OFF-1")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CONFLICTING_FILTERS"));
    }

    @Test
    void emptyResultsAreAnEmptyPageNotAnError() throws Exception {
        getJson("/api/v1/officer/claims?assigned=true&market=ZZ")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.totalPages").value(0));
        assign(unassignedOld, "OFF-2");
        assign(unassignedNew, "OFF-2");
        getJson("/api/v1/officer/claims?assigned=false")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(0));
    }
}
