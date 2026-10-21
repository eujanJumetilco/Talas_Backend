package com.talas.service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

import com.talas.dto.CrimeFilterParams;
import com.talas.model.CrimeType;
import com.talas.model.Source;

/**
 * Turns the optional filter params into a SQL WHERE clause plus its parameters.
 * Collects one SQL condition per filter the user provided, then joins them with AND.
 * Invalid input throws IllegalArgumentException, which becomes HTTP 400.
 */
public class CrimeFilter {

    private final List<String> conditions = new ArrayList<>();
    private final MapSqlParameterSource params = new MapSqlParameterSource();

    public CrimeFilter(CrimeFilterParams p) {
        // Crime type: accept any spelling fromText understands, store the exact enum name
        if (hasText(p.type())) {
            CrimeType type = CrimeType.fromText(p.type());
            if (type == null) {
                throw new IllegalArgumentException("Unknown crime type: " + p.type());
            }
            add("crime_type = :type");
            params.addValue("type", type.name());
        }

        if (hasText(p.source())) {
            Source source = Source.fromText(p.source());
            if (source == null) {
                throw new IllegalArgumentException("Unknown source: " + p.source());
            }
            add("source = :source");
            params.addValue("source", source.name());
        }

        // Area: address contains the text, ignoring case. strpos needs no escaping of % or _ (unlike LIKE).
        // A crime with no address gives strpos(NULL, ...) = NULL, so it never matches.
        if (hasText(p.area())) {
            add("strpos(lower(address), lower(:area)) > 0");
            params.addValue("area", p.area().trim());
        }

        // Time range in UTC: from is inclusive, to is exclusive
        OffsetDateTime from = parseUtc(p.from());
        OffsetDateTime to = parseUtc(p.to());
        if (from != null && to != null && !from.isBefore(to)) {
            throw new IllegalArgumentException("from must be earlier than to.");
        }
        if (from != null) {
            add("occurred_at >= :from");
            params.addValue("from", from);
        }
        if (to != null) {
            add("occurred_at < :to");
            params.addValue("to", to);
        }

        // Bounding box: all four values or none
        int boxValues = countNonNull(p.minLat(), p.minLng(), p.maxLat(), p.maxLng());
        if (boxValues != 0 && boxValues != 4) {
            throw new IllegalArgumentException(
                    "Provide all four bounding box values (minLat, minLng, maxLat, maxLng).");
        }
        if (boxValues == 4) {
            if (p.minLat() >= p.maxLat() || p.minLng() >= p.maxLng()) {
                throw new IllegalArgumentException("minLat must be less than maxLat, and minLng less than maxLng.");
            }
            // ST_MakeEnvelope takes (west, south, east, north), i.e. lng before lat.
            // The GiST index on location makes this search fast.
            add("ST_Intersects(location, ST_MakeEnvelope(:minLng, :minLat, :maxLng, :maxLat, 4326)::geography)");
            params.addValue("minLat", p.minLat());
            params.addValue("minLng", p.minLng());
            params.addValue("maxLat", p.maxLat());
            params.addValue("maxLng", p.maxLng());
        }
    }

    private void add(String condition) {
        conditions.add(condition);
    }

    /** " WHERE a AND b AND c", or "" when no filter was given. */
    public String whereClause() {
        return conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
    }

    public MapSqlParameterSource params() {
        return params;
    }

    // Parses an ISO-8601 timestamp that includes a zone (Z or +08:00) and converts it to UTC
    private static OffsetDateTime parseUtc(String raw) {
        if (!hasText(raw)) {
            return null;
        }
        try {
            return OffsetDateTime.parse(raw.trim()).withOffsetSameInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "from and to must include a time zone, for example 2026-01-01T00:00:00Z.");
        }
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private static int countNonNull(Object... values) {
        int count = 0;
        for (Object v : values) {
            if (v != null) {
                count++;
            }
        }
        return count;
    }
}
