# Talas Backend: Build Instructions for Claude Code

Save this file as `CLAUDE.md` in the root of the `talas-backend` project, then tell Claude Code:

> Read CLAUDE.md fully, then execute phases B1 to B11 in order. Stop and ask me only for the things listed under "When to stop and ask".

---

## 1. Project context

**Talas** is a school project (2nd-year IT students, course "Data Structures and Algorithms"). It is a crime-awareness and route-information system for Metro Manila. This repository is the **backend only**. A separate Next.js frontend (`talas-frontend`) consumes the JSON API defined in section 5.

What the backend does:
1. Imports a crime dataset (CSV) into PostgreSQL with **PostGIS** (Neon, free tier), and accepts **new and corrected crime reports** through the API.
2. Uses PostGIS for all location work: finding crimes in a map area, crimes near a route, and police stations near a point.
3. Computes **hotspots** (the busiest grid squares, weighted by crime type).
4. Serves crime lists, statistics (by type, source, hour of day, weekday, busiest spots), hotspots and nearby police stations.
5. Proxies **geocoding** and **routing** to OpenRouteService (ORS), checks routes against nearby crimes, scores them, and suggests a lower-risk route when possible.

The backend never draws anything. It only returns JSON. Road routing is done entirely by ORS; the team does not write its own routing algorithm. The database is the single source of truth: there is **no in-memory copy** of the crimes, so every request reads current data.

### The dataset

CSV columns (header names matched case-insensitively):

| CSV column | Required | Meaning | Stored as |
| --- | --- | --- | --- |
| `latitude` | yes | Decimal degrees | `latitude` |
| `longitude` | yes | Decimal degrees | `longitude` |
| `date` | yes | Date **and time, in UTC** | `occurred_at` (TIMESTAMPTZ) |
| `crime_type` | yes | One of the `CrimeType` enum values (section 3) | `crime_type` |
| `source` | no (defaults to `OTHER`) | One of the `Source` enum values (section 3) | `source` |
| `address` | no | Street address, barangay, city, or place description | `address` |
| `city` | no | If present, appended to the address (see B5) | (folded into `address`) |

Extra columns, if any, are ignored. There is **no separate city or barangay column** in the database; location text lives only in `address`. The PostGIS `location` column is filled in automatically by the database from `latitude` and `longitude`.

### Time rule (important): the backend is UTC-only

- Timestamps are stored, filtered, grouped and returned **in UTC only**. The backend never converts to Philippine time and never uses `Asia/Manila`.
- `byHour` and `byWeekdayHour` statistics are counted in **UTC**. The frontend converts them to Philippine time.
- The `from` and `to` filters are full UTC timestamps; the frontend computes them from the dates the user picks.
- In SQL, always group with `occurred_at AT TIME ZONE 'UTC'` so the result never depends on the database session's time zone.

---

## 2. Hard constraints (follow strictly)

**Stack:** Java 17+, Spring Boot 3.2+ (3.x), Maven (use the Maven wrapper), package `com.talas`. Database: PostgreSQL on Neon with the **PostGIS** extension.

**Allowed libraries (use them when they make the code simpler):**
- Spring Web, Spring Data JPA (used for saving entities), Spring JDBC's `NamedParameterJdbcTemplate` (used for read queries; it comes with the JPA starter), PostgreSQL Driver, Validation (`jakarta.validation` annotations such as `@NotNull`, `@Size`)
- **PostGIS** functions in SQL (`ST_DWithin`, `ST_Distance`, `ST_Intersects`, `ST_MakeEnvelope`, `ST_MakePoint`, `ST_GeomFromGeoJSON`, and so on). PostGIS does all spatial searching and distance math.
- Hibernate Spatial and JTS are allowed, but this design does not need them: entities keep plain `latitude`/`longitude` fields and spatial work happens in SQL. Add them only if a phase truly requires it.
- **Lombok** (`@Getter`, `@Setter`, `@NoArgsConstructor`, `@RequiredArgsConstructor`, `@Slf4j`). Use it for entities, services and controllers. DTOs stay as Java `record`s.
- Spring Boot DevTools
- **Apache Commons CSV** for reading the dataset

**Keep it simple. Do NOT add or use:**
- **Caching** of any kind (no Caffeine, no Spring `@Cacheable`, no saved ORS responses, no in-memory copies of the crime table).
- **Quota protection or rate limiting** (no Bucket4j, no request counters).
- **Project plumbing:** Docker, Docker Compose, Testcontainers, Flyway, Liquibase, springdoc/Swagger, Actuator, CI files.
- Your own shortest-path or routing algorithm (no Dijkstra, no A*, no pgRouting). ORS does all road routing.
- Spring Security or login. The API is open; this is a local school demo.
- Native PostgreSQL enum types. Store enums as `VARCHAR` with `@Enumerated(EnumType.STRING)`.

**Code style (important):** the authors must be able to explain every line in a defense.
- Keep SQL readable: one query per method, formatted on several lines, with a comment saying what it returns.
- Add a short comment above each non-obvious step, especially in `CrimeFilter`, `HotspotService`, `StatsService` and `RouteService`.
- One class, one job. No deep inheritance, no reflection tricks.

**Product wording rules:** never produce the words "safe route", "dangerous", or "guaranteed" in any API message. Use "reported crimes near this route" and "fewer reported crimes".

**Secrets:** never hardcode the ORS key or database credentials. Read them from environment variables (section 3). Never commit them. Do not commit the dataset file unless the user says so.

---

## 3. Configuration and enums

### Environment variables

| Name | Meaning |
| --- | --- |
| `DB_URL` | JDBC URL: `jdbc:postgresql://<neon-host>/<dbname>?sslmode=require&reWriteBatchedInserts=true` |
| `DB_USER` | Neon user |
| `DB_PASSWORD` | Neon password |
| `ORS_API_KEY` | OpenRouteService API key |
| `ALLOWED_ORIGIN` | Default `http://localhost:3000` |

