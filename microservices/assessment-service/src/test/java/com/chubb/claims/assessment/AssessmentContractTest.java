package com.chubb.claims.assessment;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;

/** HTTP-contract tests for every JSON POST endpoint of the Assessment Service. None of these may reach the Claim Service. */
class AssessmentContractTest extends IntegrationTestBase {

    static List<String> postEndpoints() {
        return List.of(
                "/api/v1/officer/claims/" + CLAIM + "/review",
                "/api/v1/officer/claims/" + CLAIM + "/decision",
                "/api/v1/officer/claims/" + CLAIM + "/settlement",
                "/api/v1/officer/claims/" + CLAIM + "/information-requests",
                "/api/v1/claims/" + CLAIM + "/information");
    }

    @ParameterizedTest
    @MethodSource("postEndpoints")
    void malformedJsonIs400(String path) throws Exception {
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{ this is not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.status").value(400));
        verifyNoInteractions(claims);
    }

    @ParameterizedTest
    @MethodSource("postEndpoints")
    void missingBodyIs400(String path) throws Exception {
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        verifyNoInteractions(claims);
    }

    @ParameterizedTest
    @MethodSource("postEndpoints")
    void emptyObjectFailsValidationWith400(String path) throws Exception {
        // for an administrator an empty body is invalid everywhere: either a required field is missing, or the officer is not named
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        verifyNoInteractions(claims);
    }

    @ParameterizedTest
    @MethodSource("postEndpoints")
    void wrongContentTypeIs415(String path) throws Exception {
        mvc.perform(post(path).contentType(MediaType.TEXT_PLAIN).content("hello"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.status").value(415));
        verifyNoInteractions(claims);
    }

    @Test
    void unsupportedMethodsAre405() throws Exception {
        mvc.perform(get("/api/v1/officer/claims/" + CLAIM + "/review")).andExpect(status().isMethodNotAllowed());
        mvc.perform(get("/api/v1/officer/claims/" + CLAIM + "/decision")).andExpect(status().isMethodNotAllowed());
        mvc.perform(get("/api/v1/officer/claims/" + CLAIM + "/settlement")).andExpect(status().isMethodNotAllowed());
        mvc.perform(post("/api/v1/officer/claims/" + CLAIM + "/assessments")).andExpect(status().isMethodNotAllowed());
        mvc.perform(put("/api/v1/officer/claims/" + CLAIM + "/assessments")).andExpect(status().isMethodNotAllowed());
        mvc.perform(delete("/api/v1/officer/claims/" + CLAIM + "/assessments")).andExpect(status().isMethodNotAllowed());
        mvc.perform(get("/api/v1/officer/claims/" + CLAIM + "/information-requests")).andExpect(status().isMethodNotAllowed());
        mvc.perform(get("/api/v1/claims/" + CLAIM + "/information")).andExpect(status().isMethodNotAllowed());
        mvc.perform(post("/api/v1/claims/" + CLAIM + "/information-requests")).andExpect(status().isMethodNotAllowed());
        mvc.perform(put("/api/v1/claims/" + CLAIM + "/information-requests")).andExpect(status().isMethodNotAllowed());
        mvc.perform(delete("/api/v1/claims/" + CLAIM + "/information-requests")).andExpect(status().isMethodNotAllowed());
    }

    @Test
    void unknownRouteIs404WithTheCommonErrorShape() throws Exception {
        getJson("/api/v1/nothing-here")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.path").value("/api/v1/nothing-here"));
    }
}
