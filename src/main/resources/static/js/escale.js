import { fetchStations } from './api.js';

const fromSelect = document.getElementById('escale-from');
const toSelect = document.getElementById('escale-to');
const budgetSelect = document.getElementById('escale-budget');
const emptyPrompt = document.getElementById('escale-empty-prompt');
const resultsSection = document.getElementById('escale-results');
const candidatesList = document.getElementById('escale-candidates-list');
const detailBox = document.getElementById('escale-detail-box');

let stationsById = new Map();
let selectedCategory = 'ALL';
let lastResult = null;
let mapInstance = null;
let mapLayerGroup = null;

function formatDuration(seconds) {
    const mins = Math.floor(seconds / 60);
    const secs = seconds % 60;
    return mins > 0 ? `${mins} min ${secs > 0 ? secs + 's' : ''}` : `${secs}s`;
}

function formatDetour(seconds) {
    if (seconds <= 30) return '0 min (sur la route)';
    return `+${Math.round(seconds / 60)} min`;
}

function getStationName(id) {
    return stationsById.get(id)?.name || id;
}

function ensureMap() {
    if (mapInstance || typeof L === 'undefined') return;
    mapInstance = L.map('escale-map', {
        center: [48.8566, 2.3522],
        zoom: 11,
        scrollWheelZoom: false
    });
    L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
        attribution: '&copy; OpenStreetMap',
        maxZoom: 19
    }).addTo(mapInstance);
    mapLayerGroup = L.layerGroup().addTo(mapInstance);
}

function toLatLngs(pathIds) {
    return (pathIds || [])
        .map(id => stationsById.get(id))
        .filter(s => s && typeof s.latitude === 'number' && typeof s.longitude === 'number')
        .map(s => [s.latitude, s.longitude]);
}

function selectCandidate(candidate, cardEl) {
    document.querySelectorAll('#escale-candidates-list .candidate-card').forEach(c => c.classList.remove('active'));
    cardEl?.classList.add('active');

    document.getElementById('escale-selected-title').textContent = `Escale à ${candidate.stationName}`;
    document.getElementById('escale-selected-sub').textContent =
        `${candidate.categoryLabel} • Total ${formatDuration(candidate.totalSeconds)} (${formatDetour(candidate.detourSeconds)})`;

    ensureMap();
    mapLayerGroup.clearLayers();

    const leg1Coords = toLatLngs(candidate.leg1Path);
    const leg2Coords = toLatLngs(candidate.leg2Path);
    const boundsPoints = [];

    if (leg1Coords.length >= 2) {
        L.polyline(leg1Coords, { color: '#c4552d', weight: 5, opacity: 0.92 }).addTo(mapLayerGroup);
        boundsPoints.push(...leg1Coords);
    }
    if (leg2Coords.length >= 2) {
        L.polyline(leg2Coords, { color: '#00a859', weight: 5, opacity: 0.92 }).addTo(mapLayerGroup);
        boundsPoints.push(...leg2Coords);
    }

    const addPin = (stationId, color, label, radius = 7) => {
        const s = stationsById.get(stationId);
        if (!s || typeof s.latitude !== 'number') return;
        L.circleMarker([s.latitude, s.longitude], {
            radius,
            fillColor: color,
            color: '#ffffff',
            weight: 2,
            fillOpacity: 1
        }).bindPopup(`<strong>${label}</strong><br>${s.name}`).addTo(mapLayerGroup);
    };

    if (lastResult) {
        addPin(lastResult.originId, '#141413', 'Départ', 7);
        addPin(candidate.stationId, '#c4552d', 'Escale choisie', 9);
        addPin(lastResult.destinationId, '#00a859', 'Arrivée', 7);
    }

    if (boundsPoints.length >= 2) {
        mapInstance.fitBounds(L.latLngBounds(boundsPoints), { padding: [36, 36] });
    }
    setTimeout(() => mapInstance.invalidateSize(), 60);

    detailBox.innerHTML = `
        <div class="leg-list">
            <div class="leg-item">
                <div class="leg-item-top">
                    <strong>1. ${lastResult.originName} → ${candidate.stationName}</strong>
                    <span class="detour-pill orange">${formatDuration(candidate.leg1Seconds)} • ${candidate.leg1Stops} arrêts</span>
                </div>
                <div class="leg-path-text">${candidate.leg1Path.map(getStationName).join(' → ')}</div>
            </div>
            <div class="leg-item">
                <div class="leg-item-top">
                    <strong>2. ${candidate.stationName} → ${lastResult.destinationName}</strong>
                    <span class="detour-pill green">${formatDuration(candidate.leg2Seconds)} • ${candidate.leg2Stops} arrêts</span>
                </div>
                <div class="leg-path-text">${candidate.leg2Path.map(getStationName).join(' → ')}</div>
            </div>
        </div>
    `;
}

