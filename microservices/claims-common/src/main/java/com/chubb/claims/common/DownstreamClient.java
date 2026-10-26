package com.chubb.claims.common;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Synchronous REST calls from one service to another, with one failure policy so every service reports downstream
 * problems the same way:
 * <ul>
 *   <li>400, 404 and 409 from the other service are business answers (unknown claim, invalid transition, bad amount)
 *       and are re-raised with the same status, code and message, so the caller's client sees the contract it always had;</li>
 *   <li>a connection failure or timeout becomes 503 {@code DOWNSTREAM_UNAVAILABLE};</li>
 *   <li>anything else (5xx, a 404 for a missing route, an unreadable body) becomes 502 {@code DOWNSTREAM_ERROR}
 *       with no detail from the other service.</li>
 * </ul>
 * There are no retries: the calls change state, and repeating one that may already have succeeded is not safe.
 */
public class DownstreamClient {

    /** 404 with this code means the route does not exist (a wiring problem), not that the claim does not exist. */
    private static final String ROUTE_NOT_FOUND = "NOT_FOUND";
    private static final Set<Integer> BUSINESS_STATUSES = Set.of(400, 404, 409);

    private static final ObjectMapper ERROR_MAPPER = JsonMapper.builder()
            .findAndAddModules()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private final String serviceName;
    private final RestClient rest;

    /**
     * @param builder        the application's {@link RestClient.Builder}, so JSON settings match the rest of the service
     * @param serviceName    human name used in error messages, e.g. {@code "Claim Service"}
     * @param baseUrl        e.g. {@code http://localhost:8181}
     */
    public DownstreamClient(RestClient.Builder builder, String serviceName, String baseUrl, Duration connectTimeout,
                            Duration readTimeout) {
        // HTTP/1.1 only: the JDK client otherwise tries an h2c upgrade on plain http. It never retries a POST.
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(connectTimeout).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(readTimeout);
        this.serviceName = serviceName;
        this.rest = builder.clone().baseUrl(baseUrl).requestFactory(factory).build();
    }

    public <T> T get(Class<T> type, String uri, Object... uriVariables) {
        return call(() -> rest.get().uri(uri, uriVariables).retrieve().onStatus(HttpStatusCode::isError, this::fail).body(type));
    }

    public <T> T get(ParameterizedTypeReference<T> type, String uri, Object... uriVariables) {
        return call(() -> rest.get().uri(uri, uriVariables).retrieve().onStatus(HttpStatusCode::isError, this::fail).body(type));
    }

    /** POST with a JSON body; pass {@code null} for calls that carry no body. */
    public <T> T post(Class<T> type, Object body, String uri, Object... uriVariables) {
        return call(() -> {
            RestClient.RequestBodySpec request = rest.post().uri(uri, uriVariables);
            if (body != null) {
                request = request.contentType(MediaType.APPLICATION_JSON).body(body);
            }
            return request.retrieve().onStatus(HttpStatusCode::isError, this::fail).body(type);
        });
    }

    private <T> T call(Supplier<T> action) {
        try {
            return action.get();
        } catch (ResourceAccessException e) {
            throw ApiException.unavailable("DOWNSTREAM_UNAVAILABLE", serviceName + " is unavailable or did not answer in time");
        } catch (RestClientException e) {
            throw ApiException.badGateway("DOWNSTREAM_ERROR", serviceName + " returned a response this service could not read");
        }
    }

    private void fail(HttpRequest request, ClientHttpResponse response) throws IOException {
        int status = response.getStatusCode().value();
        ApiError error = null;
        try {
            error = ERROR_MAPPER.readValue(response.getBody(), ApiError.class);
        } catch (IOException | RuntimeException ignored) {
            // not our error shape: treated as an unexpected downstream answer below
        }
        if (BUSINESS_STATUSES.contains(status) && error != null && error.code() != null && !ROUTE_NOT_FOUND.equals(error.code())) {
            throw ApiException.of(HttpStatus.valueOf(status), error.code(), error.message());
        }
        throw ApiException.badGateway("DOWNSTREAM_ERROR", serviceName + " answered with HTTP " + status + " which this service cannot handle");
    }
}