(`reWriteBatchedInserts=true` makes the CSV import much faster.)

### `src/main/resources/application.properties`

```properties
server.port=8080
spring.datasource.url=${DB_URL}
spring.datasource.username=${DB_USER}
spring.datasource.password=${DB_PASSWORD}
spring.jpa.hibernate.ddl-auto=none
spring.jpa.open-in-view=false
# Store and read timestamps in UTC regardless of the computer's time zone
spring.jpa.properties.hibernate.jdbc.time_zone=UTC
ors.api-key=${ORS_API_KEY}
app.allowed-origin=${ALLOWED_ORIGIN:http://localhost:3000}
talas.import-file=data/crimes.csv
```

### Tunable constants (one class `com.talas.config.AppConstants`)

| Constant | Value | Meaning |
| --- | --- | --- |
| `CELL_DEG` | `0.0025` | Grid square size in degrees (about 250 to 280 m) for hotspots and busiest spots |
| `HOTSPOT_TOP_PERCENT` | `10` | Flag the top 10% of non-empty squares |
| `HOTSPOT_MIN_WEIGHT` | `8` | A square needs at least this weight to be a hotspot (tune in B7) |
| `DEFAULT_BUFFER_M` | `150` | Distance from the route that counts as "near" |
| `MAX_DETOUR_FACTOR` | `1.5` | Accept an avoiding route only if its duration is at most 1.5x the baseline |
| `MAX_AVOID_CELLS` | `50` | Max hotspot squares sent to ORS per request |
| `MAX_NEARBY_CRIMES` | `20` | Max crimes listed per route in the response |
| `DEFAULT_CRIME_LIMIT` / `MAX_CRIME_LIMIT` | `3000` / `5000` | Limits for `GET /api/crimes` |
| `METRO_MANILA_BOUNDS` | lat 14.35 to 14.85, lng 120.85 to 121.20 | Reject route points and new crimes outside this box |

### `CrimeType` enum (in `model/`)

Each value has a `weight` (used for hotspots and route risk scores) and a human-readable `label`. Weights are **starting values** that reflect seriousness; the team may adjust them and must explain the choice in the documentation.

| Value | Label | Weight |
| --- | --- | --- |
| `THEFT` | Theft | 3 |
| `ASSAULT` | Assault | 4 |
| `HOMICIDE` | Homicide | 5 |
| `WEAPON_VIOLENCE` | Weapon violence | 4 |
| `GANG_VIOLENCE` | Gang violence | 4 |
| `SEXUAL_OFFENSE` | Sexual offense | 5 |
| `KIDNAPPING` | Kidnapping | 5 |
| `STALKING` | Stalking | 2 |
| `VEHICLE_THEFT` | Vehicle theft | 2 |
| `BURGLARY` | Burglary | 3 |
| `VANDALISM` | Vandalism | 1 |
| `ARSON` | Arson | 3 |
| `DRUG_RELATED_CRIME` | Drug-related crime | 3 |
| `HUMAN_TRAFFICKING` | Human trafficking | 5 |
| `OTHER` | Other | 1 |

### `Source` enum (in `model/`)

| Value | Label |
| --- | --- |
| `POLICE_REPORT` | Police report |
| `GOVERNMENT_STATISTICS` | Government statistics |
| `VERIFIED_PUBLIC_REPORT` | Verified public report |
| `COMMUNITY_REPORT` | Community report |
| `OTHER` | Other |

Both enums get a static helper `fromText(String raw)` that normalizes text (`trim`, upper-case, replace spaces and hyphens with `_`) and returns the matching value, or `null` if there is no match. Example: `"Drug-related crime"` becomes `DRUG_RELATED_CRIME`.

---

## 4. Database (PostgreSQL + PostGIS on Neon)

Create `src/main/resources/schema.sql` with the SQL below. **Claude Code cannot run it on Neon.** The user runs it once in Neon's SQL editor. `ddl-auto` stays `none`.

```sql
-- Turn on PostGIS (Neon supports it)
CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE crime (
    id           BIGSERIAL PRIMARY KEY,
    crime_type   VARCHAR(30)  NOT NULL,
    source       VARCHAR(30)  NOT NULL,
    occurred_at  TIMESTAMPTZ  NOT NULL,          -- always UTC
    latitude     DOUBLE PRECISION NOT NULL,
    longitude    DOUBLE PRECISION NOT NULL,
    address      VARCHAR(255),
    -- Filled in automatically from latitude/longitude. Note: longitude comes first in ST_MakePoint.
    -- "geography" means distances are measured in meters on the Earth's surface.
    location     GEOGRAPHY(Point, 4326)
                 GENERATED ALWAYS AS (ST_SetSRID(ST_MakePoint(longitude, latitude), 4326)::geography) STORED
);
CREATE INDEX idx_crime_location ON crime USING GIST (location);   -- spatial index
CREATE INDEX idx_crime_type     ON crime(crime_type);
CREATE INDEX idx_crime_source   ON crime(source);
CREATE INDEX idx_crime_time     ON crime(occurred_at);

CREATE TABLE police_station (
    id              BIGSERIAL PRIMARY KEY,
    address         VARCHAR(255) NOT NULL,
    contact_number  VARCHAR(50),
    latitude        DOUBLE PRECISION NOT NULL,
    longitude       DOUBLE PRECISION NOT NULL,
    location        GEOGRAPHY(Point, 4326)
                    GENERATED ALWAYS AS (ST_SetSRID(ST_MakePoint(longitude, latitude), 4326)::geography) STORED
);
CREATE INDEX idx_station_location ON police_station USING GIST (location);
```

**Fallback (include it in `schema.sql` as a commented-out block):** if Neon rejects the generated column with "generation expression is not immutable", define `location` as a plain `GEOGRAPHY(Point, 4326)` column instead and fill it with a trigger:

