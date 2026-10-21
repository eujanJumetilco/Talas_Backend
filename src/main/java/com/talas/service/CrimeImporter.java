package com.talas.service;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.talas.config.AppConstants;
import com.talas.model.CrimeType;
import com.talas.model.Source;
import com.talas.repository.CrimeRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Loads the crime dataset (CSV) into the database on startup, but only when the crime table is empty.
 * Bad rows are skipped and counted; a summary is logged at the end.
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class CrimeImporter implements CommandLineRunner {

    private static final int BATCH_SIZE = 1000;
    private static final List<String> REQUIRED_COLUMNS = List.of("latitude", "longitude", "date", "crime_type");

    private static final String INSERT_SQL = """
            INSERT INTO crime (crime_type, source, occurred_at, latitude, longitude, address)
            VALUES (:crimeType, :source, :occurredAt, :latitude, :longitude, :address)
            """;

    // Date-and-time formats without a zone; these are treated as UTC. [:ss] means the seconds are optional.
    private static final List<DateTimeFormatter> NO_ZONE_FORMATS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm[:ss]"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm[:ss]"));

    private final CrimeRepository crimeRepository;
    private final NamedParameterJdbcTemplate jdbc;

    @Value("${talas.import-file}")
    private String importFile;

    /** Counters for the summary that is logged after the import. */
    private static class Summary {
        int imported;
        int outsideMetroManila;
        int dateOnly;
        Map<String, Integer> skipped = new TreeMap<>();
        List<String> badDateExamples = new ArrayList<>();
        Map<CrimeType, Integer> perType = new EnumMap<>(CrimeType.class);
        Map<Source, Integer> perSource = new EnumMap<>(Source.class);
        Map<String, Integer> unknownTypes = new TreeMap<>();
        Map<String, Integer> unknownSources = new TreeMap<>();
        Instant earliest;
        Instant latest;

        void skip(String reason) {
            skipped.merge(reason, 1, Integer::sum);
        }
    }

    // One transaction: if the import fails halfway, nothing is saved and the next start tries again
    @Override
    @Transactional
    public void run(String... args) throws IOException {
        if (crimeRepository.count() > 0) {
            log.info("The crime table already has data. Skipping the CSV import.");
            return;
        }

        Path path = Path.of(importFile);
        if (!Files.exists(path)) {
            log.info("No dataset found at {}. Starting with no crimes.", path.toAbsolutePath());
            return;
        }
        String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (fileName.endsWith(".xlsx") || fileName.endsWith(".xls")) {
            log.error("{} looks like an Excel file. Save it as CSV (UTF-8) and point talas.import-file to it.", path);
            return;
        }

        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader()                    // first row is the header
                .setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(true)
                .setAllowMissingColumnNames(true)
                .setTrim(true)
                .get();

        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8);
                CSVParser parser = format.parse(reader)) {

            // Lower-case header name -> header as written in the file, so "Latitude" and "latitude" both work.
            // ﻿ is the invisible "byte order mark" some programs (e.g. Excel) put at the start of a file.
            Map<String, String> columns = new HashMap<>();
            for (String header : parser.getHeaderNames()) {
                columns.put(header.replace("﻿", "").trim().toLowerCase(Locale.ROOT), header);
            }
            List<String> missing = REQUIRED_COLUMNS.stream().filter(c -> !columns.containsKey(c)).toList();
            if (!missing.isEmpty()) {
                log.error("The dataset is missing the required column(s) {}. Headers found: {}",
                        missing, parser.getHeaderNames());
                return;
            }

            Summary summary = new Summary();
            List<SqlParameterSource> batch = new ArrayList<>();
            for (CSVRecord record : parser) {
                MapSqlParameterSource row = readRow(record, columns, summary);
                if (row == null) {
                    continue;
                }
                batch.add(row);
                if (batch.size() == BATCH_SIZE) {
                    insert(batch);
                }
            }
            insert(batch);
            logSummary(path, summary);
        }
    }

    // Checks one CSV row and turns it into insert parameters, or returns null (and counts why) if it is skipped
    private MapSqlParameterSource readRow(CSVRecord record, Map<String, String> columns, Summary summary) {
        // Coordinates: must be numbers in range and not exactly 0 (0 usually means "missing")
        Double lat = parseCoordinate(value(record, columns, "latitude"), 90);
        Double lng = parseCoordinate(value(record, columns, "longitude"), 180);
        if (lat == null || lng == null) {
            summary.skip("missing or invalid latitude/longitude");
            return null;
        }

        String rawDate = value(record, columns, "date");
        if (rawDate == null) {
            summary.skip("missing date");
            return null;
        }
        Instant occurredAt = parseDate(rawDate, summary);
        if (occurredAt == null) {
            summary.skip("unreadable date");
            if (summary.badDateExamples.size() < 5) {
                summary.badDateExamples.add(rawDate);
            }
            return null;
        }

        // Crime type: unknown text is stored as OTHER and counted so the team can review it
        String rawType = value(record, columns, "crime_type");
        if (rawType == null) {
            summary.skip("missing crime_type");
            return null;
        }
        CrimeType type = CrimeType.fromText(rawType);
        if (type == null) {
            type = CrimeType.OTHER;
            summary.unknownTypes.merge(rawType, 1, Integer::sum);
        }

        // Source is optional: missing or unknown becomes OTHER
        String rawSource = value(record, columns, "source");
        Source source = rawSource == null ? Source.OTHER : Source.fromText(rawSource);
        if (source == null) {
            source = Source.OTHER;
            summary.unknownSources.merge(rawSource, 1, Integer::sum);
        }

        String address = buildAddress(value(record, columns, "address"), value(record, columns, "city"));

        // Count rows outside Metro Manila (they are still imported)
        if (!AppConstants.inMetroManila(lat, lng)) {
            summary.outsideMetroManila++;
        }
        summary.imported++;
        summary.perType.merge(type, 1, Integer::sum);
        summary.perSource.merge(source, 1, Integer::sum);
        if (summary.earliest == null || occurredAt.isBefore(summary.earliest)) {
            summary.earliest = occurredAt;
        }
        if (summary.latest == null || occurredAt.isAfter(summary.latest)) {
            summary.latest = occurredAt;
        }

        return new MapSqlParameterSource()
                .addValue("crimeType", type.name())
                .addValue("source", source.name())
                .addValue("occurredAt", OffsetDateTime.ofInstant(occurredAt, ZoneOffset.UTC))
                .addValue("latitude", lat)
                .addValue("longitude", lng)
                .addValue("address", address);
    }

    // Sends the collected rows to the database in one batch, then empties the list
    private void insert(List<SqlParameterSource> batch) {
        if (batch.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(INSERT_SQL, batch.toArray(new SqlParameterSource[0]));
        batch.clear();
    }

    // The trimmed cell value, or null if the column is missing or the cell is blank
    private static String value(CSVRecord record, Map<String, String> columns, String column) {
        String header = columns.get(column);
        if (header == null || !record.isSet(header)) {
            return null;
        }
        String value = record.get(header).trim();
        return value.isEmpty() ? null : value;
    }

    private static Double parseCoordinate(String raw, double maxAbs) {
        if (raw == null) {
            return null;
        }
        try {
            double value = Double.parseDouble(raw);
            if (Double.isNaN(value) || value == 0 || Math.abs(value) > maxAbs) {
                return null;
            }
            return value;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // Tries the accepted date formats in order and returns the time in UTC, or null if none fits
    private static Instant parseDate(String raw, Summary summary) {
        // 1. ISO-8601 with a zone or offset, e.g. 2026-09-14T13:30:00Z or 2026-09-14T13:30:00+00:00
        try {
            return OffsetDateTime.parse(raw).toInstant();
        } catch (DateTimeParseException ignored) {
            // try the next format
        }
        // 2. Date and time without a zone: treated as UTC
        for (DateTimeFormatter format : NO_ZONE_FORMATS) {
            try {
                return LocalDateTime.parse(raw, format).toInstant(ZoneOffset.UTC);
            } catch (DateTimeParseException ignored) {
                // try the next format
            }
        }
        // 3. Date only: midnight UTC
        try {
            Instant midnight = LocalDate.parse(raw).atStartOfDay(ZoneOffset.UTC).toInstant();
            summary.dateOnly++;
            return midnight;
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    // Adds the city to the address unless the address already mentions it; cuts to 255 characters
    private static String buildAddress(String address, String city) {
        String result = address;
        if (city != null) {
            if (result == null) {
                result = city;
            } else if (!result.toLowerCase(Locale.ROOT).contains(city.toLowerCase(Locale.ROOT))) {
                result = result + ", " + city;
            }
        }
        if (result != null && result.length() > 255) {
            result = result.substring(0, 255);
        }
        return result;
    }

    private void logSummary(Path path, Summary s) {
        log.info("=== Crime import summary ({}) ===", path);
        log.info("Rows imported: {}", s.imported);
        if (s.skipped.isEmpty()) {
            log.info("Rows skipped: 0");
        } else {
            log.info("Rows skipped by reason: {}", s.skipped);
        }
        if (!s.badDateExamples.isEmpty()) {
            log.info("Examples of unreadable dates: {}", s.badDateExamples);
        }
        log.info("Rows outside Metro Manila (still imported): {}", s.outsideMetroManila);
        log.info("Rows with a date but no time (stored as 00:00 UTC): {}", s.dateOnly);
        log.info("Count per crime type: {}", s.perType);
        log.info("Count per source: {}", s.perSource);
        if (!s.unknownTypes.isEmpty()) {
            log.warn("Unknown crime types (stored as OTHER): {}", s.unknownTypes);
        }
        if (!s.unknownSources.isEmpty()) {
            log.warn("Unknown sources (stored as OTHER): {}", s.unknownSources);
        }
        log.info("Earliest: {}  Latest: {}  (UTC)", s.earliest, s.latest);
    }
}
