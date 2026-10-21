package com.talas.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.talas.config.AppConstants;
import com.talas.dto.CrimeDto;
import com.talas.dto.CrimeFilterParams;
import com.talas.dto.CrimeRequest;
import com.talas.service.CrimeFilter;
import com.talas.service.CrimeService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** List crimes (with filters), add a new crime report, correct an existing one. */
@RestController
@RequiredArgsConstructor
public class CrimeController {

    private final CrimeService crimeService;

    // The filter params (area, type, source, from, to, bounding box) are read from the query string
    @GetMapping("/api/crimes")
    public List<CrimeDto> list(CrimeFilterParams params,
            @RequestParam(defaultValue = "" + AppConstants.DEFAULT_CRIME_LIMIT) int limit) {
        return crimeService.findCrimes(new CrimeFilter(params), limit);
    }

    @PostMapping("/api/crimes")
    public ResponseEntity<CrimeDto> create(@Valid @RequestBody CrimeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(crimeService.create(request));
    }

    @PutMapping("/api/crimes/{id}")
    public CrimeDto update(@PathVariable Long id, @Valid @RequestBody CrimeRequest request) {
        return crimeService.update(id, request);
    }
}
