package com.talas.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.talas.dto.GeocodeResult;
import com.talas.service.GeocodingProvider;

import lombok.RequiredArgsConstructor;

/** GET /api/geocode?q=Cubao: up to 5 matching places in Metro Manila. */
@RestController
@RequiredArgsConstructor
public class GeocodeController {

    private final GeocodingProvider geocoding;

    @GetMapping("/api/geocode")
    public List<GeocodeResult> geocode(@RequestParam(required = false) String q) {
        if (q == null || q.trim().length() < 2) {
            throw new IllegalArgumentException("q must be at least 2 characters.");
        }
        return geocoding.geocode(q.trim());
    }
}
