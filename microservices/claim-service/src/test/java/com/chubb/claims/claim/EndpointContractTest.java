package com.chubb.claims.claim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * HTTP-contract tests that apply to every JSON POST endpoint of the Claim Service: malformed JSON, empty bodies, missing
 * required fields, wrong content type and method, plus the response shapes of the public claim endpoints.
 */
class EndpointContractTest extends IntegrationTestBase {

    /** Every POST endpoint that takes a JSON body, as {path-template}. {id} is replaced by a real claimId. */
    static List<String> postEndpoints() {
        return List.of(
                "/api/v1/claims",
                "/api/v1/officer/claims/{id}/assign",
                "/internal/v1/claims/{id}/review",
                "/internal/v1/claims/{id}/request-information",
                "/internal/v1/claims/{id}/decision",
                "/internal/v1/claims/{id}/settlement");
    }

    private ResultActions rawPost(String path, String contentType, String body) throws Exception {
        var req = post(path);
        if (contentType != null) {
            req = req.contentType(contentType);
        }
        return mvc.perform(req.content(body));
    }

    // ---- malformed requests, on every POST endpoint ----

    @ParameterizedTest
    @MethodSource("postEndpoints")
    void malformedJsonIsRejectedWith400(String template) throws Exception {
        String id = createClaimUnderReview();
        rawPost(template.replace("{id}", id), MediaType.APPLICATION_JSON_VALUE, "{ this is not json")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.status").value(400));
    }

