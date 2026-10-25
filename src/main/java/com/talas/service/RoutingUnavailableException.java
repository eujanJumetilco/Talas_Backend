package com.talas.service;

/** Thrown when the routing or geocoding provider cannot be reached or returns an error. Becomes HTTP 502. */
public class RoutingUnavailableException extends RuntimeException {

    public RoutingUnavailableException() {
        super("Routing service is unavailable right now. Please try again later.");
    }
}
