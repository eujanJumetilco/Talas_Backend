package com.talas.dto;

import java.time.Instant;

/** Earliest and latest crime time in UTC (both null when there are no crimes). */
public record DateRange(Instant earliest, Instant latest) {
}
