package com.chubb.claims;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * HTTP-contract tests that apply to every endpoint: malformed JSON, empty bodies, missing required fields,
 * wrong content type / HTTP method, and full response-body shape for the happy paths.
 */
class EndpointContractTest extends IntegrationTestBase {

    private static final String UNKNOWN = "CLM-2026-999999";

    /** Every POST endpoint that takes a JSON body, as {path-template}. {id} is replaced by a real claimId. */
    static List<String> postEndpoints() {
        return List.of(
                "/api/v1/claims",
                "/api/v1/officer/claims/{id}/assign",
                "/api/v1/officer/claims/{id}/review",
                "/api/v1/officer/claims/{id}/information-requests",
                "/api/v1/claims/{id}/information",
                "/api/v1/officer/claims/{id}/decision");
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
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("incidentDate")));
    }

    @Test
    void blankAndOverlongFieldsAreRejected() throws Exception {
        Map<String, Object> blankName = claimBody("SG", "MOTOR", "10.00", "SGD");
        blankName.put("claimantName", "   ");
        postJson("/api/v1/claims", blankName).andExpect(status().isBadRequest());

        Map<String, Object> longDescription = claimBody("SG", "MOTOR", "10.00", "SGD");
        longDescription.put("description", "x".repeat(2001));
        postJson("/api/v1/claims", longDescription).andExpect(status().isBadRequest());

        Map<String, Object> tooManyDecimals = claimBody("SG", "MOTOR", "10.001", "SGD");
        postJson("/api/v1/claims", tooManyDecimals).andExpect(status().isBadRequest());

        Map<String, Object> missingType = claimBody("SG", "MOTOR", "10.00", "SGD");
        missingType.remove("claimType");
        postJson("/api/v1/claims", missingType).andExpect(status().isBadRequest());

        Map<String, Object> amountAsText = claimBody("SG", "MOTOR", "10.00", "SGD");
        amountAsText.put("claimedAmount", "lots");
        postJson("/api/v1/claims", amountAsText).andExpect(status().isBadRequest());

        assertThat(jdbc.queryForObject("select count(*) from claims", Integer.class)).isZero();
    }

    @Test
    void officerIdIsRequiredAndLengthLimited() throws Exception {
        String id = createClaim();
        postJson("/api/v1/officer/claims/" + id + "/assign", Map.of("officerId", "")).andExpect(status().isBadRequest());
        postJson("/api/v1/officer/claims/" + id + "/assign", Map.of("officerId", "   ")).andExpect(status().isBadRequest());
        postJson("/api/v1/officer/claims/" + id + "/assign", Map.of("officerId", "O".repeat(51))).andExpect(status().isBadRequest());
        postJson("/api/v1/officer/claims/" + id + "/review", Map.of("officerId", "")).andExpect(status().isBadRequest());
        getJson("/api/v1/claims/" + id).andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.assignedOfficerId").doesNotExist());
    }

    @Test
    void clientSuppliedClaimIdIsIgnoredNotRejected() throws Exception {
        Map<String, Object> body = claimBody("SG", "MOTOR", "10.00", "SGD");
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
        mvc.perform(get("/api/v1/officer/claims/" + id + "/decision")).andExpect(status().isMethodNotAllowed());
        mvc.perform(post("/api/v1/management/summary")).andExpect(status().isMethodNotAllowed());
    }

    @Test
    void unknownRouteIs404() throws Exception {
        getJson("/api/v1/nothing-here").andExpect(status().isNotFound());
    }

    // ---- full response shapes ----

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
                .andExpect(jsonPath("$.description").value("Rear bumper damaged in a low-speed collision."))
                .andExpect(jsonPath("$.incidentDate").value("2026-10-12"))
                .andExpect(jsonPath("$.claimedAmount").value(5000.50))
                .andExpect(jsonPath("$.currency").value("HKD"))
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.assignedOfficerId").doesNotExist())
                .andExpect(jsonPath("$.settledAmount").doesNotExist())
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
    void informationRequestListIsEmptyForClaimWithNone() throws Exception {
        String id = createClaim();
        getJson("/api/v1/claims/" + id + "/information-requests")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void informationRequestsAreScopedToTheirOwnClaim() throws Exception {
        String a = createClaimUnderReview();
        String b = createClaimUnderReview();
        postJson("/api/v1/officer/claims/" + a + "/information-requests",
                Map.of("question", "Q for A", "requestedByOfficerId", "OFF-1007")).andExpect(status().isCreated());
        getJson("/api/v1/claims/" + a + "/information-requests").andExpect(jsonPath("$.length()").value(1));
        getJson("/api/v1/claims/" + b + "/information-requests").andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void answerWithUnknownRequestIdOnUnknownClaimIs404() throws Exception {
        postJson("/api/v1/claims/" + UNKNOWN + "/information",
                Map.of("informationRequestId", "IR-2026-999999", "answer", "A"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CLAIM_NOT_FOUND"));
    }

    @Test
    void answerOnClaimThatIsNotAwaitingInformationIs409() throws Exception {
        // request exists but the claim has moved on (decided), so an answer is a state conflict
        String id = createClaimUnderReview();
        String irId = read(postJson("/api/v1/officer/claims/" + id + "/information-requests",
                Map.of("question", "Q", "requestedByOfficerId", "OFF-1007"))).get("informationRequestId").asText();
        postJson("/api/v1/claims/" + id + "/information", Map.of("informationRequestId", irId, "answer", "A"))
                .andExpect(status().isOk());
        postJson("/api/v1/officer/claims/" + id + "/decision", decision("REJECTED", null))
                .andExpect(status().isOk());
        postJson("/api/v1/claims/" + id + "/information", Map.of("informationRequestId", irId, "answer", "late"))
                .andExpect(status().isConflict());
    }

    @Test
    void decisionResponseShapeForSettlementAndRejection() throws Exception {
        String settled = createClaimUnderReview();
        postJson("/api/v1/officer/claims/" + settled + "/decision", decision("SETTLED", "1999.99"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.claimId").value(settled))
                .andExpect(jsonPath("$.status").value("SETTLED"))
                .andExpect(jsonPath("$.decisionReason").value("Reason"))
                .andExpect(jsonPath("$.settledAmount").value(1999.99))
                .andExpect(jsonPath("$.currency").value("SGD"))
                .andExpect(jsonPath("$.decidedByOfficerId").value("OFF-1007"))
                .andExpect(jsonPath("$.decidedAt").exists())
                .andExpect(jsonPath("$.updatedAt").exists());

        String rejected = createClaimUnderReview();
        postJson("/api/v1/officer/claims/" + rejected + "/decision", decision("REJECTED", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.decisionReason").value("Reason"));
    }

    @Test
    void decisionOnUnknownClaimWithInvalidBodyPrefersValidationError() throws Exception {
        postJson("/api/v1/officer/claims/" + UNKNOWN + "/decision", Map.of("decision", "SETTLED"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invalidTransitionsReturn409WithStableErrorCode() throws Exception {
        String id = createClaim();
        // SUBMITTED cannot go straight to a decision or to an information request
        postJson("/api/v1/officer/claims/" + id + "/decision", decision("REJECTED", null))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_CLAIM_TRANSITION"))
                .andExpect(jsonPath("$.status").value(409));
        postJson("/api/v1/officer/claims/" + id + "/information-requests",
                Map.of("question", "Q", "requestedByOfficerId", "OFF-1"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_CLAIM_TRANSITION"));
        // SUBMITTED claims have no open request to answer
        postJson("/api/v1/claims/" + id + "/information", Map.of("informationRequestId", "IR-2026-000001", "answer", "A"))
                .andExpect(status().isNotFound());
        getJson("/api/v1/claims/" + id).andExpect(jsonPath("$.status").value("SUBMITTED"));
    }

    // ---- management summary ----

    @Test
    void summaryHasNoteAndTimestampAndReflectsDecisions() throws Exception {
        String keep = createClaim("SG", "MOTOR", "100.00", "SGD");
        String decide = createClaimUnderReview();
        postJson("/api/v1/officer/claims/" + decide + "/decision", decision("REJECTED", null)).andExpect(status().isOk());

        getJson("/api/v1/management/summary")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generatedAt").exists())
                .andExpect(jsonPath("$.liabilityNote").value(org.hamcrest.Matchers.containsString("Not an actuarial reserve")))
                .andExpect(jsonPath("$.outstandingClaimCount").value(1))
                .andExpect(jsonPath("$.countsByStatus.SUBMITTED").value(1))
                .andExpect(jsonPath("$.countsByStatus.REJECTED").value(1))
                .andExpect(jsonPath("$.outstandingLiabilityByCurrency.length()").value(1))
                .andExpect(jsonPath("$.outstandingLiabilityByCurrency[0].currency").value("SGD"))
                .andExpect(jsonPath("$.outstandingLiabilityByCurrency[0].estimatedLiability").value(100.00))
                .andExpect(jsonPath("$.outstandingLiabilityByCurrency[0].claimCount").value(1));
        assertThat(keep).startsWith("CLM-");
    }

    @Test
    void awaitingInformationClaimsCountAsOutstandingLiability() throws Exception {
        String id = createClaimUnderReview();
        postJson("/api/v1/officer/claims/" + id + "/information-requests",
                Map.of("question", "Q", "requestedByOfficerId", "OFF-1007")).andExpect(status().isCreated());
        getJson("/api/v1/management/summary")
                .andExpect(jsonPath("$.outstandingClaimCount").value(1))
                .andExpect(jsonPath("$.countsByStatus.AWAITING_INFORMATION").value(1))
                .andExpect(jsonPath("$.outstandingLiabilityByCurrency[0].estimatedLiability").value(2400.00));
    }

    @Test
    void summarySumsExactDecimalsPerCurrency() throws Exception {
        createClaim("SG", "MOTOR", "0.10", "SGD");
        createClaim("SG", "MOTOR", "0.20", "SGD");
        createClaim("US", "MOTOR", "0.30", "USD");
        var summary = read(getJson("/api/v1/management/summary"));
        Map<String, BigDecimal> byCurrency = new LinkedHashMap<>();
        summary.get("outstandingLiabilityByCurrency")
                .forEach(n -> byCurrency.put(n.get("currency").asText(), n.get("estimatedLiability").decimalValue()));
        assertThat(byCurrency).hasSize(2);
        assertThat(byCurrency.get("SGD")).isEqualByComparingTo("0.30");
        assertThat(byCurrency.get("USD")).isEqualByComparingTo("0.30");
    }

    // ---- helpers ----

    private Map<String, Object> decision(String decision, String settledAmount) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("decision", decision);
        b.put("decisionReason", "Reason");
        if (settledAmount != null) {
            b.put("settledAmount", new BigDecimal(settledAmount));
        }
        b.put("currency", "SGD");
        b.put("decidedByOfficerId", "OFF-1007");
        return b;
    }
}
