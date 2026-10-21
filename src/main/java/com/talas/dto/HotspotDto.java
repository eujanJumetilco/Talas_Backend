package com.talas.dto;

import com.talas.model.HotspotCell;

/** One hotspot square as sent to the frontend. */
public record HotspotDto(String id, int weight, int crimeCount, String topType, Bounds bounds) {

    public record Bounds(double south, double west, double north, double east) {
    }

    public static HotspotDto from(HotspotCell cell) {
        return new HotspotDto(cell.id(), cell.weight(), cell.crimeCount(), cell.topType(),
                new Bounds(cell.south(), cell.west(), cell.north(), cell.east()));
    }
}