```sql
CREATE OR REPLACE FUNCTION set_location() RETURNS trigger AS $$
BEGIN
    NEW.location := ST_SetSRID(ST_MakePoint(NEW.longitude, NEW.latitude), 4326)::geography;
    RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER crime_set_location   BEFORE INSERT OR UPDATE ON crime
    FOR EACH ROW EXECUTE FUNCTION set_location();
CREATE TRIGGER station_set_location BEFORE INSERT OR UPDATE ON police_station
    FOR EACH ROW EXECUTE FUNCTION set_location();
```

Either way, the Java code never writes `location`; it only writes `latitude` and `longitude`. The JPA entities do **not** map the `location` column. The CSV column `date` is stored in `occurred_at`. Hotspots are **not** stored; they are computed from the table on each request.

---

## 5. API contract (the single source of truth shared with the frontend)

Rules for every endpoint:
- Base path `/api`, JSON only, CORS allowed for `ALLOWED_ORIGIN` (GET, POST, PUT).
- JSON coordinates are always `lat` and `lng`, or `[lat, lng]` pairs inside arrays. The backend converts to `[lng, lat]` for ORS and PostGIS internally. The frontend never swaps.
- Timestamps are ISO-8601 **UTC** strings ending in `Z`, for example `"2026-09-14T13:30:00Z"`.
- `type` and `source` values are the exact enum names from section 3. Inputs accept them case-insensitively; an unknown value returns 400.
- The bounding box params `minLat`, `minLng`, `maxLat`, `maxLng` must be all present or all absent (else 400 "Provide all four bounding box values (minLat, minLng, maxLat, maxLng).").
- Errors: normal HTTP status plus `{ "message": "human readable text" }`. Never return a stack trace.

### Common filter set
`GET /api/crimes` and `GET /api/stats` both accept these optional filters:
- `area`: text; keeps crimes whose `address` **contains** it, case-insensitive (for example `area=Tondo`). Crimes with no address never match an `area` filter.
- `type`, `source`.
- `from` (inclusive) and `to` (exclusive): ISO-8601 timestamps **with a time zone**, for example `from=2026-01-01T00:00:00Z`. Missing zone: 400 "from and to must include a time zone, for example 2026-01-01T00:00:00Z." `from` not before `to`: 400 "from must be earlier than to."
- The bounding box.

### `GET /api/meta`
Everything the frontend needs to build its dropdowns, in one call.
```json
{
  "crimeTypes": [ { "value": "THEFT", "label": "Theft", "weight": 1 } ],
  "sources":    [ { "value": "POLICE_REPORT", "label": "Police report" } ],
  "dateRange":  { "earliest": "2025-09-30T17:05:00Z", "latest": "2026-09-30T14:20:00Z" }
}
```
- `crimeTypes` and `sources` list **all** enum values in the order of section 3.
- `dateRange`: earliest and latest `occurred_at` in UTC (`null` values if there are no crimes).

### `GET /api/crimes`
Common filters plus `limit` (default 3000, max 5000). Sorted by `occurredAt` descending (newest first).
```json
[
  { "id": 1042, "type": "ASSAULT", "source": "POLICE_REPORT",
    "lat": 14.5995, "lng": 120.9842, "occurredAt": "2026-09-14T13:30:00Z",
    "address": "Recto Ave, Manila" }
]
```
`address` may be `null`.

### `POST /api/crimes` (add a new crime report)
Request body:
```json
{ "type": "THEFT",
  "source": "COMMUNITY_REPORT",
  "lat": 14.6012, "lng": 120.9845,
  "occurredAt": "2026-10-19T13:30:00Z",
  "address": "Near Legarda LRT Station, Sampaloc, Manila" }
```
Validation:
- `type` required, valid enum value. `source` optional (default `OTHER`), valid enum value if given.
- `lat`, `lng` required and inside `METRO_MANILA_BOUNDS` ("Location must be within Metro Manila.").
- `occurredAt` required, ISO-8601 with `Z` or an offset, stored as UTC. Missing zone gives 400 "occurredAt must include a time zone, for example 2026-10-19T13:30:00Z." Not more than 5 minutes in the future ("occurredAt cannot be in the future.").
- `address` optional, at most 255 characters; blank becomes `null`.

Response: **201 Created** with the saved crime in the same shape as `GET /api/crimes` items. Because every endpoint reads the database directly, the new crime immediately appears in crimes, stats, hotspots and route checks.

### `PUT /api/crimes/{id}` (correct an existing crime)
Same body and validation as `POST`; every field is replaced. Response **200** with the updated crime, or **404** `{ "message": "Crime not found." }`.

### `GET /api/stats`
Common filters (no `limit`). **All time counts are in UTC.**
```json
{
  "total": 214,
  "byType":   { "THEFT": 90, "ASSAULT": 61, "VEHICLE_THEFT": 38, "OTHER": 25 },
  "bySource": { "POLICE_REPORT": 150, "COMMUNITY_REPORT": 64 },
  "byHour":   [3,1,0,0,2,4,5,7,6,5,4,6,8,9,7,8,10,14,20,28,31,29,22,9],
  "byWeekdayHour": [ [0,1,0,0,0,1,0,1,1,0,0,1,2,1,1,1,2,3,3,4,5,4,3,1], "... 7 rows in total ..." ],
  "busiestSpots": [ { "lat": 14.5987, "lng": 120.9737, "crimeCount": 17, "sampleAddress": "Recto Ave, Manila" } ]
}
```
- `byType` and `bySource`: only values with a count above 0, sorted by count descending.
- `byHour`: 24 integers, index = **UTC** hour of day.
- `byWeekdayHour`: 7 rows x 24 columns. Row = **UTC** weekday (**row 0 = Monday**, row 6 = Sunday), column = **UTC** hour. The frontend needs this table (not just 7 weekday totals) to convert weekdays to Philippine time correctly, because one UTC day overlaps two Philippine days.
- `busiestSpots`: at most 5 grid squares with the most matching crimes, highest first; `lat`/`lng` is the square's center; `sampleAddress` is the most common non-null address among those crimes (or `null`).

