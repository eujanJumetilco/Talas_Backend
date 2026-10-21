package com.talas.dto;

import java.util.List;
import java.util.Map;

/** GET /api/stats. All time counts are in UTC; the frontend converts them to Philippine time. */
public record StatsDto(
        long total,
        Map<String, Long> byType,
        Map<String, Long> bySource,
        int[] byHour,
        int[][] byWeekdayHour,
        List<BusiestSpot> busiestSpots) {

    public record BusiestSpot(double lat, double lng, long crimeCount, String sampleAddress) {
    }
}
