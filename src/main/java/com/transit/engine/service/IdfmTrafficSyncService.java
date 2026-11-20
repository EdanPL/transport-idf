package com.transit.engine.service;

import com.transit.engine.domain.model.NetworkSection;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.HttpClientErrorException;

import java.text.Normalizer;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class IdfmTrafficSyncService {

    private final DisruptionService disruptionService;

    @Value("${transit.traffic.mode:REALTIME}")
    private String configuredMode;

    @Value("${transit.traffic.idfm.api-key:}")
    private String apiKey;

    @Value("${transit.traffic.idfm.base-url:https://prim.iledefrance-mobilites.fr/marketplace/v2/navitia}")
    private String baseUrl;

    @Getter
    private String activeMode;

    @Getter
    private List<String> officialBulletins = new ArrayList<>();

    private RestClient restClient;

    private static final Map<String, String> RER_LINE_IDS = Map.of(
            "RER A", "line:IDFM:C01742",
            "RER B", "line:IDFM:C01743",
            "RER C", "line:IDFM:C01727",
            "RER D", "line:IDFM:C01728",
            "RER E", "line:IDFM:C01729"
    );

    @PostConstruct
    public void init() {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
        if ("REALTIME".equalsIgnoreCase(configuredMode) && (apiKey == null || apiKey.isBlank())) {
            log.warn("Aucune clé IDFM_API_KEY détectée -> bascule automatique en mode SIMULATION.");
            this.activeMode = "SIMULATION";
        } else {
            this.activeMode = configuredMode.toUpperCase();
        }
        log.info("Moteur trafic initialisé en mode : {}", this.activeMode);
    }

    public String switchMode(String newMode) {
        if ("REALTIME".equalsIgnoreCase(newMode)) {
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalStateException("Impossible d'activer REALTIME : variable IDFM_API_KEY manquante.");
            }
            this.activeMode = "REALTIME";
            disruptionService.resetAllDisruptions();
            syncRealTimeTraffic();
        } else {
            this.activeMode = "SIMULATION";
            this.officialBulletins = List.of();
            disruptionService.resetAllDisruptions();
        }
        log.info("Changement de mode trafic effectué -> {}", this.activeMode);
        return this.activeMode;
    }

    @Scheduled(fixedDelay = 180_000, initialDelay = 3000)
    @SuppressWarnings("unchecked")
    public void syncRealTimeTraffic() {
        if (!"REALTIME".equals(this.activeMode)) {
            return;
        }

        List<NetworkSection> allSections = disruptionService.getAllSections();
        List<String> freshBulletins = new ArrayList<>();
        Set<Long> impactedSectionIds = new HashSet<>();

        for (Map.Entry<String, String> lineEntry : RER_LINE_IDS.entrySet()) {
            String lineName = lineEntry.getKey();
            String lineId = lineEntry.getValue();

            try {
                Map<String, Object> root = restClient.get()
                        .uri("/lines/{lineId}/line_reports?disable_geojson=true", lineId)
                        .header("apikey", apiKey)
                        .accept(MediaType.APPLICATION_JSON)
                        .retrieve()
                        .body(new ParameterizedTypeReference<>() {});

                if (root == null) continue;
                Object disruptionsObj = root.get("disruptions");

                if (disruptionsObj instanceof List<?> disruptions) {
                    for (Object item : disruptions) {
                        if (!(item instanceof Map<?, ?> d)) continue;

                        String status = Objects.toString(d.get("status"), "");
                        if (!"active".equalsIgnoreCase(status)) continue;

                        String severityEffect = "";
                        String severityName = "";
                        if (d.get("severity") instanceof Map<?, ?> sev) {
                            severityEffect = Objects.toString(sev.get("effect"), "");
                            severityName = Objects.toString(sev.get("name"), "");
                        }

                        // Ne retenir que les perturbations impactant réellement le trafic
                        if (!"SIGNIFICANT_DELAYS".equalsIgnoreCase(severityEffect)
                                && !"NO_SERVICE".equalsIgnoreCase(severityEffect)
                                && !"REDUCED_SERVICE".equalsIgnoreCase(severityEffect)) {
                            continue;
                        }

                        boolean isBlocked = "NO_SERVICE".equalsIgnoreCase(severityEffect);
                        int delaySeconds = isBlocked ? 0 : 600;

                        String rawMessage = extractBestMessage(d);
                        String cleanText = rawMessage.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").trim();
                        String lowerMsg = cleanText.toLowerCase();

                        // Exclure les pannes d'ascenseurs/escalators et travaux d'accès (indépendant de normalize())
                        if (lowerMsg.contains("ascenseur")
                                || lowerMsg.contains("escalier")
                                || lowerMsg.contains("escalator")
                                || lowerMsg.contains("équipement")
                                || lowerMsg.contains("equipement")) {
                            continue;
                        }

                        String normalizedMsg = normalize(cleanText);

                        String bulletinEntry = "[" + lineName + " • " + severityName + "] " + cleanText;
                        if (!freshBulletins.contains(bulletinEntry)) {
                            freshBulletins.add(bulletinEntry);
                        }

                        boolean isScheduledWorks = lowerMsg.contains("travaux")
                                || lowerMsg.contains("du ")
                                || lowerMsg.contains("au ")
                                || lowerMsg.contains("à partir de");

                        boolean isImmediateInterruption = "NO_SERVICE".equalsIgnoreCase(severityEffect)
                                && (lowerMsg.contains("interrompu") || lowerMsg.contains("interruption"))
                                && !isScheduledWorks;

                        for (NetworkSection sec : allSections) {
                            String srcName = normalize(sec.getSourceStation().getName()).trim();
                            String dstName = normalize(sec.getTargetStation().getName()).trim();
                            boolean matchSrc = srcName.length() >= 5 && normalizedMsg.contains(srcName);
                            boolean matchDst = dstName.length() >= 5 && normalizedMsg.contains(dstName);

                            if (matchSrc || matchDst) {
                                boolean blockSection = isImmediateInterruption && matchSrc && matchDst;
                                int sectionDelay = blockSection ? 0 : (isImmediateInterruption ? 900 : 480);

                                impactedSectionIds.add(sec.getId());
                                disruptionService.reportDisruption(
                                        sec.getId(),
                                        sectionDelay,
                                        blockSection,
                                        lineName + " : " + cleanText
                                );
                            }
                        }
                    }
                }
                Thread.sleep(350); // <-- AJOUTER ICI (juste avant la fin du try)
            } catch (HttpClientErrorException.TooManyRequests e) {
                log.warn("Quota API PRIM atteint (429) sur {}. Synchronisation mise en pause jusqu'au prochain cycle.", lineName);
                return; // Stoppe immédiatement la méthode pour ne pas spammer les autres lignes
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                // <-- TON CATCH EXISTANT RESTE EN DERNIER
                log.error("Erreur lors de la synchronisation IDFM pour {} : {}", lineName, e.getMessage());
            }
        }

        for (Long activeId : disruptionService.getCurrentlyDisruptedSectionIds()) {
            if (!impactedSectionIds.contains(activeId)) {
                disruptionService.reportDisruption(activeId, 0, false, "Trafic rétabli (IDFM)");
            }
        }

        this.officialBulletins = freshBulletins;
        log.info("Sync IDFM PRIM terminée : {} bulletin(s) actif(s), {} tronçon(s) impacté(s).",
                freshBulletins.size(), impactedSectionIds.size());
    }

    private String extractBestMessage(Map<?, ?> disruption) {
        if (disruption.get("messages") instanceof List<?> messages && !messages.isEmpty()) {
            if (messages.get(0) instanceof Map<?, ?> firstMsg && firstMsg.get("text") != null) {
                return firstMsg.get("text").toString();
            }
        }
        return Objects.toString(disruption.get("cause"), "Perturbation en cours");
    }

    private String normalize(String input) {
        if (input == null) return "";
        return Normalizer.normalize(input, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toUpperCase()
                .replaceAll("[^A-Z0-9 ]", " ");
    }
}