### `GET /api/hotspots`
Computed from **all** crimes (not filtered) on every request. Sorted by `weight` descending.
```json
[
  { "id": "h-5617-48412", "weight": 36, "crimeCount": 17,
    "topType": "ASSAULT",
    "bounds": { "south": 14.5975, "west": 120.9725, "north": 14.6000, "east": 120.9750 } }
]
```
`topType` is the most common crime type in that square.

### `GET /api/stations`
All stations.
### `GET /api/stations/nearby`
Query params: `lat`, `lng` (required), `radiusKm` (default 2). Sorted by distance ascending.
```json
[
  { "id": 3, "address": "Police Station 3, Quezon Blvd, Quiapo, Manila",
    "contactNumber": "0000-000-0000", "lat": 14.60, "lng": 120.98, "distanceKm": 0.8 }
]
```
`/api/stations` returns the same shape with `distanceKm` set to `null`.

### `GET /api/geocode?q=Cubao`
At most 5 matches in Metro Manila; `[]` if none. `q` must be at least 2 characters or return 400.
```json
[ { "label": "Cubao, Quezon City", "lat": 14.619, "lng": 121.053 } ]
```

### `POST /api/routes/safe`
Request:
```json
{ "origin": { "lat": 14.619, "lng": 121.053 },
  "destination": { "lat": 14.601, "lng": 120.973 },
  "mode": "driving-car",
  "bufferMeters": 150 }
```
Validation (Jakarta Validation annotations where possible): `mode` is `driving-car` or `foot-walking` (default `driving-car`); `bufferMeters` optional, 50 to 500 (default 150); both points inside `METRO_MANILA_BOUNDS`, otherwise 400 "Locations must be within Metro Manila."

Response:
```json
{
  "routes": [
    { "label": "Fastest route",
      "path": [[14.619, 121.053], [14.618, 121.050]],
      "distanceKm": 9.4, "durationMin": 28, "riskScore": 17,
      "nearbyCrimes": [
        { "id": 1042, "type": "ASSAULT", "lat": 14.6, "lng": 120.98,
          "occurredAt": "2026-09-14T13:30:00Z", "address": "Recto Ave, Manila", "distanceM": 62 } ] },
    { "label": "Avoids reported hotspots",
      "path": [[14.619, 121.053]],
      "distanceKm": 10.6, "durationMin": 33, "riskScore": 5, "nearbyCrimes": [] }
  ],
  "recommendedIndex": 1,
  "warnings": [ "17 weighted reported-crime points lie within 150 m of the fastest route." ],
  "notice": "Suggestions only. Reported-crime data does not guarantee that any route is free of risk."
}
```
- `routes[0]` is always the baseline route, labeled "Fastest route".
- If no crimes are near the baseline, `routes` has one item, `warnings` is empty, and `recommendedIndex` is 0.
- `distanceKm` is rounded to 1 decimal; `durationMin` to the nearest minute; `distanceM` is an integer.
- `nearbyCrimes` is sorted by `distanceM` ascending and capped at `MAX_NEARBY_CRIMES`.
- Always include `notice`.

---

## 6. Package layout

```
src/main/java/com/talas/
  TalasApplication.java
  config/     AppConstants.java, CorsConfig.java, OrsConfig.java
  model/      Crime.java, PoliceStation.java, CrimeType.java, Source.java, HotspotCell.java
  repository/ CrimeRepository.java, PoliceStationRepository.java      (JPA, for saving)
              CrimeQueries.java, StationQueries.java                  (SQL reads with NamedParameterJdbcTemplate)
  util/       GridMath.java                                           (grid square math)
  service/    CrimeFilter.java, CrimeService.java, StatsService.java, HotspotService.java,
              StationService.java, OrsClient.java, RouteService.java, CrimeImporter.java,
              StationImporter.java, OrsUnavailableException.java, NotFoundException.java
  controller/ MetaController.java, CrimeController.java, StatsController.java,
              HotspotController.java, StationController.java,
              GeocodeController.java, RouteController.java, ApiExceptionHandler.java
  dto/        (records, see section 5)
src/main/resources/ application.properties, schema.sql, stations.csv
data/         crimes.csv   (the user's dataset; not committed)
```

---

## 7. Phases (execute in order; after each phase run its check, then `git commit`)

### B1. Scaffold
- Create the Spring Boot project: Maven wrapper, Java 17+, group `com.talas`, artifact `talas-backend`, with the allowed dependencies from section 2 (add Lombok and Apache Commons CSV to `pom.xml`).
- Add `application.properties` from section 3, `AppConstants`, and the empty package structure.
- Add `.gitignore` entries for `target/`, `.env`, IDE files, and `data/`. Add a `README.md` titled "Talas Backend" with the run commands and the environment variable table.
- **Check:** `./mvnw -q compile` succeeds.

### B2. Enums, entities, repositories, schema
- `CrimeType` and `Source` enums exactly as in section 3, with `label`, `weight` (CrimeType only), and `fromText`.
- `Crime` entity: `id`, `crimeType` (`@Enumerated(EnumType.STRING)`), `source` (same), `occurredAt` (`java.time.Instant`, column `occurred_at`), `latitude`, `longitude`, `address`. `PoliceStation` entity: `id`, `address`, `contactNumber`, `latitude`, `longitude`. **Do not map `location`.** Lombok `@Getter @Setter @NoArgsConstructor`.
- `CrimeRepository` and `PoliceStationRepository` extend `JpaRepository` (used for single saves, updates and `findById`).
- Write `schema.sql` (section 4), including the commented fallback block.
- **Check:** compile succeeds. Then tell the user to run `schema.sql` in Neon (see "When to stop and ask").

