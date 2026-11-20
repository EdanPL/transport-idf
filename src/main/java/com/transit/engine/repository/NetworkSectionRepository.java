package com.transit.engine.repository;

import com.transit.engine.domain.model.NetworkSection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface NetworkSectionRepository extends JpaRepository<NetworkSection, Long> {
}