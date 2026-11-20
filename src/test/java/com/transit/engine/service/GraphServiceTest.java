package com.transit.engine.service;

import com.transit.engine.domain.graph.RoutingProfile;
import com.transit.engine.domain.model.LiveDisruption;
import com.transit.engine.domain.model.NetworkSection;
import com.transit.engine.domain.model.Station;
import com.transit.engine.repository.LiveDisruptionRepository;
import com.transit.engine.repository.NetworkSectionRepository;
import com.transit.engine.repository.StationRepository;
import com.transit.engine.service.GraphService.DelayCertificate;
import com.transit.engine.service.GraphService.DetailedRouteResult;
import com.transit.engine.service.GraphService.MeetingSearchResult;
import com.transit.engine.service.GraphService.RadarAnalysisResult;
import com.transit.engine.service.GraphService.ShieldAnalysisResult;
import com.transit.engine.service.GraphService.StopoverSearchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GraphServiceTest {

    @Mock
    private StationRepository stationRepository;

    @Mock
    private NetworkSectionRepository sectionRepository;

    @Mock
    private LiveDisruptionRepository disruptionRepository;

    @InjectMocks
    private GraphService graphService;

    private List<Station> baseStations;
    private List<NetworkSection> baseSections;
    private NetworkSection sectionGoussainvilleGdn;

    private Station mockStation(String id, String name, double lat, double lon) {
        Station s = mock(Station.class);
        lenient().when(s.getId()).thenReturn(id);
        lenient().when(s.getName()).thenReturn(name);
        lenient().when(s.getLatitude()).thenReturn(lat);
        lenient().when(s.getLongitude()).thenReturn(lon);
        return s;
    }

    private NetworkSection mockSection(Long id, Station src, Station dst, int nominalSeconds) {
        NetworkSection sec = mock(NetworkSection.class);
        lenient().when(sec.getId()).thenReturn(id);
        lenient().when(sec.getSourceStation()).thenReturn(src);
        lenient().when(sec.getTargetStation()).thenReturn(dst);
        lenient().when(sec.getNominalDurationSeconds()).thenReturn(nominalSeconds);
        return sec;
    }

    @BeforeEach
    void setUp() {
        /*
         * Mini-réseau RER représentatif (les arêtes retour sont ajoutées automatiquement
         * par ensureBidirectionalSymmetry() lors de buildGraphFromDatabase()) :
         *
         *  GOUSSAINVILLE --(600s, #1)--> GARE_DU_NORD
         *  GARE_DU_NORD --(180s, #2)--> CHATELET_LES_HALLES --(150s, #3)--> AUBER --(300s, #4)--> LA_DEFENSE
         *  GARE_DU_NORD --(120s, #5)--> MAGENTA --(180s, #6)--> HAUSSMANN_SAINT_LAZARE --(100s, #7)--> AUBER
         *                                                       HAUSSMANN_SAINT_LAZARE --(150s, #8)--> LA_DEFENSE
         */
        Station goussainville = mockStation("GOUSSAINVILLE", "Goussainville", 49.0235, 2.4622);
        Station gdn = mockStation("GARE_DU_NORD", "Gare du Nord", 48.8809, 2.3553);
        Station chatelet = mockStation("CHATELET_LES_HALLES", "Châtelet-Les Halles", 48.8618, 2.3470);
        Station auber = mockStation("AUBER", "Auber", 48.8721, 2.3299);
        Station laDefense = mockStation("LA_DEFENSE", "La Défense", 48.8918, 2.2380);
        Station magenta = mockStation("MAGENTA", "Magenta", 48.8807, 2.3587);
        Station haussmann = mockStation("HAUSSMANN_SAINT_LAZARE", "Haussmann-Saint-Lazare", 48.8750, 2.3267);

        sectionGoussainvilleGdn = mockSection(1L, goussainville, gdn, 600);
        NetworkSection s2 = mockSection(2L, gdn, chatelet, 180);
        NetworkSection s3 = mockSection(3L, chatelet, auber, 150);
        NetworkSection s4 = mockSection(4L, auber, laDefense, 300);
        NetworkSection s5 = mockSection(5L, gdn, magenta, 120);
        NetworkSection s6 = mockSection(6L, magenta, haussmann, 180);
        NetworkSection s7 = mockSection(7L, haussmann, auber, 100);
        NetworkSection s8 = mockSection(8L, haussmann, laDefense, 150);

        baseStations = List.of(goussainville, gdn, chatelet, auber, laDefense, magenta, haussmann);
        baseSections = List.of(sectionGoussainvilleGdn, s2, s3, s4, s5, s6, s7, s8);

        when(stationRepository.findAll()).thenReturn(baseStations);
        when(sectionRepository.findAll()).thenReturn(baseSections);
        when(disruptionRepository.findAll()).thenReturn(Collections.emptyList());

        graphService.buildGraphFromDatabase();
    }

    // =========================================================================
    // SOCLE — DIJKSTRA & INTÉGRITÉ TOPOLOGIQUE
    // =========================================================================

    @Test
    @DisplayName("Socle : Doit choisir le chemin nominal le plus rapide")
    void shouldFindNominalShortestPath() {
        List<String> path = graphService.findShortestPath("GARE_DU_NORD", "AUBER");

        assertEquals(List.of("GARE_DU_NORD", "CHATELET_LES_HALLES", "AUBER"), path);
        assertEquals(330, graphService.calculateTotalDurationSeconds(path));
    }

    @Test
    @DisplayName("Socle : Doit contourner un tronçon fortement retardé")
    void shouldRerouteWhenDelayExceedsAlternative() {
        // Direct via Châtelet passe de 330s à 630s (> 400s via Magenta/Haussmann)
        graphService.updateEdgeWeight(2L, 300, false);

        List<String> path = graphService.findShortestPath("GARE_DU_NORD", "AUBER");

        assertEquals(List.of("GARE_DU_NORD", "MAGENTA", "HAUSSMANN_SAINT_LAZARE", "AUBER"), path);
        assertEquals(400, graphService.calculateTotalDurationSeconds(path));
    }

    @Test
    @DisplayName("Socle : Doit contourner un tronçon totalement bloqué")
    void shouldRerouteWhenSectionIsBlocked() {
        graphService.updateEdgeWeight(2L, 0, true);

        List<String> path = graphService.findShortestPath("GARE_DU_NORD", "AUBER");

        assertEquals(List.of("GARE_DU_NORD", "MAGENTA", "HAUSSMANN_SAINT_LAZARE", "AUBER"), path);
        assertEquals(400, graphService.calculateTotalDurationSeconds(path));
    }

    @Test
    @DisplayName("Socle : Doit retourner une liste vide si tous les accès sont bloqués")
    void shouldReturnEmptyListWhenAllPathsAreBlocked() {
        graphService.updateEdgeWeight(2L, 0, true);
        graphService.updateEdgeWeight(5L, 0, true);

        List<String> path = graphService.findShortestPath("GARE_DU_NORD", "AUBER");

        assertTrue(path.isEmpty());
        assertEquals(0, graphService.calculateTotalDurationSeconds(path));
    }

    @Test
    @DisplayName("Socle : Doit raccorder automatiquement une gare isolée par distance GPS (Haversine)")
    void shouldRepairDisconnectedStationViaGpsBridge() {
        Station isolated = mockStation("LOUVRES", "Louvres", 49.0480, 2.5010);
        List<Station> stationsWithIsolated = new ArrayList<>(baseStations);
        stationsWithIsolated.add(isolated);

        when(stationRepository.findAll()).thenReturn(stationsWithIsolated);
        when(sectionRepository.findAll()).thenReturn(baseSections);
        graphService.buildGraphFromDatabase();

        List<String> path = graphService.findShortestPath("LOUVRES", "AUBER");

        assertFalse(path.isEmpty());
        assertEquals("LOUVRES", path.get(0));
        assertEquals("GOUSSAINVILLE", path.get(1)); // Gare la plus proche géographiquement
        assertEquals("AUBER", path.get(path.size() - 1));
    }

    // =========================================================================
    // SPRINT 1 — MODE 1 : TRAJET SUR-MESURE (MULTI-PROFILS & EMBARQUEMENT)
    // =========================================================================

    @Test
    @DisplayName("Sprint 1 : Le profil NAP_FOCUS doit privilégier le direct sans correspondance")
    void shouldPreferDirectRouteForNapFocusProfile() {
        // AUBER -> LA_DEFENSE : via Haussmann = 250s (avec correspondance), direct RER A = 300s (0 correspondance)
        DetailedRouteResult fastest = graphService.buildDetailedRoute("AUBER", "LA_DEFENSE", RoutingProfile.FASTEST);
        DetailedRouteResult napFocus = graphService.buildDetailedRoute("AUBER", "LA_DEFENSE", RoutingProfile.NAP_FOCUS);

        assertNotNull(fastest);
        assertNotNull(napFocus);

        assertEquals(List.of("AUBER", "HAUSSMANN_SAINT_LAZARE", "LA_DEFENSE"), fastest.path());
        assertEquals(250, fastest.totalDurationSeconds());

        assertEquals(List.of("AUBER", "LA_DEFENSE"), napFocus.path());
        assertEquals(300, napFocus.totalDurationSeconds());
        assertEquals(0, napFocus.transfersCount());
        assertNotNull(napFocus.boardingAdvice());
        assertTrue(napFocus.boardingAdvice().carPosition().contains("Milieu de rame"));
    }

    // =========================================================================
    // SPRINT 2 — MODES 2 & 3 : ESCALE CALIBRÉE & POINT DE RENCONTRE ÉQUITABLE
    // =========================================================================

    @Test
    @DisplayName("Sprint 2 (Escale) : Doit respecter le budget de détour et le filtre de catégorie")
    void shouldFilterStopoversByDetourBudgetAndCategory() {
        // Direct GDN -> LA_DEFENSE = 450s (GDN -> MAGENTA -> HAUSSMANN -> LA_DEFENSE)
        // Escale CHATELET = 180 + 400 = 580s (détour +130s <= 5 min), GOUSSAINVILLE = +1200s (hors budget)
        StopoverSearchResult result = graphService.findStopoverCandidates("GARE_DU_NORD", "LA_DEFENSE", 5, "COMMERCE");

        assertNotNull(result);
        assertEquals(450, result.directDurationSeconds());
        assertFalse(result.candidates().isEmpty());

        List<String> candidateIds = result.candidates().stream()
                .map(GraphService.StopoverCandidate::stationId)
                .toList();

        assertTrue(candidateIds.contains("HAUSSMANN_SAINT_LAZARE"));
        assertTrue(candidateIds.contains("CHATELET_LES_HALLES"));
        assertFalse(candidateIds.contains("GOUSSAINVILLE")); // Hors budget détour
        assertFalse(candidateIds.contains("MAGENTA"));       // Hors catégorie COMMERCE
        assertTrue(result.candidates().stream().allMatch(c -> c.detourSeconds() <= 300));
    }

    @Test
    @DisplayName("Sprint 2 (Rencontre) : Doit trouver le point de rendez-vous le plus équitable (Min-Max)")
    void shouldFindFairMeetingPointMinMax() {
        // GOUSSAINVILLE -> GDN = 600s, LA_DEFENSE -> GDN = 450s (max 600s, écart 150s)
        MeetingSearchResult result = graphService.findFairMeetingPoints(
                List.of("GOUSSAINVILLE", "LA_DEFENSE"),
                "MIN_MAX"
        );

        assertNotNull(result);
        assertEquals(2, result.participantsCount());
        assertFalse(result.candidates().isEmpty());

        GraphService.MeetingCandidate best = result.candidates().get(0);
        assertEquals("GARE_DU_NORD", best.stationId());
        assertEquals(600, best.maxDurationSeconds());
        assertEquals(450, best.minDurationSeconds());
        assertEquals(150, best.spreadSeconds());
        assertEquals(2, best.participantLegs().size());
    }

    // =========================================================================
    // SPRINT 3 — MODES 4 & 5 : RADAR, BOUCLIER & JUSTIFICATIF DE RETARD
    // =========================================================================

    @Test
    @DisplayName("Sprint 3 (Radar) : Doit détecter les foyers perturbés et modéliser la propagation BFS")
    void shouldComputeRadarIncidentsAndPropagationZones() {
        graphService.updateEdgeWeight(2L, 600, false);

        RadarAnalysisResult radar = graphService.buildRadarAnalysis();

        assertNotNull(radar);
        assertEquals(5, radar.lineScores().size());
        assertTrue(radar.delayedSections() >= 1);
        assertEquals(0, radar.blockedSections());
        assertTrue(radar.globalFluidityPct() < 100);
        assertFalse(radar.incidents().isEmpty());
        assertFalse(radar.propagationZones().isEmpty());
        assertTrue(radar.propagationZones().stream().anyMatch(z -> z.hopsAway() == 1 && z.riskPct() == 54));
    }

    @Test
    @DisplayName("Sprint 3 (Bouclier) : Doit proposer un Plan B et identifier les goulots sans contournement")
    void shouldBuildShieldAlternativesAndDetectSinglePointOfFailure() {
        ShieldAnalysisResult shield = graphService.buildShieldAnalysis("GOUSSAINVILLE", "LA_DEFENSE");

        assertNotNull(shield);
        assertEquals("SAFE", shield.status());
        assertFalse(shield.alternativeRoutes().isEmpty());
        assertTrue(shield.bypassableSegmentsCount() >= 1);
        assertTrue(shield.singlePointFailureCount() >= 1);

        // Le maillon Goussainville -> Gare du Nord est une branche unique sans contournement RER
        boolean goussainvilleIsSpof = shield.bottlenecks().stream()
                .anyMatch(b -> "GOUSSAINVILLE".equals(b.fromId()) && !b.hasBypass());
        assertTrue(goussainvilleIsSpof);
    }

    @Test
    @DisplayName("Sprint 3 (Justificatif) : Doit générer un relevé SHA-256 en cas de retard et de coupure totale")
    void shouldGenerateDelayCertificateForDelayedAndInterruptedRoutes() {
        LiveDisruption disruption = mock(LiveDisruption.class);
        lenient().when(disruption.getSection()).thenReturn(sectionGoussainvilleGdn);
        lenient().when(disruption.getDelayAddedSeconds()).thenReturn(480);
        lenient().when(disruption.isBlocked()).thenReturn(false);
        lenient().when(disruption.getCause()).thenReturn("RER D : Incident de signalisation");
        when(disruptionRepository.findAll()).thenReturn(List.of(disruption));

        // Cas 1 : Retard de +8 min
        graphService.updateEdgeWeight(1L, 480, false);
        DelayCertificate delayedCert = graphService.buildDelayCertificate(
                "GOUSSAINVILLE", "AUBER", "Edan Pierre-Louis", "IUT Paris"
        );

        assertNotNull(delayedCert);
        assertTrue(delayedCert.referenceCode().startsWith("TIDF-"));
        assertEquals("DELAYED", delayedCert.status());
        assertEquals(480, delayedCert.totalDelaySeconds());
        assertEquals("Edan Pierre-Louis", delayedCert.passengerName());
        assertEquals("IUT Paris", delayedCert.organization());
        assertEquals(1, delayedCert.incidents().size());
        assertEquals("RER D : Incident de signalisation", delayedCert.incidents().get(0).cause());

        // Cas 2 : Coupure totale de la branche (le justificatif doit quand même se générer)
        graphService.updateEdgeWeight(1L, 0, true);
        DelayCertificate interruptedCert = graphService.buildDelayCertificate(
                "GOUSSAINVILLE", "AUBER", "Edan Pierre-Louis", "IUT Paris"
        );

        assertNotNull(interruptedCert);
        assertEquals("INTERRUPTED", interruptedCert.status());
        assertEquals(1, interruptedCert.blockedSectionsCount());
        assertTrue(interruptedCert.incidents().get(0).blocked());
    }
}