### B3. CORS and error handling
- `CorsConfig` (below).
- `ApiExceptionHandler` (`@RestControllerAdvice`): validation errors and `IllegalArgumentException` become 400; `NotFoundException` becomes 404; `OrsUnavailableException` becomes 502; any other `Exception` becomes 500 with message "Something went wrong." Body is always `{ "message": ... }`. For validation errors, use the first field error's message.
- `OrsUnavailableException` and `NotFoundException` (runtime exceptions) in `service/`.

```java
@Configuration
public class CorsConfig implements WebMvcConfigurer {
    @Value("${app.allowed-origin}") private String origin;
    @Override public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**").allowedOrigins(origin).allowedMethods("GET", "POST", "PUT");
    }
}
```

### B4. Query building blocks: `CrimeFilter` and `GridMath`

`CrimeFilter` turns the optional request params into a SQL `WHERE` clause plus its parameters. Build it with a plain list, so it is easy to explain:

```java
// Collects one SQL condition per filter the user provided, then joins them with AND.
public class CrimeFilter {
    private final List<String> conditions = new ArrayList<>();
    private final MapSqlParameterSource params = new MapSqlParameterSource();

    // ... constructor parses and validates each param (rules below) and calls add(...)

    private void add(String condition) { conditions.add(condition); }

    public String whereClause() {
        return conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
    }
    public MapSqlParameterSource params() { return params; }
}
```

Conditions (only added when the param is present):

| Param | SQL condition | Parameter value |
| --- | --- | --- |
| `type` | `crime_type = :type` | `CrimeType.fromText(...).name()`; `null` result means 400 "Unknown crime type: X" |
| `source` | `source = :source` | `Source.fromText(...).name()`; `null` means 400 "Unknown source: X" |
| `area` | `strpos(lower(address), lower(:area)) > 0` | trimmed text (ignore if blank) |
| `from` | `occurred_at >= :from` | `OffsetDateTime.parse(...)` converted to UTC (`withOffsetSameInstant(ZoneOffset.UTC)`) |
| `to` | `occurred_at < :to` | same |
| box | `ST_Intersects(location, ST_MakeEnvelope(:minLng, :minLat, :maxLng, :maxLat, 4326)::geography)` | the four doubles; all four or none, with `min < max` |

Use `strpos` rather than `LIKE` so characters like `%` in the user's text need no escaping. Pass timestamps to JDBC as `OffsetDateTime` (UTC), and read them back with `rs.getObject("occurred_at", OffsetDateTime.class).toInstant()`.

`GridMath` (in `util/`): the grid squares used by hotspots and busiest spots.
- `int rowOf(double lat)` = `(int) Math.floor(lat / CELL_DEG)`; `int colOf(double lng)` = `(int) Math.floor(lng / CELL_DEG)`.
- `long key(int row, int col)` = `((long) row << 32) | (col & 0xffffffffL)`.
- `double[] bounds(int row, int col)` returns south `row * CELL_DEG`, west `col * CELL_DEG`, north `(row + 1) * CELL_DEG`, east `(col + 1) * CELL_DEG`.
- `double[] center(int row, int col)` returns `((row + 0.5) * CELL_DEG, (col + 0.5) * CELL_DEG)`.

In SQL, the same square is `floor(latitude / :cell)` and `floor(longitude / :cell)`, so Java and SQL always agree.

- **Check:** compile succeeds.

### B5. Dataset import

`CrimeImporter` (`CommandLineRunner`, `@Order(1)`, `@Slf4j`): runs only if `crimeRepository.count() == 0`.
- Read the CSV at `talas.import-file` with Apache Commons CSV (UTF-8, first row is the header). Match header names case-insensitively after trimming. If any **required** column (`latitude`, `longitude`, `date`, `crime_type`) is missing, log an error naming the headers found, and do not import.
- For each row:
  - **Coordinates:** skip the row if latitude or longitude is empty, not a number, outside -90..90 / -180..180, or exactly 0.
  - **Date:** parse as UTC, trying in order: (1) ISO with zone or offset (`OffsetDateTime.parse` or `Instant.parse`, e.g. `2026-09-14T13:30:00Z`, `2026-09-14T13:30:00+00:00`); (2) no zone, treated as UTC: patterns `yyyy-MM-dd'T'HH:mm[:ss]` and `yyyy-MM-dd HH:mm[:ss]`; (3) date only `yyyy-MM-dd`, treated as 00:00 UTC (count these). If none works, skip the row.
  - **crime_type:** `CrimeType.fromText`. If it returns `null`, store `OTHER` and record the original text in an "unknown types" counter. Empty `crime_type` skips the row.
  - **source:** `Source.fromText`; a missing column, blank or unknown value becomes `OTHER` (record unknown texts in a counter).
  - **address:** trimmed; blank becomes `null`. If the CSV also has a `city` column with a value, append `", " + city` to the address unless the address already contains it (case-insensitive); if the address is blank, the address becomes the city. Cut to 255 characters.
- Insert in batches of 1,000 with `NamedParameterJdbcTemplate.batchUpdate` (`INSERT INTO crime (crime_type, source, occurred_at, latitude, longitude, address) VALUES (...)`). Do **not** use JPA `saveAll` here: with database-generated ids it sends one insert at a time, which is very slow over the internet to Neon.
- Log a summary: rows imported; rows skipped by reason (with up to 5 example values for bad dates); rows outside `METRO_MANILA_BOUNDS` (still imported); date-only rows; count per crime type; count per source; **every unknown crime type or source text with its count**; earliest and latest date (UTC).
- If the file is missing, log "No dataset found at <path>. Starting with no crimes." and continue.
- If the file is not a CSV (for example Excel), stop and ask the user (section 8).

