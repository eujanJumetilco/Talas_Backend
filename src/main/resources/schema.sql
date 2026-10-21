-- Talas database schema. Run this once in Neon's SQL editor (Spring does not run it: ddl-auto=none).

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


-- ---------------------------------------------------------------------------------------------
-- FALLBACK: only if Neon rejects the generated columns above with
-- "generation expression is not immutable".
-- In that case, in the two CREATE TABLE statements above, replace the "location ... GENERATED ... STORED"
-- lines with a plain column:
--     location     GEOGRAPHY(Point, 4326)
-- then run this block so a trigger fills in location on every insert and update.
-- ---------------------------------------------------------------------------------------------
-- CREATE OR REPLACE FUNCTION set_location() RETURNS trigger AS $$
-- BEGIN
--     NEW.location := ST_SetSRID(ST_MakePoint(NEW.longitude, NEW.latitude), 4326)::geography;
--     RETURN NEW;
-- END $$ LANGUAGE plpgsql;
--
-- CREATE TRIGGER crime_set_location   BEFORE INSERT OR UPDATE ON crime
--     FOR EACH ROW EXECUTE FUNCTION set_location();
-- CREATE TRIGGER station_set_location BEFORE INSERT OR UPDATE ON police_station
--     FOR EACH ROW EXECUTE FUNCTION set_location();
