package com.talas.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.databind.JsonNode;
import com.talas.config.GraphHopperConfig;
import com.talas.dto.LatLng;
import com.talas.dto.RoutedPath;
import com.talas.model.HotspotCell;

import lombok.extern.slf4j.Slf4j;

/**
 * Road routing with GraphHopper. Like ORS it uses [lng, lat] order; this class converts to our [lat, lng].
 * Avoided squares are sent as a custom model whose priority is 0 inside each square.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "routing.provider", havingValue = "graphhopper")
public class GraphHopperRoutingProvider implements RoutingProvider {

    private final RestClient graphHopperRestClient;
    private final String apiKey;

    public GraphHopperRoutingProvider(RestClient graphHopperRestClient,
            @Value("${graphhopper.api-key}") String apiKey) {
        this.graphHopperRestClient = graphHopperRestClient;
        this.apiKey = apiKey;
    }

    // Asks GraphHopper for a road route (see RoutingProvider).
    @Override
    public List<RoutedPath> route(LatLng origin, LatLng destination, String mode,
            List<HotspotCell> avoid, boolean alternatives) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("points", List.of(
                List.of(origin.lng(), origin.lat()),
                List.of(destination.lng(), destination.lat())));
        body.put("profile", "foot-walking".equals(mode) ? "foot" : "car");
        body.put("points_encoded", false);   // plain [lng, lat] arrays instead of an encoded string
        body.put("instructions", false);     // we only draw the line, so skip turn-by-turn text

        if (!avoid.isEmpty()) {
            body.put("custom_model", avoidModel(avoid));
            body.put("ch.disable", true);    // custom models only work when the speed-up (CH) is off
        }
        if (alternatives) {
            body.put("algorithm", "alternative_route");
            body.put("alternative_route.max_paths", 3);
        }

        JsonNode response;
        try {
            response = graphHopperRestClient.post()
                    .uri(uri -> uri.path("/route").queryParam("key", "{key}").build(apiKey))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            log.warn("GraphHopper route request failed: {}", GraphHopperConfig.describe(e));
            throw new RoutingUnavailableException();
        }
        if (response == null || !response.path("paths").isArray() || response.path("paths").isEmpty()) {
            throw new RoutingUnavailableException();
        }

        List<RoutedPath> routes = new ArrayList<>();
        for (JsonNode path : response.path("paths")) {
            JsonNode points = path.path("points");   // GeoJSON LineString because points_encoded is false

            // [lng, lat] from GraphHopper -> [lat, lng] for the frontend (a third value, elevation, is ignored)
            List<double[]> latLngPath = new ArrayList<>();
            for (JsonNode point : points.path("coordinates")) {
                latLngPath.add(new double[] { point.path(1).asDouble(), point.path(0).asDouble() });
            }

            routes.add(new RoutedPath(
                    latLngPath,
                    points.toString(),                      // GeoJSON line as sent, for PostGIS
                    path.path("distance").asDouble(),       // meters
                    path.path("time").asDouble() / 1000));  // milliseconds -> seconds
        }
        return routes;
    }

    // One named area per square, plus one priority rule per area that multiplies the roads inside by 0 (= blocked)
    private static Map<String, Object> avoidModel(List<HotspotCell> avoid) {
        List<Object> features = new ArrayList<>();
        List<Object> rules = new ArrayList<>();
        for (int i = 0; i < avoid.size(); i++) {
            HotspotCell cell = avoid.get(i);
            // A closed ring of the 4 corners, first corner repeated at the end
            List<List<Double>> ring = List.of(
                    List.of(cell.west(), cell.south()),
                    List.of(cell.east(), cell.south()),
                    List.of(cell.east(), cell.north()),
                    List.of(cell.west(), cell.north()),
                    List.of(cell.west(), cell.south()));
            features.add(Map.of(
                    "type", "Feature",
                    "id", "sq" + i,
                    "properties", Map.of(),
                    "geometry", Map.of("type", "Polygon", "coordinates", List.of(ring))));
            rules.add(Map.of("if", "in_sq" + i, "multiply_by", "0"));   // GraphHopper adds the "in_" prefix to the id
        }
        return Map.of(
                "priority", rules,
                "areas", Map.of("type", "FeatureCollection", "features", features));
    }
}
