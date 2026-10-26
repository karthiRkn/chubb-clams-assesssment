package com.chubb.claims.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class ClaimCreationTest extends SystemTestBase {

    @Test
    void createReturns201WithGeneratedIdAndClaimId() throws Exception {
        postJson("/api/v1/claims", claimBody("SG", "MOTOR", "2400.00", "INR"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.claimId", matchesPattern("CLM-\\d{4}-\\d{6}")))
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.assignedOfficerId").doesNotExist())
                .andExpect(jsonPath("$.claimedAmount").value(2400.00))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.createdAt").exists());
    }

    @Test
    void clientSuppliedIdAndClaimIdAreIgnored() throws Exception {
        Map<String, Object> body = claimBody("SG", "MOTOR", "10.00", "INR");
        body.put("id", 999999);
        body.put("claimId", "CLM-HACK-000001");
        postJson("/api/v1/claims", body)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.claimId", matchesPattern("CLM-\\d{4}-\\d{6}")))
                .andExpect(jsonPath("$.id").value(org.hamcrest.Matchers.not(999999)));
    }

    @Test
    void claimIdsAreSequentialAndDistinctAndIdDiffersFromClaimId() throws Exception {
        var a = read(postJson("/api/v1/claims", claimBody("SG", "MOTOR", "1.00", "INR")));
        var b = read(postJson("/api/v1/claims", claimBody("SG", "MOTOR", "1.00", "INR")));
        assertThat(a.get("claimId").asText()).isNotEqualTo(b.get("claimId").asText());
        assertThat(a.get("id").asLong()).isNotEqualTo(b.get("id").asLong());
        assertThat(a.get("claimId").asText()).isNotEqualTo(a.get("id").asText());
    }

    @Test
    void claimIdsStayUniqueUnderConcurrentCreation() throws Exception {
        int n = 40;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            // the monolith version called the service bean from 8 threads; here the same load goes over HTTP
            List<Callable<String>> jobs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                jobs.add(() -> read(postJson("/api/v1/claims", claimBody("SG", "MOTOR", "5.00", "INR"))).get("claimId").asText());
            }
            Set<String> ids = new HashSet<>();
            for (Future<String> f : pool.invokeAll(jobs)) {
                ids.add(f.get());
            }
            assertThat(ids).hasSize(n);
            assertThat(jdbc.queryForObject("select count(distinct claim_id) from claims", Integer.class)).isEqualTo(n);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void databaseEnforcesUniqueClaimId() throws Exception {
        String claimId = createClaim();
        String insert = "insert into claims (claim_id, claimant_name, claimant_email, market, claim_type, incident_description, "
                + "incident_date, claimed_amount, currency, status, created_at, updated_at) "
                + "values (?, 'x', 'x@example.com', 'SG', 'MOTOR', 'd', current_date, 1.00, 'INR', 'SUBMITTED', "
                + "current_timestamp, current_timestamp)";
        org.junit.jupiter.api.Assertions.assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update(insert, claimId));
    }

    @Test
    void claimIdIsStableAndUsedInPublicRoute() throws Exception {
        String claimId = createClaim();
        getJson("/api/v1/claims/" + claimId).andExpect(status().isOk()).andExpect(jsonPath("$.claimId").value(claimId));
        assign(claimId, "off-1");
        review(claimId, "off-1");
        getJson("/api/v1/claims/" + claimId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.claimId").value(claimId))
                .andExpect(jsonPath("$.status").value("UNDER_REVIEW"))
                .andExpect(jsonPath("$.decisionReason").doesNotExist());
    }

    @Test
    void internalNumericIdIsNotAcceptedInPublicRoute() throws Exception {
        var created = read(postJson("/api/v1/claims", claimBody("SG", "MOTOR", "1.00", "INR")));
        getJson("/api/v1/claims/" + created.get("id").asLong()).andExpect(status().isNotFound());
    }

    @Test
    void unknownClaimReturns404WithErrorBody() throws Exception {
        getJson("/api/v1/claims/CLM-2026-999999")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("CLAIM_NOT_FOUND"))
                .andExpect(jsonPath("$.path").value("/api/v1/claims/CLM-2026-999999"))
                .andExpect(jsonPath("$.trace").doesNotExist());
    }

    @Test
    void invalidInputReturns400() throws Exception {
        Map<String, Object> negative = claimBody("SG", "MOTOR", "-5.00", "INR");
        postJson("/api/v1/claims", negative).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        postJson("/api/v1/claims", claimBody("SG", "MOTOR", "0", "INR")).andExpect(status().isBadRequest());
        postJson("/api/v1/claims", claimBody("SG", "MOTOR", "10.00", "inr")).andExpect(status().isBadRequest());
        postJson("/api/v1/claims", claimBody("SG", "MOTOR", "10.00", null)).andExpect(status().isBadRequest());
        postJson("/api/v1/claims", claimBody("Singapore", "MOTOR", "10.00", "INR")).andExpect(status().isBadRequest());
        postJson("/api/v1/claims", claimBody("SG", "BOAT", "10.00", "INR")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

        Map<String, Object> badEmail = claimBody("SG", "MOTOR", "10.00", "INR");
        badEmail.put("claimantEmail", "not-an-email");
        postJson("/api/v1/claims", badEmail).andExpect(status().isBadRequest());

        Map<String, Object> future = claimBody("SG", "MOTOR", "10.00", "INR");
        future.put("incidentDate", "2999-01-01");
        postJson("/api/v1/claims", future).andExpect(status().isBadRequest());

        Map<String, Object> badDate = claimBody("SG", "MOTOR", "10.00", "INR");
        badDate.put("incidentDate", "12/10/2026");
        postJson("/api/v1/claims", badDate).andExpect(status().isBadRequest());

        assertThat(jdbc.queryForObject("select count(*) from claims", Integer.class)).isZero();
    }
}
