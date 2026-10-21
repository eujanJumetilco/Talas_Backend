package com.talas.service;

import static com.talas.config.AppConstants.HOTSPOT_MIN_WEIGHT;
import static com.talas.config.AppConstants.HOTSPOT_TOP_PERCENT;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.talas.model.CrimeType;
import com.talas.model.HotspotCell;
import com.talas.repository.CrimeQueries;
import com.talas.repository.CrimeQueries.SquareTypeCount;
import com.talas.util.GridMath;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Finds hotspots: the grid squares (about 250 m wide) with the highest weighted crime score.
 * A square's score is the sum of its crimes' weights. The top 10% of squares, with at least
 * HOTSPOT_MIN_WEIGHT, are hotspots. Computed from all crimes on every call (no caching).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HotspotService {

    private final CrimeQueries crimeQueries;

    // Running totals for one grid square while the query rows are combined
    private static class SquareTotals {
        final int row;
        final int col;
        int weight;
        int crimeCount;
        String topType;
        long topTypeCount;

        SquareTotals(int row, int col) {
            this.row = row;
            this.col = col;
        }
    }

    public List<HotspotCell> findHotspots() {
        // Step 1: one row per (square, crime type) with its count, over all crimes
        List<SquareTypeCount> rows = crimeQueries.countBySquareAndType();

        // Step 2: combine the rows per square. The HashMap key packs (row, col) into one long.
        Map<Long, SquareTotals> squares = new HashMap<>();
        for (SquareTypeCount r : rows) {
            SquareTotals square = squares.computeIfAbsent(
                    GridMath.key(r.row(), r.col()), k -> new SquareTotals(r.row(), r.col()));
            int typeWeight = CrimeType.valueOf(r.crimeType()).getWeight();
            square.weight += (int) (r.count() * typeWeight);
            square.crimeCount += (int) r.count();
            // Keep the most common type in this square
            if (r.count() > square.topTypeCount) {
                square.topType = r.crimeType();
                square.topTypeCount = r.count();
            }
        }
        if (squares.isEmpty()) {
            return List.of();
        }

        // Step 3: sort the square weights from high to low and find the weight at the top-10% mark.
        // A square must reach that weight and also HOTSPOT_MIN_WEIGHT.
        List<Integer> weights = new ArrayList<>();
        for (SquareTotals square : squares.values()) {
            weights.add(square.weight);
        }
        weights.sort(Comparator.reverseOrder());
        int cutoffIndex = (int) Math.ceil(HOTSPOT_TOP_PERCENT / 100.0 * weights.size()) - 1;
        int threshold = Math.max(HOTSPOT_MIN_WEIGHT, weights.get(cutoffIndex));

        // Step 4: every square at or above the threshold becomes a hotspot
        List<HotspotCell> hotspots = new ArrayList<>();
        for (SquareTotals square : squares.values()) {
            if (square.weight >= threshold) {
                double[] b = GridMath.bounds(square.row, square.col);
                hotspots.add(new HotspotCell("h-" + square.row + "-" + square.col,
                        square.weight, square.crimeCount, square.topType,
                        b[0], b[1], b[2], b[3], square.row, square.col));
            }
        }

        // Step 5: highest weight first
        hotspots.sort(Comparator.comparingInt(HotspotCell::weight).reversed().thenComparing(HotspotCell::id));

        // For tuning HOTSPOT_MIN_WEIGHT (enable with logging.level.com.talas.service.HotspotService=DEBUG)
        log.debug("{} non-empty squares; weight p50={} p90={} p99={}; threshold={}; {} hotspots",
                weights.size(), percentile(weights, 50), percentile(weights, 90), percentile(weights, 99),
                threshold, hotspots.size());

        return hotspots;
    }

    // Weight at the given percentile of a list sorted from high to low (p90 = 90% of squares are at or below it)
    private static int percentile(List<Integer> weightsHighToLow, int p) {
        int index = (int) Math.floor((100 - p) / 100.0 * (weightsHighToLow.size() - 1));
        return weightsHighToLow.get(index);
    }
}
