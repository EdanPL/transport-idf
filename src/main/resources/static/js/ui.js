export function populateStationSelectors(stations, fromSelect, toSelect) {
    const sorted = [...stations].sort((a, b) =>
        a.name.localeCompare(b.name, 'fr', { sensitivity: 'base' })
    );

    fromSelect.innerHTML = '<option value="" selected disabled>Sélectionner une gare de départ…</option>';
    toSelect.innerHTML = '<option value="" selected disabled>Sélectionner une gare d\'arrivée…</option>';

    sorted.forEach(station => {
        fromSelect.add(new Option(station.name, station.id));
        toSelect.add(new Option(station.name, station.id));
    });
}

function formatDuration(totalSeconds) {
    const mins = Math.floor(totalSeconds / 60);
    const secs = totalSeconds % 60;
    return mins > 0 ? `${mins} min ${secs > 0 ? secs + 's' : ''}` : `${secs}s`;
}

export function populateSectionSelector(sections, sectionSelect, stationsById = new Map()) {
    const previousSelection = sectionSelect.value;
    const getLabel = (id) => stationsById.get(id)?.name || id;

    const sortedSections = [...sections].sort((a, b) =>
        getLabel(a.from).localeCompare(getLabel(b.from), 'fr', { sensitivity: 'base' })
        || getLabel(a.to).localeCompare(getLabel(b.to), 'fr', { sensitivity: 'base' })
    );

    sectionSelect.innerHTML = '<option value="" selected disabled>Sélectionner un tronçon du réseau…</option>';

    sortedSections.forEach(section => {
        sectionSelect.add(new Option(`${getLabel(section.from)} → ${getLabel(section.to)} (#${section.id})`, section.id));
    });

    if (previousSelection) {
        sectionSelect.value = previousSelection;
    }
}

export function renderSelectedSectionInspector(sections, selectedId, inspectorBox, stationsById = new Map()) {
    if (!inspectorBox) return;
    if (!selectedId) {
        inspectorBox.innerHTML = '<span class="muted-text">Sélectionnez un tronçon ci-dessus pour consulter son poids temps réel ou simuler un incident.</span>';
        return;
    }

    const section = sections.find(s => String(s.id) === String(selectedId));
    if (!section) {
        inspectorBox.innerHTML = '<span class="muted-text">Tronçon introuvable.</span>';
        return;
    }

    const getLabel = (id) => stationsById.get(id)?.name || id;
    const isDisrupted = section.blocked || section.delaySeconds > 0;
    let statusText = `${section.nominalSeconds}s (Trafic nominal)`;
    if (section.blocked) {
        statusText = `BLOQUÉ — ${section.cause}`;
    } else if (section.delaySeconds > 0) {
        statusText = `${section.nominalSeconds + section.delaySeconds}s (+${section.delaySeconds}s) — ${section.cause}`;
    }

    inspectorBox.innerHTML = `
        <div>
            <span class="status-dot ${isDisrupted ? 'err' : 'ok'}"></span>
            <strong>${getLabel(section.from)} → ${getLabel(section.to)}</strong>
        </div>
        <div class="inspector-meta">
            <span>Tronçon #${section.id}</span>
            <span>Nominal : ${section.nominalSeconds}s</span>
            <span>État : ${statusText}</span>
        </div>
    `;
}

function detectRerLine(section) {
    const cause = (section.cause || '').toUpperCase();
    const match = cause.match(/RER\s*([A-E])\b/);
    if (match) return match[1];

    // Fallback si perturbation simulée manuellement sans mention "RER X"
    const s = `${section.from}_${section.to}`.toUpperCase();
    if (/CERGY|POISSY|ACHERES|SAINT_GERMAIN|BOISSY|MARNE_LA_VALLEE|CHESSY|NOISY_LE_GRAND|VINCENNES|NATION|AUBER|LA_DEFENSE|NANTERRE/.test(s)) return 'A';
    if (/MITRY|AEROPORT|ROBINSON|SAINT_REMY|BOURG_LA_REINE|ANTONY|DENFERT|LUXEMBOURG|AULNAY|LE_BOURGET/.test(s)) return 'B';
    if (/PONTOISE|VERSAILLES|DOURDAN|ETAMPES|SAINT_QUENTIN|INVALIDES|MUSEE_D_ORSAY|AUSTERLITZ|BIBLIOTHEQUE|CHOISY|ABLON|MASSY/.test(s)) return 'C';
    if (/CREIL|ORRY|MALESHERBES|MELUN|CORBEIL|VILLENEUVE_SAINT_GEORGES|JUVISY|GOUSSAINVILLE|STADE_DE_FRANCE|MAISONS_ALFORT/.test(s)) return 'D';
    if (/CHELLES|TOURNAN|HAUSSMANN|MAGENTA|ROSA_PARKS|PANTIN|NOISY_LE_SEC|BONDY|VAL_DE_FONTENAY|NANTERRE_LA_FOLIE/.test(s)) return 'E';
    return 'AUTRE';
}

