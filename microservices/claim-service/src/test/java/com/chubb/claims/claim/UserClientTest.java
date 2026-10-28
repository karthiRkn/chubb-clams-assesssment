package com.chubb.claims.claim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chubb.claims.claim.client.UserClient;
import com.chubb.claims.common.ApiException;
import com.chubb.claims.common.DownstreamClient;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;

/** The real client against a real local HTTP server standing in for the User Service. No Spring context. */
class UserClientTest {

    private HttpServer server;
    private final AtomicReference<String> requestedPath = new AtomicReference<>();
    private final AtomicReference<String> requestedQuery = new AtomicReference<>();
    private volatile int status;
    private volatile String body;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            requestedPath.set(exchange.getRequestURI().getPath());
            requestedQuery.set(exchange.getRequestURI().getRawQuery());
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private UserClient client() {
        return client("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private static UserClient client(String url) {
        return new UserClient(new DownstreamClient(RestClient.builder(), "User Service", url, Duration.ofSeconds(1), Duration.ofSeconds(2)));
    }

    private void answer(int httpStatus, String json) {
        this.status = httpStatus;
        this.body = json;
    }

    @Test
    void anActiveClaimsOfficerIsEligibleAndTheirNameIsPartOfTheInternalPath() {
        answer(200, "{\"username\":\"officer.a\",\"role\":\"CLAIMS_OFFICER\",\"active\":true,\"somethingNew\":1}");
        assertThat(client().isActiveClaimsOfficer("officer.a")).isTrue();
        assertThat(requestedPath.get()).isEqualTo("/internal/v1/users/officer.a");
    }

    @Test
    void aSwitchedOffOfficerAndEveryOtherRoleAreNotEligible() {
        answer(200, "{\"username\":\"officer.a\",\"role\":\"CLAIMS_OFFICER\",\"active\":false}");
        assertThat(client().isActiveClaimsOfficer("officer.a")).isFalse();
        for (String role : new String[] {"CLAIMANT", "MANAGER", "ADMIN", "SOMETHING_NEW"}) {
            answer(200, "{\"username\":\"x.y.z\",\"role\":\"" + role + "\",\"active\":true}");
            assertThat(client().isActiveClaimsOfficer("x.y.z")).as(role).isFalse();
        }
    }

    @Test
    void anUnknownUserIsNotEligibleRatherThanAnError() {
        answer(404, "{\"timestamp\":\"2026-10-26T10:00:00Z\",\"status\":404,\"error\":\"Not Found\",\"code\":\"USER_NOT_FOUND\","
                + "\"message\":\"User nobody was not found\",\"path\":\"/internal/v1/users/nobody\"}");
        assertThat(client().isActiveClaimsOfficer("nobody")).isFalse();
    }

    @Test
    void aMissingRouteOrAServerErrorIsAGatewayErrorNotAnAnswer() {
        // a 404 that is not USER_NOT_FOUND means the route is not there: a wiring fault, never "not an officer"
        answer(404, "{\"status\":404,\"error\":\"Not Found\",\"code\":\"NOT_FOUND\",\"message\":\"m\",\"path\":\"/x\"}");
        assertThatThrownBy(() -> client().isActiveClaimsOfficer("officer.a")).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
            assertThat(e.getCode()).isEqualTo("DOWNSTREAM_ERROR");
        });
        answer(500, "{\"status\":500}");
        assertThatThrownBy(() -> client().isActiveClaimsOfficer("officer.a")).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY));
        // a 401/403 from the User Service means the shared secret is wrong: also not an answer
        answer(403, "{\"status\":403,\"code\":\"FORBIDDEN\",\"message\":\"m\"}");
        assertThatThrownBy(() -> client().isActiveClaimsOfficer("officer.a")).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY));
    }

    @Test
    void anUnreachableUserServiceIs503() {
        int closedPort = server.getAddress().getPort();
        server.stop(0);
        assertThatThrownBy(() -> client("http://127.0.0.1:" + closedPort).isActiveClaimsOfficer("officer.a"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.getCode()).isEqualTo("DOWNSTREAM_UNAVAILABLE");
                });
    }

    // ---- the list of active claims officers (the officer report) ----

    @Test
    void theActiveOfficersAreAskedForByRoleAndActiveFlagAndReturnedByUsername() {
        answer(200, "[{\"username\":\"officer1\",\"role\":\"CLAIMS_OFFICER\",\"active\":true,\"somethingNew\":1},"
                + "{\"username\":\"officer2\",\"role\":\"CLAIMS_OFFICER\",\"active\":true}]");
        assertThat(client().activeClaimsOfficers()).containsExactly("officer1", "officer2");
        assertThat(requestedPath.get()).isEqualTo("/internal/v1/users");
        assertThat(requestedQuery.get()).isEqualTo("role=CLAIMS_OFFICER&active=true");
    }

    @Test
    void anEmptyListIsNoOfficersAndAnythingThatIsNotAnActiveOfficerIsLeftOutEvenIfTheServiceSentIt() {
        answer(200, "[]");
        assertThat(client().activeClaimsOfficers()).isEmpty();
        answer(200, "[{\"username\":\"off.a\",\"role\":\"CLAIMS_OFFICER\",\"active\":false},"
                + "{\"username\":\"mgr.a\",\"role\":\"MANAGER\",\"active\":true},"
                + "{\"username\":\"new.a\",\"role\":\"SOMETHING_NEW\",\"active\":true},"
                + "{\"username\":\"off.b\",\"role\":\"CLAIMS_OFFICER\",\"active\":true}]");
        assertThat(client().activeClaimsOfficers()).containsExactly("off.b");
    }

    @Test
    void theOfficerListIsNeverGuessedWhenTheUserServiceFails() {
        int closedPort = server.getAddress().getPort();
        answer(500, "{\"status\":500}");
        assertThatThrownBy(() -> client().activeClaimsOfficers()).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY));
        // a 403 means the shared secret is wrong; a body that is not a list is a response nobody can read
        answer(403, "{\"status\":403,\"code\":\"FORBIDDEN\",\"message\":\"m\"}");
        assertThatThrownBy(() -> client().activeClaimsOfficers()).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY));
        answer(200, "{\"not\":\"a list\"}");
        assertThatThrownBy(() -> client().activeClaimsOfficers()).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY));
        server.stop(0);
        assertThatThrownBy(() -> client("http://127.0.0.1:" + closedPort).activeClaimsOfficers())
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.getCode()).isEqualTo("DOWNSTREAM_UNAVAILABLE");
                });
    }
}
