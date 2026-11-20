import {
    fetchStations,
    fetchSections,
    fetchActiveRedisCache,
    fetchShortestRoute,
    postDisruption,
    resetAllDisruptions,
    fetchTrafficMode,
    switchTrafficMode
} from './api.js?v=4';

import {
    populateStationSelectors,
    populateSectionSelector,
    renderSelectedSectionInspector,
    renderDisruptedSummary,
    renderRedisTags,
    renderRouteResult,
    renderTrafficMode
} from './ui.js?v=4';

import { updateAnalyticsDashboard } from './analytics.js?v=4';

const fromSelect = document.getElementById('from');
const toSelect = document.getElementById('to');
const routeResultBox = document.getElementById('routeResult');
const sectionSelect = document.getElementById('sectionSelect');
const sectionInspector = document.getElementById('sectionInspector');
const delayInput = document.getElementById('delayInput');
const blockedSelect = document.getElementById('blockedSelect');
const redisCacheBox = document.getElementById('redisCache');
const disruptedListBox = document.getElementById('disruptedSectionsList');
const disruptedCountBadge = document.getElementById('disruptedCount');

let allStations = [];
let stationsById = new Map();
let allSections = [];
let mapInstance = null;
let routePolyline = null;
let activeRerFilter = 'ALL';
let activeSortOrder = 'ASC';

function initScrollReveal() {
    const elements = document.querySelectorAll('.reveal-up');
    const observer = new IntersectionObserver((entries) => {
        entries.forEach(entry => {
            if (entry.isIntersecting) {
                entry.target.classList.add('is-visible');
                observer.unobserve(entry.target);
            }
        });
    }, { threshold: 0.05 });

    elements.forEach(el => observer.observe(el));
}

function initMap(stations) {
    const mapContainer = document.getElementById('idf-map');
    if (!mapContainer || typeof L === 'undefined') return;

    mapInstance = L.map('idf-map', {
        center: [48.8566, 2.3522],
        zoom: 11,
        scrollWheelZoom: false
    });

    L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
        attribution: '&copy; OpenStreetMap',
        maxZoom: 19
    }).addTo(mapInstance);

    stations.forEach(station => {
        if (typeof station.latitude !== 'number' || typeof station.longitude !== 'number') return;

        const marker = L.circleMarker([station.latitude, station.longitude], {
            radius: 5,
            fillColor: '#c4552d',
            color: '#ffffff',
            weight: 1.5,
            opacity: 1,
            fillOpacity: 0.9
        }).addTo(mapInstance);

        const popupHtml = `
            <div class="map-popup-title">${station.name}</div>
            <div class="map-popup-actions">
                <button class="map-popup-btn origin" onclick="window.selectMapStation('from', '${station.id}')">Départ</button>
                <button class="map-popup-btn dest" onclick="window.selectMapStation('to', '${station.id}')">Arrivée</button>
            </div>
        `;
        marker.bindPopup(popupHtml);
    });
}

window.selectMapStation = async (target, stationId) => {
    if (target === 'from' && fromSelect) fromSelect.value = stationId;
    if (target === 'to' && toSelect) toSelect.value = stationId;
    mapInstance?.closePopup();
    if (fromSelect?.value && toSelect?.value) {
        await calculateCurrentRoute();
    }
};

function drawRouteOnMap(pathIds) {
    if (!mapInstance) return;
    if (routePolyline) {
        mapInstance.removeLayer(routePolyline);
        routePolyline = null;
    }
    if (!pathIds || pathIds.length < 2) return;

    const coords = pathIds
        .map(id => stationsById.get(id))
        .filter(s => s && typeof s.latitude === 'number' && typeof s.longitude === 'number')
        .map(s => [s.latitude, s.longitude]);

    if (coords.length < 2) return;

    routePolyline = L.polyline(coords, {
        color: '#00a859',
        weight: 5,
        opacity: 0.9
    }).addTo(mapInstance);

    mapInstance.fitBounds(routePolyline.getBounds(), { padding: [40, 40] });
}

async function refreshTrafficMode() {
    try {
        const modeData = await fetchTrafficMode();
        renderTrafficMode(modeData);
    } catch (err) {
        console.error('Erreur synchronisation mode trafic :', err);
    }
}

async function calculateCurrentRoute() {
    if (!fromSelect?.value || !toSelect?.value) {
        routeResultBox.classList.remove('is-error');
        routeResultBox.classList.add('is-empty');
        routeResultBox.textContent = 'Veuillez sélectionner une gare de départ et une gare d\'arrivée avant de lancer le calcul.';
        drawRouteOnMap([]);
        return;
    }

    try {
        const routeData = await fetchShortestRoute(fromSelect.value, toSelect.value);
        renderRouteResult(routeData, routeResultBox);
        drawRouteOnMap(routeData?.path || []);
    } catch (err) {
        console.error('Erreur calcul itinéraire :', err);
        renderRouteResult(null, routeResultBox);
        drawRouteOnMap([]);
    }
}

