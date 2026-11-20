package com.transit.engine.service;

import com.transit.engine.domain.model.NetworkSection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Random;

@Slf4j
@Service
@RequiredArgsConstructor
public class TrafficSimulatorService {

    private final DisruptionService disruptionService;
    private final IdfmTrafficSyncService idfmTrafficSyncService;
    private final Random random = new Random();

    private static final int MAX_ACTIVE_DISRUPTIONS = 10;

    private static final List<String> INCIDENT_CAUSES = List.of(
            "Signalisation défaillante",
            "Affluence voyageurs exceptionnelle",
            "Colis suspect en gare",
            "Régulation du trafic en cours",
            "Panne d'aiguillage"
    );

    @Scheduled(fixedDelay = 8000, initialDelay = 5000)
    public void simulateTrafficCycle() {
        // 1. Bridage : ne rien générer si le mode temps réel IDFM est actif
        if (!"SIMULATION".equals(idfmTrafficSyncService.getActiveMode())) {
            return;
        }

        List<Long> activeIds = disruptionService.getCurrentlyDisruptedSectionIds();

        // 2. Régulation : résout un incident existant si le plafond est atteint
        if (activeIds.size() >= MAX_ACTIVE_DISRUPTIONS || (activeIds.size() >= 3 && random.nextBoolean())) {
            Long sectionToHeal = activeIds.get(random.nextInt(activeIds.size()));
            disruptionService.reportDisruption(sectionToHeal, 0, false, "Trafic rétabli");
            log.info("Incident résolu automatiquement sur le tronçon #{}", sectionToHeal);
            return;
        }

        // 3. Génération d'un nouvel incident sur un tronçon sain
        List<NetworkSection> allSections = disruptionService.getAllSections();
        if (allSections.isEmpty()) return;

        NetworkSection target = allSections.get(random.nextInt(allSections.size()));
        if (activeIds.contains(target.getId())) return;

        boolean isBlocked = random.nextInt(10) < 2;
        int delaySeconds = isBlocked ? 0 : (180 + random.nextInt(420));
        String cause = INCIDENT_CAUSES.get(random.nextInt(INCIDENT_CAUSES.size()));

        disruptionService.reportDisruption(target.getId(), delaySeconds, isBlocked, cause);
    }
}