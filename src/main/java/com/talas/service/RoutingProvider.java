package com.talas.service;

import java.util.List;

import com.talas.dto.LatLng;
import com.talas.dto.RoutedPath;
import com.talas.model.HotspotCell;

/**
 * Road routing. Which implementation runs is chosen by the routing.provider property
 * (ors or graphhopper), independently of the geocoding provider.
 */
public interface RoutingProvider {

    /**
     * Asks the provider for a road route.
     * mode: "driving-car" or "foot-walking". avoid: hotspot squares the route must not enter (may be empty).
     * alternatives: also ask for up to 3 different routes.
     * Returns the routes found (at least one); throws RoutingUnavailableException on any failure.
     */
    List<RoutedPath> route(LatLng origin, LatLng destination, String mode,
            List<HotspotCell> avoid, boolean alternatives);
}
