let radarData = null;
let selectedLine = 'ALL';
let activeTab = 'INCIDENTS';
let mapInstance = null;
let radarLayer = null;

function initRadarMap() {
    if (mapInstance || typeof L === 'undefined') return;
    mapInstance = L.map('radar-map', {
        center: [48.8566, 2.3522],
        zoom: 11,
        scrollWheelZoom: false
    });
    L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
        attribution: '&copy; OpenStreetMap',
        maxZoom: 19
    }).addTo(mapInstance);
    radarLayer = L.layerGroup().addTo(mapInstance);
}

function renderLineCards(lineScores) {
    const grid = document.getElementById('rer-health-grid');
    if (!grid) return;

    grid.innerHTML = lineScores.map(ls => {
        const cls = ls.status === 'CRITICAL' ? 'is-critical' : (ls.status === 'WARNING' ? 'is-warning' : '');
        const barColor = ls.status === 'CRITICAL' ? '#141413' : (ls.status === 'WARNING' ? '#c4552d' : '#00a859');
        const statusText = ls.status === 'CRITICAL' ? 'Critique' : (ls.status === 'WARNING' ? 'Dégradé' : 'Fluide');
        const activeCls = selectedLine === ls.line ? 'active' : '';

        return `
            <button type="button" class="rer-health-card ${cls} ${activeCls}" data-line="${ls.line}">
                <div class="rer-card-top">
                    <span class="rer-card-name">${ls.line}</span>
                    <span class="rer-status-dot ${ls.status}">${statusText}</span>
                </div>
                <strong style="font-family:var(--font-display);font-size:1.35rem;">${ls.healthScore} %</strong>
                <div class="health-bar-track">
                    <div class="health-bar-fill" style="width:${ls.healthScore}%;background:${barColor};"></div>
                </div>
                <div class="rer-card-meta">
                    ${ls.delayedSections} ralenti(s) • ${ls.blockedSections} bloqué(s) / ${ls.totalSections}
                </div>
            </button>
        `;
    }).join('');

    grid.querySelectorAll('.rer-health-card').forEach(card => {
        card.addEventListener('click', () => {
            selectedLine = selectedLine === card.dataset.line ? 'ALL' : card.dataset.line;
            syncFilterPills();
            renderFilteredView();
        });
    });
}

function syncFilterPills() {
    document.querySelectorAll('#radar-filter-bar .s2-pill').forEach(btn => {
        btn.classList.toggle('active', btn.dataset.line === selectedLine);
    });
    document.querySelectorAll('#rer-health-grid .rer-health-card').forEach(card => {
        card.classList.toggle('active', card.dataset.line === selectedLine);
    });
}

