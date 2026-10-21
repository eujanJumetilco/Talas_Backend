package com.talas.model;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One reported crime. The PostGIS "location" column is not mapped here:
 * the database fills it in from latitude and longitude.
 */
@Entity
@Table(name = "crime")
@Getter
@Setter
@NoArgsConstructor
public class Crime {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "crime_type", nullable = false, length = 30)
    private CrimeType crimeType;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 30)
    private Source source;

    /** Always UTC. */
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "latitude", nullable = false)
    private double latitude;

    @Column(name = "longitude", nullable = false)
    private double longitude;

    @Column(name = "address", length = 255)
    private String address;
}
