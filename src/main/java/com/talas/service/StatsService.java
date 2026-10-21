package com.talas.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.talas.dto.StatsDto;
import com.talas.dto.StatsDto.BusiestSpot;
import com.talas.repository.CrimeQueries;
import com.talas.repository.CrimeQueries.SquareCount;
import com.talas.util.GridMath;

import lombok.RequiredArgsConstructor;

/** Builds the statistics for GET /api/stats. All time counts are in UTC. */
@Service
@RequiredArgsConstructor
public class StatsService {

    private static final int BUSIEST_SPOTS = 5;

    private final CrimeQueries crimeQueries;

    public StatsDto getStats(CrimeFilter filter) {
        // 1 and 2: counts by type and by source, largest first
        Map<String, Long> byType = sortByCountDescending(crimeQueries.countByType(filter));
        Map<String, Long> bySource = sortByCountDescending(crimeQueries.countBySource(filter));

        // Every crime has exactly one type, so the type counts add up to the total
        long total = 0;
        for (long count : byType.values()) {
            total += count;
        }

        // 3: a 7 x 24 table (UTC weekday x UTC hour). byHour is the same counts summed over the weekdays.
        int[][] byWeekdayHour = new int[7][24];
        int[] byHour = new int[24];
        for (int[] row : crimeQueries.countByWeekdayHour(filter)) {
            int dow = row[0];
            int hour = row[1];
            int count = row[2];
            byWeekdayHour[dow - 1][hour] = count;   // ISO weekday 1 = Monday goes to row 0
            byHour[hour] += count;
        }

        // 4: busiest grid squares; report each square by its center point
        List<BusiestSpot> busiestSpots = new ArrayList<>();
        for (SquareCount square : crimeQueries.findBusiestSquares(filter, BUSIEST_SPOTS)) {
            double[] center = GridMath.center(square.row(), square.col());
            busiestSpots.add(new BusiestSpot(center[0], center[1], square.count(), square.sampleAddress()));
        }

        return new StatsDto(total, byType, bySource, byHour, byWeekdayHour, busiestSpots);
    }

    // Sorts the entries by count (largest first) and keeps that order with a LinkedHashMap
    private static Map<String, Long> sortByCountDescending(Map<String, Long> counts) {
        List<Map.Entry<String, Long>> entries = new ArrayList<>(counts.entrySet());
        entries.sort(Map.Entry.<String, Long>comparingByValue().reversed());

        Map<String, Long> sorted = new LinkedHashMap<>();
        for (Map.Entry<String, Long> entry : entries) {
            sorted.put(entry.getKey(), entry.getValue());
        }
        return sorted;
    }
}
