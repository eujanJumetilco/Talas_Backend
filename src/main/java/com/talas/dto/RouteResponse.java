package com.talas.dto;

import java.util.List;

/** Response of POST /api/routes/safe. routes.get(0) is always the baseline ("Fastest route"). */
public record RouteResponse(List<RouteDto> routes, int recommendedIndex, List<String> warnings, String notice) {
}
