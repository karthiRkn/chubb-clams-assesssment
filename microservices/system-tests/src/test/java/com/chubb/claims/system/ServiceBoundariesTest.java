package com.chubb.claims.system;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** Data ownership, which service serves which route, and API documentation per service. */
class ServiceBoundariesTest {

    private final SystemUnderTest system = SystemUnderTest.get();
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    private final ObjectMapper json = new ObjectMapper();

    /** No credentials: used for the API documentation and Swagger, which must stay open. */
    private HttpResponse<String> send(String method, String url) throws Exception {
        return send(method, url, null);
    }

    /** As an administrator, so a 404 or 405 means "this service has no such route", not "you may not ask". */
    private HttpResponse<String> sendAsAdmin(String method, String url) throws Exception {
        return send(method, url, system.authorization(com.chubb.claims.common.security.Role.ADMIN));
    }

    private HttpResponse<String> send(String method, String url, String authorization) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url));
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        request.method(method, HttpRequest.BodyPublishers.noBody());
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static Set<String> tables(JdbcTemplate jdbc) {
        return new HashSet<>(jdbc.queryForList(
                "select lower(table_name) from information_schema.tables where upper(table_schema) = 'PUBLIC'", String.class));
    }

    // ---- database ownership ----

    @Test
    void everyTableLivesInExactlyOneServicesDatabase() {
        assertThat(tables(system.claim.getBean(JdbcTemplate.class))).containsExactlyInAnyOrder("claims", "claim_status_history",
                "claim_assignment_history", "claim_update_history", "flyway_schema_history");
        assertThat(tables(system.assessment.getBean(JdbcTemplate.class)))
                .containsExactlyInAnyOrder("claim_assessments", "information_requests", "flyway_schema_history");
        assertThat(tables(system.user.getBean(JdbcTemplate.class)))
                .containsExactlyInAnyOrder("users", "flyway_schema_history");
    }

    @Test
    void theServicesDoNotShareADatabase() {
        Set<String> urls = new HashSet<>();
        for (var context : List.of(system.user, system.claim, system.assessment)) {
            urls.add(context.getEnvironment().getProperty("spring.datasource.url"));
        }
        assertThat(urls).hasSize(3);
    }

    @Test
    void onlyTheUserServiceHoldsCredentialsAndNoOtherServiceCanReadThem() {
        for (var context : List.of(system.claim, system.assessment)) {
            assertThat(tables(context.getBean(JdbcTemplate.class))).doesNotContain("users");
        }
        // the other services check tokens with the shared secret and never read a user table
    }

    @Test
    void assessmentRecordsAndInformationRequestsReferToClaimsByPublicIdWithoutAForeignKey() {
        Integer foreignKeys = system.assessment.getBean(JdbcTemplate.class).queryForObject(
                "select count(*) from information_schema.table_constraints where constraint_type = 'FOREIGN KEY'", Integer.class);
        assertThat(foreignKeys).isZero();
    }

    // ---- routes ----

    @Test
    void aServiceDoesNotServeAnotherServicesPublicRoutes() throws Exception {
        String id = "CLM-2026-000101";
        // the Claim Service has no assessment or information routes
        assertThat(sendAsAdmin("GET", system.claimUrl + "/api/v1/officer/claims/" + id + "/assessments").statusCode()).isEqualTo(404);
        assertThat(sendAsAdmin("GET", system.claimUrl + "/api/v1/claims/" + id + "/information-requests").statusCode()).isEqualTo(404);
        assertThat(sendAsAdmin("POST", system.claimUrl + "/api/v1/officer/claims/" + id + "/review").statusCode()).isIn(404, 405);
        assertThat(sendAsAdmin("POST", system.claimUrl + "/api/v1/officer/claims/" + id + "/decision").statusCode()).isIn(404, 405);
        assertThat(sendAsAdmin("POST", system.claimUrl + "/api/v1/officer/claims/" + id + "/settlement").statusCode()).isIn(404, 405);
        // the Assessment Service has no claim, assignment or management routes
        assertThat(sendAsAdmin("GET", system.assessmentUrl + "/api/v1/claims/" + id).statusCode()).isEqualTo(404);
        assertThat(sendAsAdmin("GET", system.assessmentUrl + "/api/v1/officer/claims").statusCode()).isEqualTo(404);
        assertThat(sendAsAdmin("GET", system.assessmentUrl + "/api/v1/management/summary").statusCode()).isEqualTo(404);
        assertThat(sendAsAdmin("POST", system.assessmentUrl + "/api/v1/claims").statusCode()).isIn(404, 405);
        assertThat(sendAsAdmin("POST", system.assessmentUrl + "/api/v1/officer/claims/" + id + "/assign").statusCode()).isIn(404, 405);
        // nobody but the User Service has users or login
        assertThat(sendAsAdmin("GET", system.claimUrl + "/api/v1/users").statusCode()).isEqualTo(404);
        assertThat(sendAsAdmin("POST", system.claimUrl + "/api/v1/auth/login").statusCode()).isIn(404, 405);
        assertThat(sendAsAdmin("GET", system.assessmentUrl + "/api/v1/users").statusCode()).isEqualTo(404);
        assertThat(sendAsAdmin("GET", system.userUrl + "/api/v1/claims/" + id).statusCode()).isEqualTo(404);
        assertThat(sendAsAdmin("GET", system.userUrl + "/api/v1/management/summary").statusCode()).isEqualTo(404);
    }

    @Test
    void theRetiredServicesRoutesAreGoneFromEveryService() throws Exception {
        String service = system.authorization(com.chubb.claims.common.security.Role.SERVICE);
        for (String base : new String[] {system.claimUrl, system.assessmentUrl, system.userUrl}) {
            assertThat(send("GET", base + "/internal/v1/claims/stats/summary", service).statusCode()).as(base).isIn(403, 404);
            assertThat(send("GET", base + "/internal/v1/claims/stats/officers", service).statusCode()).as(base).isIn(403, 404);
            assertThat(send("GET", base + "/internal/v1/claims/CLM-2026-000101/information-requests/open", service).statusCode())
                    .as(base).isIn(403, 404);
        }
    }

    @Test
    void withoutATokenEveryServiceAnswers401NotAHintAboutWhichRoutesExist() throws Exception {
        String id = "CLM-2026-000101";
        for (String url : new String[] {system.claimUrl + "/api/v1/claims/" + id, system.claimUrl + "/api/v1/no-such-route",
                system.assessmentUrl + "/api/v1/claims/" + id + "/information-requests",
                system.assessmentUrl + "/api/v1/officer/claims/" + id + "/assessments",
                system.claimUrl + "/api/v1/management/summary", system.userUrl + "/api/v1/users",
                system.claimUrl + "/internal/v1/claims/" + id + "/status", system.userUrl + "/internal/v1/users/officer1"}) {
            HttpResponse<String> response = send("GET", url);
            assertThat(response.statusCode()).as(url).isEqualTo(401);
            assertThat(json.readTree(response.body()).get("code").asText()).as(url).isEqualTo("UNAUTHENTICATED");
        }
    }

    @Test
    void theServicesRunOnDifferentPorts() {
        assertThat(Set.of(system.userUrl, system.claimUrl, system.assessmentUrl)).hasSize(3);
    }

    // ---- health ----

    @Test
    void everyServiceAnswersItsHealthEndpointWithoutATokenAndExposesNothingElseOfTheActuator() throws Exception {
        for (String url : new String[] {system.userUrl, system.claimUrl, system.assessmentUrl}) {
            HttpResponse<String> health = send("GET", url + "/actuator/health");
            assertThat(health.statusCode()).as(url).isEqualTo(200);
            assertThat(json.readTree(health.body()).get("status").asText()).as(url).isEqualTo("UP");
            assertThat(json.readTree(health.body()).fieldNames()).toIterable().as("no details are shown").containsExactly("status");
            assertThat(send("GET", url + "/actuator/env").statusCode()).as(url + " env, no token").isEqualTo(401);
            assertThat(sendAsAdmin("GET", url + "/actuator/env").statusCode()).as(url + " env, admin").isEqualTo(404);
            assertThat(sendAsAdmin("GET", url + "/actuator/beans").statusCode()).as(url + " beans, admin").isEqualTo(404);
        }
    }

    // ---- API documentation ----

    private Set<String> documentedPaths(String baseUrl) throws Exception {
        HttpResponse<String> docs = send("GET", baseUrl + "/v3/api-docs");
        assertThat(docs.statusCode()).isEqualTo(200);
        Set<String> paths = new HashSet<>();
        Iterator<String> names = json.readTree(docs.body()).get("paths").fieldNames();
        names.forEachRemaining(paths::add);
        return paths;
    }

    private int operationsUnderApiV1(String baseUrl) throws Exception {
        JsonNode paths = json.readTree(send("GET", baseUrl + "/v3/api-docs").body()).get("paths");
        int operations = 0;
        for (Iterator<String> it = paths.fieldNames(); it.hasNext(); ) {
            String path = it.next();
            if (path.startsWith("/api/v1/")) {
                operations += paths.get(path).size();
            }
        }
        return operations;
    }

    @Test
    void eachServicePublishesItsOwnOpenApiDocument() throws Exception {
        assertThat(documentedPaths(system.claimUrl)).containsExactlyInAnyOrder(
                "/api/v1/claims", "/api/v1/claims/{claimId}", "/api/v1/claims/{claimId}/history",
                "/api/v1/claims/{claimId}/assignments", "/api/v1/claims/{claimId}/updates",
                "/api/v1/officer/claims", "/api/v1/officer/claims/{claimId}/assign",
                "/api/v1/management/summary", "/api/v1/management/officers",
                "/internal/v1/claims/{claimId}/status", "/internal/v1/claims/{claimId}/review",
                "/internal/v1/claims/{claimId}/request-information", "/internal/v1/claims/{claimId}/resume-after-information",
                "/internal/v1/claims/{claimId}/decision", "/internal/v1/claims/{claimId}/settlement");
        assertThat(documentedPaths(system.assessmentUrl)).containsExactlyInAnyOrder(
                "/api/v1/officer/claims/{claimId}/review", "/api/v1/officer/claims/{claimId}/assessments",
                "/api/v1/officer/claims/{claimId}/decision", "/api/v1/officer/claims/{claimId}/settlement",
                "/api/v1/officer/claims/{claimId}/information-requests", "/api/v1/claims/{claimId}/information-requests",
                "/api/v1/claims/{claimId}/information");
        assertThat(documentedPaths(system.userUrl)).containsExactlyInAnyOrder("/api/v1/auth/register", "/api/v1/auth/login",
                "/api/v1/users", "/api/v1/users/me", "/api/v1/users/{username}", "/api/v1/users/{username}/role",
                "/api/v1/users/{username}/active", "/internal/v1/users/{username}");
    }

    @Test
    void theClaimAndAssessmentServicesTogetherAreTheEighteenDocumentedClaimEndpointsAndTheUserServiceAddsEight() throws Exception {
        int claimDomain = operationsUnderApiV1(system.claimUrl) + operationsUnderApiV1(system.assessmentUrl);
        // claims: create, list, get, edit, history, assignments, edits history; staff list, assign; summary, officers
        // assessment: review, history, decision, settlement, ask, list requests, answer
        assertThat(claimDomain).isEqualTo(18);
        // register, login, me, create user, list users, get user, change role, activate or deactivate
        assertThat(operationsUnderApiV1(system.userUrl)).isEqualTo(8);
    }

    @Test
    void everyServiceDocumentsTheBearerSchemeAndOnlyLoginAndRegistrationAreOpen() throws Exception {
        for (String url : new String[] {system.userUrl, system.claimUrl, system.assessmentUrl}) {
            JsonNode docs = json.readTree(send("GET", url + "/v3/api-docs").body());
            assertThat(docs.at("/components/securitySchemes/bearerAuth/scheme").asText()).as(url).isEqualTo("bearer");
            assertThat(docs.at("/components/securitySchemes/bearerAuth/bearerFormat").asText()).as(url).isEqualTo("JWT");
        }
        JsonNode user = json.readTree(send("GET", system.userUrl + "/v3/api-docs").body()).get("paths");
        assertThat(user.at("/~1api~1v1~1auth~1login/post/security").size()).isZero();
        assertThat(user.at("/~1api~1v1~1auth~1register/post/security").size()).isZero();
    }

    @Test
    void eachServiceHasItsOwnTitleInSwaggerAndServesTheSwaggerUi() throws Exception {
        String[][] services = {
                {system.userUrl, "User Service API"},
                {system.claimUrl, "Claim Service API"},
                {system.assessmentUrl, "Assessment Service API"}};
        for (String[] service : services) {
            JsonNode docs = json.readTree(send("GET", service[0] + "/v3/api-docs").body());
            assertThat(docs.get("info").get("title").asText()).isEqualTo(service[1]);
            assertThat(send("GET", service[0] + "/swagger-ui/index.html").statusCode()).as(service[1]).isEqualTo(200);
        }
    }
}
