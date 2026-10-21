# Talas: Data Structures and Algorithms

This document explains the data structures and algorithms behind the Talas backend.
Road routing itself is done by OpenRouteService (agreed with the professor); our own logic covers
spatial search, hotspots, statistics and route risk scoring.

## 1. How hotspots work

The map is divided into a grid of small squares about 250 m wide (`CELL_DEG = 0.0025` degrees).
Every crime belongs to the square that contains it:

```
row = floor(latitude  / 0.0025)
col = floor(longitude / 0.0025)
```

Each square gets a score: the **sum of its crimes' weights** (so one homicide, weight 5, counts like five
acts of vandalism, weight 1). The squares with the highest scores (the **top 10%**, and at least
`HOTSPOT_MIN_WEIGHT`) are the hotspots. Crimes therefore count together when they are **in the same
~250 m square**; the system does not measure the distance between every pair of crimes.

Step by step (`HotspotService.findHotspots()`):

1. One SQL query counts crimes per (square, crime type) using `GROUP BY r, c, crime_type`.
2. Java combines those rows per square in a **`HashMap<Long, SquareTotals>`**. The key packs the row and
   column into one `long` (`row << 32 | col`), so each square has a unique key and lookups are O(1) on average.
   For each row we add `count × weight` to the square's score, add `count` to its crime count, and remember
   the most common crime type.
3. The square scores are put in a list and **sorted** from high to low. The score at position
   `ceil(10% × number of squares) − 1` is the top-10% mark. The threshold is the larger of that score and
   `HOTSPOT_MIN_WEIGHT`.
4. Every square at or above the threshold becomes a hotspot, sorted by score (highest first).

Hotspots are recomputed from the database on every request, so a newly added report counts immediately.

**Tuning.** `HOTSPOT_MIN_WEIGHT` (default 8) stops squares with only one or two minor crimes from becoming
hotspots when the data is sparse. With the real dataset, run the backend with
`--logging.level.com.talas.service.HotspotService=DEBUG`: it logs the number of non-empty squares, the score at
the 50th/90th/99th percentile and the resulting number of hotspots. Adjust the constant so the map shows
roughly 20 to 150 hotspots.

**Limitation.** A fixed grid can split a cluster that straddles a square boundary into two medium squares.

## 2. The GiST spatial index

Each table has a PostGIS `location` column (a `geography` point filled in from latitude and longitude) with a
**GiST index**. GiST here works like an **R-tree**: a balanced tree where each node stores the bounding box of
everything below it. To answer "which crimes are within 150 m of this route?", PostgreSQL walks down only the
branches whose boxes come close to the route and skips the rest of the tree.

Without the index, the database would have to compute the distance from **every** crime to the route (O(n)
for every request). With the index, a search only visits about O(log n) tree nodes plus the k crimes that
are actually close, so it stays fast as the dataset grows.

The index is used by:

- `ST_Intersects(location, ST_MakeEnvelope(...))`: crimes inside the visible map area,
- `ST_DWithin(location, route, buffer)`: crimes near a route,
- `ST_DWithin(location, point, radius)`: police stations near a point.

Crime type, source and time filters use ordinary **B-tree** indexes.

## 3. Route risk scoring and the HashSet of squares

1. ORS returns the fastest route as a GeoJSON line.
2. PostGIS returns every crime within the buffer (default 150 m) of the line, closest first.
3. **Risk score** = the sum of the weights of all those crimes.
4. If the score is above 0, the backend collects the hotspot squares the route passes through. It puts the
   hotspots in a `HashMap` by square key, then for each nearby crime computes its square key and, if that
   square is a hotspot, adds it to a **`HashSet`** (so each square is kept once, no matter how many crimes it
   has). The squares containing the start and the end are removed, and at most 50 of the heaviest are sent to
   ORS as areas to avoid.
5. The avoiding route (or, as a fallback, one of ORS's alternative routes) is kept only if it has a lower risk
   score **and** takes at most 1.5× the time of the fastest route.
6. The option with the lowest score is recommended (ties: the shorter one). Every response carries a notice that
   this is information about reported crimes only.

## 4. Statistics

`GET /api/stats` runs `GROUP BY` counts in SQL with the user's filters, then shapes the result in Java:

- counts by type and by source are sorted by count and stored in a **`LinkedHashMap`** (keeps that order in
  the JSON),
- a **`int[7][24]`** table counts crimes by UTC weekday and UTC hour; **`int[24]`** `byHour` is the same
  numbers summed over the weekdays. The frontend shifts both by +8 hours to Philippine time. A whole table is
  sent (not 7 weekday totals) because one UTC day overlaps two Philippine days,
- the 5 busiest grid squares (same grid as hotspots) with their most common address.

## 5. Complexity summary

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

## 6. Why the crime type weights are what they are

The weights (1 to 5) are a simple seriousness scale so that a square or route with a few violent crimes
ranks above one with many minor incidents:

- **5**: crimes against life and liberty with the most severe harm: homicide, sexual offense, kidnapping,
  human trafficking.
- **4**: violent crimes against a person: assault, weapon violence, gang violence.
- **3**: serious property and public-order crimes that often involve confrontation or danger to people:
  theft, burglary, arson, drug-related crime.
- **2**: lower-harm incidents: vehicle theft (property, usually no victim present), stalking.
- **1**: vandalism and other/unclassified reports.

These are starting values chosen by the team, not official severity scores; they live in one place
(`CrimeType`) and can be adjusted.
