import { fetchStations } from './api.js';

const fromSelect = document.getElementById('trajet-from');
const toSelect = document.getElementById('trajet-to');
const resultsArea = document.getElementById('trajet-results-area');
const emptyPrompt = document.getElementById('trajet-empty-prompt');
const batteryBanner = document.getElementById('battery-banner');
const profileDesc = document.getElementById('profile-description');

let selectedProfile = 'FASTEST';

const PROFILE_DESCRIPTIONS = {
    FASTEST: 'Minimise le temps de trajet total en tenant compte des retards temps réel.',
    NAP_FOCUS: 'Pénalise les changements de ligne (+12 min par correspondance) pour maximiser le temps continu dans un même train.',
    LOW_BATTERY: 'Feuille de route hors-ligne OLED noir pur, décompte exact des arrêts et zéro appel réseau continu.',
    SEAT_COMFORT: 'Évite les tronçons perturbés/chargés et suggère un rebond vers un terminus voisin pour voyager assis.'
};

function formatDuration(seconds) {
    const mins = Math.floor(seconds / 60);
    const secs = seconds % 60;
    return mins > 0 ? `${mins} min ${secs > 0 ? secs + 's' : ''}` : `${secs}s`;
}

function showUnavailableState(message) {
    emptyPrompt?.classList.add('hidden');
    resultsArea.classList.remove('hidden');

    document.getElementById('res-duration').textContent = 'Indisponible';
    document.getElementById('res-delay-info').textContent = message;
    document.getElementById('res-stops').textContent = '—';
    document.getElementById('res-transfers').textContent = '—';
    document.getElementById('res-continuous').textContent = '—';
    document.getElementById('res-route-subtitle').textContent = 'Aucun itinéraire disponible';
    document.getElementById('res-steps-list').innerHTML =
        `<li class="roadmap-step is-transfer"><span>${message}</span></li>`;

    document.getElementById('advice-car').textContent = 'Non applicable';
    document.getElementById('advice-exit').textContent = 'Choisissez deux gares distinctes desservies par un tronçon ouvert.';
    document.getElementById('advice-comfort').textContent = '—';
    document.getElementById('advice-rebound').textContent = 'Aucun conseil disponible sans itinéraire actif.';
}

async function computeCustomRoute() {
    if (!fromSelect.value || !toSelect.value) {
        emptyPrompt.textContent = 'Veuillez sélectionner une gare de départ et une gare d\'arrivée avant de lancer le calcul.';
        emptyPrompt.classList.remove('hidden');
        resultsArea.classList.add('hidden');
        return;
    }

    if (fromSelect.value === toSelect.value) {
        showUnavailableState('La gare de départ et la gare d\'arrivée sont identiques.');
        return;
    }

    const url = `/api/routes?from=${encodeURIComponent(fromSelect.value)}&to=${encodeURIComponent(toSelect.value)}&profile=${encodeURIComponent(selectedProfile)}`;
    const response = await fetch(url);

    if (!response.ok) {
        showUnavailableState('Aucun itinéraire praticable actuellement (tronçon bloqué en temps réel).');
        return;
    }

    const data = await response.json();
    emptyPrompt?.classList.add('hidden');
    resultsArea.classList.remove('hidden');

    document.getElementById('res-duration').textContent = formatDuration(data.totalDurationSeconds);
    document.getElementById('res-delay-info').textContent = data.totalDelaySeconds > 0
        ? `Inclut +${Math.round(data.totalDelaySeconds / 60)} min de retard temps réel`
        : 'Trafic nominal sur l\'ensemble du trajet';

    document.getElementById('res-stops').textContent = data.stopsCount;
    document.getElementById('res-transfers').textContent = data.transfersCount;
    document.getElementById('res-continuous').textContent = formatDuration(data.maxContinuousSeconds);
    document.getElementById('res-route-subtitle').textContent = `${data.originName} → ${data.destinationName} (${data.profileLabel})`;

    const stepsList = document.getElementById('res-steps-list');
    stepsList.innerHTML = data.steps.map(step => `
        <li class="roadmap-step ${step.transfer ? 'is-transfer' : ''}">
            <div class="step-left">
                <span class="step-num">${step.index}</span>
                <div>
                    <div class="step-stations">${step.fromName} → ${step.toName}</div>
                    ${step.delaySeconds > 0 ? `<small class="text-orange">+${Math.round(step.delaySeconds / 60)} min de ralentissement</small>` : ''}
                </div>
            </div>
            <div class="step-meta">
                <span class="rer-mini-badge">${step.line}</span>
                <span class="step-time">${formatDuration(step.durationSeconds)}</span>
            </div>
        </li>
    `).join('');

    if (data.boardingAdvice) {
        document.getElementById('advice-car').textContent = data.boardingAdvice.carPosition;
        document.getElementById('advice-exit').textContent = data.boardingAdvice.exitTip;
        document.getElementById('advice-comfort').textContent = `Indice confort : ${data.boardingAdvice.comfortScore}`;
        document.getElementById('advice-rebound').textContent = data.boardingAdvice.reboundTip;
    }
}

function bindEvents() {
    document.querySelectorAll('.profile-bubble').forEach(btn => {
        btn.addEventListener('click', async () => {
            document.querySelectorAll('.profile-bubble').forEach(b => b.classList.remove('active'));
            btn.classList.add('active');
            selectedProfile = btn.dataset.profile;

            profileDesc.textContent = PROFILE_DESCRIPTIONS[selectedProfile] || '';

            const isLowBattery = selectedProfile === 'LOW_BATTERY';
            document.body.classList.toggle('oled-mode', isLowBattery);
            batteryBanner.classList.toggle('hidden', !isLowBattery);

            // Recalcule uniquement si l'utilisateur a déjà choisi un départ et une arrivée
            if (fromSelect.value && toSelect.value) {
                await computeCustomRoute();
            }
        });
    });

    document.getElementById('btn-swap-stations')?.addEventListener('click', async () => {
        if (!fromSelect.value || !toSelect.value) return;
        const tmp = fromSelect.value;
        fromSelect.value = toSelect.value;
        toSelect.value = tmp;
        await computeCustomRoute();
    });

    document.getElementById('btn-trajet-go')?.addEventListener('click', computeCustomRoute);
}

document.addEventListener('DOMContentLoaded', async () => {
    bindEvents();

    const stations = await fetchStations();
    stations.sort((a, b) => a.name.localeCompare(b.name, 'fr', { sensitivity: 'base' }));

    stations.forEach(st => {
        fromSelect.add(new Option(st.name, st.id));
        toSelect.add(new Option(st.name, st.id));
    });
});