async function refreshDashboard() {
    try {
        const [sections, activeRedisKeys] = await Promise.all([
            fetchSections(),
            fetchActiveRedisCache()
        ]);
        allSections = sections;
        populateSectionSelector(allSections, sectionSelect, stationsById);
        renderSelectedSectionInspector(allSections, sectionSelect?.value, sectionInspector, stationsById);
        renderDisruptedSummary(
            allSections,
            disruptedListBox,
            disruptedCountBadge,
            stationsById,
            activeRerFilter,
            activeSortOrder
        );
        renderRedisTags(activeRedisKeys, redisCacheBox);
        updateAnalyticsDashboard(allStations, allSections, activeRedisKeys);
    } catch (err) {
        console.error('Erreur rafraîchissement tableau de bord :', err);
    }
}

function bindEvents() {
    document.getElementById('btn-mode-realtime')?.addEventListener('click', async () => {
        try {
            const data = await switchTrafficMode('REALTIME');
            renderTrafficMode(data);
            await refreshDashboard();
            if (fromSelect?.value && toSelect?.value) {
                await calculateCurrentRoute();
            }
        } catch (err) {
            alert(err.message);
        }
    });

    document.getElementById('btn-mode-simulation')?.addEventListener('click', async () => {
        try {
            const data = await switchTrafficMode('SIMULATION');
            renderTrafficMode(data);
            await refreshDashboard();
            if (fromSelect?.value && toSelect?.value) {
                await calculateCurrentRoute();
            }
        } catch (err) {
            alert(err.message);
        }
    });

    sectionSelect?.addEventListener('change', () => {
        renderSelectedSectionInspector(allSections, sectionSelect.value, sectionInspector, stationsById);
    });

    document.querySelectorAll('#rerFilterGroup .rer-pill').forEach(btn => {
        btn.addEventListener('click', () => {
            document.querySelectorAll('#rerFilterGroup .rer-pill').forEach(b => b.classList.remove('active'));
            btn.classList.add('active');
            activeRerFilter = btn.dataset.rer;
            renderDisruptedSummary(
                allSections,
                disruptedListBox,
                disruptedCountBadge,
                stationsById,
                activeRerFilter,
                activeSortOrder
            );
        });
    });

    document.getElementById('disruptedSortSelect')?.addEventListener('change', (e) => {
        activeSortOrder = e.target.value;
        renderDisruptedSummary(
            allSections,
            disruptedListBox,
            disruptedCountBadge,
            stationsById,
            activeRerFilter,
            activeSortOrder
        );
    });

    document.getElementById('btn-calculate')?.addEventListener('click', calculateCurrentRoute);

    document.getElementById('btn-disrupt')?.addEventListener('click', async () => {
        if (!sectionSelect?.value) {
            sectionInspector.innerHTML = '<span class="text-orange">Veuillez d\'abord sélectionner un tronçon dans la liste déroulante.</span>';
            return;
        }
        try {
            const blocked = blockedSelect.value === 'true';
            await postDisruption({
                sectionId: Number(sectionSelect.value),
                delaySeconds: blocked ? 0 : Number(delayInput.value),
                blocked: blocked,
                cause: blocked ? 'Voie bloquée (Simulation)' : 'Ralentissement (Simulation)'
            });
            await refreshDashboard();
            if (fromSelect?.value && toSelect?.value) {
                await calculateCurrentRoute();
            }
        } catch (err) {
            console.error('Erreur injection perturbation :', err);
        }
    });

    document.getElementById('btn-reset')?.addEventListener('click', async () => {
        try {
            await resetAllDisruptions();
            await refreshDashboard();
            if (fromSelect?.value && toSelect?.value) {
                await calculateCurrentRoute();
            }
        } catch (err) {
            console.error('Erreur réinitialisation :', err);
        }
    });
}

document.addEventListener('DOMContentLoaded', async () => {
    initScrollReveal();
    bindEvents();

    try {
        allStations = await fetchStations();
        stationsById = new Map(allStations.map(s => [s.id, s]));
        populateStationSelectors(allStations, fromSelect, toSelect);
        initMap(allStations);
    } catch (err) {
        console.error('Erreur chargement gares :', err);
    }

    await refreshTrafficMode();
    await refreshDashboard();

    setInterval(async () => {
        await refreshTrafficMode();
        await refreshDashboard();
    }, 5000);
});

document.addEventListener('DOMContentLoaded', async () => {
    initScrollReveal();
    bindEvents();

    try {
        allStations = await fetchStations();
        stationsById = new Map(allStations.map(s => [s.id, s]));
        populateStationSelectors(allStations, fromSelect, toSelect);
        initMap(allStations);
        await calculateCurrentRoute();
    } catch (err) {
        console.error('Erreur chargement gares :', err);
    }

    await refreshTrafficMode();
    await refreshDashboard();

    setInterval(async () => {
        await refreshTrafficMode();
        await refreshDashboard();
    }, 5000);
});