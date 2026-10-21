package com.talas.model;

import java.util.Locale;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Crime categories. The weight reflects seriousness and is used for hotspot scores
 * and route risk scores (one homicide counts like five acts of vandalism).
 */
@Getter
@RequiredArgsConstructor
public enum CrimeType {
    THEFT("Theft", 3),
    ASSAULT("Assault", 4),
    HOMICIDE("Homicide", 5),
    WEAPON_VIOLENCE("Weapon violence", 4),
    GANG_VIOLENCE("Gang violence", 4),
    SEXUAL_OFFENSE("Sexual offense", 5),
    KIDNAPPING("Kidnapping", 5),
    STALKING("Stalking", 2),
    VEHICLE_THEFT("Vehicle theft", 2),
    BURGLARY("Burglary", 3),
    VANDALISM("Vandalism", 1),
    ARSON("Arson", 3),
    DRUG_RELATED_CRIME("Drug-related crime", 3),
    HUMAN_TRAFFICKING("Human trafficking", 5),
    OTHER("Other", 1);

    private final String label;
    private final int weight;

    /** Turns text like "Drug-related crime" into DRUG_RELATED_CRIME. Returns null if nothing matches. */
    public static CrimeType fromText(String raw) {
        if (raw == null) {
            return null;
        }
        String key = raw.trim().toUpperCase(Locale.ROOT).replaceAll("[\\s-]+", "_");
        for (CrimeType type : values()) {
            if (type.name().equals(key)) {
                return type;
            }
        }
        return null;
    }
}
