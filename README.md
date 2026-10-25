# Talas Backend

Spring Boot API for **Talas**, a crime-awareness and route-information system for Metro Manila
(CIT 202 Data Structures and Algorithms final project). It serves crimes, statistics, hotspots and
police stations from PostgreSQL + PostGIS, and proxies geocoding and routing to an external provider (GraphHopper or OpenRouteService, your choice per feature).

> **Time zones:** the backend works **only in UTC**. Timestamps are stored, filtered, counted and returned
> in UTC (ending in `Z`). The frontend converts everything to Philippine time (UTC+8) for display.

> **No login:** the API is open. It is meant as a local school demo, not for public deployment.

## Requirements

- Java 17 or newer (Maven is included as the wrapper `./mvnw`)
- A PostgreSQL database with PostGIS (we use [Neon](https://neon.tech), free tier)
- An API key for each provider you use: [GraphHopper](https://www.graphhopper.com/) and/or [OpenRouteService](https://openrouteservice.org/dev/#/signup) (free tiers)

## 1. Create the database

1. Create a Neon project and database.
2. Open Neon's **SQL Editor** and run the whole of `src/main/resources/schema.sql`. It turns on PostGIS and
   creates the `crime` and `police_station` tables with their indexes.
3. If Neon rejects the `GENERATED ALWAYS AS ... STORED` columns with *"generation expression is not immutable"*,
   use the commented **fallback** block at the bottom of `schema.sql` (plain `location` column + trigger).

Spring never creates or changes tables (`ddl-auto=none`).

## 2. Environment variables

| Name | Meaning |
| --- | --- |
| `DB_URL` | JDBC URL: `jdbc:postgresql://<neon-host>/<dbname>?sslmode=require&reWriteBatchedInserts=true` |
| `DB_USER` | Neon user |
| `DB_PASSWORD` | Neon password |
| `GEOCODING_PROVIDER` | Who answers `/api/geocode`: `graphhopper` (default) or `ors` |
| `ROUTING_PROVIDER` | Who answers `/api/routes/safe`: `graphhopper` (default) or `ors` |
| `GRAPHHOPPER_API_KEY` | GraphHopper API key. Needed when either provider above is `graphhopper`. |
| `ORS_API_KEY` | OpenRouteService API key. Needed when either provider above is `ors`. |
| `ALLOWED_ORIGIN` | The frontend's address for CORS. Default `http://localhost:3000` |

`reWriteBatchedInserts=true` makes the CSV import much faster. Never commit these values.

The two providers are chosen separately, so you can mix them (for example geocoding with ORS and routing
with GraphHopper). Without the matching key the app still starts, but that feature returns 502.

Example (Linux/macOS):

```bash
export DB_URL="jdbc:postgresql://ep-xxxx.ap-southeast-1.aws.neon.tech/talas?sslmode=require&reWriteBatchedInserts=true"
export DB_USER="..."
export DB_PASSWORD="..."
export GRAPHHOPPER_API_KEY="..."
export ROUTING_PROVIDER=graphhopper   # or ors
export GEOCODING_PROVIDER=graphhopper  # or ors
```

## 3. The crime dataset: `data/crimes.csv`

Put the dataset at `data/crimes.csv` (the `data/` folder is git-ignored). On startup, **if the crime table is
empty**, it is imported in batches of 1,000 and a summary is logged (rows imported, rows skipped and why,
unknown crime types/sources, date range). If the table already has rows, the import is skipped. To re-import,
run `TRUNCATE crime;` in Neon and restart.

Columns (header names are matched case-insensitively; extra columns are ignored):

| Column | Required | Notes |
| --- | --- | --- |
| `latitude`, `longitude` | yes | Decimal degrees. Empty, non-numeric, out-of-range or exactly 0 → row skipped |
| `date` | yes | Date **and time in UTC** (formats below) |
| `crime_type` | yes | One of the crime types below (spaces/hyphens and case are forgiven). Unknown text → stored as `OTHER` and listed in the summary |
| `source` | no | One of the sources below. Missing/unknown → `OTHER` |
| `address` | no | Street, barangay, city or place description |
| `city` | no | Appended to the address (`", " + city`) unless the address already contains it |

Accepted `date` formats (all read as **UTC**):

1. ISO-8601 with a zone: `2026-09-14T13:30:00Z`, `2026-09-14T13:30:00+00:00`
2. No zone (treated as UTC): `2026-09-14T13:30`, `2026-09-14T13:30:00`, `2026-09-14 13:30`, `2026-09-14 13:30:00`
3. Date only (treated as 00:00 UTC, counted in the summary): `2026-09-14`

Crime types and weights (the weight is used for hotspot scores and route risk scores):

| Value | Label | Weight | | Value | Label | Weight |
| --- | --- | --- | --- | --- | --- | --- |
| `THEFT` | Theft | 3 | | `VEHICLE_THEFT` | Vehicle theft | 2 |
| `ASSAULT` | Assault | 4 | | `BURGLARY` | Burglary | 3 |
| `HOMICIDE` | Homicide | 5 | | `VANDALISM` | Vandalism | 1 |
| `WEAPON_VIOLENCE` | Weapon violence | 4 | | `ARSON` | Arson | 3 |
| `GANG_VIOLENCE` | Gang violence | 4 | | `DRUG_RELATED_CRIME` | Drug-related crime | 3 |
| `SEXUAL_OFFENSE` | Sexual offense | 5 | | `HUMAN_TRAFFICKING` | Human trafficking | 5 |
| `KIDNAPPING` | Kidnapping | 5 | | `OTHER` | Other | 1 |
| `STALKING` | Stalking | 2 | | | | |

Sources: `POLICE_REPORT`, `GOVERNMENT_STATISTICS`, `VERIFIED_PUBLIC_REPORT`, `COMMUNITY_REPORT`, `OTHER`.

## 4. Police stations: `src/main/resources/stations.csv`

```csv
address,contact_number,latitude,longitude
"Police Station 3, Quezon Blvd, Quiapo, Manila",0000-000-0000,14.6000,120.9800
```

The address should include the station's name. Stations are imported on startup only if the
`police_station` table is empty.

**The file currently holds 40 MOCK stations** ("Sample Station N (mock data), …", placeholder numbers
`0000-000-NNNN`), made by `../mock_data/generate_mock_data.py`. They are not real police stations. Replace them
with real, verified station data before presenting the app as anything other than a demo.

## Mock data

`../mock_data/generate_mock_data.py` writes ~5,000 mock crimes to `data/crimes.csv` and the 40 mock stations to
`src/main/resources/stations.csv`, all inside Metro Manila (see `../mock_data/README.md`). The importers load them
on the next start when the tables are empty.

## 5. Run

```bash
./mvnw spring-boot:run
```

The API listens on `http://localhost:8080`. Quick check: `curl localhost:8080/api/meta`.

To see the hotspot tuning numbers (squares, weight percentiles, threshold) in the log:

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments=--logging.level.com.talas.service.HotspotService=DEBUG
```

## Endpoints

All under `/api`, JSON only. Coordinates are `lat`/`lng` (or `[lat, lng]` pairs). Errors are
`{ "message": "..." }` with a normal HTTP status (400 bad input, 404 not found, 502 routing/geocoding provider unavailable).

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/api/meta` | Crime types (with labels and weights), sources, and the UTC date range of the data |
| GET | `/api/crimes` | Crimes, newest first. Filters below, plus `limit` (default 3000, max 5000) |
| POST | `/api/crimes` | Add a crime report (201) |
| PUT | `/api/crimes/{id}` | Correct a crime report (200, or 404) |
| GET | `/api/stats` | Total, counts by type and source, by UTC hour (`byHour`) and UTC weekday × hour (`byWeekdayHour`), busiest spots |
| GET | `/api/hotspots` | Hotspot grid squares computed from all crimes, highest weight first |
| GET | `/api/stations` | All police stations |
| GET | `/api/stations/nearby?lat=&lng=&radiusKm=2` | Stations near a point, closest first |
| GET | `/api/geocode?q=Cubao` | Up to 5 places in Metro Manila (via the geocoding provider) |
| POST | `/api/routes/safe` | Fastest route plus, when possible, an option with fewer reported crimes nearby |

Filters for `/api/crimes` and `/api/stats` (all optional): `area` (address contains, case-insensitive),
`type`, `source`, `from` (inclusive) and `to` (exclusive) as UTC timestamps **with a zone**
(e.g. `2026-01-01T00:00:00Z`), and a bounding box `minLat`, `minLng`, `maxLat`, `maxLng` (all four or none).

Sample `POST /api/crimes` body:

```json
{ "type": "THEFT",
  "source": "COMMUNITY_REPORT",
  "lat": 14.6012, "lng": 120.9845,
  "occurredAt": "2026-10-19T13:30:00Z",
  "address": "Near Legarda LRT Station, Sampaloc, Manila" }
```

Sample `POST /api/routes/safe` body (`mode`: `driving-car` or `foot-walking`; `bufferMeters`: 50 to 500):

```json
{ "origin": { "lat": 14.619, "lng": 121.053 },
  "destination": { "lat": 14.601, "lng": 120.973 },
  "mode": "driving-car",
  "bufferMeters": 150 }
```

Every route response includes a `notice`: results are suggestions based on reported-crime data only.

## Course documentation

See [`docs/DSA.md`](docs/DSA.md) for the data structures and algorithms used.
