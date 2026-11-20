package com.transit.engine.domain.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "network_sections")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class NetworkSection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY) // Auto-incrément PostgreSQL (SERIAL)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "source_station_id", nullable = false)
    private Station sourceStation;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "target_station_id", nullable = false)
    private Station targetStation;

    @Column(name = "nominal_duration_seconds", nullable = false)
    private int nominalDurationSeconds;

    @Column(name = "distance_meters", nullable = false)
    private int distanceMeters;
}