package com.chubb.claims.claim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chubb.claims.common.security.Role;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** A claimant correcting their own claim: PATCH /api/v1/claims/{claimId}, and the edit history it leaves. */
class ClaimUpdateTest extends IntegrationTestBase {

    private static final String ALICE = "claimant.alice";

    private String aliceClaim() throws Exception {
        String id = read(mvc.perform(post("/api/v1/claims").header(HttpHeaders.AUTHORIZATION, as(Role.CLAIMANT, ALICE))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(claimBody("SG", "MOTOR", "2400.00", "INR")))))
                .get("claimId").asText();
        return id;
    }

    private ResultActions patchAs(String authorization, String claimId, Object body) throws Exception {
        return mvc.perform(patch("/api/v1/claims/" + claimId).header(HttpHeaders.AUTHORIZATION, authorization)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private ResultActions patchAsAlice(String claimId, Object body) throws Exception {
        return patchAs(as(Role.CLAIMANT, ALICE), claimId, body);
    }

    private ResultActions updatesOf(String claimId) throws Exception {
        return mvc.perform(get("/api/v1/claims/" + claimId + "/updates").header(HttpHeaders.AUTHORIZATION, as(Role.CLAIMANT, ALICE)));
    }

    @Test
    void theOwnerCorrectsTheirClaimAndOnlyTheSentFieldsChange() throws Exception {
        String id = aliceClaim();
        patchAsAlice(id, Map.of("claimedAmount", new BigDecimal("2600.50"), "policyNumber", "POL-2026-0099",
                "incidentDescription", "Rear bumper and boot lid damaged.", "claimType", "PROPERTY", "incidentDate", "2026-10-13"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.claimId").value(id))
                .andExpect(jsonPath("$.claimedAmount").value(2600.50))
                .andExpect(jsonPath("$.policyNumber").value("POL-2026-0099"))
                .andExpect(jsonPath("$.incidentDescription").value("Rear bumper and boot lid damaged."))
                .andExpect(jsonPath("$.claimType").value("PROPERTY"))
                .andExpect(jsonPath("$.incidentDate").value("2026-10-13"))
                // untouched
                .andExpect(jsonPath("$.claimantName").value("Mei Tan"))
                .andExpect(jsonPath("$.claimantEmail").value("mei.tan@example.com"))
                .andExpect(jsonPath("$.market").value("SG"))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.claimantId").value(ALICE));
        assertThat(jdbc.queryForObject("select claimed_amount from claims where claim_id = ?", BigDecimal.class, id))
                .isEqualByComparingTo("2600.50");
    }

    @Test
    void aSingleFieldCanBeChangedAndTextIsTrimmedLikeAtCreation() throws Exception {
        String id = aliceClaim();
        patchAsAlice(id, Map.of("claimantName", "  Mei Tan Lee  ", "policyNumber", "POL-2026-0123")).andExpect(status().isOk())
                .andExpect(jsonPath("$.claimantName").value("Mei Tan Lee"))
                .andExpect(jsonPath("$.policyNumber").value("POL-2026-0123"))
                .andExpect(jsonPath("$.claimedAmount").value(2400.00));
        // an e-mail address with spaces around it is refused, exactly as when the claim was created (it is not silently trimmed)
        patchAsAlice(id, Map.of("claimantEmail", "  mei.lee@example.com ")).andExpect(status().isBadRequest());
        patchAsAlice(id, Map.of("claimantEmail", "mei.lee@example.com")).andExpect(status().isOk())
                .andExpect(jsonPath("$.claimantEmail").value("mei.lee@example.com"));
    }

    @Test
    void aDescriptionOverSeveralLinesIsAcceptedJustAsWhenTheClaimWasCreated() throws Exception {
        // creating a claim with a multi-line description works; correcting it with one must work too
        Map<String, Object> created = claimBody("SG", "MOTOR", "10.00", "INR");
        created.put("incidentDescription", "Rear-ended.\nDriver fled.");
        String id = read(mvc.perform(post("/api/v1/claims").header(HttpHeaders.AUTHORIZATION, as(Role.CLAIMANT, ALICE))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(created))).andExpect(status().isCreated()))
                .get("claimId").asText();
        patchAsAlice(id, Map.of("incidentDescription", "Rear-ended at the lights.\nDriver fled.\r\nPolice report attached.")).andExpect(status().isOk())
                .andExpect(jsonPath("$.incidentDescription").value("Rear-ended at the lights.\nDriver fled.\r\nPolice report attached."));
        updatesOf(id).andExpect(jsonPath("$[0].changes[0]").value("incidentDescription: changed"));
        // a text that is only white space over several lines is still blank
        patchAsAlice(id, Map.of("incidentDescription", " \n\t \r\n ")).andExpect(status().isBadRequest());
        patchAsAlice(id, Map.of("claimantName", "\n")).andExpect(status().isBadRequest());
    }

    @Test
    void theClaimNumberTheClaimantTheMarketTheCurrencyAndTheStatusCannotBeChangedThroughTheBody() throws Exception {
        String id = aliceClaim();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("claimedAmount", new BigDecimal("1.00"));
        body.put("claimId", "CLM-HACK-1");
        body.put("claimantId", "somebody.else");
        body.put("market", "HK");
        body.put("currency", "USD");
        body.put("status", "SETTLED");
        body.put("assignedOfficerId", "OFF-1");
        body.put("approvedAmount", new BigDecimal("1.00"));
        patchAsAlice(id, body).andExpect(status().isOk())
                .andExpect(jsonPath("$.claimId").value(id))
                .andExpect(jsonPath("$.claimantId").value(ALICE))
                .andExpect(jsonPath("$.market").value("SG"))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.assignedOfficerId").doesNotExist())
                .andExpect(jsonPath("$.approvedAmount").doesNotExist())
                .andExpect(jsonPath("$.claimedAmount").value(1.00));
    }

    // ---- the edit history ----

    @Test
    void everyRealChangeIsRecordedWithWhoAndWhatButNoPersonalData() throws Exception {
        String id = aliceClaim();
        patchAsAlice(id, Map.of("claimedAmount", new BigDecimal("2500.00"), "claimantName", "A New Name Entirely")).andExpect(status().isOk());
        patchAsAlice(id, Map.of("policyNumber", "POL-2026-0100", "incidentDescription", "A completely different story")).andExpect(status().isOk());

        String body = updatesOf(id).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].claimId").value(id))
                .andExpect(jsonPath("$[0].actor").value(ALICE))
                .andExpect(jsonPath("$[0].changedAt").isNotEmpty())
                .andExpect(jsonPath("$[0].changes[0]").value("claimantName: changed"))
                .andExpect(jsonPath("$[0].changes[1]").value("claimedAmount: 2400.00 -> 2500.00"))
                .andExpect(jsonPath("$[1].changes[0]").value("policyNumber: POL-2026-0042 -> POL-2026-0100"))
                .andExpect(jsonPath("$[1].changes[1]").value("incidentDescription: changed"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("A New Name Entirely").doesNotContain("A completely different story");
        // an edit is not a status change
        getJson("/api/v1/claims/" + id + "/history").andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void sendingTheSameValuesAgainChangesNothingAndRecordsNothing() throws Exception {
        String id = aliceClaim();
        patchAsAlice(id, Map.of("claimedAmount", new BigDecimal("2400.0"), "policyNumber", "POL-2026-0042")).andExpect(status().isOk());
        updatesOf(id).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void aClaimThatWasNeverEditedHasAnEmptyEditHistory() throws Exception {
        updatesOf(aliceClaim()).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    // ---- validation ----

    @Test
    void anEmptyRequestIs400BecauseThereIsNothingToChange() throws Exception {
        String id = aliceClaim();
        patchAsAlice(id, Map.of()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("NO_FIELDS_TO_UPDATE"));
        patchAsAlice(id, Map.of("market", "HK")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("NO_FIELDS_TO_UPDATE"));
    }

    @Test
    void theSameRulesApplyAsWhenTheClaimWasCreated() throws Exception {
        String id = aliceClaim();
        for (Map<String, Object> bad : List.<Map<String, Object>>of(
                Map.of("claimedAmount", new BigDecimal("-1.00")),
                Map.of("claimedAmount", new BigDecimal("0")),
                Map.of("claimedAmount", new BigDecimal("10.001")),
                Map.of("incidentDate", "2999-01-01"),
                Map.of("incidentDate", "12/10/2026"),
                Map.of("claimantEmail", "not-an-email"),
                Map.of("claimantName", "   "),
                Map.of("incidentDescription", ""),
                Map.of("incidentDescription", "x".repeat(2001)),
                Map.of("policyNumber", "AB"),
                Map.of("policyNumber", "POL 1"),
                Map.of("claimType", "BOAT"))) {
            patchAsAlice(id, bad).andExpect(status().isBadRequest());
        }
        getJson("/api/v1/claims/" + id).andExpect(jsonPath("$.claimedAmount").value(2400.00)).andExpect(jsonPath("$.claimantName").value("Mei Tan"));
        updatesOf(id).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void malformedJsonAndWrongContentTypeAreRejected() throws Exception {
        String id = aliceClaim();
        mvc.perform(patch("/api/v1/claims/" + id).header(HttpHeaders.AUTHORIZATION, as(Role.CLAIMANT, ALICE))
                .contentType(MediaType.APPLICATION_JSON).content("{ not json")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        mvc.perform(patch("/api/v1/claims/" + id).header(HttpHeaders.AUTHORIZATION, as(Role.CLAIMANT, ALICE))
                .contentType(MediaType.APPLICATION_JSON)).andExpect(status().isBadRequest());
        mvc.perform(patch("/api/v1/claims/" + id).header(HttpHeaders.AUTHORIZATION, as(Role.CLAIMANT, ALICE))
                .contentType(MediaType.TEXT_PLAIN).content("hello")).andExpect(status().isUnsupportedMediaType());
    }

    // ---- who, and when ----

    @Test
    void anotherClaimantGetsTheAnswerForAClaimThatDoesNotExistAndChangesNothing() throws Exception {
        String id = aliceClaim();
        patchAs(as(Role.CLAIMANT, "claimant.bob"), id, Map.of("claimedAmount", new BigDecimal("1.00"))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CLAIM_NOT_FOUND"));
        patchAs(as(Role.CLAIMANT, "claimant.bob"), "CLM-2099-999999", Map.of("claimedAmount", new BigDecimal("1.00")))
                .andExpect(status().isNotFound());
        getJson("/api/v1/claims/" + id).andExpect(jsonPath("$.claimedAmount").value(2400.00));
        updatesOf(id).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void officersManagersAndServicesCannotEditAClaimantsClaim() throws Exception {
        String id = aliceClaim();
        for (Role role : new Role[] {Role.CLAIMS_OFFICER, Role.MANAGER, Role.SERVICE}) {
            patchAs(as(role), id, Map.of("claimedAmount", new BigDecimal("1.00"))).andExpect(status().isForbidden());
        }
        patchAs("", id, Map.of("claimedAmount", new BigDecimal("1.00"))).andExpect(status().isUnauthorized());
        getJson("/api/v1/claims/" + id).andExpect(jsonPath("$.claimedAmount").value(2400.00));
    }

    @Test
    void anUnknownClaimIs404() throws Exception {
        patchAsAlice("CLM-2026-999999", Map.of("claimedAmount", new BigDecimal("1.00"))).andExpect(status().isNotFound());
    }

    @Test
    void onceAnOfficerHasStartedOnTheClaimItCanNoLongerBeChanged() throws Exception {
        String id = aliceClaim();
        review(id, "OFF-1");
        patchAsAlice(id, Map.of("claimedAmount", new BigDecimal("1.00"))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CLAIM_NOT_EDITABLE"));
        getJson("/api/v1/claims/" + id).andExpect(jsonPath("$.claimedAmount").value(2400.00));
        updatesOf(id).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void anEditRacingTheStartOfReviewIsSerialisedByTheClaimLock() throws Exception {
        String id = aliceClaim();
        BigDecimal newAmount = new BigDecimal("2999.00");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            Callable<Integer> edit = () -> {
                go.await();
                return patchAsAlice(id, Map.of("claimedAmount", newAmount)).andReturn().getResponse().getStatus();
            };
            Callable<Integer> start = () -> {
                go.await();
                return reviewRequest(id, "OFF-1", false).andReturn().getResponse().getStatus();
            };
            Future<Integer> editStatus = pool.submit(edit);
            Future<Integer> reviewStatus = pool.submit(start);
            go.countDown();
            int e = editStatus.get(30, TimeUnit.SECONDS);
            int r = reviewStatus.get(30, TimeUnit.SECONDS);

            assertThat(r).as("the review always succeeds, before or after the edit").isEqualTo(200);
            BigDecimal stored = jdbc.queryForObject("select claimed_amount from claims where claim_id = ?", BigDecimal.class, id);
            if (e == 200) {
                assertThat(stored).as("edit first, then review").isEqualByComparingTo(newAmount);
            } else {
                assertThat(e).as("review first: the edit is refused").isEqualTo(409);
                assertThat(stored).isEqualByComparingTo("2400.00");
            }
            assertThat(jdbc.queryForObject("select status from claims where claim_id = ?", String.class, id)).isEqualTo("UNDER_REVIEW");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void theEditHistoryIsHiddenFromOtherClaimantsAndVisibleToStaff() throws Exception {
        String id = aliceClaim();
        patchAsAlice(id, Map.of("claimedAmount", new BigDecimal("2500.00"))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/claims/" + id + "/updates").header(HttpHeaders.AUTHORIZATION, as(Role.CLAIMANT, "claimant.bob")))
                .andExpect(status().isNotFound());
        for (Role role : new Role[] {Role.CLAIMS_OFFICER, Role.MANAGER, Role.ADMIN}) {
            mvc.perform(get("/api/v1/claims/" + id + "/updates").header(HttpHeaders.AUTHORIZATION, as(role)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        }
    }
}