`StationImporter` (`CommandLineRunner`, `@Order(2)`): reads `src/main/resources/stations.csv` (header: `address,contact_number,latitude,longitude`) and inserts stations **only if the station table is empty** (JPA `saveAll` is fine for a few dozen rows). If the CSV has only the header, insert nothing and log a message. **Do not invent real police station data.** Create the CSV with just the header. The `address` should include the station's name, for example "Police Station 3, Quezon Blvd, Quiapo, Manila".

- **Check:** with valid Neon variables and `data/crimes.csv` present, startup logs the import summary. A second start does not add rows. In Neon's SQL editor, `SELECT count(*) FROM crime WHERE location IS NULL;` returns 0 (the location column was filled in).
- After the check, **show the user the import summary**, especially skipped rows and any unknown crime type or source texts (these were stored as `OTHER`).

### B6. Read endpoints: meta, crimes, stats, stations

Put the SQL in `CrimeQueries` and `StationQueries`; services call them. Every query that accepts filters appends `filter.whereClause()` and uses `filter.params()`.

- `GET /api/meta`: enum lists from `CrimeType.values()` and `Source.values()`; date range from `SELECT min(occurred_at), max(occurred_at) FROM crime`.
- `GET /api/crimes`:
  ```sql
  SELECT id, crime_type, source, latitude, longitude, occurred_at, address
  FROM crime <where>
  ORDER BY occurred_at DESC
  LIMIT :limit
  ```
- `GET /api/stats` runs these queries with the same filter, then shapes the result in Java:
  1. Counts by type: `SELECT crime_type, count(*) AS n FROM crime <where> GROUP BY crime_type`. `total` is the sum. Sort by count descending into a `LinkedHashMap`.
  2. Counts by source: same with `source`.
  3. Counts by UTC weekday and hour:
     ```sql
     SELECT EXTRACT(ISODOW FROM occurred_at AT TIME ZONE 'UTC')::int AS dow,   -- 1 = Monday ... 7 = Sunday
            EXTRACT(HOUR   FROM occurred_at AT TIME ZONE 'UTC')::int AS hr,
            count(*) AS n
     FROM crime <where>
     GROUP BY dow, hr
     ```
     Fill `int[7][24] byWeekdayHour` with `[dow - 1][hr] = n`, and `int[24] byHour` with `byHour[hr] += n`.
  4. Busiest spots:
     ```sql
     SELECT floor(latitude  / :cell)::int AS r,
            floor(longitude / :cell)::int AS c,
            count(*) AS n,
            mode() WITHIN GROUP (ORDER BY address) AS sample_address   -- most common address
     FROM crime <where>
     GROUP BY r, c
     ORDER BY n DESC
     LIMIT 5
     ```
     `lat`/`lng` = `GridMath.center(r, c)`.
- `GET /api/stations`: `findAll()` mapped to DTOs with `distanceKm = null`.
- `GET /api/stations/nearby`:
  ```sql
  SELECT id, address, contact_number, latitude, longitude,
         ST_Distance(location, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography) AS distance_m
  FROM police_station
  WHERE ST_DWithin(location, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography, :radiusM)
  ORDER BY distance_m
  ```
  `distanceKm` = `distance_m / 1000`, rounded to 2 decimals.
- **Check (curl):**
  - `curl "localhost:8080/api/meta"` lists all 15 crime types, 5 sources and the UTC date range
  - `curl "localhost:8080/api/crimes?limit=3"` returns the 3 newest crimes, with `occurredAt` ending in `Z`
  - `curl "localhost:8080/api/crimes?area=manila&limit=3"` returns crimes whose address contains "manila"
  - `curl "localhost:8080/api/crimes?minLat=14.59&minLng=120.97&maxLat=14.61&maxLng=120.99&limit=5"` returns only crimes inside that box
  - `curl "localhost:8080/api/stats"` has `total` equal to the imported row count, a 24-item `byHour`, and a 7 x 24 `byWeekdayHour` whose numbers add up to `total`
  - Pick one crime from `/api/crimes`; it is counted at its **UTC** hour in `byHour`
  - `curl "localhost:8080/api/crimes?type=FOO"`, `curl "localhost:8080/api/crimes?minLat=14.5"` and `curl "localhost:8080/api/stats?from=2026-01-01T00:00:00"` (no zone) each return 400 with a message

### B7. Hotspots

**How hotspots work (put this explanation in `docs/DSA.md` too):** the map is divided into a grid of small squares about 250 m wide. Every crime belongs to the square that contains it. Each square gets a score: the sum of its crimes' weights (so one homicide counts like five thefts). The squares with the highest scores (the top 10%, and at least `HOTSPOT_MIN_WEIGHT`) are the hotspots. Crimes therefore count together when they are **in the same ~250 m square**; the system does not measure the distance between every pair of crimes.

`HotspotCell` record: `String id`, `int weight`, `int crimeCount`, `String topType`, `double south, west, north, east`, plus `int row, col` (used by the route logic, not sent to the frontend; use a separate DTO for the response).

`HotspotService.findHotspots()` runs on every call (no caching):
1. One query counts crimes per square and type, over **all** crimes:
   ```sql
   SELECT floor(latitude  / :cell)::int AS r,
          floor(longitude / :cell)::int AS c,
          crime_type,
          count(*) AS n
   FROM crime
   GROUP BY r, c, crime_type
   ```
2. In Java, combine the rows per square in a `HashMap<Long, SquareTotals>` keyed by `GridMath.key(r, c)`: add `n * weight` to the square's weight, add `n` to its crime count, and keep the type with the highest `n` as `topType`.
3. Put the square weights in a list and sort descending. `threshold = max(HOTSPOT_MIN_WEIGHT, weight at index ceil(0.10 * size) - 1)`.
4. Every square with `weight >= threshold` becomes a `HotspotCell` with bounds from `GridMath.bounds(r, c)` and `id` = `"h-" + r + "-" + c`.
5. Return them sorted by weight descending.

