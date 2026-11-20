package com.transit.engine.service;

import com.transit.engine.domain.graph.GraphEdge;
import com.transit.engine.domain.graph.RoutingProfile;
import com.transit.engine.domain.graph.TransitGraph;
import com.transit.engine.domain.model.LiveDisruption;
import com.transit.engine.domain.model.NetworkSection;
import com.transit.engine.domain.model.Station;
import com.transit.engine.repository.LiveDisruptionRepository;
import com.transit.engine.repository.NetworkSectionRepository;
import com.transit.engine.repository.StationRepository;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class GraphService {

    private final StationRepository stationRepository;
    private final NetworkSectionRepository sectionRepository;
    private final LiveDisruptionRepository disruptionRepository;

    @Getter
    private final TransitGraph transitGraph = new TransitGraph();

    private final Map<String, Station> stationsById = new ConcurrentHashMap<>();

    private static final Set<String> INTERCHANGE_NODES = Set.of(
            "CHATELET_LES_HALLES", "GARE_DU_NORD", "GARE_DE_LYON", "MAGENTA",
            "HAUSSMANN_SAINT_LAZARE", "AUBER", "LA_DEFENSE", "VAL_DE_FONTENAY",
            "SAINT_MICHEL", "INVALIDES", "NEUILLY_PORTE_MAILLOT", "PORTE_MAILLOT",
            "NANTERRE_PREFECTURE", "NANTERRE_LA_FOLIE", "JUVISY", "MASSY_PALAISEAU"
    );

    @PostConstruct
    public void buildGraphFromDatabase() {
        log.info("Chargement du réseau de transport en mémoire...");
        transitGraph.clear();
        stationsById.clear();

        List<Station> stations = stationRepository.findAll();
        stations.forEach(s -> {
            transitGraph.addStation(s.getId());
            stationsById.put(s.getId(), s);
        });

        Map<Long, LiveDisruption> disruptionsBySection = disruptionRepository.findAll().stream()
                .collect(Collectors.toMap(d -> d.getSection().getId(), d -> d, (a, b) -> b));

        List<NetworkSection> sections = sectionRepository.findAll();
        for (NetworkSection section : sections) {
            LiveDisruption disruption = disruptionsBySection.get(section.getId());
            int delay = (disruption != null) ? disruption.getDelayAddedSeconds() : 0;
            boolean blocked = (disruption != null) && disruption.isBlocked();

            GraphEdge edge = new GraphEdge(
                    section.getId(),
                    section.getTargetStation().getId(),
                    section.getNominalDurationSeconds(),
                    delay,
                    blocked
            );
            transitGraph.addEdge(section.getSourceStation().getId(), edge);
        }

        // Garantie topologique globale sur 100 % du réseau (zéro gare codée en dur)
        ensureBidirectionalSymmetry();
        ensureFullGraphConnectivity();

        long blockedEdgesCount = countBlockedEdges();
        log.info("Graphe chargé : {} gares, {} tronçons SQL ({} arêtes actuellement bloquées).",
                stations.size(), sections.size(), blockedEdgesCount);
    }

    /**
     * Garantit que toute arête A -> B dispose bien de son retour B -> A.
     */
    private void ensureBidirectionalSymmetry() {
        record PendingEdge(String source, String target, int durationSeconds) {}
        List<PendingEdge> missingReverse = new ArrayList<>();

        for (Map.Entry<String, List<GraphEdge>> entry : transitGraph.getAdjacencyList().entrySet()) {
            String src = entry.getKey();
            for (GraphEdge edge : entry.getValue()) {
                String dst = edge.getTargetStationId();
                boolean hasReverse = transitGraph.getOutgoingEdges(dst).stream()
                        .anyMatch(rev -> rev.getTargetStationId().equals(src));
                if (!hasReverse) {
                    missingReverse.add(new PendingEdge(dst, src, edge.getNominalDurationSeconds()));
                }
            }
        }

        long syntheticId = 800_000L;
        for (PendingEdge pe : missingReverse) {
            boolean alreadyAdded = transitGraph.getOutgoingEdges(pe.source()).stream()
                    .anyMatch(e -> e.getTargetStationId().equals(pe.target()));
            if (!alreadyAdded) {
                transitGraph.addEdge(pe.source(), new GraphEdge(syntheticId++, pe.target(), pe.durationSeconds(), 0, false));
            }
        }

        if (!missingReverse.isEmpty()) {
            log.warn("Symétrie restaurée : {} liaison(s) retour manquante(s) ajoutée(s) en mémoire.", missingReverse.size());
        }
    }

    /**
     * Analyse les composantes connexes par BFS et relie automatiquement toute
     * composante isolée à la gare la plus proche (distance GPS Haversine).
     */
    private void ensureFullGraphConnectivity() {
        List<Set<String>> components = findConnectedComponents();
        if (components.size() <= 1) {
            log.info("Topologie vérifiée : 100% du réseau forme 1 seule composante connexe.");
            return;
        }

        log.warn("Topologie fragmentée détectée : {} composantes isolées. Raccordement GPS automatique en cours...",
                components.size());

        Set<String> mainComponent = new HashSet<>(components.get(0));
        List<Set<String>> remaining = new ArrayList<>(components.subList(1, components.size()));
        long syntheticId = 900_000L;

        while (!remaining.isEmpty()) {
            String bestFromMain = null;
            String bestFromIsolated = null;
            Set<String> bestComponent = null;
            double bestDistanceKm = Double.MAX_VALUE;

            for (Set<String> comp : remaining) {
                for (String isolatedId : comp) {
                    Station isolatedStation = stationsById.get(isolatedId);
                    if (!hasValidCoordinates(isolatedStation)) continue;

                    for (String mainId : mainComponent) {
                        Station mainStation = stationsById.get(mainId);
                        if (!hasValidCoordinates(mainStation)) continue;

                        double distKm = haversineKm(
                                mainStation.getLatitude(), mainStation.getLongitude(),
                                isolatedStation.getLatitude(), isolatedStation.getLongitude()
                        );
                        if (distKm < bestDistanceKm) {
                            bestDistanceKm = distKm;
                            bestFromMain = mainId;
                            bestFromIsolated = isolatedId;
                            bestComponent = comp;
                        }
                    }
                }
            }

            if (bestComponent == null || bestFromMain == null || bestFromIsolated == null) {
                log.error("Impossible de raccorder {} composante(s) restante(s) (coordonnées GPS manquantes).", remaining.size());
                break;
            }

            int durationSec = estimateDurationSeconds(bestDistanceKm);
            transitGraph.addEdge(bestFromMain, new GraphEdge(syntheticId++, bestFromIsolated, durationSec, 0, false));
            transitGraph.addEdge(bestFromIsolated, new GraphEdge(syntheticId++, bestFromMain, durationSec, 0, false));

            log.warn("Pont topologique rétabli : {} <-> {} ({} km, {}s) [composante de {} gare(s) rattachée]",
                    getStationName(bestFromMain),
                    getStationName(bestFromIsolated),
                    String.format(Locale.US, "%.2f", bestDistanceKm),
                    durationSec,
                    bestComponent.size());

            mainComponent.addAll(bestComponent);
            remaining.remove(bestComponent);
        }
    }

    private List<Set<String>> findConnectedComponents() {
        Set<String> visited = new HashSet<>();
        List<Set<String>> components = new ArrayList<>();

        for (String stationId : transitGraph.getAdjacencyList().keySet()) {
            if (visited.contains(stationId)) continue;

            Set<String> component = new HashSet<>();
            Queue<String> queue = new ArrayDeque<>();
            queue.add(stationId);
            visited.add(stationId);

            while (!queue.isEmpty()) {
                String current = queue.poll();
                component.add(current);
                for (GraphEdge edge : transitGraph.getOutgoingEdges(current)) {
                    String target = edge.getTargetStationId();
                    if (!visited.contains(target) && transitGraph.getAdjacencyList().containsKey(target)) {
                        visited.add(target);
                        queue.add(target);
                    }
                }
            }
            components.add(component);
        }

        components.sort((a, b) -> Integer.compare(b.size(), a.size()));
        return components;
    }

    private boolean hasValidCoordinates(Station s) {
        if (s == null) return false;
        Double lat = s.getLatitude();
        Double lon = s.getLongitude();
        return lat != null && lon != null && (lat != 0.0 || lon != 0.0);
    }

    private double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        final double R = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }

    private int estimateDurationSeconds(double distanceKm) {
        // < 800m : correspondance piétonne ; au-delà : vitesse commerciale RER (~48 km/h)
        if (distanceKm <= 0.8) {
            return Math.max(120, (int) Math.round(distanceKm * 750.0));
        }
        return Math.max(120, (int) Math.round((distanceKm / 48.0) * 3600.0));
    }

    private long countBlockedEdges() {
        return transitGraph.getAdjacencyList().values().stream()
                .flatMap(List::stream)
                .filter(GraphEdge::isBlocked)
                .count();
    }

    private String resolveStationId(String rawId) {
        if (rawId == null) return null;
        String trimmed = rawId.trim();
        Map<String, List<GraphEdge>> adj = transitGraph.getAdjacencyList();
        if (adj.containsKey(trimmed)) {
            return trimmed;
        }
        String upper = trimmed.toUpperCase(Locale.ROOT);
        if (adj.containsKey(upper)) {
            return upper;
        }
        return trimmed;
    }

    public List<String> findShortestPath(String startStationId, String endStationId) {
        return findPathWithProfile(startStationId, endStationId, RoutingProfile.FASTEST);
    }

    public List<String> findPathWithProfile(String startStationId, String endStationId, RoutingProfile profile) {
        String startId = resolveStationId(startStationId);
        String endId = resolveStationId(endStationId);

        Map<String, Integer> distances = new HashMap<>();
        Map<String, String> previousStations = new HashMap<>();
        PriorityQueue<StationDistance> queue = new PriorityQueue<>(Comparator.comparingInt(StationDistance::distance));

        for (String stationId : transitGraph.getAdjacencyList().keySet()) {
            distances.put(stationId, Integer.MAX_VALUE);
        }

        if (!distances.containsKey(startId) || !distances.containsKey(endId)) {
            log.warn("Calcul impossible : gare inconnue dans le graphe (depart='{}', arrivee='{}').", startId, endId);
            return Collections.emptyList();
        }

        distances.put(startId, 0);
        queue.add(new StationDistance(startId, 0));

        while (!queue.isEmpty()) {
            StationDistance current = queue.poll();
            String currentId = current.stationId();

            if (currentId.equals(endId)) {
                break;
            }
            if (current.distance() > distances.get(currentId)) {
                continue;
            }

            String prevId = previousStations.get(currentId);
            String currentLine = (prevId != null) ? inferLine(prevId, currentId) : null;

            for (GraphEdge edge : transitGraph.getOutgoingEdges(currentId)) {
                int baseWeight = edge.getCurrentWeightSeconds();
                if (baseWeight == Integer.MAX_VALUE) {
                    continue;
                }

                int effectiveWeight = computeProfileWeight(currentId, edge, currentLine, profile);
                int newDist = distances.get(currentId) + effectiveWeight;

                if (newDist < distances.get(edge.getTargetStationId())) {
                    distances.put(edge.getTargetStationId(), newDist);
                    previousStations.put(edge.getTargetStationId(), currentId);
                    queue.add(new StationDistance(edge.getTargetStationId(), newDist));
                }
            }
        }

        List<String> path = reconstructPath(previousStations, startId, endId);
        if (path.isEmpty()) {
            log.warn("Aucun chemin praticable entre {} et {} (profil={}, arêtes bloquées actives={}).",
                    startId, endId, profile, countBlockedEdges());
        }
        return path;
    }

    private int computeProfileWeight(String sourceId, GraphEdge edge, String currentLine, RoutingProfile profile) {
        int baseWeight = edge.getCurrentWeightSeconds();
        String nextLine = inferLine(sourceId, edge.getTargetStationId());
        boolean isTransfer = "CORRESPONDANCE".equals(nextLine)
                || (currentLine != null && !"CORRESPONDANCE".equals(currentLine) && !currentLine.equals(nextLine));

        return switch (profile) {
            case FASTEST, LOW_BATTERY -> baseWeight;
            case NAP_FOCUS -> baseWeight + (isTransfer ? 720 : 0);
            case SEAT_COMFORT -> {
                int congestionPenalty = edge.getDelayAddedSeconds() > 0 ? edge.getDelayAddedSeconds() * 2 : 0;
                int hubPenalty = INTERCHANGE_NODES.contains(edge.getTargetStationId()) ? 120 : 0;
                yield baseWeight + congestionPenalty + hubPenalty;
            }
        };
    }

    public DetailedRouteResult buildDetailedRoute(String rawStartId, String rawEndId, RoutingProfile profile) {
        String startId = resolveStationId(rawStartId);
        String endId = resolveStationId(rawEndId);

        List<String> path = findPathWithProfile(startId, endId, profile);
        if (path.isEmpty()) {
            return null;
        }

        List<RouteStep> steps = new ArrayList<>();
        int totalNominal = 0;
        int totalDelay = 0;
        int transfersCount = 0;
        int maxContinuousSeconds = 0;
        int currentContinuousSeconds = 0;
        String previousLine = null;

        for (int i = 0; i < path.size() - 1; i++) {
            String src = path.get(i);
            String dst = path.get(i + 1);
            GraphEdge edge = findActiveEdge(src, dst);
            int nominal = (edge != null) ? edge.getNominalDurationSeconds() : 120;
            int delay = (edge != null) ? edge.getDelayAddedSeconds() : 0;
            int stepDuration = nominal + delay;

            String line = inferLine(src, dst);
            boolean transfer = "CORRESPONDANCE".equals(line)
                    || (previousLine != null && !"CORRESPONDANCE".equals(previousLine) && !previousLine.equals(line));

            if (transfer) {
                transfersCount++;
                maxContinuousSeconds = Math.max(maxContinuousSeconds, currentContinuousSeconds);
                currentContinuousSeconds = stepDuration;
            } else {
                currentContinuousSeconds += stepDuration;
            }

            totalNominal += nominal;
            totalDelay += delay;
            previousLine = line;

            steps.add(new RouteStep(
                    i + 1,
                    src,
                    getStationName(src),
                    dst,
                    getStationName(dst),
                    line,
                    stepDuration,
                    delay,
                    transfer
            ));
        }
        maxContinuousSeconds = Math.max(maxContinuousSeconds, currentContinuousSeconds);

        BoardingAdvice boardingAdvice = buildBoardingAdvice(path, steps, totalDelay, profile);

        return new DetailedRouteResult(
                startId,
                getStationName(startId),
                endId,
                getStationName(endId),
                profile.name(),
                profile.getLabel(),
                profile.getDescription(),
                path.size(),
                transfersCount,
                totalNominal + totalDelay,
                totalDelay,
                maxContinuousSeconds,
                path,
                steps,
                boardingAdvice
        );
    }

    private BoardingAdvice buildBoardingAdvice(List<String> path, List<RouteStep> steps, int totalDelay, RoutingProfile profile) {
        String destination = path.get(path.size() - 1);
        String carPosition;
        String exitTip;

        if (Set.of("CHATELET_LES_HALLES", "GARE_DU_NORD", "GARE_DE_LYON", "LA_DEFENSE").contains(destination)) {
            carPosition = "Milieu de rame (Voitures 4 à 6)";
            exitTip = "Accès direct aux escalators centraux des pôles majeurs pour gagner 2 à 3 minutes sur la sortie.";
        } else if (Set.of("AUBER", "HAUSSMANN_SAINT_LAZARE", "MAGENTA", "SAINT_MICHEL", "INVALIDES").contains(destination)) {
            carPosition = "Tête de train (Voitures 1 à 3)";
            exitTip = "Position idéale face aux couloirs de correspondance souterrains d'arrivée.";
        } else {
            carPosition = "Queue de train (Voitures 7 à 8)";
            exitTip = "Zones généralement 25 % moins denses à l'embarquement et proches des sorties de gare de banlieue.";
        }

        String start = path.get(0);
        String second = path.size() > 1 ? path.get(1) : null;
        String reboundTip = null;

        if (totalDelay > 0 || profile == RoutingProfile.SEAT_COMFORT || INTERCHANGE_NODES.contains(start)) {
            for (GraphEdge candidate : transitGraph.getOutgoingEdges(start)) {
                String neighbor = candidate.getTargetStationId();
                if (!neighbor.equals(second) && !candidate.isBlocked() && candidate.getNominalDurationSeconds() <= 240) {
                    reboundTip = "Astuce rebond place assise : en cas de rame saturée à " + getStationName(start)
                            + ", remontez 1 arrêt à contre-sens jusqu'à " + getStationName(neighbor)
                            + " (+" + Math.round(candidate.getNominalDurationSeconds() / 60.0) + " min) pour embarquer avant la foule.";
                    break;
                }
            }
        }

        if (reboundTip == null) {
            reboundTip = "Trafic fluide sur votre tronçon de départ : embarquement direct recommandé sans rebond nécessaire.";
        }

        String comfortScore = totalDelay == 0 ? "Optimal (92 %)" : (totalDelay <= 300 ? "Modéré (74 %)" : "Chargé (51 %)");

        return new BoardingAdvice(carPosition, exitTip, reboundTip, comfortScore);
    }

    private GraphEdge findActiveEdge(String src, String dst) {
        for (GraphEdge edge : transitGraph.getOutgoingEdges(src)) {
            if (edge.getTargetStationId().equals(dst) && !edge.isBlocked()) {
                return edge;
            }
        }
        return null;
    }

    private String getStationName(String id) {
        Station st = stationsById.get(id);
        return (st != null) ? st.getName() : id;
    }

    private String inferLine(String src, String dst) {
        Set<String> pair = Set.of(src, dst);
        if (pair.equals(Set.of("MAGENTA", "GARE_DU_NORD"))
                || pair.equals(Set.of("HAUSSMANN_SAINT_LAZARE", "AUBER"))
                || pair.equals(Set.of("NEUILLY_PORTE_MAILLOT", "PORTE_MAILLOT"))
                || pair.equals(Set.of("NANTERRE_LA_FOLIE", "NANTERRE_PREFECTURE"))
                || pair.equals(Set.of("CHARLES_DE_GAULLE_ETOILE", "NEUILLY_PORTE_MAILLOT"))) {
            return "CORRESPONDANCE";
        }
        String combined = src + "_" + dst;
        if (combined.matches(".*(CERGY|POISSY|ACHERES|SAINT_GERMAIN|BOISSY|MARNE_LA_VALLEE|CHESSY|NOISY_LE_GRAND|VINCENNES|NATION|AUBER|LA_DEFENSE|NANTERRE_PREFECTURE|NANTERRE_UNIVERSITE).*")) {
            return "RER A";
        }
        if (combined.matches(".*(MITRY|AEROPORT|ROBINSON|SAINT_REMY|BOURG_LA_REINE|ANTONY|DENFERT|LUXEMBOURG|AULNAY|LE_BOURGET|LA_PLAINE).*")) {
            return "RER B";
        }
        if (combined.matches(".*(PONTOISE|VERSAILLES|DOURDAN|ETAMPES|SAINT_QUENTIN|INVALIDES|MUSEE_D_ORSAY|AUSTERLITZ|BIBLIOTHEQUE|CHOISY|ABLON|MASSY_VERRIERES).*")) {
            return "RER C";
        }
        if (combined.matches(".*(CREIL|ORRY|MALESHERBES|MELUN|CORBEIL|VILLENEUVE_SAINT_GEORGES|JUVISY|GOUSSAINVILLE|STADE_DE_FRANCE|MAISONS_ALFORT).*")) {
            return "RER D";
        }
        if (combined.matches(".*(CHELLES|TOURNAN|HAUSSMANN|MAGENTA|ROSA_PARKS|PANTIN|NOISY_LE_SEC|BONDY|VAL_DE_FONTENAY|NANTERRE_LA_FOLIE).*")) {
            return "RER E";
        }
        return "RER";
    }

    public void updateEdgeWeight(Long sectionId, int delayAddedSeconds, boolean blocked) {
        transitGraph.getAdjacencyList().values().forEach(edges -> {
            for (GraphEdge edge : edges) {
                if (edge.getSectionId().equals(sectionId)) {
                    edge.setDelayAddedSeconds(delayAddedSeconds);
                    edge.setBlocked(blocked);
                    log.info("Arête #{} mise à jour en RAM -> retard: {}s, bloqué: {}",
                            sectionId, delayAddedSeconds, blocked);
                }
            }
        });
    }

    private List<String> reconstructPath(Map<String, String> previousStations, String start, String end) {
        LinkedList<String> path = new LinkedList<>();
        String step = end;

        if (!start.equals(end) && !previousStations.containsKey(step)) {
            return Collections.emptyList();
        }

        while (step != null) {
            path.addFirst(step);
            step = previousStations.get(step);
        }
        return path;
    }

    public int calculateTotalDurationSeconds(List<String> path) {
        if (path == null || path.size() < 2) return 0;
        int total = 0;
        for (int i = 0; i < path.size() - 1; i++) {
            String current = path.get(i);
            String next = path.get(i + 1);
            for (GraphEdge edge : transitGraph.getOutgoingEdges(current)) {
                if (edge.getTargetStationId().equals(next) && !edge.isBlocked()) {
                    total += edge.getNominalDurationSeconds() + edge.getDelayAddedSeconds();
                    break;
                }
            }
        }
        return total;
    }

    private record StationDistance(String stationId, int distance) {}

    public record RouteStep(
            int index,
            String fromId,
            String fromName,
            String toId,
            String toName,
            String line,
            int durationSeconds,
            int delaySeconds,
            boolean transfer
    ) {}

    public record BoardingAdvice(
            String carPosition,
            String exitTip,
            String reboundTip,
            String comfortScore
    ) {}

    public record DetailedRouteResult(
            String origin,
            String originName,
            String destination,
            String destinationName,
            String profile,
            String profileLabel,
            String profileDescription,
            int stopsCount,
            int transfersCount,
            int totalDurationSeconds,
            int totalDelaySeconds,
            int maxContinuousSeconds,
            List<String> path,
            List<RouteStep> steps,
            BoardingAdvice boardingAdvice
    ) {}

    private static final Set<String> COMMERCE_HUBS = Set.of(
            "CHATELET_LES_HALLES", "LA_DEFENSE", "AUBER", "HAUSSMANN_SAINT_LAZARE",
            "GARE_DU_NORD", "GARE_DE_LYON", "NATION", "VAL_D_EUROPE", "MARNE_LA_VALLEE_CHESSY",
            "BIBLIOTHEQUE_FRANCOIS_MITTERRAND", "SAINT_MICHEL", "INVALIDES",
            "MASSY_PALAISEAU", "JUVISY", "VAL_DE_FONTENAY", "NOISY_LE_GRAND_MONT_D_EST",
            "VERSAILLES_CHATEAU_RIVE_GAUCHE", "VERSAILLES_CHANTIERS", "CERGY_PREFECTURE"
    );

    // =========================================================================
    // SPRINT 2 — MODE 2 : ESCALE & DÉTOUR CALIBRÉ
    // =========================================================================

    public StopoverSearchResult findStopoverCandidates(
            String rawStartId,
            String rawEndId,
            int maxDetourMinutes,
            String category) {

        String startId = resolveStationId(rawStartId);
        String endId = resolveStationId(rawEndId);

        if (startId == null || endId == null || startId.equals(endId)
                || !transitGraph.getAdjacencyList().containsKey(startId)
                || !transitGraph.getAdjacencyList().containsKey(endId)) {
            return null;
        }

        DijkstraTree forward = runDijkstraAll(startId, transitGraph.getAdjacencyList());
        int directSeconds = forward.distances().getOrDefault(endId, Integer.MAX_VALUE);
        if (directSeconds == Integer.MAX_VALUE) {
            return null;
        }

        Map<String, List<GraphEdge>> reverseAdjacency = buildReverseAdjacency();
        DijkstraTree backward = runDijkstraAll(endId, reverseAdjacency);

        int maxDetourSeconds = Math.max(0, maxDetourMinutes) * 60;
        String normalizedCategory = (category == null || category.isBlank()) ? "ALL" : category.trim().toUpperCase(Locale.ROOT);

        List<StopoverCandidate> candidates = new ArrayList<>();

        for (String stationId : transitGraph.getAdjacencyList().keySet()) {
            if (stationId.equals(startId) || stationId.equals(endId)) continue;
            if (!matchesStopoverCategory(stationId, normalizedCategory)) continue;

            int leg1 = forward.distances().getOrDefault(stationId, Integer.MAX_VALUE);
            int leg2 = backward.distances().getOrDefault(stationId, Integer.MAX_VALUE);
            if (leg1 == Integer.MAX_VALUE || leg2 == Integer.MAX_VALUE) continue;

            int totalWithStop = leg1 + leg2;
            int detourSeconds = Math.max(0, totalWithStop - directSeconds);

            if (detourSeconds <= maxDetourSeconds) {
                Station st = stationsById.get(stationId);
                List<String> leg1Path = reconstructPath(forward.previous(), startId, stationId);
                List<String> leg2Path = reconstructReversePath(backward.previous(), stationId, endId);

                candidates.add(new StopoverCandidate(
                        stationId,
                        getStationName(stationId),
                        st != null ? st.getLatitude() : null,
                        st != null ? st.getLongitude() : null,
                        resolveStationTag(stationId),
                        leg1,
                        leg2,
                        totalWithStop,
                        detourSeconds,
                        Math.max(0, leg1Path.size() - 1),
                        Math.max(0, leg2Path.size() - 1),
                        leg1Path,
                        leg2Path
                ));
            }
        }

        // Trie par surcoût minimal, puis privilégie les escales équilibrées au milieu du trajet
        candidates.sort(Comparator
                .comparingInt(StopoverCandidate::detourSeconds)
                .thenComparingInt(c -> Math.abs(c.leg1Seconds() - c.leg2Seconds())));

        List<StopoverCandidate> topCandidates = candidates.stream().limit(12).toList();
        List<String> directPath = reconstructPath(forward.previous(), startId, endId);

        return new StopoverSearchResult(
                startId,
                getStationName(startId),
                endId,
                getStationName(endId),
                directSeconds,
                Math.max(0, directPath.size() - 1),
                maxDetourMinutes,
                normalizedCategory,
                candidates.size(),
                directPath,
                topCandidates
        );
    }

    private boolean matchesStopoverCategory(String stationId, String category) {
        int degree = transitGraph.getOutgoingEdges(stationId).size();
        return switch (category) {
            case "COMMERCE" -> COMMERCE_HUBS.contains(stationId);
            case "HUB" -> INTERCHANGE_NODES.contains(stationId) || degree >= 3;
            case "CALM" -> !INTERCHANGE_NODES.contains(stationId) && !COMMERCE_HUBS.contains(stationId) && degree <= 2;
            default -> true;
        };
    }

    private String resolveStationTag(String stationId) {
        if (COMMERCE_HUBS.contains(stationId)) return "Pôle Commerces & Cafés";
        if (INTERCHANGE_NODES.contains(stationId) || transitGraph.getOutgoingEdges(stationId).size() >= 3) {
            return "Hub Correspondances";
        }
        return "Gare Calme";
    }

    // =========================================================================
    // SPRINT 2 — MODE 3 : POINT DE RENCONTRE ÉQUITABLE
    // =========================================================================

    public MeetingSearchResult findFairMeetingPoints(List<String> rawOrigins, String strategy) {
        if (rawOrigins == null || rawOrigins.size() < 2) {
            return null;
        }

        List<String> origins = new ArrayList<>();
        for (String raw : rawOrigins) {
            String resolved = resolveStationId(raw);
            if (resolved != null && transitGraph.getAdjacencyList().containsKey(resolved) && !origins.contains(resolved)) {
                origins.add(resolved);
            }
        }
        if (origins.size() < 2) {
            return null;
        }

        String normalizedStrategy = "MIN_SUM".equalsIgnoreCase(strategy) ? "MIN_SUM" : "MIN_MAX";

        List<DijkstraTree> trees = origins.stream()
                .map(origin -> runDijkstraAll(origin, transitGraph.getAdjacencyList()))
                .toList();

        record ScoredStation(
                String stationId,
                int maxSec,
                int minSec,
                int sumSec,
                int spreadSec,
                int[] durations
        ) {}

        List<ScoredStation> scored = new ArrayList<>();

        for (String candidateId : transitGraph.getAdjacencyList().keySet()) {
            int[] durations = new int[origins.size()];
            boolean reachableByAll = true;
            int maxSec = 0;
            int minSec = Integer.MAX_VALUE;
            int sumSec = 0;

            for (int i = 0; i < trees.size(); i++) {
                int dist = trees.get(i).distances().getOrDefault(candidateId, Integer.MAX_VALUE);
                if (dist == Integer.MAX_VALUE) {
                    reachableByAll = false;
                    break;
                }
                durations[i] = dist;
                maxSec = Math.max(maxSec, dist);
                minSec = Math.min(minSec, dist);
                sumSec += dist;
            }

            if (reachableByAll) {
                scored.add(new ScoredStation(candidateId, maxSec, minSec, sumSec, maxSec - minSec, durations));
            }
        }

        if (scored.isEmpty()) {
            return null;
        }

        Comparator<ScoredStation> comparator = "MIN_SUM".equals(normalizedStrategy)
                ? Comparator.comparingInt(ScoredStation::sumSec)
                .thenComparingInt(ScoredStation::maxSec)
                .thenComparingInt(ScoredStation::spreadSec)
                : Comparator.comparingInt(ScoredStation::maxSec)
                .thenComparingInt(ScoredStation::spreadSec)
                .thenComparingInt(ScoredStation::sumSec);

        scored.sort(comparator);

        List<MeetingCandidate> topCandidates = new ArrayList<>();
        for (ScoredStation s : scored.stream().limit(8).toList()) {
            Station st = stationsById.get(s.stationId());
            List<ParticipantLeg> legs = new ArrayList<>();

            for (int i = 0; i < origins.size(); i++) {
                String originId = origins.get(i);
                List<String> path = reconstructPath(trees.get(i).previous(), originId, s.stationId());
                legs.add(new ParticipantLeg(
                        i + 1,
                        originId,
                        getStationName(originId),
                        s.durations()[i],
                        Math.max(0, path.size() - 1),
                        path
                ));
            }

            int fairnessScore = s.maxSec() == 0
                    ? 100
                    : Math.max(0, 100 - (int) Math.round((s.spreadSec() / (double) s.maxSec()) * 100.0));

            topCandidates.add(new MeetingCandidate(
                    s.stationId(),
                    getStationName(s.stationId()),
                    st != null ? st.getLatitude() : null,
                    st != null ? st.getLongitude() : null,
                    resolveStationTag(s.stationId()),
                    s.maxSec(),
                    s.minSec(),
                    s.sumSec(),
                    Math.round((float) s.sumSec() / origins.size()),
                    s.spreadSec(),
                    fairnessScore,
                    legs
            ));
        }

        return new MeetingSearchResult(
                normalizedStrategy,
                origins.size(),
                origins.stream().map(this::getStationName).toList(),
                topCandidates
        );
    }

    // =========================================================================
    // UTILITAIRES DIJKSTRA TOUTES DESTINATIONS & GRAPHE TRANSPOSÉ
    // =========================================================================

    private DijkstraTree runDijkstraAll(String sourceId, Map<String, List<GraphEdge>> adjacency) {
        Map<String, Integer> distances = new HashMap<>();
        Map<String, String> previous = new HashMap<>();
        PriorityQueue<StationDistance> queue = new PriorityQueue<>(Comparator.comparingInt(StationDistance::distance));

        for (String id : adjacency.keySet()) {
            distances.put(id, Integer.MAX_VALUE);
        }
        distances.put(sourceId, 0);
        queue.add(new StationDistance(sourceId, 0));

        while (!queue.isEmpty()) {
            StationDistance current = queue.poll();
            String currentId = current.stationId();

            if (current.distance() > distances.getOrDefault(currentId, Integer.MAX_VALUE)) {
                continue;
            }

            for (GraphEdge edge : adjacency.getOrDefault(currentId, Collections.emptyList())) {
                int weight = edge.getCurrentWeightSeconds();
                if (weight == Integer.MAX_VALUE) continue;

                int newDist = current.distance() + weight;
                if (newDist < distances.getOrDefault(edge.getTargetStationId(), Integer.MAX_VALUE)) {
                    distances.put(edge.getTargetStationId(), newDist);
                    previous.put(edge.getTargetStationId(), currentId);
                    queue.add(new StationDistance(edge.getTargetStationId(), newDist));
                }
            }
        }

        return new DijkstraTree(distances, previous);
    }

    private Map<String, List<GraphEdge>> buildReverseAdjacency() {
        Map<String, List<GraphEdge>> reverse = new HashMap<>();
        for (String id : transitGraph.getAdjacencyList().keySet()) {
            reverse.put(id, new ArrayList<>());
        }
        for (Map.Entry<String, List<GraphEdge>> entry : transitGraph.getAdjacencyList().entrySet()) {
            String src = entry.getKey();
            for (GraphEdge edge : entry.getValue()) {
                reverse.computeIfAbsent(edge.getTargetStationId(), k -> new ArrayList<>())
                        .add(new GraphEdge(
                                edge.getSectionId(),
                                src,
                                edge.getNominalDurationSeconds(),
                                edge.getDelayAddedSeconds(),
                                edge.isBlocked()
                        ));
            }
        }
        return reverse;
    }

    private List<String> reconstructReversePath(Map<String, String> nextMap, String start, String end) {
        List<String> path = new ArrayList<>();
        String current = start;
        if (!start.equals(end) && !nextMap.containsKey(current)) {
            return Collections.emptyList();
        }
        int guard = transitGraph.getAdjacencyList().size() + 1;
        while (current != null && guard-- > 0) {
            path.add(current);
            if (current.equals(end)) break;
            current = nextMap.get(current);
        }
        return path;
    }

    private record DijkstraTree(Map<String, Integer> distances, Map<String, String> previous) {}

    public record StopoverCandidate(
            String stationId,
            String stationName,
            Double latitude,
            Double longitude,
            String categoryLabel,
            int leg1Seconds,
            int leg2Seconds,
            int totalSeconds,
            int detourSeconds,
            int leg1Stops,
            int leg2Stops,
            List<String> leg1Path,
            List<String> leg2Path
    ) {}

    public record StopoverSearchResult(
            String originId,
            String originName,
            String destinationId,
            String destinationName,
            int directDurationSeconds,
            int directStops,
            int maxDetourMinutes,
            String category,
            int totalMatchingCount,
            List<String> directPath,
            List<StopoverCandidate> candidates
    ) {}

    public record ParticipantLeg(
            int participantIndex,
            String originId,
            String originName,
            int durationSeconds,
            int stopsCount,
            List<String> path
    ) {}

    public record MeetingCandidate(
            String stationId,
            String stationName,
            Double latitude,
            Double longitude,
            String categoryLabel,
            int maxDurationSeconds,
            int minDurationSeconds,
            int totalDurationSeconds,
            int avgDurationSeconds,
            int spreadSeconds,
            int fairnessScore,
            List<ParticipantLeg> participantLegs
    ) {}

    public record MeetingSearchResult(
            String strategy,
            int participantsCount,
            List<String> originNames,
            List<MeetingCandidate> candidates
    ) {}

    // =========================================================================
    // SPRINT 3 — MODE 4 : RADAR DE PERTURBATIONS & PROPAGATION (EFFET DOMINO)
    // =========================================================================

    public RadarAnalysisResult buildRadarAnalysis() {
        List<String> rerLines = List.of("RER A", "RER B", "RER C", "RER D", "RER E");

        Map<String, int[]> lineCounters = new LinkedHashMap<>();
        // [0]=total, [1]=fluid, [2]=delayed, [3]=blocked, [4]=delaySumSeconds
        for (String line : rerLines) {
            lineCounters.put(line, new int[5]);
        }

        List<RadarIncidentEdge> incidentEdges = new ArrayList<>();
        Set<String> seenPairs = new HashSet<>();
        Map<String, Integer> seedSeverity = new HashMap<>(); // stationId -> 2 (bloqué) ou 1 (ralenti)
        Map<String, Integer> seedDelay = new HashMap<>();
        Map<String, String> seedOriginName = new HashMap<>();

        int totalEdges = 0;
        int blockedEdges = 0;
        int delayedEdges = 0;
        int totalDelaySeconds = 0;

        for (Map.Entry<String, List<GraphEdge>> entry : transitGraph.getAdjacencyList().entrySet()) {
            String src = entry.getKey();
            for (GraphEdge edge : entry.getValue()) {
                String dst = edge.getTargetStationId();
                String line = inferLine(src, dst);
                int delay = edge.getDelayAddedSeconds();
                boolean blocked = edge.isBlocked();

                totalEdges++;
                if (blocked) blockedEdges++;
                else if (delay > 0) {
                    delayedEdges++;
                    totalDelaySeconds += delay;
                }

                if (lineCounters.containsKey(line)) {
                    int[] c = lineCounters.get(line);
                    c[0]++;
                    if (blocked) c[3]++;
                    else if (delay > 0) {
                        c[2]++;
                        c[4] += delay;
                    } else {
                        c[1]++;
                    }
                }

                if (blocked || delay > 0) {
                    int sev = blocked ? 2 : 1;
                    int effectiveDelay = blocked ? 900 : delay;

                    for (String endpoint : List.of(src, dst)) {
                        if (sev > seedSeverity.getOrDefault(endpoint, 0)) {
                            seedSeverity.put(endpoint, sev);
                            seedOriginName.put(endpoint, getStationName(src) + " ↔ " + getStationName(dst));
                        }
                        seedDelay.merge(endpoint, effectiveDelay, Math::max);
                    }

                    String pairKey = src.compareTo(dst) < 0 ? src + "::" + dst : dst + "::" + src;
                    if (seenPairs.add(pairKey)) {
                        Station s1 = stationsById.get(src);
                        Station s2 = stationsById.get(dst);
                        String severityLabel = blocked ? "CRITICAL" : (delay >= 600 ? "HIGH" : "MODERATE");

                        incidentEdges.add(new RadarIncidentEdge(
                                edge.getSectionId(),
                                src,
                                getStationName(src),
                                s1 != null ? s1.getLatitude() : null,
                                s1 != null ? s1.getLongitude() : null,
                                dst,
                                getStationName(dst),
                                s2 != null ? s2.getLatitude() : null,
                                s2 != null ? s2.getLongitude() : null,
                                line,
                                delay,
                                blocked,
                                severityLabel
                        ));
                    }
                }
            }
        }

        incidentEdges.sort(Comparator
                .comparing(RadarIncidentEdge::blocked).reversed()
                .thenComparingInt(RadarIncidentEdge::delaySeconds).reversed()
                .thenComparing(RadarIncidentEdge::fromName));

        // Propagation BFS à 2 sauts depuis les gares directement impactées
        List<PropagationZone> propagationZones = computePropagationZones(seedSeverity, seedDelay, seedOriginName);

        // Scores par ligne RER
        List<LineVulnerability> lineScores = new ArrayList<>();
        for (String line : rerLines) {
            int[] c = lineCounters.get(line);
            int total = Math.max(1, c[0]);
            int impacted = c[2] + c[3];
            double impactedRatio = (impacted * 100.0) / total;
            int vulnerabilityScore = (int) Math.min(100, Math.round(
                    ((c[3] * 3.0 + c[2] * 1.4) / total) * 100.0
            ));
            int healthScore = Math.max(0, 100 - vulnerabilityScore);
            String status = vulnerabilityScore >= 35 ? "CRITICAL"
                    : (vulnerabilityScore >= 12 ? "WARNING" : "FLUID");

            lineScores.add(new LineVulnerability(
                    line,
                    c[0],
                    c[1],
                    c[2],
                    c[3],
                    Math.round(impactedRatio * 10.0) / 10.0,
                    healthScore,
                    vulnerabilityScore,
                    status
            ));
        }

        // Top 6 des hubs critiques (degré du graphe + exposition aux perturbations)
        List<CriticalHubStatus> criticalHubs = transitGraph.getAdjacencyList().entrySet().stream()
                .map(e -> {
                    String id = e.getKey();
                    int degree = e.getValue().size();
                    long impactedOut = e.getValue().stream()
                            .filter(ed -> ed.isBlocked() || ed.getDelayAddedSeconds() > 0)
                            .count();
                    Station st = stationsById.get(id);
                    int exposurePct = degree == 0 ? 0 : (int) Math.round((impactedOut * 100.0) / degree);
                    return new CriticalHubStatus(
                            id,
                            getStationName(id),
                            st != null ? st.getLatitude() : null,
                            st != null ? st.getLongitude() : null,
                            degree,
                            (int) impactedOut,
                            exposurePct
                    );
                })
                .sorted(Comparator
                        .comparingInt(CriticalHubStatus::impactedConnections).reversed()
                        .thenComparingInt(CriticalHubStatus::degree).reversed())
                .limit(6)
                .toList();

        int globalFluidityPct = totalEdges == 0
                ? 100
                : (int) Math.round(((totalEdges - blockedEdges - delayedEdges) * 100.0) / totalEdges);

        int avgDelayMinutes = delayedEdges == 0
                ? 0
                : (int) Math.round((totalDelaySeconds / (double) delayedEdges) / 60.0);

        return new RadarAnalysisResult(
                totalEdges,
                blockedEdges,
                delayedEdges,
                globalFluidityPct,
                avgDelayMinutes,
                propagationZones.size(),
                lineScores,
                incidentEdges,
                propagationZones,
                criticalHubs
        );
    }

    private List<PropagationZone> computePropagationZones(
            Map<String, Integer> seedSeverity,
            Map<String, Integer> seedDelay,
            Map<String, String> seedOriginName) {

        if (seedSeverity.isEmpty()) {
            return Collections.emptyList();
        }

        record BfsNode(String stationId, int depth, int severity, int baseDelay, String sourceIncident) {}
        Queue<BfsNode> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>(seedSeverity.keySet());
        List<PropagationZone> zones = new ArrayList<>();

        for (String seedId : seedSeverity.keySet()) {
            queue.add(new BfsNode(
                    seedId,
                    0,
                    seedSeverity.get(seedId),
                    seedDelay.getOrDefault(seedId, 300),
                    seedOriginName.getOrDefault(seedId, getStationName(seedId))
            ));
        }

        while (!queue.isEmpty()) {
            BfsNode current = queue.poll();
            if (current.depth() >= 2) continue;

            for (GraphEdge edge : transitGraph.getOutgoingEdges(current.stationId())) {
                String neighbor = edge.getTargetStationId();
                if (visited.contains(neighbor)) continue;
                visited.add(neighbor);

                int nextDepth = current.depth() + 1;
                int riskPct = (nextDepth == 1)
                        ? (current.severity() == 2 ? 78 : 54)
                        : (current.severity() == 2 ? 42 : 26);

                int rippleSeconds = (int) Math.round(current.baseDelay() * (nextDepth == 1 ? 0.55 : 0.25));
                Station st = stationsById.get(neighbor);

                zones.add(new PropagationZone(
                        neighbor,
                        getStationName(neighbor),
                        st != null ? st.getLatitude() : null,
                        st != null ? st.getLongitude() : null,
                        inferLine(current.stationId(), neighbor),
                        nextDepth,
                        riskPct,
                        rippleSeconds,
                        current.sourceIncident()
                ));

                queue.add(new BfsNode(neighbor, nextDepth, current.severity(), current.baseDelay(), current.sourceIncident()));
            }
        }

        zones.sort(Comparator
                .comparingInt(PropagationZone::riskPct).reversed()
                .thenComparingInt(PropagationZone::hopsAway)
                .thenComparing(PropagationZone::stationName));

        return zones.stream().limit(20).toList();
    }

    // =========================================================================
    // SPRINT 3 — MODE 5 : BOUCLIER ANTI-GALÈRE (PLANS B/C + STRESS-TEST)
    // =========================================================================

    public ShieldAnalysisResult buildShieldAnalysis(String rawStartId, String rawEndId) {
        String startId = resolveStationId(rawStartId);
        String endId = resolveStationId(rawEndId);

        if (startId == null || endId == null || startId.equals(endId)
                || !transitGraph.getAdjacencyList().containsKey(startId)
                || !transitGraph.getAdjacencyList().containsKey(endId)) {
            return null;
        }

        List<String> primaryPath = findShortestPath(startId, endId);
        if (primaryPath.isEmpty()) {
            return null;
        }

        ShieldRouteOption primary = buildShieldRouteOption("Plan A — Itinéraire Principal", primaryPath, 0, primaryPath);

        // Calcul de plans de repli alternatifs par pénalisation d'arêtes (non mutatif)
        List<ShieldRouteOption> alternatives = new ArrayList<>();
        Set<String> penalizedEdges = new HashSet<>(extractEdgeKeys(primaryPath));
        Set<String> seenSignatures = new HashSet<>();
        seenSignatures.add(String.join(">", primaryPath));

        String[] labels = {"Plan B — Contournement prioritaire", "Plan C — Repli secondaire"};
        for (int attempt = 0; attempt < 2; attempt++) {
            List<String> altPath = findConstrainedPath(startId, endId, Collections.emptySet(), penalizedEdges, 3.5, 420);
            if (!altPath.isEmpty()) {
                String sig = String.join(">", altPath);
                if (seenSignatures.add(sig)) {
                    ShieldRouteOption opt = buildShieldRouteOption(
                            labels[alternatives.size()],
                            altPath,
                            primary.totalDurationSeconds(),
                            primaryPath
                    );
                    alternatives.add(opt);
                    penalizedEdges.addAll(extractEdgeKeys(altPath));
                }
            }
        }

        // Stress-test de chaque tronçon de l'itinéraire principal (détection des goulots)
        List<BottleneckRisk> bottlenecks = new ArrayList<>();
        int bypassableCount = 0;
        int singlePointCount = 0;

        for (int i = 0; i < primaryPath.size() - 1; i++) {
            String u = primaryPath.get(i);
            String v = primaryPath.get(i + 1);
            Set<String> blockedPair = Set.of(edgeKey(u, v), edgeKey(v, u));

            List<String> bypassPath = findConstrainedPath(startId, endId, blockedPair, Collections.emptySet(), 1.0, 0);
            GraphEdge originalEdge = findActiveEdge(u, v);
            boolean alreadyDisrupted = originalEdge != null && originalEdge.getDelayAddedSeconds() > 0;

            if (bypassPath.isEmpty()) {
                singlePointCount++;
                bottlenecks.add(new BottleneckRisk(
                        u,
                        getStationName(u),
                        v,
                        getStationName(v),
                        inferLine(u, v),
                        false,
                        0,
                        alreadyDisrupted,
                        "Branche unique sans contournement RER (prévoir bus/tram de substitution)"
                ));
            } else {
                bypassableCount++;
                int bypassDuration = calculateTotalDurationSeconds(bypassPath);
                int detourSec = Math.max(0, bypassDuration - primary.totalDurationSeconds());
                String pivot = findFirstDivergenceName(primaryPath, bypassPath);
                bottlenecks.add(new BottleneckRisk(
                        u,
                        getStationName(u),
                        v,
                        getStationName(v),
                        inferLine(u, v),
                        true,
                        detourSec,
                        alreadyDisrupted,
                        "Contournement RER actif via " + pivot + " (+" + Math.round(detourSec / 60.0) + " min)"
                ));
            }
        }

        // Trier pour remonter en premier les tronçons perturbés puis les goulots sans contournement
        bottlenecks.sort(Comparator
                .comparing(BottleneckRisk::currentlyDisrupted).reversed()
                .thenComparing(BottleneckRisk::hasBypass)
                .thenComparingInt(BottleneckRisk::bypassDetourSeconds).reversed());

        int totalSegments = Math.max(1, primaryPath.size() - 1);
        double bypassRatio = (bypassableCount * 100.0) / totalSegments;

        int resilienceScore = 100;
        resilienceScore -= Math.min(40, (int) Math.round(primary.totalDelaySeconds() / 20.0));
        resilienceScore -= Math.min(30, (int) Math.round((100.0 - bypassRatio) * 0.3));
        if (alternatives.isEmpty()) resilienceScore -= 15;
        resilienceScore = Math.max(10, Math.min(100, resilienceScore));

        String shieldStatus;
        String shieldHeadline;
        if (primary.totalDelaySeconds() >= 600 || primary.disruptedSegmentsCount() >= 3) {
            shieldStatus = "CRITICAL";
            shieldHeadline = alternatives.isEmpty()
                    ? "Axe principal fortement perturbé — aucun contournement RER direct, anticipez votre départ."
                    : "Bascule recommandée sur " + alternatives.get(0).label() + " pour contourner les perturbations.";
        } else if (primary.totalDelaySeconds() > 0) {
            shieldStatus = "WARNING";
            shieldHeadline = "Ralentissement détecté (+" + Math.round(primary.totalDelaySeconds() / 60.0)
                    + " min) — " + (alternatives.isEmpty() ? "restez vigilant sur cet axe." : "Plan B prêt à prendre le relais.");
        } else {
            shieldStatus = "SAFE";
            shieldHeadline = "Axe nominal sans retard détecté. "
                    + (alternatives.isEmpty() ? "Branche directe surveillée en continu." : alternatives.size() + " itinéraire(s) de repli pré-calculé(s).");
        }

        return new ShieldAnalysisResult(
                startId,
                getStationName(startId),
                endId,
                getStationName(endId),
                shieldStatus,
                shieldHeadline,
                resilienceScore,
                bypassableCount,
                singlePointCount,
                primary,
                alternatives,
                bottlenecks.stream().limit(8).toList()
        );
    }

    private ShieldRouteOption buildShieldRouteOption(
            String label,
            List<String> path,
            int referenceSeconds,
            List<String> primaryPath) {

        int totalDuration = 0;
        int totalDelay = 0;
        int disruptedCount = 0;
        int transfers = 0;
        String prevLine = null;
        Set<String> linesUsed = new LinkedHashSet<>();

        for (int i = 0; i < path.size() - 1; i++) {
            String src = path.get(i);
            String dst = path.get(i + 1);
            GraphEdge edge = findActiveEdge(src, dst);
            int nom = edge != null ? edge.getNominalDurationSeconds() : 120;
            int del = edge != null ? edge.getDelayAddedSeconds() : 0;

            totalDuration += nom + del;
            totalDelay += del;
            if (del > 0) disruptedCount++;

            String line = inferLine(src, dst);
            if (!"CORRESPONDANCE".equals(line)) {
                linesUsed.add(line);
            }
            if ("CORRESPONDANCE".equals(line) || (prevLine != null && !"CORRESPONDANCE".equals(prevLine) && !prevLine.equals(line))) {
                transfers++;
            }
            prevLine = line;
        }

        Set<String> primarySet = new HashSet<>(primaryPath);
        long sharedStations = path.stream().filter(primarySet::contains).count();
        int overlapPct = path.isEmpty() ? 100 : (int) Math.round((sharedStations * 100.0) / path.size());
        int deltaSeconds = referenceSeconds > 0 ? totalDuration - referenceSeconds : 0;

        return new ShieldRouteOption(
                label,
                totalDuration,
                totalDelay,
                deltaSeconds,
                Math.max(0, path.size() - 1),
                transfers,
                disruptedCount,
                overlapPct,
                new ArrayList<>(linesUsed),
                path,
                path.stream().map(this::getStationName).toList()
        );
    }

    private List<String> findConstrainedPath(
            String startId,
            String endId,
            Set<String> excludedEdges,
            Set<String> penalizedEdges,
            double penaltyMultiplier,
            int flatPenaltySeconds) {

        Map<String, Integer> distances = new HashMap<>();
        Map<String, String> previous = new HashMap<>();
        PriorityQueue<StationDistance> queue = new PriorityQueue<>(Comparator.comparingInt(StationDistance::distance));

        for (String id : transitGraph.getAdjacencyList().keySet()) {
            distances.put(id, Integer.MAX_VALUE);
        }
        distances.put(startId, 0);
        queue.add(new StationDistance(startId, 0));

        while (!queue.isEmpty()) {
            StationDistance current = queue.poll();
            String currentId = current.stationId();

            if (currentId.equals(endId)) break;
            if (current.distance() > distances.get(currentId)) continue;

            for (GraphEdge edge : transitGraph.getOutgoingEdges(currentId)) {
                int baseWeight = edge.getCurrentWeightSeconds();
                if (baseWeight == Integer.MAX_VALUE) continue;

                String key = edgeKey(currentId, edge.getTargetStationId());
                if (excludedEdges.contains(key)) continue;

                int weight = baseWeight;
                if (penalizedEdges.contains(key)) {
                    weight = (int) Math.round(baseWeight * penaltyMultiplier) + flatPenaltySeconds;
                }

                int newDist = current.distance() + weight;
                if (newDist < distances.getOrDefault(edge.getTargetStationId(), Integer.MAX_VALUE)) {
                    distances.put(edge.getTargetStationId(), newDist);
                    previous.put(edge.getTargetStationId(), currentId);
                    queue.add(new StationDistance(edge.getTargetStationId(), newDist));
                }
            }
        }

        return reconstructPath(previous, startId, endId);
    }

    private String edgeKey(String u, String v) {
        return u + "->" + v;
    }

    private List<String> extractEdgeKeys(List<String> path) {
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < path.size() - 1; i++) {
            keys.add(edgeKey(path.get(i), path.get(i + 1)));
            keys.add(edgeKey(path.get(i + 1), path.get(i)));
        }
        return keys;
    }

    private String findFirstDivergenceName(List<String> primary, List<String> bypass) {
        Set<String> primarySet = new HashSet<>(primary);
        for (String st : bypass) {
            if (!primarySet.contains(st)) {
                return getStationName(st);
            }
        }
        return bypass.size() > 1 ? getStationName(bypass.get(1)) : getStationName(bypass.get(0));
    }

    // =========================================================================
    // RECORDS SPRINT 3 (RADAR & BOUCLIER)
    // =========================================================================

    public record RadarIncidentEdge(
            Long sectionId,
            String fromId,
            String fromName,
            Double fromLat,
            Double fromLon,
            String toId,
            String toName,
            Double toLat,
            Double toLon,
            String line,
            int delaySeconds,
            boolean blocked,
            String severity
    ) {}

    public record PropagationZone(
            String stationId,
            String stationName,
            Double latitude,
            Double longitude,
            String line,
            int hopsAway,
            int riskPct,
            int estimatedRippleSeconds,
            String sourceIncident
    ) {}

    public record LineVulnerability(
            String line,
            int totalSections,
            int fluidSections,
            int delayedSections,
            int blockedSections,
            double impactedPct,
            int healthScore,
            int vulnerabilityScore,
            String status
    ) {}

    public record CriticalHubStatus(
            String stationId,
            String stationName,
            Double latitude,
            Double longitude,
            int degree,
            int impactedConnections,
            int exposurePct
    ) {}

    public record RadarAnalysisResult(
            int totalSections,
            int blockedSections,
            int delayedSections,
            int globalFluidityPct,
            int avgDelayMinutes,
            int propagationZonesCount,
            List<LineVulnerability> lineScores,
            List<RadarIncidentEdge> incidents,
            List<PropagationZone> propagationZones,
            List<CriticalHubStatus> criticalHubs
    ) {}

    public record ShieldRouteOption(
            String label,
            int totalDurationSeconds,
            int totalDelaySeconds,
            int deltaSeconds,
            int stopsCount,
            int transfersCount,
            int disruptedSegmentsCount,
            int overlapPct,
            List<String> linesUsed,
            List<String> path,
            List<String> pathNames
    ) {}

    public record BottleneckRisk(
            String fromId,
            String fromName,
            String toId,
            String toName,
            String line,
            boolean hasBypass,
            int bypassDetourSeconds,
            boolean currentlyDisrupted,
            String recommendation
    ) {}

    public record ShieldAnalysisResult(
            String originId,
            String originName,
            String destinationId,
            String destinationName,
            String status,
            String headline,
            int resilienceScore,
            int bypassableSegmentsCount,
            int singlePointFailureCount,
            ShieldRouteOption primaryRoute,
            List<ShieldRouteOption> alternativeRoutes,
            List<BottleneckRisk> bottlenecks
    ) {}

    // =========================================================================
    // SPRINT 3 (EXTENSION) — GÉNÉRATEUR DE JUSTIFICATIF DE RETARD (BOUCLIER)
    // =========================================================================

    public DelayCertificate buildDelayCertificate(
            String rawStartId,
            String rawEndId,
            String passengerName,
            String organization) {

        String startId = resolveStationId(rawStartId);
        String endId = resolveStationId(rawEndId);

        if (startId == null || endId == null || startId.equals(endId)
                || !transitGraph.getAdjacencyList().containsKey(startId)
                || !transitGraph.getAdjacencyList().containsKey(endId)) {
            return null;
        }

        List<String> path = findShortestPath(startId, endId);
        boolean routeInterrupted = false;

        // Si une voie est totalement bloquée, on reconstitue l'axe nominal pour documenter la coupure
        if (path.isEmpty()) {
            path = findNominalPathIgnoringBlocks(startId, endId);
            routeInterrupted = true;
            if (path.isEmpty()) {
                return null;
            }
        }

        Map<Long, LiveDisruption> disruptionsBySection = disruptionRepository.findAll().stream()
                .collect(Collectors.toMap(d -> d.getSection().getId(), d -> d, (a, b) -> b));

        int nominalTotalSeconds = 0;
        int totalDelaySeconds = 0;
        int blockedCount = 0;
        Set<String> linesUsed = new LinkedHashSet<>();
        List<CertificateIncidentItem> incidents = new ArrayList<>();

        for (int i = 0; i < path.size() - 1; i++) {
            String src = path.get(i);
            String dst = path.get(i + 1);
            GraphEdge edge = findEdgeIncludingBlocked(src, dst);

            int nom = (edge != null) ? edge.getNominalDurationSeconds() : 120;
            int del = (edge != null) ? edge.getDelayAddedSeconds() : 0;
            boolean blocked = (edge != null) && edge.isBlocked();

            nominalTotalSeconds += nom;
            if (blocked) {
                blockedCount++;
            } else {
                totalDelaySeconds += del;
            }

            String line = inferLine(src, dst);
            if (!"CORRESPONDANCE".equals(line)) {
                linesUsed.add(line);
            }

            if (blocked || del > 0) {
                LiveDisruption d = (edge != null) ? disruptionsBySection.get(edge.getSectionId()) : null;
                String cause = (d != null && d.getCause() != null && !d.getCause().isBlank())
                        ? d.getCause()
                        : (blocked ? "Interruption de trafic signalée sur l'axe" : "Ralentissement détecté sur le tronçon");

                incidents.add(new CertificateIncidentItem(
                        edge != null ? edge.getSectionId() : 0L,
                        getStationName(src),
                        getStationName(dst),
                        line,
                        blocked,
                        del,
                        cause
                ));
            }
        }

        // Recherche d'un Plan B si le trajet est perturbé mais contournable
        String fallbackSummary = null;
        if (!routeInterrupted && totalDelaySeconds > 0) {
            Set<String> penalized = new HashSet<>(extractEdgeKeys(path));
            List<String> altPath = findConstrainedPath(startId, endId, Collections.emptySet(), penalized, 3.5, 420);
            if (!altPath.isEmpty() && !altPath.equals(path)) {
                int altDuration = calculateTotalDurationSeconds(altPath);
                fallbackSummary = "Itinéraire de repli calculé via "
                        + findFirstDivergenceName(path, altPath)
                        + " (" + Math.round(altDuration / 60.0) + " min estimées)";
            }
        }

        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Europe/Paris"));
        String issuedAtDisplay = now.format(DateTimeFormatter.ofPattern("dd/MM/yyyy 'à' HH'h'mm", Locale.FRENCH));
        String issuedAtIso = now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        String dateStamp = now.format(DateTimeFormatter.ofPattern("yyyyMMdd"));

        String cleanPassenger = (passengerName == null || passengerName.isBlank())
                ? "Non renseigné"
                : passengerName.trim();
        String cleanOrg = (organization == null || organization.isBlank())
                ? "Administration / Service RH"
                : organization.trim();

        String status;
        String statusLabel;
        if (routeInterrupted || blockedCount > 0) {
            status = "INTERRUPTED";
            statusLabel = "Interruption de trafic constatée";
        } else if (totalDelaySeconds > 0) {
            status = "DELAYED";
            statusLabel = "Retard constaté sur le parcours";
        } else {
            status = "NOMINAL";
            statusLabel = "Trafic nominal constaté à l'heure du relevé";
        }

        String rawPayload = startId + "|" + endId + "|" + issuedAtIso + "|" + totalDelaySeconds + "|" + blockedCount + "|" + cleanPassenger;
        String referenceCode = "TIDF-" + dateStamp + "-" + shortSha256(rawPayload);

        return new DelayCertificate(
                referenceCode,
                issuedAtDisplay,
                issuedAtIso,
                cleanPassenger,
                cleanOrg,
                startId,
                getStationName(startId),
                endId,
                getStationName(endId),
                status,
                statusLabel,
                nominalTotalSeconds,
                nominalTotalSeconds + totalDelaySeconds,
                totalDelaySeconds,
                blockedCount,
                new ArrayList<>(linesUsed),
                path.stream().map(this::getStationName).toList(),
                incidents,
                fallbackSummary,
                "Flux temps réel API PRIM — Île-de-France Mobilités (relevé Transport IDF)"
        );
    }

    private List<String> findNominalPathIgnoringBlocks(String startId, String endId) {
        Map<String, Integer> distances = new HashMap<>();
        Map<String, String> previous = new HashMap<>();
        PriorityQueue<StationDistance> queue = new PriorityQueue<>(Comparator.comparingInt(StationDistance::distance));

        for (String id : transitGraph.getAdjacencyList().keySet()) {
            distances.put(id, Integer.MAX_VALUE);
        }
        distances.put(startId, 0);
        queue.add(new StationDistance(startId, 0));

        while (!queue.isEmpty()) {
            StationDistance current = queue.poll();
            String currentId = current.stationId();
            if (currentId.equals(endId)) break;
            if (current.distance() > distances.get(currentId)) continue;

            for (GraphEdge edge : transitGraph.getOutgoingEdges(currentId)) {
                int newDist = current.distance() + edge.getNominalDurationSeconds();
                if (newDist < distances.getOrDefault(edge.getTargetStationId(), Integer.MAX_VALUE)) {
                    distances.put(edge.getTargetStationId(), newDist);
                    previous.put(edge.getTargetStationId(), currentId);
                    queue.add(new StationDistance(edge.getTargetStationId(), newDist));
                }
            }
        }
        return reconstructPath(previous, startId, endId);
    }

    private GraphEdge findEdgeIncludingBlocked(String src, String dst) {
        GraphEdge fallback = null;
        for (GraphEdge edge : transitGraph.getOutgoingEdges(src)) {
            if (edge.getTargetStationId().equals(dst)) {
                if (!edge.isBlocked()) return edge;
                fallback = edge;
            }
        }
        return fallback;
    }

    private String shortSha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                sb.append(String.format("%02X", hash[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return Long.toHexString(System.currentTimeMillis()).toUpperCase(Locale.ROOT);
        }
    }

    public record CertificateIncidentItem(
            Long sectionId,
            String fromName,
            String toName,
            String line,
            boolean blocked,
            int delaySeconds,
            String cause
    ) {}

    public record DelayCertificate(
            String referenceCode,
            String issuedAtDisplay,
            String issuedAtIso,
            String passengerName,
            String organization,
            String originId,
            String originName,
            String destinationId,
            String destinationName,
            String status,
            String statusLabel,
            int nominalDurationSeconds,
            int realDurationSeconds,
            int totalDelaySeconds,
            int blockedSectionsCount,
            List<String> linesUsed,
            List<String> pathNames,
            List<CertificateIncidentItem> incidents,
            String fallbackSummary,
            String dataSource
    ) {}
}