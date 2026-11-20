package com.transit.engine.domain.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "live_disruptions")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LiveDisruption {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(optional = false)
    @JoinColumn(name = "section_id", nullable = false, unique = true)
    private NetworkSection section;

    @Column(name = "delay_added_seconds", nullable = false)
    private int delayAddedSeconds = 0;

    @Column(name = "is_blocked", nullable = false)
    private boolean blocked = false;

    @Column(length = 255)
    private String cause;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();
}