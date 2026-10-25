package com.talas.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.databind.JsonNode;
import com.talas.dto.LatLng;
import com.talas.dto.RoutedPath;
import com.talas.model.HotspotCell;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Road routing with OpenRouteService. ORS uses [lng, lat] order; this class converts to our [lat, lng]. */
@Slf4j
@Service
@ConditionalOnProperty(name = "routing.provider", havingValue = "ors")
@RequiredArgsConstructor
public class OrsRoutingProvider implements RoutingProvider {

    private final RestClient orsRestClient;

    /**
     * Asks ORS for a road route (see RoutingProvider).
     */
    @Override
    public List<RoutedPath> route(LatLng origin, LatLng destination, String mode,
            List<HotspotCell> avoid, boolean alternatives) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("coordinates", List.of(
                List.of(origin.lng(), origin.lat()),
                List.of(destination.lng(), destination.lat())));

        if (!avoid.isEmpty()) {
            // Each square is one polygon: a closed ring of its 4 corners, first corner repeated at the end
            List<Object> polygons = new ArrayList<>();
            for (HotspotCell cell : avoid) {
                List<List<Double>> ring = List.of(
                        List.of(cell.west(), cell.south()),
                        List.of(cell.east(), cell.south()),
                        List.of(cell.east(), cell.north()),
                        List.of(cell.west(), cell.north()),
                        List.of(cell.west(), cell.south()));
                polygons.add(List.of(ring));
            }
            body.put("options", Map.of("avoid_polygons", Map.of("type", "MultiPolygon", "coordinates", polygons)));
        }
        if (alternatives) {
            body.put("alternative_routes", Map.of("target_count", 3));
        }

        JsonNode response;
        try {
            response = orsRestClient.post()
                    .uri("/v2/directions/{mode}/geojson", mode)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            log.warn("ORS directions request failed: {}", e.getMessage());
            throw new RoutingUnavailableException();
        }
        if (response == null || !response.path("features").isArray() || response.path("features").isEmpty()) {
            throw new RoutingUnavailableException();
        }

        List<RoutedPath> routes = new ArrayList<>();
        for (JsonNode feature : response.path("features")) {
            JsonNode geometry = feature.path("geometry");

            // [lng, lat] from ORS -> [lat, lng] for the frontend
            List<double[]> path = new ArrayList<>();
            for (JsonNode point : geometry.path("coordinates")) {
                path.add(new double[] { point.path(1).asDouble(), point.path(0).asDouble() });
            }

            JsonNode summary = feature.path("properties").path("summary");
            routes.add(new RoutedPath(
                    path,
                    geometry.toString(),   // unchanged GeoJSON line, for PostGIS
                    summary.path("distance").asDouble(),
                    summary.path("duration").asDouble()));
        }
        return routes;
    }
}
