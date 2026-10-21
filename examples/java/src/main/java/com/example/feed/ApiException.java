package com.example.feed;

import org.springframework.http.HttpStatus;

/** An error with a status and a client-facing message, rendered as {"error":"<message>"}. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message, null, false, false);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
