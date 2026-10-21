package com.talas.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.talas.model.Crime;

/** JPA repository, used for single saves, updates and findById. Read queries live in CrimeQueries. */
public interface CrimeRepository extends JpaRepository<Crime, Long> {
}
