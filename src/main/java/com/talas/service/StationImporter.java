package com.talas.service;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.talas.model.PoliceStation;
import com.talas.repository.PoliceStationRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Loads police stations from src/main/resources/stations.csv on startup, only when the station table is empty.
 * Header: address,contact_number,latitude,longitude. The address should include the station's name.
 */
@Slf4j
@Component
@Order(2)
@RequiredArgsConstructor
public class StationImporter implements CommandLineRunner {

    private final PoliceStationRepository stationRepository;

    @Override
    public void run(String... args) throws IOException {
        if (stationRepository.count() > 0) {
            log.info("The police_station table already has data. Skipping stations.csv.");
            return;
        }
        ClassPathResource file = new ClassPathResource("stations.csv");
        if (!file.exists()) {
            log.info("No stations.csv found. Starting with no police stations.");
            return;
        }

        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(true)
                .setTrim(true)
                .get();

        List<PoliceStation> stations = new ArrayList<>();
        try (Reader reader = new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8);
                CSVParser parser = format.parse(reader)) {
            for (CSVRecord record : parser) {
                try {
                    PoliceStation station = new PoliceStation();
                    station.setAddress(record.get("address"));
                    station.setContactNumber(record.get("contact_number").isEmpty() ? null : record.get("contact_number"));
                    station.setLatitude(Double.parseDouble(record.get("latitude")));
                    station.setLongitude(Double.parseDouble(record.get("longitude")));
                    if (station.getAddress().isEmpty()) {
                        throw new IllegalArgumentException("address is empty");
                    }
                    stations.add(station);
                } catch (IllegalArgumentException e) {
                    // NumberFormatException is an IllegalArgumentException too
                    log.warn("Skipping stations.csv line {}: {}", record.getRecordNumber() + 1, e.getMessage());
                }
            }
        }

        if (stations.isEmpty()) {
            log.info("stations.csv has no station rows yet. Add stations to src/main/resources/stations.csv "
                    + "(header: address,contact_number,latitude,longitude).");
            return;
        }
        stationRepository.saveAll(stations);
        log.info("Imported {} police stations.", stations.size());
    }
}