async function searchStopovers() {
    if (!fromSelect.value || !toSelect.value) {
        emptyPrompt.textContent = 'Veuillez sélectionner une gare de départ et une gare d\'arrivée.';
        emptyPrompt.classList.remove('hidden');
        resultsSection.classList.add('hidden');
        return;
    }

    if (fromSelect.value === toSelect.value) {
        emptyPrompt.textContent = 'La gare de départ et la gare d\'arrivée doivent être différentes.';
        emptyPrompt.classList.remove('hidden');
        resultsSection.classList.add('hidden');
        return;
    }

    const params = new URLSearchParams({
        from: fromSelect.value,
        to: toSelect.value,
        maxDetourMinutes: budgetSelect.value,
        category: selectedCategory
    });

    const response = await fetch(`/api/routes/stopover?${params.toString()}`);
    if (!response.ok) {
        emptyPrompt.textContent = 'Aucun itinéraire direct praticable entre ces deux gares actuellement.';
        emptyPrompt.classList.remove('hidden');
        resultsSection.classList.add('hidden');
        return;
    }

    const data = await response.json();
    lastResult = data;

    emptyPrompt.classList.add('hidden');
    resultsSection.classList.remove('hidden');

    document.getElementById('escale-kpi-direct').textContent = formatDuration(data.directDurationSeconds);
    document.getElementById('escale-kpi-direct-sub').textContent = `${data.directStops} arrêts sans escale`;

    document.getElementById('escale-kpi-count').textContent = data.totalMatchingCount;
    document.getElementById('escale-kpi-budget-sub').textContent = `Top ${data.candidates.length} affiché(s) (budget +${data.maxDetourMinutes} min)`;

    if (data.candidates.length === 0) {
        document.getElementById('escale-kpi-best').textContent = 'Aucune';
        document.getElementById('escale-kpi-best-sub').textContent = 'Augmentez le budget ou changez de catégorie';
        candidatesList.innerHTML = '<p class="muted-text">Aucune gare ne correspond à ce filtre dans le budget de détour choisi.</p>';
        detailBox.innerHTML = '';
        ensureMap();
        mapLayerGroup.clearLayers();
        return;
    }

    const best = data.candidates[0];
    document.getElementById('escale-kpi-best').textContent = formatDetour(best.detourSeconds);
    document.getElementById('escale-kpi-best-sub').textContent = best.stationName;

    candidatesList.innerHTML = '';
    data.candidates.forEach((cand, idx) => {
        const btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'candidate-card';
        const isZero = cand.detourSeconds <= 30;
        btn.innerHTML = `
            <div class="candidate-head">
                <span class="candidate-title">${idx + 1}. ${cand.stationName}</span>
                <span class="detour-pill ${isZero ? 'green' : 'orange'}">${formatDetour(cand.detourSeconds)}</span>
            </div>
            <div class="candidate-meta">
                <span>${cand.categoryLabel}</span>
                <span>Total : <strong>${formatDuration(cand.totalSeconds)}</strong></span>
                <span>${formatDuration(cand.leg1Seconds)} + ${formatDuration(cand.leg2Seconds)}</span>
            </div>
        `;
        btn.addEventListener('click', () => selectCandidate(cand, btn));
        candidatesList.appendChild(btn);

        if (idx === 0) {
            selectCandidate(cand, btn);
        }
    });
}

function bindEvents() {
    document.querySelectorAll('#escale-category-bar .s2-pill').forEach(btn => {
        btn.addEventListener('click', async () => {
            document.querySelectorAll('#escale-category-bar .s2-pill').forEach(b => b.classList.remove('active'));
            btn.classList.add('active');
            selectedCategory = btn.dataset.category;
            if (fromSelect.value && toSelect.value) {
                await searchStopovers();
            }
        });
    });

    document.getElementById('btn-escale-swap')?.addEventListener('click', async () => {
        if (!fromSelect.value || !toSelect.value) return;
        const tmp = fromSelect.value;
        fromSelect.value = toSelect.value;
        toSelect.value = tmp;
        await searchStopovers();
    });

    document.getElementById('btn-escale-go')?.addEventListener('click', searchStopovers);
}

document.addEventListener('DOMContentLoaded', async () => {
    bindEvents();

    const stations = await fetchStations();
    stations.sort((a, b) => a.name.localeCompare(b.name, 'fr', { sensitivity: 'base' }));
    stationsById = new Map(stations.map(s => [s.id, s]));

    stations.forEach(st => {
        fromSelect.add(new Option(st.name, st.id));
        toSelect.add(new Option(st.name, st.id));
    });
});