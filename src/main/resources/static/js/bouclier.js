import { fetchStations } from './api.js';

const STORAGE_KEY = 'transport_idf_shield_favorites_v1';
const fromSelect = document.getElementById('shield-from');
const toSelect = document.getElementById('shield-to');
const emptyPrompt = document.getElementById('shield-empty-prompt');
const resultsSection = document.getElementById('shield-results');
const watchBtn = document.getElementById('btn-shield-watch');

// Éléments Modale Justificatif
const certModal = document.getElementById('certificate-modal');
const certInputName = document.getElementById('cert-input-name');
const certInputOrg = document.getElementById('cert-input-org');
const btnCertCopy = document.getElementById('btn-cert-copy');

let stationsById = new Map();
let lastShieldData = null;
let lastCertificateData = null;
let autoWatch = true;
let watchTimer = null;
let mapInstance = null;
let shieldLayer = null;

function formatDuration(seconds) {
    const mins = Math.floor(seconds / 60);
    const secs = seconds % 60;
    return mins > 0 ? `${mins} min ${secs > 0 ? secs + 's' : ''}` : `${secs}s`;
}

function decodeHtmlEntities(str) {
    const textarea = document.createElement('textarea');
    textarea.innerHTML = String(str ?? '');
    return textarea.value.replace(/\u00A0/g, ' ');
}

function escapeHtml(str) {
    return decodeHtmlEntities(str)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');
}

function getStationName(id) {
    return stationsById.get(id)?.name || id;
}

function loadFavorites() {
    try {
        return JSON.parse(localStorage.getItem(STORAGE_KEY) || '[]');
    } catch {
        return [];
    }
}

function saveFavorites(list) {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(list.slice(0, 3)));
}

function renderFavorites() {
    const strip = document.getElementById('shield-favorites-strip');
    if (!strip) return;

    const favs = loadFavorites();
    if (favs.length === 0) {
        strip.innerHTML = `
            <span class="favorites-label">Trajets favoris :</span>
            <span class="muted-text" style="font-size:0.82rem;">Aucun trajet enregistré (cliquez sur « + Enregistrer favori »).</span>
        `;
        return;
    }

    strip.innerHTML = '<span class="favorites-label">Trajets favoris :</span>';
    favs.forEach((fav, index) => {
        const pill = document.createElement('div');
        pill.className = 'fav-pill';
        pill.innerHTML = `
            <span>${escapeHtml(getStationName(fav.from))} → ${escapeHtml(getStationName(fav.to))}</span>
            <button type="button" class="fav-del" title="Supprimer ce favori">×</button>
        `;
        pill.addEventListener('click', async (e) => {
            if (e.target.classList.contains('fav-del')) {
                const updated = loadFavorites().filter((_, i) => i !== index);
                saveFavorites(updated);
                renderFavorites();
                return;
            }
            fromSelect.value = fav.from;
            toSelect.value = fav.to;
            await runShieldAnalysis();
        });
        strip.appendChild(pill);
    });
}

function ensureMap() {
    if (mapInstance || typeof L === 'undefined') return;
    mapInstance = L.map('shield-map', {
        center: [48.8566, 2.3522],
        zoom: 11,
        scrollWheelZoom: false
    });
    L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
        attribution: '&copy; OpenStreetMap',
        maxZoom: 19
    }).addTo(mapInstance);
    shieldLayer = L.layerGroup().addTo(mapInstance);
}

function toCoords(pathIds) {
    return (pathIds || [])
        .map(id => stationsById.get(id))
        .filter(s => s && typeof s.latitude === 'number' && typeof s.longitude === 'number')
        .map(s => [s.latitude, s.longitude]);
}

