package com.talas.service;

import java.util.List;

import com.talas.dto.GeocodeResult;

/**
 * Place search. Which implementation runs is chosen by the geocoding.provider property
 * (ors or graphhopper), independently of the routing provider.
 */
public interface GeocodingProvider {

    /** Up to 5 matching places in Metro Manila; an empty list if none. */
    List<GeocodeResult> geocode(String text);
}
