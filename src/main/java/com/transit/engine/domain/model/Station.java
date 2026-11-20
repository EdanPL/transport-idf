package com.transit.engine.domain.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "stations")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Station {

    @Id
    @Column(length = 128)
    private String id; // Ex: "GARE_DU_NORD", "CHATELET"

    @Column(nullable = false, length = 128)
    private String name;

    @Column(nullable = false)
    private double latitude;

    @Column(nullable = false)
    private double longitude;
}