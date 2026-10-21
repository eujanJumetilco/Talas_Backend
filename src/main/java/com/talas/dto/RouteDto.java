package com.talas.dto;

import java.util.List;

/** One route option. path is a list of [lat, lng] pairs; riskScore is the sum of nearby crime weights. */
public record RouteDto(
        String label,
        List<double[]> path,
        double distanceKm,
        long durationMin,
        int riskScore,
        List<NearbyCrimeDto> nearbyCrimes) {
}
