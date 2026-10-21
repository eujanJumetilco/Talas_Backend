package com.talas.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.talas.dto.CrimeFilterParams;
import com.talas.dto.StatsDto;
import com.talas.service.CrimeFilter;
import com.talas.service.StatsService;

import lombok.RequiredArgsConstructor;

/** GET /api/stats: counts by type, source, UTC hour and weekday, and the busiest spots. */
@RestController
@RequiredArgsConstructor
public class StatsController {

    private final StatsService statsService;

    @GetMapping("/api/stats")
    public StatsDto stats(CrimeFilterParams params) {
        return statsService.getStats(new CrimeFilter(params));
    }
}