- **Tune:** log the number of non-empty squares and the weight at the 50th, 90th and 99th percentile. Adjust `HOTSPOT_MIN_WEIGHT` so there are roughly **20 to 150 hotspots**, and tell the user the final value.
- **Check:** `curl localhost:8080/api/hotspots` returns a non-empty list sorted by weight descending, each with a `topType`.

### B8. Write endpoints: add and correct crimes

`CrimeRequest` record (shared by POST and PUT) with Jakarta Validation: `@NotBlank String type`, `String source`, `@NotNull Double lat`, `@NotNull Double lng`, `@NotBlank String occurredAt`, `@Size(max = 255) String address`.

`CrimeService`:
- `apply(CrimeRequest req, Crime target)`: applies the validation rules from section 5 (enum parsing with `fromText`, Metro Manila bounds, `OffsetDateTime.parse(occurredAt).toInstant()`; a `DateTimeParseException` becomes the "must include a time zone" 400; future check against `Instant.now().plus(5, ChronoUnit.MINUTES)`), trims the address, and copies the values into `target`.
- `create(CrimeRequest)`: new `Crime`, `apply`, `crimeRepository.save`, return the DTO.
- `update(Long id, CrimeRequest)`: `findById` or throw `NotFoundException("Crime not found.")`, `apply`, `save`, return the DTO.

Nothing needs refreshing afterwards: the database fills in `location`, and every read endpoint queries the database directly.

`CrimeController`: `POST /api/crimes` returns `ResponseEntity.status(201)`; `PUT /api/crimes/{id}` returns 200. Both use `@Valid @RequestBody`.

- **Check (curl):**
  - POST a valid THEFT inside Metro Manila: 201 with an `id`; `GET /api/stats` `total` increased by 1; in Neon, the new row's `location` is not null.
  - POST with `type=FOO`, with `lat=10`, with `occurredAt="2026-10-19T13:30:00"` (no zone), and with a date next year: each returns 400 with the matching message.
  - PUT the new crime with a changed type: 200, and `GET /api/crimes` shows the new type.
  - PUT `/api/crimes/999999999`: 404 "Crime not found."

### B9. ORS client and geocoding

`OrsConfig`: a `RestClient` bean with base URL `https://api.openrouteservice.org` and the header `Authorization: <ORS_API_KEY>` (the key alone, no "Bearer").

`OrsClient` methods (on any `RestClientException` or missing data throw `OrsUnavailableException` with the message "Routing service is unavailable right now. Please try again later."):
- `List<GeocodeResult> geocode(String text)`: `GET /geocode/search` with `text`, `size=5`, and a Metro Manila filter (`boundary.country=PH`, `boundary.rect.min_lon=120.90`, `boundary.rect.min_lat=14.40`, `boundary.rect.max_lon=121.15`, `boundary.rect.max_lat=14.80`). Read `features[].geometry.coordinates` (`[lng, lat]`) and `features[].properties.label`.
- `List<OrsRoute> route(Point origin, Point dest, String mode, List<HotspotCell> avoid, boolean alternatives)`: `POST /v2/directions/{mode}/geojson`. For each returned feature, keep:
  - `path`: `geometry.coordinates` converted from `[lng, lat]` to `[lat, lng]` (for the frontend);
  - `lineGeoJson`: the feature's `geometry` object written back to a JSON string, unchanged (for PostGIS, which uses `[lng, lat]` like ORS);
  - `distanceM` and `durationS` from `properties.summary.distance` and `properties.summary.duration`.
- `OrsRoute` record: `List<double[]> path`, `String lineGeoJson`, `double distanceM`, `double durationS`.

Request bodies (ORS uses `[lng, lat]`):
```json
{ "coordinates": [[121.053, 14.619], [120.973, 14.601]] }
```
With squares to avoid (each square is a closed ring, wrapped as a MultiPolygon):
```json
{ "coordinates": [[121.053, 14.619], [120.973, 14.601]],
  "options": { "avoid_polygons": { "type": "MultiPolygon", "coordinates": [
    [ [ [120.9725,14.5975],[120.9750,14.5975],[120.9750,14.6000],[120.9725,14.6000],[120.9725,14.5975] ] ]
  ] } } }
```
With alternatives: add `"alternative_routes": { "target_count": 3 }`.

**Verify against the official ORS documentation before finalizing** (fetch `https://openrouteservice.org/dev/#/api-docs`). The shapes above are from memory and were not tested. If the docs differ, follow the docs and tell the user.

`GeocodeController`: `GET /api/geocode`, `q` at least 2 characters.

- **Check (needs the ORS key):** `curl "localhost:8080/api/geocode?q=Cubao"` returns results with `lat` near 14.6 and `lng` near 121.05. With a wrong key, it returns 502 with the friendly message.

### B10. Route with risk scoring

`corridorCrimes(route, bufferM)` asks PostGIS for every crime within the buffer of the route line, closest first:
```sql
WITH route AS (
    SELECT ST_SetSRID(ST_GeomFromGeoJSON(:line), 4326)::geography AS g
)
SELECT c.id, c.crime_type, c.latitude, c.longitude, c.occurred_at, c.address,
       ST_Distance(c.location, route.g) AS distance_m
FROM crime c, route
WHERE ST_DWithin(c.location, route.g, :buffer)
ORDER BY distance_m
```
`:line` is `route.lineGeoJson()`. The risk score uses **all** returned rows; only the first `MAX_NEARBY_CRIMES` go into the DTO.

`RouteService.findRoutes(request)` implements exactly this algorithm. Routes are checked against **all** crimes (no filters).

