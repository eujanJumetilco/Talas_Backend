package com.talas.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.talas.model.PoliceStation;

/** JPA repository for police stations (findAll and the initial import). */
public interface PoliceStationRepository extends JpaRepository<PoliceStation, Long> {
}
