package com.transit.engine.domain.graph;

import lombok.Getter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Getter
public class TransitGraph {

    // Clé : ID de la gare de départ -> Valeur : Liste des tronçons sortants
    private final Map<String, List<GraphEdge>> adjacencyList = new ConcurrentHashMap<>();

    public void addStation(String stationId) {
        adjacencyList.putIfAbsent(stationId, Collections.synchronizedList(new ArrayList<>()));
    }

    public void addEdge(String sourceStationId, GraphEdge edge) {
        addStation(sourceStationId);
        addStation(edge.getTargetStationId());
        adjacencyList.get(sourceStationId).add(edge);
    }

    public List<GraphEdge> getOutgoingEdges(String stationId) {
        return adjacencyList.getOrDefault(stationId, Collections.emptyList());
    }

    public void clear() {
        adjacencyList.clear();
    }
}