function renderFilteredView() {
    if (!radarData) return;

    const incidents = selectedLine === 'ALL'
        ? radarData.incidents
        : radarData.incidents.filter(i => i.line === selectedLine);

    const ripples = selectedLine === 'ALL'
        ? radarData.propagationZones
        : radarData.propagationZones.filter(z => z.line === selectedLine);

    // 1. Carte thermique Leaflet
    initRadarMap();
    radarLayer.clearLayers();
    const boundsPoints = [];

    // Halos de propagation (cercles ambrés translucides)
    ripples.forEach(z => {
        if (typeof z.latitude !== 'number' || typeof z.longitude !== 'number') return;
        L.circle([z.latitude, z.longitude], {
            radius: z.hopsAway === 1 ? 950 : 650,
            color: '#d97706',
            weight: 1,
            fillColor: '#d97706',
            fillOpacity: z.hopsAway === 1 ? 0.28 : 0.16
        }).bindPopup(`
            <strong>Zone de propagation : ${z.stationName}</strong><br>
            Risque domino : ${z.riskPct} % (${z.hopsAway} arrêt(s))<br>
            Impact estimé : +${Math.max(1, Math.round(z.estimatedRippleSeconds / 60))} min<br>
            Origine : ${z.sourceIncident}
        `).addTo(radarLayer);
        boundsPoints.push([z.latitude, z.longitude]);
    });

    // Tronçons perturbés (segments épais orange / noir)
    incidents.forEach(inc => {
        if (typeof inc.fromLat !== 'number' || typeof inc.toLat !== 'number') return;
        const color = inc.blocked ? '#141413' : '#c4552d';
        const coords = [[inc.fromLat, inc.fromLon], [inc.toLat, inc.toLon]];

        L.polyline(coords, {
            color,
            weight: inc.blocked ? 7 : 5,
            opacity: 0.95
        }).bindPopup(`
            <strong>${inc.fromName} ↔ ${inc.toName}</strong><br>
            ${inc.line} • ${inc.blocked ? 'Voie bloquée' : `+${Math.round(inc.delaySeconds / 60)} min de retard`}
        `).addTo(radarLayer);

        L.circleMarker([inc.fromLat, inc.fromLon], {
            radius: 5, fillColor: color, color: '#ffffff', weight: 1.5, fillOpacity: 1
        }).addTo(radarLayer);

        L.circleMarker([inc.toLat, inc.toLon], {
            radius: 5, fillColor: color, color: '#ffffff', weight: 1.5, fillOpacity: 1
        }).addTo(radarLayer);

        boundsPoints.push(...coords);
    });

    // Hubs critiques si aucun incident n'est actif
    if (boundsPoints.length === 0) {
        radarData.criticalHubs.forEach(h => {
            if (typeof h.latitude !== 'number') return;
            L.circleMarker([h.latitude, h.longitude], {
                radius: 7, fillColor: '#00a859', color: '#ffffff', weight: 2, fillOpacity: 0.95
            }).bindPopup(`<strong>${h.stationName}</strong><br>Trafic nominal (${h.degree} connexions)`).addTo(radarLayer);
        });
        mapInstance.setView([48.8566, 2.3522], 11);
    } else if (boundsPoints.length >= 2) {
        mapInstance.fitBounds(L.latLngBounds(boundsPoints), { padding: [40, 40], maxZoom: 13 });
    }

    // 2. Liste latérale (Foyers ou Effet Domino)
    const listBox = document.getElementById('radar-items-list');
    const subtitle = document.getElementById('radar-list-subtitle');

    if (activeTab === 'INCIDENTS') {
        subtitle.textContent = `${incidents.length} tronçon(s) directement impacté(s)`;
        if (incidents.length === 0) {
            listBox.innerHTML = '<div class="s2-empty-prompt">Aucun incident actif sur ce périmètre. Le réseau circule de manière fluide.</div>';
        } else {
            listBox.innerHTML = incidents.map(inc => `
                <div class="radar-item ${inc.blocked ? 'is-critical' : ''}">
                    <div class="radar-item-head">
                        <strong>${inc.fromName} ↔ ${inc.toName}</strong>
                        <span class="rer-mini-badge">${inc.line}</span>
                    </div>
                    <div class="radar-item-sub">
                        ${inc.blocked ? '<strong>Voie bloquée</strong> — interruption active' : `<strong>+${Math.round(inc.delaySeconds / 60)} min</strong> de retard détecté`}
                    </div>
                </div>
            `).join('');
        }
    } else {
        subtitle.textContent = `${ripples.length} gare(s) exposée(s) par contagion (1 à 2 arrêts)`;
        if (ripples.length === 0) {
            listBox.innerHTML = '<div class="s2-empty-prompt">Aucune propagation détectée sur les gares adjacentes.</div>';
        } else {
            listBox.innerHTML = ripples.map(z => `
                <div class="radar-item is-ripple">
                    <div class="radar-item-head">
                        <strong>${z.stationName}</strong>
                        <span class="detour-pill orange">Risque ${z.riskPct} %</span>
                    </div>
                    <div class="radar-item-sub">
                        ${z.line} • À ${z.hopsAway} arrêt(s) de <em>${z.sourceIncident}</em><br>
                        Répercussion estimée : <strong>+${Math.max(1, Math.round(z.estimatedRippleSeconds / 60))} min</strong>
                    </div>
                </div>
            `).join('');
        }
    }
}

function renderCriticalHubs(hubs) {
    const container = document.getElementById('radar-hubs-grid');
    if (!container) return;

    container.innerHTML = hubs.map(h => {
        const isExposed = h.impactedConnections > 0;
        return `
            <div class="hub-card">
                <div class="hub-card-top">
                    <strong>${h.stationName}</strong>
                    <span class="detour-pill ${isExposed ? 'orange' : 'green'}">
                        ${isExposed ? `${h.exposurePct} % exposé` : 'Nominal'}
                    </span>
                </div>
                <div class="radar-item-sub">
                    ${h.degree} connexions directes • ${h.impactedConnections} axe(s) perturbé(s)
                </div>
            </div>
        `;
    }).join('');
}

async function refreshRadar() {
    try {
        const response = await fetch('/api/routes/radar');
        if (!response.ok) return;
        radarData = await response.json();

        document.getElementById('radar-kpi-fluidity').textContent = `${radarData.globalFluidityPct} %`;
        document.getElementById('radar-kpi-total').textContent = `${radarData.totalSections} tronçons analysés`;
        document.getElementById('radar-kpi-delayed').textContent = radarData.delayedSections;
        document.getElementById('radar-kpi-avg-delay').textContent = radarData.avgDelayMinutes > 0
            ? `Retard moyen : +${radarData.avgDelayMinutes} min`
            : 'Aucun retard actif';
        document.getElementById('radar-kpi-blocked').textContent = radarData.blockedSections;
        document.getElementById('radar-kpi-ripple').textContent = radarData.propagationZonesCount;

        renderLineCards(radarData.lineScores);
        renderCriticalHubs(radarData.criticalHubs);
        renderFilteredView();
    } catch (err) {
        console.error('Erreur chargement radar :', err);
    }
}

function bindEvents() {
    document.querySelectorAll('#radar-filter-bar .s2-pill').forEach(btn => {
        btn.addEventListener('click', () => {
            selectedLine = btn.dataset.line;
            syncFilterPills();
            renderFilteredView();
        });
    });

    document.querySelectorAll('#radar-tab-bar .s2-pill').forEach(btn => {
        btn.addEventListener('click', () => {
            document.querySelectorAll('#radar-tab-bar .s2-pill').forEach(b => b.classList.remove('active'));
            btn.classList.add('active');
            activeTab = btn.dataset.tab;
            renderFilteredView();
        });
    });
}

document.addEventListener('DOMContentLoaded', async () => {
    bindEvents();
    await refreshRadar();
    setInterval(refreshRadar, 10000);
});