    @ParameterizedTest
    @MethodSource("postEndpoints")
    void missingBodyIsRejectedWith400(String template) throws Exception {
        String id = createClaimUnderReview();
        mvc.perform(post(template.replace("{id}", id)).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @ParameterizedTest
    @MethodSource("postEndpoints")
    void emptyObjectFailsValidationWith400(String template) throws Exception {
        String id = createClaimUnderReview();
        rawPost(template.replace("{id}", id), MediaType.APPLICATION_JSON_VALUE, "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @ParameterizedTest
    @MethodSource("postEndpoints")
    void wrongContentTypeIs415(String template) throws Exception {
        String id = createClaimUnderReview();
        rawPost(template.replace("{id}", id), MediaType.TEXT_PLAIN_VALUE, "hello")
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.status").value(415));
    }

    @Test
    void validationErrorsNameTheOffendingFields() throws Exception {
        rawPost("/api/v1/claims", MediaType.APPLICATION_JSON_VALUE, "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("claimantName")))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("claimedAmount")))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("currency")))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("incidentDate")))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("policyNumber")))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("incidentDescription")));
    }

    @Test
    void blankOverlongAndMalformedFieldsAreRejectedAndNothingIsStored() throws Exception {
        Map<String, Object> blankName = claimBody("SG", "MOTOR", "10.00", "INR");
        blankName.put("claimantName", "   ");
        postJson("/api/v1/claims", blankName).andExpect(status().isBadRequest());

        Map<String, Object> longDescription = claimBody("SG", "MOTOR", "10.00", "INR");
        longDescription.put("incidentDescription", "x".repeat(2001));
        postJson("/api/v1/claims", longDescription).andExpect(status().isBadRequest());

        postJson("/api/v1/claims", claimBody("SG", "MOTOR", "10.001", "INR")).andExpect(status().isBadRequest());

        Map<String, Object> missingType = claimBody("SG", "MOTOR", "10.00", "INR");
        missingType.remove("claimType");
        postJson("/api/v1/claims", missingType).andExpect(status().isBadRequest());

        Map<String, Object> amountAsText = claimBody("SG", "MOTOR", "10.00", "INR");
        amountAsText.put("claimedAmount", "lots");
        postJson("/api/v1/claims", amountAsText).andExpect(status().isBadRequest());

        assertThat(jdbc.queryForObject("select count(*) from claims", Integer.class)).isZero();
    }

    @Test
    void officerIdIsRequiredLengthLimitedAndNotThePlaceholder() throws Exception {
        String id = createClaim();
        String assign = "/api/v1/officer/claims/" + id + "/assign";
        postJson(assign, Map.of("officerId", "")).andExpect(status().isBadRequest());
        postJson(assign, Map.of("officerId", "   ")).andExpect(status().isBadRequest());
        postJson(assign, Map.of("officerId", "O".repeat(51))).andExpect(status().isBadRequest());
        for (String placeholder : new String[] {"string", "String", "STRING"}) {
            postJson(assign, Map.of("officerId", placeholder)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("must be a real officer ID")));
        }
        postJson(assign, Map.of("officerId", "string-officer-1")).andExpect(status().isOk());
        getJson("/api/v1/claims/" + id + "/assignments").andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void clientSuppliedClaimIdAndStatusAreIgnoredNotRejected() throws Exception {
        Map<String, Object> body = claimBody("SG", "MOTOR", "10.00", "INR");
        body.put("claimId", "CLM-HACK-1");
        body.put("status", "SETTLED");
        postJson("/api/v1/claims", body).andExpect(status().isCreated())
                .andExpect(jsonPath("$.claimId").value(org.hamcrest.Matchers.startsWith("CLM-")))
                .andExpect(jsonPath("$.claimId").value(org.hamcrest.Matchers.not("CLM-HACK-1")))
                .andExpect(jsonPath("$.status").value("SUBMITTED"));
    }

    // ---- wrong method / unknown route ----

    @Test
    void unsupportedHttpMethodsAre405() throws Exception {
        String id = createClaim();
        mvc.perform(put("/api/v1/claims/" + id)).andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.status").value(405));
        mvc.perform(delete("/api/v1/claims/" + id)).andExpect(status().isMethodNotAllowed());
        mvc.perform(get("/api/v1/officer/claims/" + id + "/assign")).andExpect(status().isMethodNotAllowed());
        mvc.perform(get(INTERNAL + id + "/review")).andExpect(status().isMethodNotAllowed());
        mvc.perform(get(INTERNAL + id + "/decision")).andExpect(status().isMethodNotAllowed());
        mvc.perform(get(INTERNAL + id + "/settlement")).andExpect(status().isMethodNotAllowed());
        mvc.perform(post(INTERNAL + id + "/status")).andExpect(status().isMethodNotAllowed());
    }

    @Test
    void unknownRouteIs404() throws Exception {
        getJson("/api/v1/nothing-here").andExpect(status().isNotFound());
    }

    // ---- response shapes ----

    @Test
    void createResponseContainsEveryDocumentedField() throws Exception {
        postJson("/api/v1/claims", claimBody("HK", "PROPERTY", "5000.50", "HKD"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.claimId").value(org.hamcrest.Matchers.matchesPattern("CLM-\\d{4}-\\d{6}")))
                .andExpect(jsonPath("$.claimantName").value("Mei Tan"))
                .andExpect(jsonPath("$.claimantEmail").value("mei.tan@example.com"))
                .andExpect(jsonPath("$.market").value("HK"))
                .andExpect(jsonPath("$.claimType").value("PROPERTY"))
                .andExpect(jsonPath("$.incidentDescription").value("Rear bumper damaged in a low-speed collision."))
                .andExpect(jsonPath("$.policyNumber").value("POL-2026-0042"))
                .andExpect(jsonPath("$.claimantId").value("test-admin"))
                .andExpect(jsonPath("$.incidentDate").value("2026-10-12"))
                .andExpect(jsonPath("$.claimedAmount").value(5000.50))
                .andExpect(jsonPath("$.currency").value("HKD"))
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.assignedOfficerId").doesNotExist())
                .andExpect(jsonPath("$.approvedAmount").doesNotExist())
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.updatedAt").exists());
    }

    @Test
    void getClaimReturnsWhatWasCreated() throws Exception {
        var created = read(postJson("/api/v1/claims", claimBody("AU", "MOTOR", "123.45", "AUD")));
        getJson("/api/v1/claims/" + created.get("claimId").asText())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(created.get("id").asLong()))
                .andExpect(jsonPath("$.market").value("AU"))
                .andExpect(jsonPath("$.claimedAmount").value(123.45))
                .andExpect(jsonPath("$.currency").value("AUD"))
                .andExpect(jsonPath("$.status").value("SUBMITTED"));
    }

    @Test
    void assignResponseShapeAndReassignmentBeforeReview() throws Exception {
        String id = createClaim();
        postJson("/api/v1/officer/claims/" + id + "/assign", Map.of("officerId", "OFF-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.claimId").value(id))
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.assignedOfficerId").value("OFF-1"))
                .andExpect(jsonPath("$.updatedAt").exists());
        postJson("/api/v1/officer/claims/" + id + "/assign", Map.of("officerId", "OFF-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedOfficerId").value("OFF-2"));
    }

    @Test
    void assignOnUnknownClaimIs404AndOnATerminalClaimIs409() throws Exception {
        postJson("/api/v1/officer/claims/CLM-2026-999999/assign", Map.of("officerId", "OFF-1"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CLAIM_NOT_FOUND"));
        String id = createClaimUnderReview();
        decide(id, decisionBody("REJECTED", null, "INR", "OFF-1007")).andExpect(status().isOk());
        postJson("/api/v1/officer/claims/" + id + "/assign", Map.of("officerId", "OFF-2"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_CLAIM_TRANSITION"));
    }

    @Test
    void errorBodyHasTheCommonShapeAndNoStackTrace() throws Exception {
        getJson("/api/v1/claims/CLM-2026-999999")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.code").value("CLAIM_NOT_FOUND"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.path").value("/api/v1/claims/CLM-2026-999999"))
                .andExpect(jsonPath("$.trace").doesNotExist());
    }
}
