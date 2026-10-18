package com.chubb.claims;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** The Swagger UI placeholder "string" is rejected as an officer ID; real-looking IDs are still accepted and unverified. */
class PlaceholderActorTest extends IntegrationTestBase {

    private static final String PLACEHOLDER_ERROR = "must be a real officer ID";

    @Test
    void assignAndReviewRejectThePlaceholder() throws Exception {
        String id = createClaim();
        for (String action : new String[] {"assign", "review"}) {
            for (String value : new String[] {"string", "String", "STRING"}) {
                postJson("/api/v1/officer/claims/" + id + "/" + action, Map.of("officerId", value))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                        .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(PLACEHOLDER_ERROR)));
            }
        }
        getJson("/api/v1/claims/" + id).andExpect(jsonPath("$.assignedOfficerId").doesNotExist());
        getJson("/api/v1/claims/" + id + "/history").andExpect(jsonPath("$.length()").value(1));
        getJson("/api/v1/claims/" + id + "/assignments").andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void informationRequestRejectsThePlaceholder() throws Exception {
        String id = createClaimUnderReview();
        postJson("/api/v1/officer/claims/" + id + "/information-requests",
                Map.of("question", "Photos?", "requestedByOfficerId", "string"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        getJson("/api/v1/claims/" + id).andExpect(jsonPath("$.status").value("UNDER_REVIEW"));
    }

    @Test
    void decisionRejectsThePlaceholderAndKeepsTheClaimUnderReview() throws Exception {
        String id = createClaimUnderReview();
        postJson("/api/v1/officer/claims/" + id + "/decision", Map.of("decision", "REJECTED",
                "decisionReason", "No cover.", "currency", "INR", "decidedByOfficerId", "string"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        getJson("/api/v1/claims/" + id).andExpect(jsonPath("$.status").value("UNDER_REVIEW"));
        getJson("/api/v1/claims/" + id + "/history").andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void idsThatMerelyContainTheWordAreAccepted() throws Exception {
        String id = createClaim();
        postJson("/api/v1/officer/claims/" + id + "/assign", Map.of("officerId", "string-officer-1"))
                .andExpect(status().isOk());
        postJson("/api/v1/officer/claims/" + id + "/assign", Map.of("officerId", "OFF-1"))
                .andExpect(status().isOk());
    }
}
