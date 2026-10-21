package com.talas.service;

import java.util.List;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import com.talas.dto.StationDto;
import com.talas.repository.PoliceStationRepository;
import com.talas.repository.StationQueries;

import lombok.RequiredArgsConstructor;

/** Lists police stations and finds the ones near a point. */
@Service
@RequiredArgsConstructor
public class StationService {

    private static final double MAX_RADIUS_KM = 50;

    private final PoliceStationRepository stationRepository;
    private final StationQueries stationQueries;

    // All stations; distanceKm is null because there is no reference point
    public List<StationDto> findAll() {
        return stationRepository.findAll(Sort.by("id")).stream()
                .map(s -> new StationDto(s.getId(), s.getAddress(), s.getContactNumber(),
                        s.getLatitude(), s.getLongitude(), null))
                .toList();
    }

    public List<StationDto> findNearby(double lat, double lng, double radiusKm) {
        if (lat < -90 || lat > 90 || lng < -180 || lng > 180) {
            throw new IllegalArgumentException("lat must be between -90 and 90, and lng between -180 and 180.");
        }
        if (radiusKm <= 0 || radiusKm > MAX_RADIUS_KM) {
            throw new IllegalArgumentException("radiusKm must be greater than 0 and at most 50.");
        }
        return stationQueries.findNearby(lat, lng, radiusKm * 1000);
    }
}
