package com.talas.util;

import static com.talas.config.AppConstants.CELL_DEG;

/**
 * Grid square math for hotspots and busiest spots. The map is cut into squares of CELL_DEG degrees.
 * SQL uses the same formula (floor(latitude / cell), floor(longitude / cell)), so Java and SQL agree.
 */
public final class GridMath {

    private GridMath() {
    }

    public static int rowOf(double lat) {
        return (int) Math.floor(lat / CELL_DEG);
    }

    public static int colOf(double lng) {
        return (int) Math.floor(lng / CELL_DEG);
    }

    /** Packs row and col into one long so a square can be a HashMap/HashSet key. Row goes in the high 32 bits. */
    public static long key(int row, int col) {
        return ((long) row << 32) | (col & 0xffffffffL);
    }

    /** Returns {south, west, north, east} of the square. */
    public static double[] bounds(int row, int col) {
        return new double[] {
                round6(row * CELL_DEG), round6(col * CELL_DEG),
                round6((row + 1) * CELL_DEG), round6((col + 1) * CELL_DEG) };
    }

    /** Returns {lat, lng} of the square's center. */
    public static double[] center(int row, int col) {
        return new double[] { round6((row + 0.5) * CELL_DEG), round6((col + 0.5) * CELL_DEG) };
    }

    // Removes floating-point noise such as 14.597500000000002 (6 decimals is about 0.1 m)
    private static double round6(double value) {
        return Math.round(value * 1_000_000) / 1_000_000.0;
    }
}
