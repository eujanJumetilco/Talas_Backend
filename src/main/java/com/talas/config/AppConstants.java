package com.talas.config;

/** Tunable numbers used across the backend, kept in one place so they are easy to find and explain. */
public final class AppConstants {

    private AppConstants() {
    }

    /** Grid square size in degrees (about 250 to 280 m) for hotspots and busiest spots. */
    public static final double CELL_DEG = 0.0025;

    /** Flag the top 10% of non-empty squares as hotspots. */
    public static final int HOTSPOT_TOP_PERCENT = 10;

    /** A square needs at least this total weight to be a hotspot. */
    public static final int HOTSPOT_MIN_WEIGHT = 8;

    /** Distance from the route (meters) that counts as "near". */
    public static final int DEFAULT_BUFFER_M = 150;

    /** Accept a different route only if it takes at most 1.5x the baseline duration. */
    public static final double MAX_DETOUR_FACTOR = 1.5;

    /** Max hotspot squares sent to the routing provider per request. */
    public static final int MAX_AVOID_CELLS = 50;

    /** Max crimes listed per route in the response. */
    public static final int MAX_NEARBY_CRIMES = 20;

    /** Limits for GET /api/crimes. */
    public static final int DEFAULT_CRIME_LIMIT = 3000;
    public static final int MAX_CRIME_LIMIT = 5000;

    /** Metro Manila bounding box: route points and new crimes must be inside it. */
    public static final double MIN_LAT = 14.35;
    public static final double MAX_LAT = 14.85;
    public static final double MIN_LNG = 120.85;
    public static final double MAX_LNG = 121.20;

    public static boolean inMetroManila(double lat, double lng) {
        return lat >= MIN_LAT && lat <= MAX_LAT && lng >= MIN_LNG && lng <= MAX_LNG;
    }
}
