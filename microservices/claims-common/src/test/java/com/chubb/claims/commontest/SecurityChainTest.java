package com.chubb.claims.commontest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chubb.claims.common.security.JwtUtil;
import com.chubb.claims.common.security.Role;
import com.chubb.claims.testsupport.RouteCoverage;
import com.chubb.claims.testsupport.TestAuth;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.client.RestClient;

/** The shared security chain, checked once against probe routes: who gets 401, who gets 403, and what the body looks like. */
@SpringBootTest(classes = SecurityTestApp.class, properties = "spring.application.name=probe-service")
@AutoConfigureMockMvc
class SecurityChainTest {

    private static final String SECRET = TestAuth.secret();

    @DynamicPropertySource
    static void secret(DynamicPropertyRegistry registry) {
        registry.add("claims.security.jwt.secret", TestAuth::secret);
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    JwtUtil jwt;
    @Autowired
    ObjectMapper json;
    @Autowired
    RestClient.Builder restClientBuilder;
    @Autowired
    ApplicationContext context;

    private ResultActions call(org.springframework.test.web.servlet.RequestBuilder request) throws Exception {
        return mvc.perform(request);
    }

    private String as(Role role) {
        return TestAuth.bearer(jwt, role);
    }

    // ---- 401: no usable token ----

    @Test
    void noTokenIs401AndTellsTheClientToSendABearerToken() throws Exception {
        call(get("/api/v1/probe/officer")).andExpect(status().isUnauthorized())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void aBodyIsReturnedInTheSharedErrorShape() throws Exception {
        String body = call(get("/api/v1/probe/officer")).andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
        JsonNode error = json.readTree(body);
        assertThat(error.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("timestamp", "status", "error", "code", "message", "path");
        assertThat(error.get("status").asInt()).isEqualTo(401);
        assertThat(error.get("error").asText()).isEqualTo("Unauthorized");
        assertThat(error.get("code").asText()).isEqualTo("UNAUTHENTICATED");
        assertThat(error.get("path").asText()).isEqualTo("/api/v1/probe/officer");
    }

    @Test
    void aTamperedExpiredForeignOrMalformedTokenIs401() throws Exception {
        String valid = jwt.generate("someone", Role.CLAIMS_OFFICER);
        String tampered = valid.substring(0, valid.length() - 2) + (valid.endsWith("AA") ? "BB" : "AA");
        String expired = Jwts.builder().subject("someone").issuer("chubb-claims").claim("role", "CLAIMS_OFFICER")
                .expiration(Date.from(Instant.now().minusSeconds(60)))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
        String foreign = Jwts.builder().subject("someone").issuer("chubb-claims").claim("role", "ADMIN")
                .expiration(Date.from(Instant.now().plusSeconds(600)))
                .signWith(Keys.hmacShaKeyFor("some-other-secret-that-is-long-enough-for-hmac".getBytes(StandardCharsets.UTF_8))).compact();
        for (String token : new String[] {tampered, expired, foreign, "not-a-token", ""}) {
            call(get("/api/v1/probe/officer").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        }
    }

    @Test
    void onlyTheBearerSchemeIsAccepted() throws Exception {
        String token = jwt.generate("someone", Role.CLAIMS_OFFICER);
        for (String header : new String[] {"Basic " + token, token, "Token " + token, ""}) {
            call(get("/api/v1/probe/officer").header(HttpHeaders.AUTHORIZATION, header))
                    .andExpect(status().isUnauthorized());
        }
        // the scheme name is case-insensitive
        call(get("/api/v1/probe/officer").header(HttpHeaders.AUTHORIZATION, "bearer " + token)).andExpect(status().isOk());
    }

    // ---- 403 and 200: a valid token, by role ----

    @Test
    void eachRouteAllowsExactlyItsRolesAndAdmin() throws Exception {
        for (Role role : EnumSet.allOf(Role.class)) {
            boolean officerRoute = role == Role.CLAIMS_OFFICER || role == Role.ADMIN;
            boolean managerRoute = role == Role.MANAGER || role == Role.ADMIN;
            call(get("/api/v1/probe/officer").header(HttpHeaders.AUTHORIZATION, as(role)))
                    .andExpect(status().is(officerRoute ? 200 : 403));
            call(post("/api/v1/probe/manager").header(HttpHeaders.AUTHORIZATION, as(role)))
                    .andExpect(status().is(managerRoute ? 200 : 403));
        }
    }

    @Test
    void aForbiddenAnswerUsesTheStandardBodyAndGivesNoReason() throws Exception {
        call(get("/api/v1/probe/officer").header(HttpHeaders.AUTHORIZATION, as(Role.CLAIMANT)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("Forbidden"))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.path").value("/api/v1/probe/officer"));
    }

    @Test
    void aMethodNotInTheTableFallsBackToAdminOnly() throws Exception {
        // the table lists GET for the officer route, so DELETE is unlisted: only ADMIN gets through to MVC (405)
        call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/probe/officer")
                .header(HttpHeaders.AUTHORIZATION, as(Role.CLAIMS_OFFICER))).andExpect(status().isForbidden());
        call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/probe/officer")
                .header(HttpHeaders.AUTHORIZATION, as(Role.ADMIN))).andExpect(status().isMethodNotAllowed());
    }

    @Test
    void aRouteNobodyListedIsAdminOnlyAndAnUnknownRouteIsA404ForAdmin() throws Exception {
        call(get("/api/v1/probe/unlisted")).andExpect(status().isUnauthorized());
        for (Role role : EnumSet.complementOf(EnumSet.of(Role.ADMIN))) {
            call(get("/api/v1/probe/unlisted").header(HttpHeaders.AUTHORIZATION, as(role))).andExpect(status().isForbidden());
        }
        call(get("/api/v1/probe/unlisted").header(HttpHeaders.AUTHORIZATION, as(Role.ADMIN))).andExpect(status().isOk());
        call(get("/api/v1/does-not-exist").header(HttpHeaders.AUTHORIZATION, as(Role.ADMIN)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void anOpenRouteNeedsNoToken() throws Exception {
        call(get("/api/v1/probe/open")).andExpect(status().isOk());
    }

    @Test
    void swaggerAndApiDocsAreNotBehindAToken() throws Exception {
        // nothing serves them in this probe app, so a 404 (not a 401) proves the chain let the request through
        for (String path : new String[] {"/v3/api-docs", "/swagger-ui/index.html", "/swagger-ui.html"}) {
            assertThat(call(get(path)).andReturn().getResponse().getStatus()).as(path).isNotEqualTo(401).isNotEqualTo(403);
        }
    }

    @Test
    void theHealthEndpointIsNotBehindAToken() throws Exception {
        // nothing serves it in this probe app (no actuator), so a 404 (not a 401) proves the chain let the request through
        for (String path : new String[] {"/actuator/health", "/actuator/health/liveness"}) {
            assertThat(call(get(path)).andReturn().getResponse().getStatus()).as(path).isNotEqualTo(401).isNotEqualTo(403);
        }
        // other actuator endpoints are not opened: with no token they are still a 401
        call(get("/actuator/env")).andExpect(status().isUnauthorized());
        call(get("/actuator/health-secrets")).andExpect(status().isUnauthorized());
    }

    // ---- internal routes ----

    @Test
    void internalRoutesAcceptOnlyAServiceToken() throws Exception {
        call(get("/internal/v1/probe/1")).andExpect(status().isUnauthorized());
        for (Role role : EnumSet.complementOf(EnumSet.of(Role.SERVICE))) {
            // not even ADMIN: a person's token must never work on a service-to-service route
            call(get("/internal/v1/probe/1").header(HttpHeaders.AUTHORIZATION, as(role))).andExpect(status().isForbidden());
        }
        call(get("/internal/v1/probe/1").header(HttpHeaders.AUTHORIZATION, as(Role.SERVICE))).andExpect(status().isOk());
    }

    @Test
    void aServiceTokenCannotUseThePublicApi() throws Exception {
        call(get("/api/v1/probe/officer").header(HttpHeaders.AUTHORIZATION, as(Role.SERVICE))).andExpect(status().isForbidden());
        call(get("/api/v1/probe/whoami").header(HttpHeaders.AUTHORIZATION, as(Role.SERVICE))).andExpect(status().isForbidden());
    }

    // ---- identity reaches controllers; denial raised in code is a 403 ----

    @Test
    void theControllerSeesWhoIsCalling() throws Exception {
        call(get("/api/v1/probe/whoami").header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(jwt, Role.MANAGER, "meera")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("meera"))
                .andExpect(jsonPath("$.role").value("MANAGER"));
    }

    @Test
    void accessDeniedRaisedInsideAControllerIs403NotA500() throws Exception {
        call(get("/api/v1/probe/denied-in-code").header(HttpHeaders.AUTHORIZATION, as(Role.CLAIMANT)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    // ---- start-up behaviour ----

    @Test
    void springBootsGeneratedPasswordUserIsNotCreated() {
        // otherwise every service would log a throw-away password and accept it for basic auth
        assertThat(context.getBeansOfType(UserDetailsService.class)).isEmpty();
    }

    @Test
    void theRouteCoverageGuardNoticesAnEndpointWithoutAnAccessRule() {
        // the probe app deliberately serves /api/v1/probe/unlisted without a rule
        assertThatThrownBy(() -> RouteCoverage.assertEveryApiRouteHasExactlyOneRule(context))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("/api/v1/probe/unlisted");
    }

    // ---- service-to-service tokens ----

    @Test
    void everyOutgoingCallCarriesAShortLivedServiceTokenNamedAfterThisService() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            seen.set(exchange.getRequestHeaders().getFirst(HttpHeaders.AUTHORIZATION));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        try {
            restClientBuilder.build().get().uri("http://127.0.0.1:" + server.getAddress().getPort() + "/x").retrieve().toBodilessEntity();
        } finally {
            server.stop(0);
        }
        assertThat(seen.get()).startsWith("Bearer ");
        assertThat(jwt.parse(seen.get().substring("Bearer ".length())))
                .hasValueSatisfying(user -> {
                    assertThat(user.role()).isEqualTo(Role.SERVICE);
                    assertThat(user.username()).isEqualTo("probe-service");
                });
    }
}