```
validate request (mode, buffer, bounds)

baseline = ors.route(origin, dest, mode, null, false)[0]   // if this throws, let it propagate (502)
nearby = corridorCrimes(baseline, buffer)
baselineRisk = sum of CrimeType weight over ALL nearby crimes

routes = [ toRouteDto("Fastest route", baseline, nearby, baselineRisk) ]
warnings = []

if nearby is empty:
    return response(routes, recommendedIndex = 0, warnings = [])

warnings.add(baselineRisk + " weighted reported-crime points lie within " + buffer + " m of the fastest route.")

// Step A: try a route that avoids the hotspot squares the baseline passes through
hotspots = hotspotService.findHotspots()  -> put in a HashMap<Long, HotspotCell> by GridMath.key(row, col)
cells = a HashSet: for each nearby crime, key = GridMath.key(rowOf(lat), colOf(lng));
        if the key is a hotspot, add that hotspot
cells = remove the squares that contain the origin or the destination
cells = keep the MAX_AVOID_CELLS cells with the highest weight
if cells not empty:
    try:
        avoid = ors.route(origin, dest, mode, cells, false)[0]
        avoidNearby = corridorCrimes(avoid, buffer)
        avoidRisk = sum of weights
        if avoidRisk < baselineRisk and avoid.durationS <= MAX_DETOUR_FACTOR * baseline.durationS:
            routes.add(toRouteDto("Avoids reported hotspots", ...))
    catch OrsUnavailableException:
        warnings.add("Could not look up an alternative route.")

// Step B: fallback if Step A added nothing
if routes.size() == 1:
    try:
        alts = ors.route(origin, dest, mode, null, true)
        best = the alternative with the lowest risk that is lower than baselineRisk and within the detour limit
        if best exists: routes.add(toRouteDto("Alternative route", ...))
    catch OrsUnavailableException:
        add "Could not look up an alternative route." if not already present

recommendedIndex = index of the route with the lowest risk (ties: shorter distance)
return response(routes, recommendedIndex, warnings)
```

`RouteController`: `POST /api/routes/safe` with `@Valid` on the request record.

- **Check (needs the ORS key):**
  - A route through an area with no crimes nearby returns one route and empty `warnings`.
  - A route between two busy places (pick a pair near two of the highest-weight hotspots from `/api/hotspots`) returns `warnings` and a non-empty `nearbyCrimes`; if an avoiding route is accepted, `routes[1].riskScore < routes[0].riskScore`.
  - An origin placed exactly at a hotspot center still returns a route with no exception.
  - After POSTing a new crime right on a route's path, the same route request lists that crime in `nearbyCrimes`.
  - With a wrong ORS key the response is 502 with the friendly message.

### B11. Final pass
- Re-read the code for style: comments on all non-obvious steps and SQL queries, no unused imports, no leftover debug prints.
- Search all message strings for "safe route", "dangerous", "guaranteed". There must be none.
- Search the code for `Asia/Manila`, `ZoneId.of(` and `systemDefault`: the backend must not convert to local time anywhere.
- Confirm `pom.xml` contains none of the libraries banned in section 2.
- Update `README.md`: how to create the Neon DB and run `schema.sql` (PostGIS included); where to put `data/crimes.csv`, its columns, accepted date formats (UTC) and enum values; the `stations.csv` format; the environment variables; how to run (`./mvnw spring-boot:run`); a table of all endpoints with a sample `POST /api/crimes` body; and a note that the API has no login (local demo only). State that the backend works only in UTC and the frontend shows Philippine time.
- Write `docs/DSA.md` for the course documentation: the hotspot explanation from B7, how the GiST spatial index and the `HashMap`/`HashSet` grouping are used, and this table:

| Component | Structure / algorithm | Complexity (n = crimes, k = results) |
| --- | --- | --- |
| Crimes in a map box, crimes near a route, nearby stations | PostGIS **GiST spatial index** (a tree of bounding boxes, in the R-tree family) | about O(log n + k) per search instead of O(n) |
| Filters by type, source, date | B-tree indexes | about O(log n + k) |
| Grid squares | `floor(lat / cell)`, `floor(lng / cell)`; `HashMap<Long, ...>` keyed by square | O(n) to group, O(1) average lookup |
| Hotspot ranking | Sort square weights, take the top 10% | O(c log c), c = non-empty squares |
| Statistics by type, source, weekday and hour | `GROUP BY` counts, then `LinkedHashMap`, `int[24]`, `int[7][24]` | O(n) |
| Busiest spots | Group by square, sort, take top 5 | O(n + c log c) |
| Hotspot squares on a route | `HashSet` of square keys from the nearby crimes | O(m), m = nearby crimes |
| Add / correct a crime | Insert/update one row; indexes updated by PostgreSQL | about O(log n) |

Also explain in 2 to 3 sentences why a spatial index is faster than checking every crime's distance to the route, and give the reasoning behind the crime type weights.

---

## 8. When to stop and ask the user

Stop and ask only for:
1. **Neon credentials** (`DB_URL`, `DB_USER`, `DB_PASSWORD`) and confirmation that `schema.sql` has been run. If Neon rejected the generated column, ask the user to run the fallback block instead.
2. **The dataset file** at `data/crimes.csv`. If the data is in another format (Excel, JSON, another database), ask how to read it.
3. **The import summary** after B5, if rows were skipped or unknown crime types/sources were stored as `OTHER`.
4. **An ORS API key** (`ORS_API_KEY`).
5. **Police station data.** The user must fill `stations.csv`. Never invent stations.

If credentials are not available yet, finish B1 to B4 and the code for B5 to B10, make sure the project compiles, and report what remains untested.

## 9. Definition of done

- `./mvnw spring-boot:run` starts cleanly with the environment variables set and the dataset present.
- All curl checks in B5 to B10 pass.
- Every endpoint in section 5 exists and matches the JSON shapes exactly (field names and types).
- The backend uses UTC only. No banned library in `pom.xml`. No secrets committed.
- `README.md` and `docs/DSA.md` exist.
