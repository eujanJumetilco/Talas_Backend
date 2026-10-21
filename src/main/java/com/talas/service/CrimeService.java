package com.talas.service;

import static com.talas.config.AppConstants.MAX_CRIME_LIMIT;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.stereotype.Service;

import com.talas.config.AppConstants;
import com.talas.dto.CrimeDto;
import com.talas.dto.CrimeRequest;
import com.talas.model.Crime;
import com.talas.model.CrimeType;
import com.talas.model.Source;
import com.talas.repository.CrimeQueries;
import com.talas.repository.CrimeRepository;

import lombok.RequiredArgsConstructor;

/** Lists crimes, adds new crime reports and corrects existing ones. */
@Service
@RequiredArgsConstructor
public class CrimeService {

    private final CrimeRepository crimeRepository;
    private final CrimeQueries crimeQueries;

    public List<CrimeDto> findCrimes(CrimeFilter filter, int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1.");
        }
        return crimeQueries.findCrimes(filter, Math.min(limit, MAX_CRIME_LIMIT));
    }

    public CrimeDto create(CrimeRequest request) {
        Crime crime = new Crime();
        apply(request, crime);
        return CrimeDto.from(crimeRepository.save(crime));
    }

    public CrimeDto update(Long id, CrimeRequest request) {
        Crime crime = crimeRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Crime not found."));
        apply(request, crime);
        return CrimeDto.from(crimeRepository.save(crime));
    }

    // Checks the request (beyond the @NotBlank/@NotNull annotations) and copies the values into the entity.
    // The database fills in the PostGIS location column itself, so we only set latitude and longitude.
    private void apply(CrimeRequest request, Crime target) {
        CrimeType type = CrimeType.fromText(request.type());
        if (type == null) {
            throw new IllegalArgumentException("Unknown crime type: " + request.type());
        }

        // Source is optional and defaults to OTHER
        Source source = Source.OTHER;
        if (request.source() != null && !request.source().isBlank()) {
            source = Source.fromText(request.source());
            if (source == null) {
                throw new IllegalArgumentException("Unknown source: " + request.source());
            }
        }

        if (!AppConstants.inMetroManila(request.lat(), request.lng())) {
            throw new IllegalArgumentException("Location must be within Metro Manila.");
        }

        // The time must say which zone it is in (Z or an offset such as +08:00); it is stored as UTC
        Instant occurredAt;
        try {
            occurredAt = OffsetDateTime.parse(request.occurredAt().trim()).toInstant();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "occurredAt must include a time zone, for example 2026-10-19T13:30:00Z.");
        }
        // Allow 5 minutes of clock difference between the user's device and the server
        if (occurredAt.isAfter(Instant.now().plus(5, ChronoUnit.MINUTES))) {
            throw new IllegalArgumentException("occurredAt cannot be in the future.");
        }

        String address = request.address() == null || request.address().isBlank()
                ? null
                : request.address().trim();

        target.setCrimeType(type);
        target.setSource(source);
        target.setLatitude(request.lat());
        target.setLongitude(request.lng());
        target.setOccurredAt(occurredAt);
        target.setAddress(address);
    }
}
