import { fetchStations } from './api.js';

const PARTICIPANT_COLORS = ['#c4552d', '#00a859', '#141413', '#575652'];
const participantsContainer = document.getElementById('participants-container');
const btnAddParticipant = document.getElementById('btn-add-participant');
const emptyPrompt = document.getElementById('meeting-empty-prompt');
const resultsSection = document.getElementById('meeting-results');
const candidatesList = document.getElementById('meeting-candidates-list');
const detailBox = document.getElementById('meeting-detail-box');

let allStations = [];
let stationsById = new Map();
let selectedStrategy = 'MIN_MAX';
let participantCount = 0;
let mapInstance = null;
let mapLayerGroup = null;

function formatDuration(seconds) {
    const mins = Math.floor(seconds / 60);
    const secs = seconds % 60;
    return mins > 0 ? `${mins} min ${secs > 0 ? secs + 's' : ''}` : `${secs}s`;
}

function getStationName(id) {
    return stationsById.get(id)?.name || id;
}

function populateSelectOptions(select) {
    select.innerHTML = '<option value="" selected disabled>Sélectionner une gare de départ…</option>';
    allStations.forEach(st => select.add(new Option(st.name, st.id)));
}

function refreshParticipantBadges() {
    const rows = participantsContainer.querySelectorAll('.participant-row');
    participantCount = rows.length;

    rows.forEach((row, idx) => {
        const badge = row.querySelector('.participant-badge');
        badge.textContent = `P${idx + 1}`;
        badge.style.background = PARTICIPANT_COLORS[idx % PARTICIPANT_COLORS.length];

        const removeBtn = row.querySelector('.btn-remove-participant');
        if (removeBtn) {
            removeBtn.style.display = rows.length > 2 ? 'inline-flex' : 'none';
        }
    });

    btnAddParticipant.disabled = rows.length >= 4;
    btnAddParticipant.style.opacity = rows.length >= 4 ? '0.5' : '1';
}

function addParticipantRow() {
    if (participantsContainer.querySelectorAll('.participant-row').length >= 4) return;

    const row = document.createElement('div');
    row.className = 'participant-row';

    const badge = document.createElement('span');
    badge.className = 'participant-badge';

    const select = document.createElement('select');
    select.className = 'participant-select';
    select.setAttribute('aria-label', 'Gare de départ du participant');
    populateSelectOptions(select);

    const removeBtn = document.createElement('button');
    removeBtn.type = 'button';
    removeBtn.className = 'btn-remove-participant';
    removeBtn.textContent = '×';
    removeBtn.title = 'Retirer ce participant';
    removeBtn.addEventListener('click', () => {
        row.remove();
        refreshParticipantBadges();
    });

    row.append(badge, select, removeBtn);
    participantsContainer.appendChild(row);
    refreshParticipantBadges();
}