function drawShieldMap(primary, highlightedAlt) {
    ensureMap();
    shieldLayer.clearLayers();
    const bounds = [];

    const primaryCoords = toCoords(primary.path);
    if (primaryCoords.length >= 2) {
        L.polyline(primaryCoords, { color: '#00a859', weight: 5, opacity: 0.9 }).addTo(shieldLayer);
        bounds.push(...primaryCoords);
    }

    if (highlightedAlt) {
        const altCoords = toCoords(highlightedAlt.path);
        if (altCoords.length >= 2) {
            L.polyline(altCoords, {
                color: '#c4552d',
                weight: 4.5,
                opacity: 0.9,
                dashArray: '9, 7'
            }).addTo(shieldLayer);
            bounds.push(...altCoords);
        }
        document.getElementById('shield-map-sub').textContent =
            `Comparaison : Plan A (vert) vs ${highlightedAlt.label} (orange pointillé)`;
    } else {
        document.getElementById('shield-map-sub').textContent = 'Tracé de l\'itinéraire principal (Plan A)';
    }

    const startSt = stationsById.get(primary.path[0]);
    const endSt = stationsById.get(primary.path[primary.path.length - 1]);

    if (startSt && typeof startSt.latitude === 'number') {
        L.circleMarker([startSt.latitude, startSt.longitude], {
            radius: 7, fillColor: '#141413', color: '#ffffff', weight: 2, fillOpacity: 1
        }).bindPopup(`<strong>Départ :</strong> ${escapeHtml(startSt.name)}`).addTo(shieldLayer);
    }
    if (endSt && typeof endSt.latitude === 'number') {
        L.circleMarker([endSt.latitude, endSt.longitude], {
            radius: 8, fillColor: '#00a859', color: '#ffffff', weight: 2, fillOpacity: 1
        }).bindPopup(`<strong>Arrivée :</strong> ${escapeHtml(endSt.name)}`).addTo(shieldLayer);
    }

    if (bounds.length >= 2) {
        mapInstance.fitBounds(L.latLngBounds(bounds), { padding: [36, 36] });
    }
    setTimeout(() => mapInstance.invalidateSize(), 60);
}

function renderShieldResults(data) {
    lastShieldData = data;
    emptyPrompt.classList.add('hidden');
    resultsSection.classList.remove('hidden');

    const banner = document.getElementById('shield-status-banner');
    banner.className = `shield-status-banner ${data.status}`;
    const titleLabel = data.status === 'SAFE'
        ? `Bouclier Vert — ${data.originName} → ${data.destinationName}`
        : (data.status === 'WARNING'
            ? `Vigilance Active — ${data.originName} → ${data.destinationName}`
            : `Alerte Critique — Bascule recommandée`);
    document.getElementById('shield-banner-title').textContent = titleLabel;
    document.getElementById('shield-banner-sub').textContent = data.headline;

    document.getElementById('shield-kpi-score').textContent = `${data.resilienceScore} / 100`;
    document.getElementById('shield-kpi-primary').textContent = formatDuration(data.primaryRoute.totalDurationSeconds);
    document.getElementById('shield-kpi-delay').textContent = data.primaryRoute.totalDelaySeconds > 0
        ? `Inclut +${Math.round(data.primaryRoute.totalDelaySeconds / 60)} min de retard`
        : 'Trafic nominal sur le Plan A';

    document.getElementById('shield-kpi-alts').textContent = data.alternativeRoutes.length;
    document.getElementById('shield-kpi-alt-sub').textContent = data.alternativeRoutes.length > 0
        ? `Meilleur repli : ${data.alternativeRoutes[0].deltaSeconds >= 0 ? '+' : ''}${Math.round(data.alternativeRoutes[0].deltaSeconds / 60)} min`
        : 'Axe direct sans branche parallèle';

    const totalSegs = data.bypassableSegmentsCount + data.singlePointFailureCount;
    document.getElementById('shield-kpi-bypass').textContent = `${data.bypassableSegmentsCount} / ${totalSegs}`;
    document.getElementById('shield-kpi-spof').textContent = data.singlePointFailureCount > 0
        ? `${data.singlePointFailureCount} goulot(s) branche unique`
        : '100 % contournable par le RER';

    const plansContainer = document.getElementById('shield-plans-list');
    plansContainer.innerHTML = '';

    const allPlans = [data.primaryRoute, ...data.alternativeRoutes];
    allPlans.forEach((plan, idx) => {
        const isAlt = idx > 0;
        const card = document.createElement('div');
        card.className = `plan-card ${isAlt ? 'alt' : ''} ${idx === (data.alternativeRoutes.length > 0 ? 1 : 0) ? 'active' : ''}`;

        const deltaBadge = isAlt
            ? `<span class="detour-pill ${plan.deltaSeconds <= 0 ? 'green' : 'orange'}">${plan.deltaSeconds <= 0 ? `${Math.round(plan.deltaSeconds / 60)} min` : `+${Math.round(plan.deltaSeconds / 60)} min`} vs Plan A</span>`
            : `<span class="detour-pill green">Référence</span>`;

        card.innerHTML = `
            <div class="plan-card-head">
                <strong>${escapeHtml(plan.label)}</strong>
                ${deltaBadge}
            </div>
            <div class="plan-lines">
                ${plan.linesUsed.map(l => `<span class="rer-mini-badge">${escapeHtml(l)}</span>`).join('')}
            </div>
            <div class="candidate-meta" style="margin-bottom:8px;">
                <span>Durée : <strong>${formatDuration(plan.totalDurationSeconds)}</strong></span>
                <span>${plan.stopsCount} arrêts • ${plan.transfersCount} corresp.</span>
                ${isAlt ? `<span>Tronçon commun : ${plan.overlapPct} %</span>` : ''}
            </div>
            <div class="leg-path-text">${plan.pathNames.map(escapeHtml).join(' → ')}</div>
        `;

        card.addEventListener('click', () => {
            plansContainer.querySelectorAll('.plan-card').forEach(c => c.classList.remove('active'));
            card.classList.add('active');
            drawShieldMap(data.primaryRoute, isAlt ? plan : null);
        });

        plansContainer.appendChild(card);
    });

    drawShieldMap(data.primaryRoute, data.alternativeRoutes[0] || null);

    const bnGrid = document.getElementById('shield-bottlenecks-grid');
    bnGrid.innerHTML = data.bottlenecks.map(bn => {
        const cls = bn.currentlyDisrupted ? 'is-disrupted' : (!bn.hasBypass ? 'no-bypass' : '');
        const badge = bn.currentlyDisrupted
            ? '<span class="detour-pill orange">Perturbé</span>'
            : (bn.hasBypass
                ? '<span class="detour-pill green">Contournable</span>'
                : '<span class="detour-pill orange">Goulot unique</span>');

        return `
            <div class="bottleneck-card ${cls}">
                <div class="radar-item-head">
                    <strong>${escapeHtml(bn.fromName)} → ${escapeHtml(bn.toName)}</strong>
                    ${badge}
                </div>
                <div class="radar-item-sub">
                    <strong>${escapeHtml(bn.line)}</strong> — ${escapeHtml(bn.recommendation)}
                </div>
            </div>
        `;
    }).join('');
}