export function renderDisruptedSummary(
    sections,
    container,
    countBadge,
    stationsById = new Map(),
    rerFilter = 'ALL',
    sortOrder = 'ASC'
) {
    if (!container) return;

    const getStationLabel = (id) => stationsById.get(id)?.name || id;

    let disrupted = sections
        .filter(s => s.blocked || s.delaySeconds > 0)
        .map(s => ({
            ...s,
            rerLine: detectRerLine(s),
            fromName: getStationLabel(s.from),
            toName: getStationLabel(s.to)
        }));

    // 1. Filtre par ligne RER (A, B, C, D, E)
    if (rerFilter && rerFilter !== 'ALL') {
        disrupted = disrupted.filter(s => s.rerLine === rerFilter);
    }

    // 2. Tri alphabétique français sur la gare de départ puis d'arrivée
    disrupted.sort((a, b) => {
        const cmp = a.fromName.localeCompare(b.fromName, 'fr', { sensitivity: 'base' })
            || a.toName.localeCompare(b.toName, 'fr', { sensitivity: 'base' });
        return sortOrder === 'DESC' ? -cmp : cmp;
    });

    if (countBadge) {
        countBadge.textContent = disrupted.length;
    }

    if (disrupted.length === 0) {
        const label = rerFilter === 'ALL' ? 'l\'ensemble des tronçons' : `le RER ${rerFilter}`;
        container.innerHTML = `<p class="muted-text">Trafic nominal sur ${label}.</p>`;
        return;
    }

    container.innerHTML = disrupted.map(s => `
        <div class="disrupted-item">
            <div class="disrupted-item-head">
                <strong>#${s.id} • ${s.fromName} → ${s.toName}</strong>
                ${s.rerLine !== 'AUTRE' ? `<span class="rer-mini-badge">RER ${s.rerLine}</span>` : ''}
            </div>
            <span>${s.blocked ? 'Voie bloquée' : `+${Math.round(s.delaySeconds / 60)} min`} — ${s.cause || 'Perturbation active'}</span>
        </div>
    `).join('');
}

export function renderRedisTags(activeRedisKeys, container) {
    if (!container) return;
    if (!activeRedisKeys.length) {
        container.innerHTML = '<span class="muted-text">Aucune perturbation active en cache.</span>';
        return;
    }
    container.innerHTML = activeRedisKeys
        .map(key => `<span class="redis-tag">${key}</span>`)
        .join('');
}

export function renderRouteResult(routeData, resultBox) {
    resultBox.classList.remove('is-empty');

    if (!routeData) {
        resultBox.classList.add('is-error');
        resultBox.textContent = 'Aucun itinéraire praticable (tronçons bloqués).';
        return;
    }

    resultBox.classList.remove('is-error');
    const durationText = formatDuration(routeData.totalDurationSeconds);
    resultBox.innerHTML = `
        <strong>${routeData.stopsCount} arrêts • Temps estimé : ${durationText}</strong><br>
        ${routeData.path.join(' → ')}
    `;
}

export function renderTrafficMode(modeData) {
    const btnReal = document.getElementById('btn-mode-realtime');
    const btnSim = document.getElementById('btn-mode-simulation');
    const banner = document.getElementById('idfm-bulletins-banner');
    const list = document.getElementById('idfm-bulletins-list');
    const count = document.getElementById('bulletins-count');

    if (!btnReal || !btnSim) return;

    const isReal = modeData.mode === 'REALTIME';
    btnReal.classList.toggle('active', isReal);
    btnSim.classList.toggle('active', !isReal);

    const bulletins = modeData.bulletins || [];
    if (isReal && bulletins.length > 0) {
        banner.classList.remove('hidden');
        count.textContent = bulletins.length;
        list.innerHTML = bulletins.map(b => `<li>${b}</li>`).join('');
    } else {
        banner.classList.add('hidden');
        list.innerHTML = '';
    }
}