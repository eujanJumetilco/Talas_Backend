package com.talas.dto;

/** A police station. distanceKm is null when no reference point was given. */
public record StationDto(Long id, String address, String contactNumber, double lat, double lng, Double distanceKm) {
}