// =========================================================================
// GÉNÉRATEUR DE JUSTIFICATIF DE RETARD
// =========================================================================

async function openAndLoadCertificate() {
    if (!fromSelect.value || !toSelect.value || fromSelect.value === toSelect.value) return;

    const params = new URLSearchParams({
        from: fromSelect.value,
        to: toSelect.value
    });
    if (certInputName?.value.trim()) params.set('passengerName', certInputName.value.trim());
    if (certInputOrg?.value.trim()) params.set('organization', certInputOrg.value.trim());

    const response = await fetch(`/api/routes/shield/certificate?${params.toString()}`);
    if (!response.ok) return;

    const cert = await response.json();
    lastCertificateData = cert;

    document.getElementById('cert-ref-code').textContent = cert.referenceCode;
    document.getElementById('cert-stamp-code').textContent = cert.referenceCode;
    document.getElementById('cert-issued-at').textContent = `Relevé du ${cert.issuedAtDisplay}`;
    document.getElementById('cert-passenger').textContent = cert.passengerName;
    document.getElementById('cert-org').textContent = cert.organization;
    document.getElementById('cert-route').textContent = `${cert.originName} → ${cert.destinationName}`;
    document.getElementById('cert-lines').textContent = cert.linesUsed.length ? cert.linesUsed.join(' • ') : 'Réseau RER';

    const verdictBox = document.getElementById('cert-verdict-box');
    verdictBox.className = `cert-verdict-box ${cert.status}`;
    document.getElementById('cert-status-label').textContent = cert.statusLabel;
    document.getElementById('cert-nominal').textContent = formatDuration(cert.nominalDurationSeconds);
    document.getElementById('cert-real').textContent = cert.status === 'INTERRUPTED'
        ? 'Trafic interrompu'
        : formatDuration(cert.realDurationSeconds);

    const delayEl = document.getElementById('cert-delay');
    if (cert.status === 'INTERRUPTED') {
        delayEl.textContent = `${cert.blockedSectionsCount} tronçon(s) coupé(s)`;
    } else if (cert.totalDelaySeconds > 0) {
        delayEl.textContent = `+${Math.round(cert.totalDelaySeconds / 60)} min`;
    } else {
        delayEl.textContent = '0 min';
    }

    const incBox = document.getElementById('cert-incidents-list');
    if (!cert.incidents || cert.incidents.length === 0) {
        incBox.innerHTML = '<div class="cert-incident-row">Aucune perturbation active relevée sur cet itinéraire à l\'heure du contrôle.</div>';
    } else {
        incBox.innerHTML = cert.incidents.map(inc => `
            <div class="cert-incident-row ${inc.blocked ? 'is-blocked' : ''}">
                <div class="cert-incident-top">
                    <strong>${escapeHtml(inc.fromName)} → ${escapeHtml(inc.toName)} (${escapeHtml(inc.line)})</strong>
                    <span class="detour-pill orange">${inc.blocked ? 'Voie bloquée' : `+${Math.round(inc.delaySeconds / 60)} min`}</span>
                </div>
                <div>${escapeHtml(inc.cause)}</div>
            </div>
        `).join('');
    }

    const fallbackSec = document.getElementById('cert-fallback-section');
    if (cert.fallbackSummary) {
        fallbackSec.classList.remove('hidden');
        document.getElementById('cert-fallback-text').textContent = cert.fallbackSummary;
    } else {
        fallbackSec.classList.add('hidden');
    }

    document.getElementById('cert-data-source').textContent = cert.dataSource;
    certModal.classList.remove('hidden');
}

