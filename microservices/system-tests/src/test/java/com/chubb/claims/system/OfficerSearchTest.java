package com.chubb.claims.system;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OfficerSearchTest extends SystemTestBase {

    private String sgMotorSubmitted;
    private String sgPropertyReview;
    private String auMotorAssigned;

    @BeforeEach
    void seed() throws Exception {
        sgMotorSubmitted = createClaim("SG", "MOTOR", "100.00", "INR");
        sgPropertyReview = createClaim("SG", "PROPERTY", "200.00", "INR");
        review(sgPropertyReview, "off-1");
        auMotorAssigned = createClaim("AU", "MOTOR", "300.00", "AUD");
        assign(auMotorAssigned, "off-2");
    }

    @Test
    void noFiltersReturnsEverythingNewestFirstWithDefaults() throws Exception {
        getJson("/api/v1/officer/claims")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.content[0].claimId").value(auMotorAssigned))
                .andExpect(jsonPath("$.content[2].claimId").value(sgMotorSubmitted));
    }

    @Test
    void filtersByStatus() throws Exception {
        getJson("/api/v1/officer/claims?status=UNDER_REVIEW")
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].claimId").value(sgPropertyReview));
    }

    @Test
    void filtersByMarketTypeAndOfficerAndCombinations() throws Exception {
        getJson("/api/v1/officer/claims?market=SG").andExpect(jsonPath("$.totalElements").value(2));
        getJson("/api/v1/officer/claims?claimType=MOTOR").andExpect(jsonPath("$.totalElements").value(2));
        getJson("/api/v1/officer/claims?assignedOfficerId=off-2")
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].claimId").value(auMotorAssigned));
        getJson("/api/v1/officer/claims?status=SUBMITTED&market=SG")
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].claimId").value(sgMotorSubmitted));
        getJson("/api/v1/officer/claims?status=SUBMITTED&market=SG&claimType=PROPERTY")
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    void paginationSplitsResultsInDatabase() throws Exception {
        getJson("/api/v1/officer/claims?page=0&size=2")
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2));
        getJson("/api/v1/officer/claims?page=1&size=2")
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.content[0].claimId").value(sgMotorSubmitted));
        getJson("/api/v1/officer/claims?page=5&size=2").andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    void summaryItemsHaveWorkloadFieldsOnly() throws Exception {
        getJson("/api/v1/officer/claims?market=AU")
                .andExpect(jsonPath("$.content[0].id").isNumber())
                .andExpect(jsonPath("$.content[0].claimantName").value("Mei Tan"))
                .andExpect(jsonPath("$.content[0].currency").value("AUD"))
                .andExpect(jsonPath("$.content[0].description").doesNotExist());
    }

    @Test
    void invalidQueryParametersReturn400() throws Exception {
        getJson("/api/v1/officer/claims?status=NOPE").andExpect(status().isBadRequest());
        getJson("/api/v1/officer/claims?claimType=BOAT").andExpect(status().isBadRequest());
        getJson("/api/v1/officer/claims?page=-1").andExpect(status().isBadRequest());
        getJson("/api/v1/officer/claims?size=0").andExpect(status().isBadRequest());
        getJson("/api/v1/officer/claims?size=101").andExpect(status().isBadRequest());
        getJson("/api/v1/officer/claims?size=100").andExpect(status().isOk());
        getJson("/api/v1/officer/claims?page=abc").andExpect(status().isBadRequest());
        // sanity: unchanged data
        getJson("/api/v1/officer/claims").andExpect(jsonPath("$.totalElements").value(3));
        postJson("/api/v1/officer/claims/" + sgMotorSubmitted + "/assign", Map.of("officerId", "off-3")).andExpect(status().isOk());
    }
}
