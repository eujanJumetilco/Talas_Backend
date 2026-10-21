package com.talas.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.databind.JsonNode;
import com.talas.dto.GeocodeResult;
import com.talas.dto.LatLng;
import com.talas.dto.OrsRoute;
import com.talas.model.HotspotCell;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Talks to OpenRouteService (ORS) for geocoding and road routing. ORS uses [lng, lat] order;
 * this class converts to and from the [lat, lng] order used by our API.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrsClient {

    private final RestClient orsRestClient;

    // Place search limited to Metro Manila. Returns at most 5 places.
    public List<GeocodeResult> geocode(String text) {
        JsonNode response;
        try {
            response = orsRestClient.get()
                    .uri(uri -> uri.path("/geocode/search")
                            .queryParam("text", "{text}")
                            .queryParam("size", 5)
                            .queryParam("boundary.country", "PH")
                            .queryParam("boundary.rect.min_lon", 120.90)
                            .queryParam("boundary.rect.min_lat", 14.40)
                            .queryParam("boundary.rect.max_lon", 121.15)
                            .queryParam("boundary.rect.max_lat", 14.80)
                            .build(text))   // {text} is filled in and URL-encoded here
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            log.warn("ORS geocode request failed: {}", e.getMessage());
            throw new OrsUnavailableException();
        }
        if (response == null || !response.path("features").isArray()) {
            throw new OrsUnavailableException();
        }

        List<GeocodeResult> results = new ArrayList<>();
        for (JsonNode feature : response.path("features")) {
            JsonNode coordinates = feature.path("geometry").path("coordinates");   // [lng, lat]
            results.add(new GeocodeResult(
                    feature.path("properties").path("label").asText(),
                    coordinates.path(1).asDouble(),
                    coordinates.path(0).asDouble()));
        }
        return results;
    }

    /**
     * Asks ORS for a road route. avoid: hotspot squares the route must not enter (may be empty).
     * alternatives: also ask for up to 3 different routes. Returns the routes ORS found (at least one).
     */
    public List<OrsRoute> route(LatLng origin, LatLng destination, String mode,
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
            throw new OrsUnavailableException();
        }
        if (response == null || !response.path("features").isArray() || response.path("features").isEmpty()) {
            throw new OrsUnavailableException();
        }

        List<OrsRoute> routes = new ArrayList<>();
        for (JsonNode feature : response.path("features")) {
            JsonNode geometry = feature.path("geometry");

            // [lng, lat] from ORS -> [lat, lng] for the frontend
            List<double[]> path = new ArrayList<>();
            for (JsonNode point : geometry.path("coordinates")) {
                path.add(new double[] { point.path(1).asDouble(), point.path(0).asDouble() });
            }

            JsonNode summary = feature.path("properties").path("summary");
            routes.add(new OrsRoute(
                    path,
                    geometry.toString(),   // unchanged GeoJSON line, for PostGIS
                    summary.path("distance").asDouble(),
                    summary.path("duration").asDouble()));
        }
        return routes;
    }
}
