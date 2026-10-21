package com.talas.dto;

import java.time.Instant;

import com.talas.model.Crime;

/** One crime as sent to the frontend. occurredAt is UTC; address may be null. */
public record CrimeDto(
        Long id,
        String type,
        String source,
        double lat,
        double lng,
        Instant occurredAt,
        String address) {

    public static CrimeDto from(Crime crime) {
        return new CrimeDto(crime.getId(), crime.getCrimeType().name(), crime.getSource().name(),
                crime.getLatitude(), crime.getLongitude(), crime.getOccurredAt(), crime.getAddress());
    }
}
