package com.transit.engine.repository;

import com.transit.engine.domain.model.LiveDisruption;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface LiveDisruptionRepository extends JpaRepository<LiveDisruption, Long> {
    Optional<LiveDisruption> findBySectionId(Long sectionId);
}