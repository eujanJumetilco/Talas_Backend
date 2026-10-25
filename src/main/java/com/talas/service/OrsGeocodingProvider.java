package com.talas.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.databind.JsonNode;
import com.talas.dto.GeocodeResult;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Place search with OpenRouteService. ORS uses [lng, lat] order; this class converts to our lat/lng. */
@Slf4j
@Service
@ConditionalOnProperty(name = "geocoding.provider", havingValue = "ors")
@RequiredArgsConstructor
public class OrsGeocodingProvider implements GeocodingProvider {

    private final RestClient orsRestClient;

    // Place search limited to Metro Manila. Returns at most 5 places.
    @Override
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
            throw new RoutingUnavailableException();
        }
        if (response == null || !response.path("features").isArray()) {
            throw new RoutingUnavailableException();
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
}
