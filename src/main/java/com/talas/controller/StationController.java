package com.talas.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.talas.dto.StationDto;
import com.talas.service.StationService;

import lombok.RequiredArgsConstructor;

/** Police stations: all of them, or the ones near a point. */
@RestController
@RequiredArgsConstructor
public class StationController {

    private final StationService stationService;

    @GetMapping("/api/stations")
    public List<StationDto> all() {
        return stationService.findAll();
    }

    @GetMapping("/api/stations/nearby")
    public List<StationDto> nearby(@RequestParam double lat, @RequestParam double lng,
            @RequestParam(defaultValue = "2") double radiusKm) {
        return stationService.findNearby(lat, lng, radiusKm);
    }
}
