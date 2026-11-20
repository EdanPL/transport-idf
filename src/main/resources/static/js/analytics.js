const charts = {
    gauge: null,
    pie: null,
    bar: null,
    hubs: null
};

const COLORS = {
    green: '#00a859',
    orange: '#c4552d',
    ink: '#141413',
    slate: '#575652',
    muted: '#b8b5ad',
    grid: 'rgba(20, 20, 19, 0.06)'
};

function formatMinSec(seconds) {
    const rounded = Math.round(seconds);
    const m = Math.floor(rounded / 60);
    const s = rounded % 60;
    return m > 0 ? `${m}m ${String(s).padStart(2, '0')}s` : `${s}s`;
}

function setText(id, text) {
    const el = document.getElementById(id);
    if (el) el.textContent = text;
}

export function updateAnalyticsDashboard(stations, sections, activeRedisKeys = []) {
    if (!sections || sections.length === 0 || typeof Chart === 'undefined') return;

    const total = sections.length;
    const stationsCount = stations.length;
    const stationsById = new Map(stations.map(s => [s.id, s.name]));

    let blockedCount = 0;
    let delayedCount = 0;
    let nominalSum = 0;
    let totalDelayAdded = 0;

    // Tranches pour le diagramme circulaire (4 catégories)
    const pieBuckets = [0, 0, 0, 0]; // <2m, 2-3m, 3-5m, >5m

    // Tranches détaillées pour l'histogramme vertical (6 paliers)
    const barBuckets = [0, 0, 0, 0, 0, 0]; // <90s, 90-150s, 150-210s, 210-300s, 300-420s, >420s

    // Degré de connexion par gare (nombre de tronçons sortants)
    const degreeByStation = new Map();

    sections.forEach(sec => {
        const nom = Number(sec.nominalSeconds) || 0;
        const del = Number(sec.delaySeconds) || 0;
        nominalSum += nom;

        if (sec.blocked) {
            blockedCount++;
        } else if (del > 0) {
            delayedCount++;
            totalDelayAdded += del;
        }

        // Pie buckets
        if (nom < 120) pieBuckets[0]++;
        else if (nom < 180) pieBuckets[1]++;
        else if (nom < 300) pieBuckets[2]++;
        else pieBuckets[3]++;

        // Bar buckets
        if (nom < 90) barBuckets[0]++;
        else if (nom < 150) barBuckets[1]++;
        else if (nom < 210) barBuckets[2]++;
        else if (nom < 300) barBuckets[3]++;
        else if (nom < 420) barBuckets[4]++;
        else barBuckets[5]++;

        degreeByStation.set(sec.from, (degreeByStation.get(sec.from) || 0) + 1);
    });

    const fluidCount = total - blockedCount - delayedCount;
    const disruptedCount = blockedCount + delayedCount;
    const fluidityPct = ((fluidCount / total) * 100).toFixed(1);
    const disruptedPct = ((disruptedCount / total) * 100).toFixed(1);
    const avgNominal = nominalSum / total;
    const avgDegree = stationsCount > 0 ? (total / stationsCount).toFixed(1) : '0';

    // 1. Mise à jour des 5 tuiles KPI Power BI
    setText('kpi-total-sections', total.toLocaleString('fr-FR'));
    setText('kpi-total-stations', stationsCount.toLocaleString('fr-FR'));
    setText('kpi-avg-degree', `${avgDegree} liens / gare en moy.`);
    setText('kpi-fluidity-pct', `${fluidityPct}%`);
    setText('kpi-avg-duration', formatMinSec(avgNominal));
    setText('kpi-disrupted-pct', `${disruptedPct}%`);
    setText('kpi-disrupted-sub', `${disruptedCount} tronçon(s) • ${activeRedisKeys.length} clé(s) Redis`);

    setText('gauge-center-value', `${fluidityPct}%`);
    setText('gauge-center-sub', `${fluidCount} / ${total} tronçons fluides`);

    // 2. Graphique 1 : Jauge semi-circulaire (Fluidité temps réel)
    const gaugeData = [fluidCount, delayedCount, blockedCount];
    if (!charts.gauge) {
        const ctx = document.getElementById('chart-gauge-fluidity');
        if (ctx) {
            charts.gauge = new Chart(ctx, {
                type: 'doughnut',
                data: {
                    labels: ['Trafic fluide', 'Ralentissement', 'Voie bloquée'],
                    datasets: [{
                        data: gaugeData,
                        backgroundColor: [COLORS.green, COLORS.orange, COLORS.ink],
                        borderWidth: 0
                    }]
                },
                options: {
                    responsive: true,
                    maintainAspectRatio: false,
                    rotation: -90,
                    circumference: 180,
                    cutout: '76%',
                    plugins: {
                        legend: { display: false },
                        tooltip: {
                            callbacks: {
                                label: (c) => ` ${c.label} : ${c.raw} (${((c.raw / total) * 100).toFixed(1)}%)`
                            }
                        }
                    }
                }
            });
        }
    } else {
        charts.gauge.data.datasets[0].data = gaugeData;
        charts.gauge.update('none');
    }

    // 3. Graphique 2 : Camembert (Structure temporelle PostgreSQL en %)
    const piePctLabels = [
        `Courts < 2 min (${((pieBuckets[0] / total) * 100).toFixed(0)}%)`,
        `Urbains 2–3 min (${((pieBuckets[1] / total) * 100).toFixed(0)}%)`,
        `Intermédiaires 3–5 min (${((pieBuckets[2] / total) * 100).toFixed(0)}%)`,
        `Longs > 5 min (${((pieBuckets[3] / total) * 100).toFixed(0)}%)`
    ];
    if (!charts.pie) {
        const ctx = document.getElementById('chart-pie-structure');
        if (ctx) {
            charts.pie = new Chart(ctx, {
                type: 'pie',
                data: {
                    labels: piePctLabels,
                    datasets: [{
                        data: pieBuckets,
                        backgroundColor: [COLORS.green, COLORS.orange, COLORS.ink, COLORS.muted],
                        borderColor: '#ffffff',
                        borderWidth: 2
                    }]
                },
                options: {
                    responsive: true,
                    maintainAspectRatio: false,
                    plugins: {
                        legend: {
                            position: 'right',
                            labels: {
                                usePointStyle: true,
                                pointStyle: 'circle',
                                boxWidth: 8,
                                padding: 14,
                                font: { family: 'Inter', size: 12 }
                            }
                        },
                        tooltip: {
                            callbacks: {
                                label: (c) => ` ${c.raw} tronçons (${((c.raw / total) * 100).toFixed(1)}%)`
                            }
                        }
                    }
                }
            });
        }
    } else {
        charts.pie.data.labels = piePctLabels;
        charts.pie.data.datasets[0].data = pieBuckets;
        charts.pie.update('none');
    }

    // 4. Graphique 3 : Histogramme vertical (Distribution détaillée des durées)
    if (!charts.bar) {
        const ctx = document.getElementById('chart-bar-durations');
        if (ctx) {
            charts.bar = new Chart(ctx, {
                type: 'bar',
                data: {
                    labels: ['< 1m30', '1m30–2m30', '2m30–3m30', '3m30–5m', '5m–7m', '> 7m'],
                    datasets: [{
                        label: 'Tronçons en base',
                        data: barBuckets,
                        backgroundColor: [
                            COLORS.green,
                            COLORS.green,
                            COLORS.orange,
                            COLORS.orange,
                            COLORS.ink,
                            COLORS.slate
                        ],
                        borderRadius: 0,
                        barPercentage: 0.68
                    }]
                },
                options: {
                    responsive: true,
                    maintainAspectRatio: false,
                    plugins: {
                        legend: { display: false },
                        tooltip: {
                            callbacks: {
                                label: (c) => ` ${c.raw} tronçons (${((c.raw / total) * 100).toFixed(1)}%)`
                            }
                        }
                    },
                    scales: {
                        x: {
                            grid: { display: false },
                            ticks: { font: { family: 'Inter', size: 11 }, color: COLORS.slate }
                        },
                        y: {
                            beginAtZero: true,
                            grid: { color: COLORS.grid },
                            ticks: { font: { family: 'Inter', size: 11 }, color: COLORS.slate }
                        }
                    }
                }
            });
        }
    } else {
        charts.bar.data.datasets[0].data = barBuckets;
        charts.bar.update('none');
    }

    // 5. Graphique 4 : Barres horizontales (Top 8 des hubs les plus connectés)
    const topHubs = [...degreeByStation.entries()]
        .sort((a, b) => b[1] - a[1])
        .slice(0, 8);

    const hubLabels = topHubs.map(([id]) => stationsById.get(id) || id);
    const hubCounts = topHubs.map(([, count]) => count);

    if (!charts.hubs) {
        const ctx = document.getElementById('chart-hbar-hubs');
        if (ctx) {
            charts.hubs = new Chart(ctx, {
                type: 'bar',
                data: {
                    labels: hubLabels,
                    datasets: [{
                        label: 'Connexions sortantes',
                        data: hubCounts,
                        backgroundColor: COLORS.orange,
                        hoverBackgroundColor: COLORS.green,
                        borderRadius: 0,
                        barThickness: 18
                    }]
                },
                options: {
                    indexAxis: 'y',
                    responsive: true,
                    maintainAspectRatio: false,
                    plugins: {
                        legend: { display: false },
                        tooltip: {
                            callbacks: {
                                label: (c) => ` ${c.raw} tronçons (${((c.raw / total) * 100).toFixed(1)}% du réseau)`
                            }
                        }
                    },
                    scales: {
                        x: {
                            beginAtZero: true,
                            grid: { color: COLORS.grid },
                            ticks: { stepSize: 1, font: { family: 'Inter', size: 11 }, color: COLORS.slate }
                        },
                        y: {
                            grid: { display: false },
                            ticks: { font: { family: 'Inter', size: 11 }, color: COLORS.ink }
                        }
                    }
                }
            });
        }
    } else {
        charts.hubs.data.labels = hubLabels;
        charts.hubs.data.datasets[0].data = hubCounts;
        charts.hubs.update('none');
    }
}