function ensureMap() {
    if (mapInstance || typeof L === 'undefined') return;
    mapInstance = L.map('meeting-map', {
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

function selectMeetingCandidate(cand, cardEl) {
    document.querySelectorAll('#meeting-candidates-list .candidate-card').forEach(c => c.classList.remove('active'));
    cardEl?.classList.add('active');

    document.getElementById('meet-selected-title').textContent = `Rendez-vous à ${cand.stationName}`;
    document.getElementById('meet-selected-sub').textContent =
        `${cand.categoryLabel} • Indice d'équité : ${cand.fairnessScore} % (écart max ${formatDuration(cand.spreadSeconds)})`;

    ensureMap();
    mapLayerGroup.clearLayers();
    const boundsPoints = [];

    cand.participantLegs.forEach((leg, idx) => {
        const color = PARTICIPANT_COLORS[idx % PARTICIPANT_COLORS.length];
        const coords = (leg.path || [])
            .map(id => stationsById.get(id))
            .filter(s => s && typeof s.latitude === 'number' && typeof s.longitude === 'number')
            .map(s => [s.latitude, s.longitude]);

        if (coords.length >= 2) {
            L.polyline(coords, { color, weight: 4.5, opacity: 0.9 }).addTo(mapLayerGroup);
            boundsPoints.push(...coords);
        }

        const originSt = stationsById.get(leg.originId);
        if (originSt && typeof originSt.latitude === 'number') {
            L.circleMarker([originSt.latitude, originSt.longitude], {
                radius: 7,
                fillColor: color,
                color: '#ffffff',
                weight: 2,
                fillOpacity: 1
            }).bindPopup(`<strong>Participant ${leg.participantIndex}</strong><br>${originSt.name} (${formatDuration(leg.durationSeconds)})`)
                .addTo(mapLayerGroup);
            boundsPoints.push([originSt.latitude, originSt.longitude]);
        }
    });

    if (typeof cand.latitude === 'number' && typeof cand.longitude === 'number') {
        L.circleMarker([cand.latitude, cand.longitude], {
            radius: 10,
            fillColor: '#00a859',
            color: '#141413',
            weight: 2.5,
            fillOpacity: 1
        }).bindPopup(`<strong>Point de rencontre : ${cand.stationName}</strong>`).addTo(mapLayerGroup);
        boundsPoints.push([cand.latitude, cand.longitude]);
    }

    if (boundsPoints.length >= 2) {
        mapInstance.fitBounds(L.latLngBounds(boundsPoints), { padding: [36, 36] });
    }
    setTimeout(() => mapInstance.invalidateSize(), 60);

    const maxSec = Math.max(1, cand.maxDurationSeconds);
    detailBox.innerHTML = `
        <div class="leg-list">
            ${cand.participantLegs.map((leg, idx) => {
        const color = PARTICIPANT_COLORS[idx % PARTICIPANT_COLORS.length];
        const pct = Math.round((leg.durationSeconds / maxSec) * 100);
        return `
                    <div class="leg-item">
                        <div class="leg-item-top">
                            <strong><span class="s2-dot" style="display:inline-block;margin-right:6px;background:${color};"></span>P${leg.participantIndex} • ${leg.originName} → ${cand.stationName}</strong>
                            <span class="detour-pill ${idx % 2 === 0 ? 'orange' : 'green'}">${formatDuration(leg.durationSeconds)} • ${leg.stopsCount} arrêts</span>
                        </div>
                        <div class="equity-bar-track">
                            <div class="equity-bar-fill" style="width:${pct}%;background:${color};"></div>
                        </div>
                        <div class="leg-path-text">${leg.path.map(getStationName).join(' → ')}</div>
                    </div>
                `;
    }).join('')}
        </div>
    `;
}

async function computeMeetingPoints() {
    const selectedOrigins = [...participantsContainer.querySelectorAll('.participant-select')]
        .map(s => s.value)
        .filter(Boolean);

    const uniqueOrigins = [...new Set(selectedOrigins)];

    if (uniqueOrigins.length < 2) {
        emptyPrompt.textContent = 'Sélectionnez au moins 2 gares de départ distinctes avant de lancer le calcul.';
        emptyPrompt.classList.remove('hidden');
        resultsSection.classList.add('hidden');
        return;
    }

    const params = new URLSearchParams();
    uniqueOrigins.forEach(o => params.append('origins', o));
    params.set('strategy', selectedStrategy);

    const response = await fetch(`/api/routes/meeting?${params.toString()}`);
    if (!response.ok) {
        emptyPrompt.textContent = 'Aucun point de rencontre joignable par tous les participants actuellement.';
        emptyPrompt.classList.remove('hidden');
        resultsSection.classList.add('hidden');
        return;
    }

    const data = await response.json();
    if (!data.candidates || data.candidates.length === 0) {
        emptyPrompt.textContent = 'Aucun point de rencontre trouvé.';
        emptyPrompt.classList.remove('hidden');
        resultsSection.classList.add('hidden');
        return;
    }

    emptyPrompt.classList.add('hidden');
    resultsSection.classList.remove('hidden');

    const best = data.candidates[0];
    document.getElementById('meet-kpi-station').textContent = best.stationName;
    document.getElementById('meet-kpi-tag').textContent = best.categoryLabel;
    document.getElementById('meet-kpi-max').textContent = formatDuration(best.maxDurationSeconds);
    document.getElementById('meet-kpi-spread').textContent = formatDuration(best.spreadSeconds);
    document.getElementById('meet-kpi-score').textContent = `Indice d'équité : ${best.fairnessScore} %`;
    document.getElementById('meet-kpi-avg').textContent = formatDuration(best.avgDurationSeconds);
    document.getElementById('meet-kpi-sum').textContent = `Cumul groupe : ${formatDuration(best.totalDurationSeconds)}`;

    candidatesList.innerHTML = '';
    data.candidates.forEach((cand, idx) => {
        const btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'candidate-card';
        btn.innerHTML = `
            <div class="candidate-head">
                <span class="candidate-title">${idx + 1}. ${cand.stationName}</span>
                <span class="detour-pill ${cand.fairnessScore >= 80 ? 'green' : 'orange'}">Équité ${cand.fairnessScore} %</span>
            </div>
            <div class="candidate-meta">
                <span>Max : <strong>${formatDuration(cand.maxDurationSeconds)}</strong></span>
                <span>Écart : ${formatDuration(cand.spreadSeconds)}</span>
                <span>Moy. : ${formatDuration(cand.avgDurationSeconds)}</span>
            </div>
        `;
        btn.addEventListener('click', () => selectMeetingCandidate(cand, btn));
        candidatesList.appendChild(btn);

        if (idx === 0) {
            selectMeetingCandidate(cand, btn);
        }
    });
}

function bindEvents() {
    document.querySelectorAll('#meeting-strategy-bar .s2-pill').forEach(btn => {
        btn.addEventListener('click', async () => {
            document.querySelectorAll('#meeting-strategy-bar .s2-pill').forEach(b => b.classList.remove('active'));
            btn.classList.add('active');
            selectedStrategy = btn.dataset.strategy;

            const filled = [...participantsContainer.querySelectorAll('.participant-select')]
                .map(s => s.value)
                .filter(Boolean);
            if (new Set(filled).size >= 2) {
                await computeMeetingPoints();
            }
        });
    });

    btnAddParticipant?.addEventListener('click', addParticipantRow);
    document.getElementById('btn-meeting-go')?.addEventListener('click', computeMeetingPoints);
}

document.addEventListener('DOMContentLoaded', async () => {
    bindEvents();

    allStations = await fetchStations();
    allStations.sort((a, b) => a.name.localeCompare(b.name, 'fr', { sensitivity: 'base' }));
    stationsById = new Map(allStations.map(s => [s.id, s]));

    // 2 participants par défaut au lancement
    addParticipantRow();
    addParticipantRow();
});