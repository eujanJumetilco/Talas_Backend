package com.talas.service;

import static com.talas.config.AppConstants.DEFAULT_BUFFER_M;
import static com.talas.config.AppConstants.MAX_AVOID_CELLS;
import static com.talas.config.AppConstants.MAX_DETOUR_FACTOR;
import static com.talas.config.AppConstants.MAX_NEARBY_CRIMES;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.talas.config.AppConstants;
import com.talas.dto.LatLng;
import com.talas.dto.NearbyCrimeDto;
import com.talas.dto.OrsRoute;
import com.talas.dto.RouteDto;
import com.talas.dto.RouteRequest;
import com.talas.dto.RouteResponse;
import com.talas.model.CrimeType;
import com.talas.model.HotspotCell;
import com.talas.repository.CrimeQueries;
import com.talas.util.GridMath;

import lombok.RequiredArgsConstructor;

/**
 * Finds a route with ORS, checks it against reported crimes nearby, and when possible suggests
 * a route with fewer reported crimes. Routes are checked against all crimes (no filters).
 */
@Service
@RequiredArgsConstructor
public class RouteService {

    private static final String NOTICE =
            "Suggestions only. Reported-crime data does not guarantee that any route is free of risk.";
    private static final String NO_ALTERNATIVE = "Could not look up an alternative route.";

    private final OrsClient ors;
    private final CrimeQueries crimeQueries;
    private final HotspotService hotspotService;

    public RouteResponse findRoutes(RouteRequest request) {
        // Defaults for the optional fields (the annotations on RouteRequest already checked their values)
        String mode = request.mode() == null ? "driving-car" : request.mode();
        int buffer = request.bufferMeters() == null ? DEFAULT_BUFFER_M : request.bufferMeters();
        LatLng origin = request.origin();
        LatLng destination = request.destination();
        if (!AppConstants.inMetroManila(origin.lat(), origin.lng())
                || !AppConstants.inMetroManila(destination.lat(), destination.lng())) {
            throw new IllegalArgumentException("Locations must be within Metro Manila.");
        }

        // The baseline is the route ORS gives by default. If ORS fails here, the user gets a 502.
        OrsRoute baseline = ors.route(origin, destination, mode, List.of(), false).get(0);
        List<NearbyCrimeDto> nearby = crimeQueries.findCrimesNearRoute(baseline.lineGeoJson(), buffer);
        int baselineRisk = riskScore(nearby);

        List<RouteDto> routes = new ArrayList<>();
        routes.add(toRouteDto("Fastest route", baseline, nearby, baselineRisk));
        List<String> warnings = new ArrayList<>();

        // Nothing reported near the baseline: no need to look for another route
        if (nearby.isEmpty()) {
            return new RouteResponse(routes, 0, warnings, NOTICE);
        }
        warnings.add(baselineRisk + " weighted reported-crime points lie within " + buffer
                + " m of the fastest route.");

        // Step A: ask ORS for a route that avoids the hotspot squares the baseline passes through
        List<HotspotCell> avoidCells = hotspotsAlongRoute(nearby, origin, destination);
        if (!avoidCells.isEmpty()) {
            try {
                OrsRoute avoiding = ors.route(origin, destination, mode, avoidCells, false).get(0);
                List<NearbyCrimeDto> avoidingNearby =
                        crimeQueries.findCrimesNearRoute(avoiding.lineGeoJson(), buffer);
                int avoidingRisk = riskScore(avoidingNearby);
                // Keep it only if it really has fewer reported crimes and is not a big detour
                if (avoidingRisk < baselineRisk && withinDetourLimit(avoiding, baseline)) {
                    routes.add(toRouteDto("Avoids reported hotspots", avoiding, avoidingNearby, avoidingRisk));
                }
            } catch (OrsUnavailableException e) {
                warnings.add(NO_ALTERNATIVE);
            }
        }

        // Step B (fallback): if Step A added nothing, ask ORS for alternative routes and keep the best one
        if (routes.size() == 1) {
            try {
                RouteDto best = null;
                for (OrsRoute alternative : ors.route(origin, destination, mode, List.of(), true)) {
                    List<NearbyCrimeDto> altNearby =
                            crimeQueries.findCrimesNearRoute(alternative.lineGeoJson(), buffer);
                    int altRisk = riskScore(altNearby);
                    boolean better = altRisk < baselineRisk
                            && withinDetourLimit(alternative, baseline)
                            && (best == null || altRisk < best.riskScore());
                    if (better) {
                        best = toRouteDto("Alternative route", alternative, altNearby, altRisk);
                    }
                }
                if (best != null) {
                    routes.add(best);
                }
            } catch (OrsUnavailableException e) {
                if (!warnings.contains(NO_ALTERNATIVE)) {
                    warnings.add(NO_ALTERNATIVE);
                }
            }
        }

        return new RouteResponse(routes, recommendedIndex(routes), warnings, NOTICE);
    }

