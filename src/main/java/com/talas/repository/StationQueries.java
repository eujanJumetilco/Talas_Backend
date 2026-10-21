package com.talas.repository;

import java.util.List;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.talas.dto.StationDto;

import lombok.RequiredArgsConstructor;

/** Read queries on the police_station table. */
@Repository
@RequiredArgsConstructor
public class StationQueries {

    private final NamedParameterJdbcTemplate jdbc;

    // Returns: stations within :radiusM meters of the point, closest first.
    // ST_MakePoint takes (lng, lat). ST_DWithin uses the GiST index on location.
    public List<StationDto> findNearby(double lat, double lng, double radiusM) {
        String sql = """
                SELECT id, address, contact_number, latitude, longitude,
                       ST_Distance(location, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography) AS distance_m
                FROM police_station
                WHERE ST_DWithin(location, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography, :radiusM)
                ORDER BY distance_m
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("lat", lat)
                .addValue("lng", lng)
                .addValue("radiusM", radiusM);
        return jdbc.query(sql, params, (rs, rowNum) -> new StationDto(
                rs.getLong("id"),
                rs.getString("address"),
                rs.getString("contact_number"),
                rs.getDouble("latitude"),
                rs.getDouble("longitude"),
                // meters -> kilometers, rounded to 2 decimals
                Math.round(rs.getDouble("distance_m") / 10.0) / 100.0));
    }
}
