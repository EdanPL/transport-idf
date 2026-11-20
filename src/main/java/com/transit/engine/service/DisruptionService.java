package com.transit.engine.service;

import com.transit.engine.domain.model.LiveDisruption;
import com.transit.engine.domain.model.NetworkSection;
import com.transit.engine.repository.LiveDisruptionRepository;
import com.transit.engine.repository.NetworkSectionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class DisruptionService {

    private final LiveDisruptionRepository disruptionRepository;
    private final NetworkSectionRepository sectionRepository;
    private final GraphService graphService;
    private final StringRedisTemplate redisTemplate;

    private static final String REDIS_KEY_PREFIX = "disruption:section:";

    @Transactional
    public LiveDisruption reportDisruption(Long sectionId, int delaySeconds, boolean blocked, String cause) {
        NetworkSection section = sectionRepository.findById(sectionId)
                .orElseThrow(() -> new IllegalArgumentException("Tronçon introuvable avec l'ID : " + sectionId));

        LiveDisruption disruption = disruptionRepository.findBySectionId(sectionId)
                .orElse(new LiveDisruption());

        disruption.setSection(section);
        disruption.setDelayAddedSeconds(delaySeconds);
        disruption.setBlocked(blocked);
        disruption.setCause(cause);
        disruption.setUpdatedAt(LocalDateTime.now());

        LiveDisruption saved = disruptionRepository.save(disruption);

        // 1. Mise à jour immédiate du graphe en mémoire (Dijkstra)
        graphService.updateEdgeWeight(sectionId, delaySeconds, blocked);

        // 2. Mise en cache dans Redis (expiration automatique après 30 minutes)
        String redisKey = REDIS_KEY_PREFIX + sectionId;
        if (blocked || delaySeconds > 0) {
            String redisValue = String.format("cause=%s | delay=%ds | blocked=%b", cause, delaySeconds, blocked);
            redisTemplate.opsForValue().set(redisKey, redisValue, Duration.ofSeconds(45));
            log.info("Cache Redis mis à jour [{}] -> {}", redisKey, redisValue);
        } else {
            redisTemplate.delete(redisKey);
            log.info("Incident résolu : clé Redis supprimée [{}]", redisKey);
        }

        return saved;
    }

    // Ajoute cette méthode utilitaire pour lister les tronçons actuellement actifs dans Redis :
    public List<Long> getCurrentlyDisruptedSectionIds() {
        Set<String> keys = redisTemplate.keys("disruption:section:*");
        if (keys == null || keys.isEmpty()) return List.of();
        return keys.stream()
                .map(k -> Long.parseLong(k.replace("disruption:section:", "")))
                .toList();
    }

    public Set<String> getActiveRedisKeys() {
        Set<String> keys = redisTemplate.keys("disruption:section:*");
        Set<String> activeKeys = keys != null ? keys : Set.of();

        // Nettoyage automatique en RAM des perturbations dont le TTL Redis a expiré
        for (NetworkSection section : sectionRepository.findAll()) {
            String expectedKey = "disruption:section:" + section.getId();
            if (!activeKeys.contains(expectedKey)) {
                graphService.updateEdgeWeight(section.getId(), 0, false);
            }
        }
        return activeKeys;
    }

    @Transactional
    public void resetAllDisruptions() {
        List<NetworkSection> sections = sectionRepository.findAll();
        for (NetworkSection section : sections) {
            reportDisruption(section.getId(), 0, false, "Trafic normal (Réinitialisation)");
        }
        log.info("Réseau entièrement réinitialisé à l'état nominal.");
    }

    public List<NetworkSection> getAllSections() {
        return sectionRepository.findAll();
    }

    public Set<String> getActiveRedisDisruptions() {
        return redisTemplate.keys(REDIS_KEY_PREFIX + "*");
    }
}