function buildEmailSummary(cert) {
    const delayText = cert.status === 'INTERRUPTED'
        ? `une interruption de trafic (${cert.blockedSectionsCount} tronçon(s) bloqué(s))`
        : (cert.totalDelaySeconds > 0
            ? `un retard estimé à +${Math.round(cert.totalDelaySeconds / 60)} minutes`
            : 'un trafic nominal sans retard relevé');

    const incidentsLines = (cert.incidents || []).length > 0
        ? cert.incidents.map(i => `- ${i.fromName} → ${i.toName} (${i.line}) : ${i.blocked ? 'Voie bloquée' : `+${Math.round(i.delaySeconds / 60)} min`} — ${decodeHtmlEntities(i.cause)}`).join('\n')
        : '- Aucun incident actif signalé sur cet axe.';

    return [
        `Objet : Justificatif de perturbation transport — ${cert.originName} → ${cert.destinationName} (${cert.issuedAtDisplay})`,
        '',
        'Bonjour,',
        '',
        `Je vous informe que mon trajet ${cert.originName} → ${cert.destinationName} (${cert.linesUsed.join(', ')}) a été impacté le ${cert.issuedAtDisplay} par ${delayText}.`,
        '',
        'Détail du relevé temps réel (Source : API PRIM Île-de-France Mobilités) :',
        incidentsLines,
        '',
        `Référence du relevé : ${cert.referenceCode}`,
        cert.passengerName !== 'Non renseigné' ? `\nCordialement,\n${cert.passengerName}` : '\nCordialement,'
    ].join('\n');
}

