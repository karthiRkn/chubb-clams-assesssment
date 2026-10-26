package com.chubb.claims.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;

/** The one failure policy every service uses when it calls another, checked against a real local HTTP server. */
class DownstreamClientTest {

    record View(String claimId, String status) {
    }

    record Echo(String officerId) {
    }

    private static HttpServer server;
    private static DownstreamClient client;

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/ok", ex -> reply(ex, 200, "{\"claimId\":\"CLM-1\",\"status\":\"SUBMITTED\",\"extra\":\"ignored\"}"));
        server.createContext("/claim-missing", ex -> reply(ex, 404, apiError(404, "Not Found", "CLAIM_NOT_FOUND", "Claim CLM-9 was not found")));
        server.createContext("/route-missing", ex -> reply(ex, 404, apiError(404, "Not Found", "NOT_FOUND", "Resource not found")));
        server.createContext("/conflict", ex -> reply(ex, 409, apiError(409, "Conflict", "INVALID_CLAIM_TRANSITION", "claim is SETTLED")));
        server.createContext("/bad", ex -> reply(ex, 400, apiError(400, "Bad Request", "INVALID_SETTLED_AMOUNT", "too much")));
        server.createContext("/method", ex -> reply(ex, 405, apiError(405, "Method Not Allowed", "METHOD_NOT_ALLOWED", "no")));
        server.createContext("/unauthenticated", ex -> reply(ex, 401, apiError(401, "Unauthorized", "UNAUTHENTICATED", "token rejected")));
        server.createContext("/forbidden", ex -> reply(ex, 403, apiError(403, "Forbidden", "FORBIDDEN", "role not allowed")));
        server.createContext("/boom", ex -> reply(ex, 500, "<html>stack trace with secrets</html>"));
        server.createContext("/odd-404", ex -> reply(ex, 404, "<html>nginx</html>"));
        server.createContext("/garbage", ex -> reply(ex, 200, "this is not json"));
        server.createContext("/slow", ex -> {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            reply(ex, 200, "{}");
        });
        server.createContext("/echo", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String type = ex.getRequestHeaders().getFirst("Content-Type");
            reply(ex, 200, "{\"officerId\":\"" + (type != null && type.startsWith("application/json") ? "json:" : "other:")
                    + body.replaceAll("[^A-Za-z0-9:\\-]", "") + "\"}");
        });
        server.start();
        client = clientFor(server.getAddress().getPort(), Duration.ofMillis(800));
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    private static DownstreamClient clientFor(int port, Duration readTimeout) {
        return new DownstreamClient(RestClient.builder(), "Test Service", "http://127.0.0.1:" + port, Duration.ofSeconds(1), readTimeout);
    }

    private static String apiError(int status, String error, String code, String message) {
        return "{\"timestamp\":\"2026-10-22T04:00:00Z\",\"status\":" + status + ",\"error\":\"" + error + "\",\"code\":\"" + code
                + "\",\"message\":\"" + message + "\",\"path\":\"/x\"}";
    }

    private static void reply(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        boolean html = body.startsWith("<");
        ex.getResponseHeaders().add("Content-Type", html ? "text/html" : "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static ApiException failure(Runnable call) {
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(call::run);
        assertThat(thrown).isInstanceOf(ApiException.class);
        return (ApiException) thrown;
    }

    @Test
    void successIsDeserialisedAndUnknownFieldsAreIgnored() {
        View view = client.get(View.class, "/ok");
        assertThat(view).isEqualTo(new View("CLM-1", "SUBMITTED"));
    }

    @Test
    void aBodyIsSentAsJson() {
        Echo echo = client.post(Echo.class, new Echo("OFF-1"), "/echo");
        assertThat(echo.officerId()).startsWith("json:").contains("OFF-1");
    }

    @Test
    void aPostWithoutABodyIsAllowed() {
        assertThat(client.post(Echo.class, null, "/echo").officerId()).isNotNull();
    }

    @Test
    void businessAnswersKeepTheirStatusCodeAndMessage() {
        ApiException notFound = failure(() -> client.get(View.class, "/claim-missing"));
        assertThat(notFound.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(notFound.getCode()).isEqualTo("CLAIM_NOT_FOUND");
        assertThat(notFound.getMessage()).isEqualTo("Claim CLM-9 was not found");

        ApiException conflict = failure(() -> client.post(View.class, new Echo("x"), "/conflict"));
        assertThat(conflict.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflict.getCode()).isEqualTo("INVALID_CLAIM_TRANSITION");

        ApiException bad = failure(() -> client.post(View.class, new Echo("x"), "/bad"));
        assertThat(bad.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(bad.getCode()).isEqualTo("INVALID_SETTLED_AMOUNT");
    }

    @Test
    void aMissingRouteIsAWiringProblemNotAMissingClaim() {
        ApiException e = failure(() -> client.get(View.class, "/route-missing"));
        assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(e.getCode()).isEqualTo("DOWNSTREAM_ERROR");
    }

    @Test
    void unexpectedAnswersBecome502WithoutLeakingTheirBody() {
        for (String path : new String[] {"/boom", "/odd-404", "/method"}) {
            ApiException e = failure(() -> client.get(View.class, path));
            assertThat(e.getStatus()).as(path).isEqualTo(HttpStatus.BAD_GATEWAY);
            assertThat(e.getCode()).as(path).isEqualTo("DOWNSTREAM_ERROR");
            assertThat(e.getMessage()).as(path).contains("Test Service").doesNotContain("secrets").doesNotContain("nginx");
        }
    }

    @Test
    void aRefusedServiceTokenIsAConfigurationProblemAndBecomes502NotA401() {
        // if this service's token is not accepted (for example the secrets differ), the caller of THIS service did nothing
        // wrong, so it must not be told 401/403 and must not learn why
        for (String path : new String[] {"/unauthenticated", "/forbidden"}) {
            ApiException e = failure(() -> client.get(View.class, path));
            assertThat(e.getStatus()).as(path).isEqualTo(HttpStatus.BAD_GATEWAY);
            assertThat(e.getCode()).as(path).isEqualTo("DOWNSTREAM_ERROR");
            assertThat(e.getMessage()).as(path).doesNotContain("token").doesNotContain("role");
        }
    }

    @Test
    void anUnreadableSuccessResponseBecomes502() {
        ApiException e = failure(() -> client.get(View.class, "/garbage"));
        assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(e.getCode()).isEqualTo("DOWNSTREAM_ERROR");
    }

    @Test
    void aServiceThatIsNotListeningIs503() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            closedPort = socket.getLocalPort();
        }
        DownstreamClient down = clientFor(closedPort, Duration.ofSeconds(1));
        assertThatThrownBy(() -> down.get(View.class, "/ok"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.getCode()).isEqualTo("DOWNSTREAM_UNAVAILABLE");
                    assertThat(e.getMessage()).contains("Test Service");
                });
    }

    @Test
    void aServiceThatAnswersTooSlowlyIs503() {
        ApiException e = failure(() -> client.get(View.class, "/slow"));
        assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(e.getCode()).isEqualTo("DOWNSTREAM_UNAVAILABLE");
    }

    @Test
    void uriVariablesAreEncodedIntoThePath() {
        assertThat(client.get(View.class, "/{name}", "ok").claimId()).isEqualTo("CLM-1");
    }
}
