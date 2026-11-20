export async function fetchStations() {
    const response = await fetch('/api/disruptions/stations');
    if (!response.ok) throw new Error('Erreur lors du chargement des gares');
    return response.json();
}

export async function fetchSections() {
    const response = await fetch('/api/disruptions/sections');
    if (!response.ok) throw new Error('Erreur lors du chargement des tronçons');
    return response.json();
}

export async function fetchActiveRedisCache() {
    const response = await fetch('/api/disruptions/active-cache');
    if (!response.ok) throw new Error('Erreur lors de la lecture du cache Redis');
    return response.json();
}

export async function fetchShortestRoute(from, to) {
    const response = await fetch(`/api/routes?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`);
    if (!response.ok) return null;
    return response.json();
}

export async function postDisruption(payload) {
    const response = await fetch('/api/disruptions', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
    });
    if (!response.ok) throw new Error('Erreur lors de la création de la perturbation');
    return response.json();
}

export async function resetAllDisruptions() {
    const response = await fetch('/api/disruptions/reset', { method: 'POST' });
    if (!response.ok) throw new Error('Erreur lors de la réinitialisation du réseau');
    return response.json();
}

export async function fetchTrafficMode() {
    const response = await fetch('/api/disruptions/mode');
    if (!response.ok) throw new Error('Erreur lors de la récupération du mode trafic');
    return response.json();
}

export async function switchTrafficMode(mode) {
    const response = await fetch(`/api/disruptions/mode?value=${encodeURIComponent(mode)}`, {
        method: 'POST'
    });
    const data = await response.json();
    if (!response.ok) {
        throw new Error(data.error || 'Impossible de changer le mode trafic');
    }
    return data;
}