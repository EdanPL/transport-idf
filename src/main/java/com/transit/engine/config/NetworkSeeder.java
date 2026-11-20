package com.transit.engine.config;

import com.transit.engine.domain.model.NetworkSection;
import com.transit.engine.domain.model.Station;
import com.transit.engine.repository.NetworkSectionRepository;
import com.transit.engine.repository.StationRepository;
import com.transit.engine.service.GraphService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class NetworkSeeder implements CommandLineRunner {

    private final StationRepository stationRepository;
    private final NetworkSectionRepository sectionRepository;
    private final GraphService graphService;
    private final ObjectMapper objectMapper;

    @Override
    public void run(String... args) throws Exception {
        if (stationRepository.count() == 0) {
            log.info("Importation de la topologie réseau depuis data/network-seed.json...");
            InputStream inputStream = new ClassPathResource("data/network-seed.json").getInputStream();
            NetworkSeedPayload payload = objectMapper.readValue(inputStream, NetworkSeedPayload.class);

            List<Station> savedStations = stationRepository.saveAll(payload.stations());
            Map<String, Station> stationMap = savedStations.stream()
                    .collect(Collectors.toMap(Station::getId, Function.identity()));

            List<NetworkSection> sections = payload.sections().stream()
                    .map(s -> new NetworkSection(
                            null,
                            stationMap.get(s.source()),
                            stationMap.get(s.target()),
                            s.durationSeconds(),
                            s.distanceMeters()
                    ))
                    .toList();

            sectionRepository.saveAll(sections);
        } else {
            log.info("Base PostgreSQL déjà peuplée ({} gares).", stationRepository.count());
        }

        ensureMultimodalInterchanges();
        graphService.buildGraphFromDatabase();
    }

    /**
     * Garantit que tous les pôles de correspondance piétonne d'Île-de-France
     * relient bien les 5 lignes RER entre elles sans doublon.
     */
    private void ensureMultimodalInterchanges() {
        List<SectionSeed> interchanges = List.of(
                // RER E <-> RER B / RER D (Magenta <-> Gare du Nord)
                new SectionSeed("MAGENTA", "GARE_DU_NORD", 240, 300),
                new SectionSeed("GARE_DU_NORD", "MAGENTA", 240, 300),
                // RER E <-> RER A (Haussmann-Saint-Lazare <-> Auber)
                new SectionSeed("HAUSSMANN_SAINT_LAZARE", "AUBER", 300, 400),
                new SectionSeed("AUBER", "HAUSSMANN_SAINT_LAZARE", 300, 400),
                // RER C <-> RER E (Neuilly Porte Maillot <-> Porte Maillot)
                new SectionSeed("NEUILLY_PORTE_MAILLOT", "PORTE_MAILLOT", 180, 200),
                new SectionSeed("PORTE_MAILLOT", "NEUILLY_PORTE_MAILLOT", 180, 200),
                // RER E <-> RER A (Nanterre-La Folie <-> Nanterre-Préfecture)
                new SectionSeed("NANTERRE_LA_FOLIE", "NANTERRE_PREFECTURE", 240, 300),
                new SectionSeed("NANTERRE_PREFECTURE", "NANTERRE_LA_FOLIE", 240, 300),
                // RER A <-> RER C / E (Charles de Gaulle - Étoile <-> Neuilly Porte Maillot)
                new SectionSeed("CHARLES_DE_GAULLE_ETOILE", "NEUILLY_PORTE_MAILLOT", 360, 900),
                new SectionSeed("NEUILLY_PORTE_MAILLOT", "CHARLES_DE_GAULLE_ETOILE", 360, 900)
        );

        Map<String, Station> stationMap = stationRepository.findAll().stream()
                .collect(Collectors.toMap(Station::getId, Function.identity()));
        List<NetworkSection> existingSections = sectionRepository.findAll();

        int added = 0;
        for (SectionSeed link : interchanges) {
            Station src = stationMap.get(link.source());
            Station dst = stationMap.get(link.target());
            if (src == null || dst == null) continue;

            boolean alreadyExists = existingSections.stream().anyMatch(sec ->
                    sec.getSourceStation().getId().equals(link.source())
                            && sec.getTargetStation().getId().equals(link.target()));

            if (!alreadyExists) {
                sectionRepository.save(new NetworkSection(null, src, dst, link.durationSeconds(), link.distanceMeters()));
                added++;
            }
        }

        if (added > 0) {
            log.info("{} correspondance(s) multimodale(s) ajoutée(s) pour unifier le réseau RER.", added);
        }
    }

    // Records internes pour mapper proprement le fichier JSON
    private record NetworkSeedPayload(List<Station> stations, List<SectionSeed> sections) {}
    private record SectionSeed(String source, String target, int durationSeconds, int distanceMeters) {}
}