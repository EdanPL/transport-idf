document.addEventListener('DOMContentLoaded', () => {
    const current = window.location.pathname.split('/').pop() || 'index.html';

    const popover = document.createElement('div');
    popover.className = 'ios-popover-sheet hidden';
    popover.id = 'ios-more-popover';
    popover.innerHTML = `
        <a href="escale.html" class="ios-popover-link ${current === 'escale.html' ? 'active' : ''}">
            <span>Escale calibrée</span>
        </a>
        <a href="rencontre.html" class="ios-popover-link ${current === 'rencontre.html' ? 'active' : ''}">
            <span>Point de rencontre</span>
        </a>
    `;

    const isMoreActive = current === 'escale.html' || current === 'rencontre.html';

    const dock = document.createElement('nav');
    dock.className = 'ios-dock-wrapper';
    dock.setAttribute('aria-label', 'Navigation mobile');
    dock.innerHTML = `
        <div class="ios-glass-pill">
            <a href="index.html" class="ios-dock-item ${current === 'index.html' ? 'active' : ''}">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor"><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/></svg>
                <span>Direct</span>
            </a>
            <a href="trajet.html" class="ios-dock-item ${current === 'trajet.html' ? 'active' : ''}">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor"><path d="M4 19L20 5M20 5H9M20 5v11"/></svg>
                <span>Trajet</span>
            </a>
            <a href="radar.html" class="ios-dock-item ${current === 'radar.html' ? 'active' : ''}">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor"><circle cx="12" cy="12" r="9"/><circle cx="12" cy="12" r="4"/><path d="M12 3v3"/></svg>
                <span>Radar</span>
            </a>
            <button type="button" id="btn-ios-more" class="ios-dock-item ${isMoreActive ? 'active' : ''}">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor"><circle cx="6" cy="12" r="1.5"/><circle cx="12" cy="12" r="1.5"/><circle cx="18" cy="12" r="1.5"/></svg>
                <span>Modes</span>
            </button>
        </div>

        <a href="bouclier.html" class="ios-glass-orb ${current === 'bouclier.html' ? 'active' : ''}" aria-label="Bouclier Anti-Galère">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.1">
                <path d="M12 3l7 3v6c0 5-3.5 8-7 9-3.5-1-7-4-7-9V6l7-3z"/>
            </svg>
            <span>Bouclier</span>
        </a>
    `;

    document.body.appendChild(popover);
    document.body.appendChild(dock);

    const moreBtn = document.getElementById('btn-ios-more');
    moreBtn?.addEventListener('click', (e) => {
        e.stopPropagation();
        popover.classList.toggle('hidden');
    });

    document.addEventListener('click', (e) => {
        if (!popover.contains(e.target) && e.target !== moreBtn) {
            popover.classList.add('hidden');
        }
    });
});