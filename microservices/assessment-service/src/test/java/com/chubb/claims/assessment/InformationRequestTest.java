package com.chubb.claims.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chubb.claims.common.ApiException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Information requests: the officer asks, the claimant answers (formerly the Information Service, now part of this one). */
class InformationRequestTest extends IntegrationTestBase {

    // ---- POST /api/v1/officer/claims/{claimId}/information-requests ----

    @Test
    void requestStoresAnOpenRequestAndAsksTheClaimServiceForTheTransition() throws Exception {
        ask(CLAIM, "  Please provide the repair estimate.  ", "OFF-1007")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.informationRequestId").value(org.hamcrest.Matchers.matchesPattern("IR-\\d{4}-\\d{6}")))
                .andExpect(jsonPath("$.claimId").value(CLAIM))
                .andExpect(jsonPath("$.question").value("Please provide the repair estimate."))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.requestedByOfficerId").value("OFF-1007"))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.answer").doesNotExist());
        verify(claims).requestInformation(CLAIM, "OFF-1007");
        assertThat(informationRows()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select claim_id from information_requests", String.class)).isEqualTo(CLAIM);
    }

    @Test
    void requestRefusedByTheClaimServiceStoresNothing() throws Exception {
        doThrow(ApiException.conflict("INVALID_CLAIM_TRANSITION",
                "Information can only be requested for a claim under review, but claim is SUBMITTED"))
                .when(claims).requestInformation(eq(CLAIM), any());
        ask(CLAIM, "Q", "OFF-1")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_CLAIM_TRANSITION"))
                .andExpect(jsonPath("$.path").value("/api/v1/officer/claims/" + CLAIM + "/information-requests"));
        assertThat(informationRows()).isZero();
    }

    @Test
    void requestByAnOfficerWhoIsNotTheAssignedOneIsRefusedByTheClaimServiceAndStoresNothing() throws Exception {
        doThrow(ApiException.conflict("OFFICER_MISMATCH", "Claim is assigned to OFF-1007, not OFF-1"))
                .when(claims).requestInformation(eq(CLAIM), eq("OFF-1"));
        ask(CLAIM, "Q", "OFF-1").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OFFICER_MISMATCH"));
        assertThat(informationRows()).isZero();
    }

    @Test
    void requestForAnUnknownClaimIs404AndStoresNothing() throws Exception {
        ask(UNKNOWN, "Q", "OFF-1").andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CLAIM_NOT_FOUND"));
        assertThat(informationRows()).isZero();
    }

    @Test
    void requestWhenTheClaimServiceIsDownIs503AndStoresNothing() throws Exception {
        doThrow(ApiException.unavailable("DOWNSTREAM_UNAVAILABLE", "Claim Service is unavailable or did not answer in time"))
                .when(claims).requestInformation(eq(CLAIM), any());
        ask(CLAIM, "Q", "OFF-1")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DOWNSTREAM_UNAVAILABLE"));
        assertThat(informationRows()).isZero();
    }

    @Test
    void invalidRequestBodiesAre400AndTheClaimServiceIsNotCalled() throws Exception {
        ask(CLAIM, "", "OFF-1").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        ask(CLAIM, "Q", "string").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("must be a real officer ID")));
        ask(CLAIM, "x".repeat(2001), "OFF-1").andExpect(status().isBadRequest());
        postJson("/api/v1/officer/claims/" + CLAIM + "/information-requests", Map.of()).andExpect(status().isBadRequest());
        verifyNoInteractions(claims);
        assertThat(informationRows()).isZero();
    }

    // ---- GET /api/v1/claims/{claimId}/information-requests ----

    @Test
    void listReturnsTheClaimsRequestsOldestFirstAndOnlyThoseOfThatClaim() throws Exception {
        String first = askAndGetId(CLAIM);
        String second = read(ask(CLAIM, "Photos please", "OFF-1007")).get("informationRequestId").asText();
        askAndGetId("CLM-2026-000102");

        getJson("/api/v1/claims/" + CLAIM + "/information-requests")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].informationRequestId").value(first))
                .andExpect(jsonPath("$[0].claimId").value(CLAIM))
                .andExpect(jsonPath("$[0].status").value("OPEN"))
                .andExpect(jsonPath("$[1].informationRequestId").value(second));
    }

    @Test
    void listIsEmptyForAClaimWithNoRequestsAnd404ForAnUnknownClaim() throws Exception {
        getJson("/api/v1/claims/" + CLAIM + "/information-requests").andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        getJson("/api/v1/claims/" + UNKNOWN + "/information-requests")
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CLAIM_NOT_FOUND"));
    }

    // ---- POST /api/v1/claims/{claimId}/information ----

    @Test
    void answeringTheLastOpenRequestSavesTheAnswerAndResumesTheClaimNamingTheAnswerer() throws Exception {
        String irId = askAndGetId(CLAIM);
        claimIs(CLAIM, "MORE_INFO_REQUIRED");

        answer(CLAIM, irId, "  Estimate is 2,200 INR.  ")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.informationRequestId").value(irId))
                .andExpect(jsonPath("$.claimId").value(CLAIM))
                .andExpect(jsonPath("$.status").value("ANSWERED"))
                .andExpect(jsonPath("$.answer").value("Estimate is 2,200 INR."))
                .andExpect(jsonPath("$.answeredAt").exists());
        verify(claims, times(1)).resumeAfterInformation(CLAIM, "test-admin");
        assertThat(statusOf(irId)).isEqualTo("ANSWERED");
    }

    @Test
    void answeringOneOfSeveralOpenRequestsDoesNotResumeTheClaim() throws Exception {
        String first = askAndGetId(CLAIM);
        askAndGetId(CLAIM); // the real Claim Service would refuse a second request; the mock accepts it, so two are OPEN
        claimIs(CLAIM, "MORE_INFO_REQUIRED");

        answer(CLAIM, first, "A1").andExpect(status().isOk());
        verify(claims, never()).resumeAfterInformation(anyString(), any());
        assertThat(jdbc.queryForObject("select count(*) from information_requests where status = 'OPEN'", Integer.class)).isEqualTo(1);
    }

    @Test
    void answerErrorsUseTheDocumentedPrecedence() throws Exception {
        String irId = askAndGetId(CLAIM);
        claimIs(CLAIM, "MORE_INFO_REQUIRED");

        // claim 404 before request 404
        answer(UNKNOWN, irId, "A").andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CLAIM_NOT_FOUND"));
        answer(UNKNOWN, "IR-2026-999999", "A").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CLAIM_NOT_FOUND"));
        // unknown request, and a request that belongs to another claim
        answer(CLAIM, "IR-2026-999999", "A").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INFORMATION_REQUEST_NOT_FOUND"));
        answer("CLM-2026-000102", irId, "A").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INFORMATION_REQUEST_NOT_FOUND"));
        // validation
        answer(CLAIM, irId, "").andExpect(status().isBadRequest());
        postJson("/api/v1/claims/" + CLAIM + "/information", Map.of("answer", "A")).andExpect(status().isBadRequest());

        assertThat(statusOf(irId)).isEqualTo("OPEN");
        verify(claims, never()).resumeAfterInformation(anyString(), any());
    }

    @Test
    void answerWhenTheClaimIsNotWaitingForInformationIs409AndTheRequestStaysOpen() throws Exception {
        String irId = askAndGetId(CLAIM);
        claimIs(CLAIM, "UNDER_REVIEW");
        answer(CLAIM, irId, "A").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_CLAIM_TRANSITION"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("claim is UNDER_REVIEW")));
        assertThat(statusOf(irId)).isEqualTo("OPEN");
    }

    @Test
    void answeringAnAlreadyAnsweredRequestIs409() throws Exception {
        String irId = askAndGetId(CLAIM);
        claimIs(CLAIM, "MORE_INFO_REQUIRED");
        answer(CLAIM, irId, "First").andExpect(status().isOk());
        answer(CLAIM, irId, "Second").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INFORMATION_REQUEST_ALREADY_ANSWERED"));
        assertThat(jdbc.queryForObject("select answer from information_requests", String.class)).isEqualTo("First");
    }

    @Test
    void ifTheClaimServiceRefusesTheResumeTheAnswerIsRolledBackAndCanBeRetried() throws Exception {
        String irId = askAndGetId(CLAIM);
        claimIs(CLAIM, "MORE_INFO_REQUIRED");
        doThrow(ApiException.unavailable("DOWNSTREAM_UNAVAILABLE", "Claim Service is unavailable or did not answer in time"))
                .doNothing()
                .when(claims).resumeAfterInformation(eq(CLAIM), any());

        answer(CLAIM, irId, "A").andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DOWNSTREAM_UNAVAILABLE"));
        assertThat(statusOf(irId)).isEqualTo("OPEN");
        assertThat(jdbc.queryForObject("select answer from information_requests", String.class)).isNull();

        answer(CLAIM, irId, "A").andExpect(status().isOk());
        assertThat(statusOf(irId)).isEqualTo("ANSWERED");
    }

    @Test
    void twoConcurrentAnswersToOneRequestOnlyOneIsAccepted() throws Exception {
        String irId = askAndGetId(CLAIM);
        claimIs(CLAIM, "MORE_INFO_REQUIRED");
        // keep the winner inside its transaction (holding the row lock) long enough for the loser to arrive
        doAnswer(inv -> {
            Thread.sleep(500);
            return null;
        }).when(claims).resumeAfterInformation(eq(CLAIM), any());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (String text : List.of("First answer", "Second answer")) {
                Callable<Integer> call = () -> {
                    go.await();
                    return answer(CLAIM, irId, text).andReturn().getResponse().getStatus();
                };
                futures.add(pool.submit(call));
            }
            go.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : futures) {
                statuses.add(f.get(30, TimeUnit.SECONDS));
            }
            assertThat(statuses.stream().filter(s -> s == 200).count()).as("statuses: " + statuses).isEqualTo(1);
            assertThat(statuses.stream().filter(s -> s == 409).count()).as("statuses: " + statuses).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        verify(claims, times(1)).resumeAfterInformation(eq(CLAIM), any());
        assertThat(statusOf(irId)).isEqualTo("ANSWERED");
    }

    // ---- the whole loop, with the real review check ----

    @Test
    void anOpenRequestStopsAReviewFromResumingAndAnAnsweredOneDoesNot() throws Exception {
        String irId = askAndGetId(CLAIM);
        review(CLAIM, "OFF-1007", null).andExpect(status().isOk());
        verify(claims).startReview(CLAIM, "OFF-1007", true, false);

        claimIs(CLAIM, "MORE_INFO_REQUIRED");
        answer(CLAIM, irId, "Done.").andExpect(status().isOk());
        review(CLAIM, "OFF-1007", null).andExpect(status().isOk());
        verify(claims).startReview(CLAIM, "OFF-1007", false, false);
    }

    @Test
    void theOldInternalOpenRequestRouteIsGoneBecauseTheCheckIsNowLocal() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/internal/v1/claims/" + CLAIM + "/information-requests/open")
                        .header(org.springframework.http.HttpHeaders.AUTHORIZATION, as(com.chubb.claims.common.security.Role.SERVICE)))
                .andExpect(status().isNotFound());
    }
}
