package com.talas.dto;

import java.util.List;

/** GET /api/meta: everything the frontend needs for its dropdowns. */
public record MetaDto(List<CrimeTypeOption> crimeTypes, List<SourceOption> sources, DateRange dateRange) {

    public record CrimeTypeOption(String value, String label, int weight) {
    }

    public record SourceOption(String value, String label) {
    }
}
