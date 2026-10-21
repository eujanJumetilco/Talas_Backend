package com.talas.model;

import java.util.Locale;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** Where a crime record came from. */
@Getter
@RequiredArgsConstructor
public enum Source {
    POLICE_REPORT("Police report"),
    GOVERNMENT_STATISTICS("Government statistics"),
    VERIFIED_PUBLIC_REPORT("Verified public report"),
    COMMUNITY_REPORT("Community report"),
    OTHER("Other");

    private final String label;

    /** Turns text like "Police report" into POLICE_REPORT. Returns null if nothing matches. */
    public static Source fromText(String raw) {
        if (raw == null) {
            return null;
        }
        String key = raw.trim().toUpperCase(Locale.ROOT).replaceAll("[\\s-]+", "_");
        for (Source source : values()) {
            if (source.name().equals(key)) {
                return source;
            }
        }
        return null;
    }
}
