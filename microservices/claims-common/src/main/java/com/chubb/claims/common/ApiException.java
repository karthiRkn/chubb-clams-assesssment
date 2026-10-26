package com.chubb.claims.common;

import org.springframework.http.HttpStatus;

/** Business-level failure that maps to a specific HTTP status and machine-readable code. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    private ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    /** The caller is known, but this action is not theirs to take (not a missing or invalid token: that is a 401). */
    public static ApiException forbidden(String code, String message) {
        return new ApiException(HttpStatus.FORBIDDEN, code, message);
    }

    /** Re-raises a failure reported by another service with the status and code it used. */
    public static ApiException of(HttpStatus status, String code, String message) {
        return new ApiException(status, code, message);
    }

    /** Another service answered, but with something this service cannot use (5xx, unexpected 4xx, unreadable body). */
    public static ApiException badGateway(String code, String message) {
        return new ApiException(HttpStatus.BAD_GATEWAY, code, message);
    }

    /** Another service could not be reached or did not answer in time. */
    public static ApiException unavailable(String code, String message) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, code, message);
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
