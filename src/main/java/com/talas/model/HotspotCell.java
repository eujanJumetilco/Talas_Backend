package com.talas.model;

/**
 * One hotspot grid square. row and col are used by the route logic and are not sent to the frontend
 * (the controller turns this into a HotspotDto).
 */
public record HotspotCell(
        String id,
        int weight,
        int crimeCount,
        String topType,
        double south,
        double west,
        double north,
        double east,
        int row,
        int col) {
}
