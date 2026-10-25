package com.talas.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.databind.JsonNode;
import com.talas.config.AppConstants;
import com.talas.config.GraphHopperConfig;
import com.talas.dto.GeocodeResult;

import lombok.extern.slf4j.Slf4j;

/** Place search with GraphHopper. */
@Slf4j
@Service
@ConditionalOnProperty(name = "geocoding.provider", havingValue = "graphhopper")
public class GraphHopperGeocodingProvider implements GeocodingProvider {

    private static final int MAX_RESULTS = 5;
    // Centre of Metro Manila: GraphHopper ranks places closer to this point higher
    private static final String BIAS_POINT = "14.5995,120.9842";

    private final RestClient graphHopperRestClient;
    private final String apiKey;

    public GraphHopperGeocodingProvider(RestClient graphHopperRestClient,
            @Value("${graphhopper.api-key}") String apiKey) {
        this.graphHopperRestClient = graphHopperRestClient;
        this.apiKey = apiKey;
    }

    // Place search near Metro Manila. Returns at most 5 places.
    @Override
    public List<GeocodeResult> geocode(String text) {
        JsonNode response;
        try {
            response = graphHopperRestClient.get()
                    .uri(uri -> uri.path("/geocode")
                            .queryParam("q", "{text}")
                            .queryParam("point", BIAS_POINT)
                            .queryParam("limit", 10)   // ask for extra: some hits are dropped by the Metro Manila check below
                            .queryParam("locale", "en")
                            .queryParam("key", "{key}")
                            .build(text, apiKey))   // {text} is filled in and URL-encoded here
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            log.warn("GraphHopper geocode request failed: {}", GraphHopperConfig.describe(e));
            throw new RoutingUnavailableException();
        }
        if (response == null || !response.path("hits").isArray()) {
            throw new RoutingUnavailableException();
        }

        List<GeocodeResult> results = new ArrayList<>();
        for (JsonNode hit : response.path("hits")) {
            double lat = hit.path("point").path("lat").asDouble();
            double lng = hit.path("point").path("lng").asDouble();
            // Keep only places inside Metro Manila (same box as the rest of the API)
            if (!AppConstants.inMetroManila(lat, lng)) {
                continue;
            }
            results.add(new GeocodeResult(label(hit), lat, lng));
            if (results.size() == MAX_RESULTS) {
                break;
            }
        }
        return results;
    }

    // Joins the non-empty address parts, for example "Cubao, Quezon City, Metro Manila"
    private static String label(JsonNode hit) {
        Set<String> parts = new LinkedHashSet<>();   // a set drops repeated parts such as name == city
        for (String field : List.of("name", "street", "city", "state")) {
            String value = hit.path(field).asText("").trim();
            if (!value.isEmpty()) {
                parts.add(value);
            }
        }
        return String.join(", ", parts);
    }
}
