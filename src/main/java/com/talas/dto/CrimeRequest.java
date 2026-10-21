package com.talas.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Body of POST /api/crimes and PUT /api/crimes/{id}. Further checks happen in CrimeService. */
public record CrimeRequest(
        @NotBlank(message = "type is required.") String type,
        String source,
        @NotNull(message = "lat is required.") Double lat,
        @NotNull(message = "lng is required.") Double lng,
        @NotBlank(message = "occurredAt is required.") String occurredAt,
        @Size(max = 255, message = "address must be at most 255 characters.") String address) {
}