async function runShieldAnalysis() {
    if (!fromSelect.value || !toSelect.value) {
        emptyPrompt.textContent = 'Sélectionnez une gare de départ et une gare d\'arrivée pour activer le Bouclier.';
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

    const url = `/api/routes/shield?from=${encodeURIComponent(fromSelect.value)}&to=${encodeURIComponent(toSelect.value)}`;
    const response = await fetch(url);
    if (!response.ok) {
        emptyPrompt.innerHTML = `
            Aucun itinéraire praticable entre ces deux gares actuellement (tronçon bloqué).<br>
            <button type="button" id="btn-cert-interrupted" class="btn-certificate" style="margin-top:14px;">
                Générer un justificatif d'interruption (PDF)
            </button>
        `;
        emptyPrompt.classList.remove('hidden');
        resultsSection.classList.add('hidden');
        document.getElementById('btn-cert-interrupted')?.addEventListener('click', openAndLoadCertificate);
        return;
    }

    const data = await response.json();
    renderShieldResults(data);
    startWatchLoop();
}

function startWatchLoop() {
    if (watchTimer) clearInterval(watchTimer);
    if (!autoWatch) return;
    watchTimer = setInterval(async () => {
        if (autoWatch && fromSelect.value && toSelect.value && fromSelect.value !== toSelect.value) {
            const url = `/api/routes/shield?from=${encodeURIComponent(fromSelect.value)}&to=${encodeURIComponent(toSelect.value)}`;
            const res = await fetch(url);
            if (res.ok) {
                renderShieldResults(await res.json());
            }
        }
    }, 10000);
}

function printCertificateSheet() {
    const sheet = document.getElementById('certificate-sheet');
    if (!sheet) return;

    const existingFrame = document.getElementById('cert-print-frame');
    if (existingFrame) existingFrame.remove();

    const iframe = document.createElement('iframe');
    iframe.id = 'cert-print-frame';
    iframe.style.position = 'fixed';
    iframe.style.right = '0';
    iframe.style.bottom = '0';
    iframe.style.width = '0';
    iframe.style.height = '0';
    iframe.style.border = '0';
    document.body.appendChild(iframe);

    const doc = iframe.contentWindow.document;
    doc.open();
    doc.write(`<!DOCTYPE html>
<html lang="fr">
<head>
    <meta charset="UTF-8">
    <title>Justificatif de Retard — Transport IDF</title>
    <style>
        @page { size: A4 portrait; margin: 12mm; }
        * { box-sizing: border-box; margin: 0; padding: 0; -webkit-print-color-adjust: exact; print-color-adjust: exact; }
        body { font-family: 'Inter', 'Segoe UI', Arial, sans-serif; color: #141413; background: #ffffff; font-size: 13px; line-height: 1.45; }
        .hidden { display: none !important; }
        .text-orange { color: #c4552d; }

        .cert-sheet { width: 100%; background: #ffffff; }
        .cert-sheet-header { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; padding-bottom: 14px; border-bottom: 2px solid #141413; margin-bottom: 16px; }
        .cert-brand-row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-bottom: 8px; }
        .brand-dot { display: inline-block; width: 10px; height: 10px; border-radius: 50%; background: #00a859; }
        .cert-brand-title { font-weight: 700; font-size: 14px; }
        .cert-source-pill { padding: 2px 10px; border-radius: 9999px; background: rgba(0, 168, 89, 0.12); color: #00a859; font-size: 11px; font-weight: 700; }
        .cert-doc-title { font-size: 19px; font-weight: 700; letter-spacing: -0.02em; margin-bottom: 4px; }
        .cert-doc-subtitle { font-size: 12px; color: #575652; }

        .cert-ref-box { text-align: right; padding: 10px 14px; border: 1px solid #d4d2cc; background: #f8f7f4; flex-shrink: 0; }
        .cert-ref-box strong { display: block; font-size: 13px; letter-spacing: 0.04em; margin: 2px 0; }
        .cert-meta-label { display: block; font-size: 10px; font-weight: 700; text-transform: uppercase; letter-spacing: 0.05em; color: #78756e; margin-bottom: 3px; }
        .cert-meta-date { font-size: 11px; color: #575652; }

        .cert-info-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 10px; margin-bottom: 16px; }
        .cert-info-cell { padding: 10px 14px; border: 1px solid #e5e3dd; background: #f8f7f4; }
        .cert-info-cell strong { font-size: 13px; color: #141413; }

        .cert-verdict-box { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 14px 16px; border: 1px solid #d4d2cc; border-left: 5px solid #00a859; background: #ffffff; margin-bottom: 16px; break-inside: avoid; }
        .cert-verdict-box.DELAYED { border-left-color: #c4552d; background: rgba(196, 85, 45, 0.05); }
        .cert-verdict-box.INTERRUPTED { border-left-color: #141413; background: rgba(20, 20, 19, 0.05); }
        .cert-metrics-row { display: flex; gap: 22px; }
        .cert-metrics-row strong { font-size: 15px; font-weight: 700; }

        .cert-section { margin-bottom: 16px; }
        .cert-section-title { font-size: 11px; font-weight: 700; text-transform: uppercase; letter-spacing: 0.05em; margin-bottom: 8px; color: #141413; }
        .cert-incidents-list { display: flex; flex-direction: column; gap: 8px; }
        .cert-incident-row { padding: 9px 12px; border: 1px solid #e5e3dd; border-left: 3px solid #c4552d; background: #f8f7f4; font-size: 12px; break-inside: avoid; }
        .cert-incident-row.is-blocked { border-left-color: #141413; }
        .cert-incident-top { display: flex; justify-content: space-between; align-items: center; gap: 10px; margin-bottom: 4px; }
        .detour-pill { padding: 2px 9px; border-radius: 9999px; font-size: 11px; font-weight: 700; white-space: nowrap; }
        .detour-pill.orange { background: rgba(196, 85, 45, 0.12); color: #c4552d; }
        .cert-fallback-text { padding: 10px 12px; border: 1px solid #e5e3dd; border-left: 3px solid #00a859; background: #f8f7f4; font-size: 12px; color: #575652; }

        .cert-sheet-footer { display: flex; justify-content: space-between; align-items: flex-end; gap: 16px; padding-top: 12px; border-top: 1px solid #d4d2cc; font-size: 10.5px; color: #575652; line-height: 1.45; margin-top: 16px; break-inside: avoid; }
        .cert-stamp { padding: 7px 12px; border: 1px solid #141413; text-align: center; flex-shrink: 0; }
        .cert-stamp span { display: block; font-size: 9px; letter-spacing: 0.08em; color: #78756e; }
        .cert-stamp strong { font-size: 11px; color: #141413; }
    </style>
</head>
<body>${sheet.outerHTML}</body>
</html>`);
    doc.close();

    iframe.contentWindow.focus();
    setTimeout(() => {
        iframe.contentWindow.print();
    }, 150);
}

function bindEvents() {
    document.getElementById('btn-shield-go')?.addEventListener('click', runShieldAnalysis);
    document.getElementById('btn-open-certificate')?.addEventListener('click', openAndLoadCertificate);
    document.getElementById('btn-cert-refresh')?.addEventListener('click', openAndLoadCertificate);

    document.getElementById('btn-cert-close')?.addEventListener('click', () => {
        certModal.classList.add('hidden');
    });

    certModal?.addEventListener('click', (e) => {
        if (e.target === certModal) certModal.classList.add('hidden');
    });

    document.getElementById('btn-cert-print')?.addEventListener('click', printCertificateSheet);

    btnCertCopy?.addEventListener('click', async () => {
        if (!lastCertificateData) return;
        const text = buildEmailSummary(lastCertificateData);
        try {
            await navigator.clipboard.writeText(text);
            const original = btnCertCopy.textContent;
            btnCertCopy.textContent = 'Message copié !';
            setTimeout(() => { btnCertCopy.textContent = original; }, 2200);
        } catch {
            alert(text);
        }
    });

    document.getElementById('btn-shield-swap')?.addEventListener('click', async () => {
        if (!fromSelect.value || !toSelect.value) return;
        const tmp = fromSelect.value;
        fromSelect.value = toSelect.value;
        toSelect.value = tmp;
        await runShieldAnalysis();
    });

    document.getElementById('btn-shield-save')?.addEventListener('click', () => {
        if (!fromSelect.value || !toSelect.value || fromSelect.value === toSelect.value) return;
        const favs = loadFavorites();
        const exists = favs.some(f => f.from === fromSelect.value && f.to === toSelect.value);
        if (!exists) {
            favs.unshift({ from: fromSelect.value, to: toSelect.value });
            saveFavorites(favs);
            renderFavorites();
        }
    });

    watchBtn?.addEventListener('click', () => {
        autoWatch = !autoWatch;
        watchBtn.classList.toggle('active', autoWatch);
        watchBtn.innerHTML = autoWatch
            ? '<span class="live-pulse"></span> Veille auto : ACTIVE (10s)'
            : 'Veille auto : EN PAUSE';
        if (autoWatch) startWatchLoop();
        else if (watchTimer) clearInterval(watchTimer);
    });
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

    renderFavorites();
});