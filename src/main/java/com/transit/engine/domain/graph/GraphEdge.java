package com.transit.engine.domain.graph;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class GraphEdge {
    private Long sectionId;
    private String targetStationId;
    private int nominalDurationSeconds;
    private int delayAddedSeconds;
    private boolean blocked;

    /**
     * Calcule le poids réel de l'arête pour Dijkstra.
     * Retourne Integer.MAX_VALUE si le tronçon est bloqué.
     */
    public int getCurrentWeightSeconds() {
        if (blocked) {
            return Integer.MAX_VALUE;
        }
        return nominalDurationSeconds + delayAddedSeconds;
    }
}