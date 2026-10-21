package com.talas.dto;

import java.time.Instant;

/** A crime close to a route, with its distance from the route line in meters. */
public record NearbyCrimeDto(
        Long id,
        String type,
        double lat,
        double lng,
        Instant occurredAt,
        String address,
        int distanceM) {
}
