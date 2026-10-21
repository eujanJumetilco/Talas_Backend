package com.talas.service;

/** Thrown when OpenRouteService cannot be reached or returns an error. Becomes HTTP 502. */
public class OrsUnavailableException extends RuntimeException {

    public OrsUnavailableException() {
        super("Routing service is unavailable right now. Please try again later.");
    }
}
