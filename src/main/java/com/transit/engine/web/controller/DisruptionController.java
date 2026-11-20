package com.transit.engine.web.controller;

import com.transit.engine.domain.model.LiveDisruption;
import com.transit.engine.service.DisruptionService;
import com.transit.engine.service.IdfmTrafficSyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/disruptions")
@RequiredArgsConstructor
public class DisruptionController {

    private final DisruptionService disruptionService;
    private final IdfmTrafficSyncService idfmTrafficSyncService;
    private final com.transit.engine.repository.StationRepository stationRepository;
    private final com.transit.engine.repository.LiveDisruptionRepository disruptionRepository;

    // Liste tous les tronçons avec leur ID pour savoir lequel perturber
    @GetMapping("/sections")
    public List<Map<String, Object>> listSections() {
        return disruptionService.getAllSections().stream()
                .map(s -> {
                    var disruption = disruptionRepository.findBySectionId(s.getId()).orElse(null);
                    int delay = disruption != null ? disruption.getDelayAddedSeconds() : 0;
                    boolean blocked = disruption != null && disruption.isBlocked();
                    String cause = disruption != null ? disruption.getCause() : "Trafic normal";

                    return Map.<String, Object>of(
                            "id", s.getId(),
                            "from", s.getSourceStation().getId(),
                            "to", s.getTargetStation().getId(),
                            "nominalSeconds", s.getNominalDurationSeconds(),
                            "delaySeconds", delay,
                            "blocked", blocked,
                            "cause", cause
                    );
                })
                .toList();
    }

    // Ajoute cet endpoint de réinitialisation :
    @PostMapping("/reset")
    public ResponseEntity<Map<String, Object>> resetNetwork() {
        disruptionService.resetAllDisruptions();
        return ResponseEntity.ok(Map.of("status", "RESET_OK"));
    }

    // Injecte ou résout une perturbation sur un tronçon
    @PostMapping
    public ResponseEntity<Map<String, Object>> reportDisruption(@RequestBody DisruptionRequest request) {
        LiveDisruption updated = disruptionService.reportDisruption(
                request.sectionId(),
                request.delaySeconds(),
                request.blocked(),
                request.cause()
        );

        return ResponseEntity.ok(Map.of(
                "status", "UPDATED",
                "sectionId", updated.getSection().getId(),
                "route", updated.getSection().getSourceStation().getId() + " -> " + updated.getSection().getTargetStation().getId(),
                "delayAddedSeconds", updated.getDelayAddedSeconds(),
                "blocked", updated.isBlocked(),
                "cause", updated.getCause()
        ));
    }

    @GetMapping("/active-cache")
    public Set<String> getActiveCacheKeys() {
        return disruptionService.getActiveRedisKeys();
    }

    @GetMapping("/stations")
    public List<com.transit.engine.domain.model.Station> listStations() {
        return stationRepository.findAll();
    }

    @GetMapping("/mode")
    public Map<String, Object> getTrafficMode() {
        return Map.of(
                "mode", idfmTrafficSyncService.getActiveMode(),
                "bulletins", idfmTrafficSyncService.getOfficialBulletins()
        );
    }

    @PostMapping("/mode")
    public ResponseEntity<?> switchTrafficMode(@RequestParam String value) {
        try {
            String updatedMode = idfmTrafficSyncService.switchMode(value);
            return ResponseEntity.ok(Map.of(
                    "mode", updatedMode,
                    "bulletins", idfmTrafficSyncService.getOfficialBulletins()
            ));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    public record DisruptionRequest(Long sectionId, int delaySeconds, boolean blocked, String cause) {}
}