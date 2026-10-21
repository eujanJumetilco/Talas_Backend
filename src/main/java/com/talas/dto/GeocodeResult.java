package com.talas.dto;

/** One place found by GET /api/geocode. */
public record GeocodeResult(String label, double lat, double lng) {
}
