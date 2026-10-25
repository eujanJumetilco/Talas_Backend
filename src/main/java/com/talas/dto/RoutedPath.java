package com.talas.dto;

import java.util.List;

/**
 * One route returned by the routing provider.
 * path: [lat, lng] pairs for the frontend. lineGeoJson: the original GeoJSON line ([lng, lat]) for PostGIS.
 */
public record RoutedPath(List<double[]> path, String lineGeoJson, double distanceM, double durationS) {
}
