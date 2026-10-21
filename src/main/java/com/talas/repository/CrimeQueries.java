package com.talas.repository;

import static com.talas.config.AppConstants.CELL_DEG;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.talas.dto.CrimeDto;
import com.talas.dto.DateRange;
import com.talas.dto.NearbyCrimeDto;
import com.talas.service.CrimeFilter;

import lombok.RequiredArgsConstructor;

/**
 * Read queries on the crime table, written in plain SQL (PostGIS does the spatial work).
 * Queries that accept filters insert filter.whereClause() into the SQL and use filter.params().
 */
@Repository
@RequiredArgsConstructor
public class CrimeQueries {

    private final NamedParameterJdbcTemplate jdbc;

    /** A grid square with its crime count and most common address (for busiest spots). */
    public record SquareCount(int row, int col, long count, String sampleAddress) {
    }

    /** How many crimes of one type are in one grid square (for hotspots). */
    public record SquareTypeCount(int row, int col, String crimeType, long count) {
    }

    // Returns: the newest crimes matching the filter, at most :limit rows
    public List<CrimeDto> findCrimes(CrimeFilter filter, int limit) {
        String sql = """
                SELECT id, crime_type, source, latitude, longitude, occurred_at, address
                FROM crime %s
                ORDER BY occurred_at DESC
                LIMIT :limit
                """.formatted(filter.whereClause());
        MapSqlParameterSource params = copyOf(filter).addValue("limit", limit);
        return jdbc.query(sql, params, (rs, rowNum) -> new CrimeDto(
                rs.getLong("id"),
                rs.getString("crime_type"),
                rs.getString("source"),
                rs.getDouble("latitude"),
                rs.getDouble("longitude"),
                utc(rs, "occurred_at"),
                rs.getString("address")));
    }

    // Returns: one row with the earliest and latest occurred_at (both NULL when the table is empty)
    public DateRange findDateRange() {
        String sql = """
                SELECT min(occurred_at) AS earliest,
                       max(occurred_at) AS latest
                FROM crime
                """;
        return jdbc.queryForObject(sql, new MapSqlParameterSource(),
                (rs, rowNum) -> new DateRange(utc(rs, "earliest"), utc(rs, "latest")));
    }

    // Returns: crime_type -> number of matching crimes (only types that occur)
    public Map<String, Long> countByType(CrimeFilter filter) {
        String sql = """
                SELECT crime_type AS name, count(*) AS n
                FROM crime %s
                GROUP BY crime_type
                """.formatted(filter.whereClause());
        return countsByName(sql, filter);
    }

    // Returns: source -> number of matching crimes (only sources that occur)
    public Map<String, Long> countBySource(CrimeFilter filter) {
        String sql = """
                SELECT source AS name, count(*) AS n
                FROM crime %s
                GROUP BY source
                """.formatted(filter.whereClause());
        return countsByName(sql, filter);
    }

    // Returns: rows of {dow, hour, count} in UTC. dow is ISO: 1 = Monday ... 7 = Sunday.
    // "AT TIME ZONE 'UTC'" makes the result independent of the database session's time zone.
    public List<int[]> countByWeekdayHour(CrimeFilter filter) {
        String sql = """
                SELECT EXTRACT(ISODOW FROM occurred_at AT TIME ZONE 'UTC')::int AS dow,
                       EXTRACT(HOUR   FROM occurred_at AT TIME ZONE 'UTC')::int AS hr,
                       count(*) AS n
                FROM crime %s
                GROUP BY dow, hr
                """.formatted(filter.whereClause());
        return jdbc.query(sql, filter.params(),
                (rs, rowNum) -> new int[] { rs.getInt("dow"), rs.getInt("hr"), rs.getInt("n") });
    }

    // Returns: the grid squares with the most matching crimes, highest first, with the most common
    // non-null address in each (mode() ignores NULL addresses)
    public List<SquareCount> findBusiestSquares(CrimeFilter filter, int howMany) {
        String sql = """
                SELECT floor(latitude  / :cell)::int AS r,
                       floor(longitude / :cell)::int AS c,
                       count(*) AS n,
                       mode() WITHIN GROUP (ORDER BY address) AS sample_address
                FROM crime %s
                GROUP BY r, c
                ORDER BY n DESC, r, c
                LIMIT :howMany
                """.formatted(filter.whereClause());
        MapSqlParameterSource params = copyOf(filter).addValue("cell", CELL_DEG).addValue("howMany", howMany);
        return jdbc.query(sql, params, (rs, rowNum) -> new SquareCount(
                rs.getInt("r"), rs.getInt("c"), rs.getLong("n"), rs.getString("sample_address")));
    }

    // Returns: for ALL crimes, one row per (grid square, crime type) with the number of crimes
    public List<SquareTypeCount> countBySquareAndType() {
        String sql = """
                SELECT floor(latitude  / :cell)::int AS r,
                       floor(longitude / :cell)::int AS c,
                       crime_type,
                       count(*) AS n
                FROM crime
                GROUP BY r, c, crime_type
                """;
        MapSqlParameterSource params = new MapSqlParameterSource("cell", CELL_DEG);
        return jdbc.query(sql, params, (rs, rowNum) -> new SquareTypeCount(
                rs.getInt("r"), rs.getInt("c"), rs.getString("crime_type"), rs.getLong("n")));
    }

    // Returns: every crime within :buffer meters of the route line, closest first.
    // :line is GeoJSON from ORS ([lng, lat] order, which is what PostGIS expects).
    // ST_DWithin uses the GiST index, so PostgreSQL only checks crimes close to the line.
    public List<NearbyCrimeDto> findCrimesNearRoute(String lineGeoJson, int bufferMeters) {
        String sql = """
                WITH route AS (
                    SELECT ST_SetSRID(ST_GeomFromGeoJSON(:line), 4326)::geography AS g
                )
                SELECT c.id, c.crime_type, c.latitude, c.longitude, c.occurred_at, c.address,
                       ST_Distance(c.location, route.g) AS distance_m
                FROM crime c, route
                WHERE ST_DWithin(c.location, route.g, :buffer)
                ORDER BY distance_m
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("line", lineGeoJson)
                .addValue("buffer", bufferMeters);
        return jdbc.query(sql, params, (rs, rowNum) -> new NearbyCrimeDto(
                rs.getLong("id"),
                rs.getString("crime_type"),
                rs.getDouble("latitude"),
                rs.getDouble("longitude"),
                utc(rs, "occurred_at"),
                rs.getString("address"),
                (int) Math.round(rs.getDouble("distance_m"))));
    }

    // Runs a "name, n" query and collects the rows into a map
    private Map<String, Long> countsByName(String sql, CrimeFilter filter) {
        Map<String, Long> counts = new HashMap<>();
        jdbc.query(sql, filter.params(), rs -> {
            counts.put(rs.getString("name"), rs.getLong("n"));
        });
        return counts;
    }

    // A copy of the filter's params, so adding :limit or :cell does not change the filter itself
    private static MapSqlParameterSource copyOf(CrimeFilter filter) {
        return new MapSqlParameterSource(new HashMap<>(filter.params().getValues()));
    }

    // Reads a TIMESTAMPTZ column as an Instant (a point in time, shown in UTC), or null
    private static Instant utc(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
