package com.talas.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** Body of POST /api/routes/safe. mode and bufferMeters are optional (defaults in RouteService). */
public record RouteRequest(
        @NotNull(message = "origin is required.") @Valid LatLng origin,
        @NotNull(message = "destination is required.") @Valid LatLng destination,
        @Pattern(regexp = "driving-car|foot-walking", message = "mode must be driving-car or foot-walking.") String mode,
        @Min(value = 50, message = "bufferMeters must be between 50 and 500.")
        @Max(value = 500, message = "bufferMeters must be between 50 and 500.") Integer bufferMeters) {
}
