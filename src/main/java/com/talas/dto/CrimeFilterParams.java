package com.talas.dto;

/**
 * The optional query params shared by GET /api/crimes and GET /api/stats.
 * Spring fills this record from the query string; CrimeFilter validates it.
 */
public record CrimeFilterParams(
        String area,
        String type,
        String source,
        String from,
        String to,
        Double minLat,
        Double minLng,
        Double maxLat,
        Double maxLng) {
}
