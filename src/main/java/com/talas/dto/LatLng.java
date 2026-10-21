package com.talas.dto;

import jakarta.validation.constraints.NotNull;

/** A point as the frontend sends it: lat first, then lng. */
public record LatLng(
        @NotNull(message = "lat is required.") Double lat,
        @NotNull(message = "lng is required.") Double lng) {
}