    // Risk score = sum of the crime type weights of ALL crimes near the route
    private static int riskScore(List<NearbyCrimeDto> nearby) {
        int score = 0;
        for (NearbyCrimeDto crime : nearby) {
            score += CrimeType.valueOf(crime.type()).getWeight();
        }
        return score;
    }

    private static boolean withinDetourLimit(OrsRoute candidate, OrsRoute baseline) {
        return candidate.durationS() <= MAX_DETOUR_FACTOR * baseline.durationS();
    }

    // The hotspot squares that contain at least one crime near the route, heaviest first
    private List<HotspotCell> hotspotsAlongRoute(List<NearbyCrimeDto> nearby, LatLng origin, LatLng destination) {
        // Look up hotspots by square key in O(1)
        Map<Long, HotspotCell> hotspotByKey = new HashMap<>();
        for (HotspotCell hotspot : hotspotService.findHotspots()) {
            hotspotByKey.put(GridMath.key(hotspot.row(), hotspot.col()), hotspot);
        }

        // A HashSet keeps each square only once, even when many crimes fall in it
        Set<HotspotCell> cells = new HashSet<>();
        for (NearbyCrimeDto crime : nearby) {
            long key = GridMath.key(GridMath.rowOf(crime.lat()), GridMath.colOf(crime.lng()));
            HotspotCell hotspot = hotspotByKey.get(key);
            if (hotspot != null) {
                cells.add(hotspot);
            }
        }

        // The route has to start and end somewhere, so never avoid the start or end square
        long originKey = GridMath.key(GridMath.rowOf(origin.lat()), GridMath.colOf(origin.lng()));
        long destinationKey = GridMath.key(GridMath.rowOf(destination.lat()), GridMath.colOf(destination.lng()));
        cells.removeIf(cell -> {
            long key = GridMath.key(cell.row(), cell.col());
            return key == originKey || key == destinationKey;
        });

        // Send at most MAX_AVOID_CELLS squares, the heaviest ones
        return cells.stream()
                .sorted(Comparator.comparingInt(HotspotCell::weight).reversed())
                .limit(MAX_AVOID_CELLS)
                .toList();
    }

    // Lowest risk wins; on a tie, the shorter route wins
    private static int recommendedIndex(List<RouteDto> routes) {
        int best = 0;
        for (int i = 1; i < routes.size(); i++) {
            RouteDto candidate = routes.get(i);
            RouteDto current = routes.get(best);
            boolean lowerRisk = candidate.riskScore() < current.riskScore();
            boolean sameRiskShorter = candidate.riskScore() == current.riskScore()
                    && candidate.distanceKm() < current.distanceKm();
            if (lowerRisk || sameRiskShorter) {
                best = i;
            }
        }
        return best;
    }

    private static RouteDto toRouteDto(String label, OrsRoute route, List<NearbyCrimeDto> nearby, int risk) {
        double distanceKm = Math.round(route.distanceM() / 100.0) / 10.0;   // 1 decimal
        long durationMin = Math.round(route.durationS() / 60.0);
        // nearby is already sorted closest first; only the first MAX_NEARBY_CRIMES are listed
        List<NearbyCrimeDto> listed = List.copyOf(nearby.subList(0, Math.min(nearby.size(), MAX_NEARBY_CRIMES)));
        return new RouteDto(label, route.path(), distanceKm, durationMin, risk, listed);
    }
}
