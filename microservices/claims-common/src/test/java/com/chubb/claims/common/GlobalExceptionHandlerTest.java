package com.chubb.claims.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void aClientThatClosedTheConnectionIsNotReportedAsAServerError() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.handleClientAbort(new AsyncRequestNotUsableException("ServletOutputStream failed to flush"),
                new MockHttpServletRequest("GET", "/v3/api-docs"), response);
        // nothing is written: there is nobody left to read it
        assertThat(response.getContentAsString()).isEmpty();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void businessErrorsKeepTheirStatusAndCode() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/claims/CLM-1");
        ResponseEntity<ApiError> response = handler.handleApi(ApiException.notFound("CLAIM_NOT_FOUND", "Claim CLM-1 was not found"), request);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().code()).isEqualTo("CLAIM_NOT_FOUND");
        assertThat(response.getBody().path()).isEqualTo("/api/v1/claims/CLM-1");
    }

    @Test
    void anythingUnexpectedIs500WithoutDetail() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
        ResponseEntity<ApiError> response = handler.handleUnexpected(new IllegalStateException("secret internals"), request);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().code()).isEqualTo("INTERNAL_ERROR");
        assertThat(response.getBody().message()).doesNotContain("secret");
    }

    @Test
    void securityExceptionsAreNeverTurnedIntoA500() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/claims/CLM-1");

        ResponseEntity<ApiError> denied = handler.handleAccessDenied(new AccessDeniedException("owner is someone else"), request);
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(denied.getBody().code()).isEqualTo("FORBIDDEN");
        assertThat(denied.getBody().message()).doesNotContain("owner");

        ResponseEntity<ApiError> unauthenticated = handler.handleAuthentication(new BadCredentialsException("bad password for kate"), request);
        assertThat(unauthenticated.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(unauthenticated.getBody().code()).isEqualTo("UNAUTHENTICATED");
        assertThat(unauthenticated.getBody().message()).doesNotContain("kate");
    }
}
