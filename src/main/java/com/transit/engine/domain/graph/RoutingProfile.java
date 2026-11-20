package com.transit.engine.domain.graph;

public enum RoutingProfile {
    FASTEST("Rapide", "Minimise le temps de trajet total en tenant compte des retards temps réel."),
    NAP_FOCUS("Sieste / Révision", "Pénalise les changements de ligne et les correspondances piétonnes pour maximiser le temps continu dans un même train."),
    LOW_BATTERY("Batterie Critique", "Feuille de route hors-ligne ultra-sobre, décompte exact des arrêts et zéro appel réseau continu."),
    SEAT_COMFORT("Place Assise & Rebond", "Évite les tronçons chargés en cache et propose un rebond stratégique vers un terminus voisin pour voyager assis.");

    private final String label;
    private final String description;

    RoutingProfile(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String getLabel() {
        return label;
    }

    public String getDescription() {
        return description;
    }

    public static RoutingProfile fromString(String value) {
        if (value == null || value.isBlank()) {
            return FASTEST;
        }
        try {
            return RoutingProfile.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return FASTEST;
        }
    }
}