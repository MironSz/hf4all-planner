// Shared tooltip popup, dynamically positioned to stay on-screen.
// Pseudo-element approach was abandoned because CSS cannot read the
// popup's own width to flip alignment near edges.
export function initTooltip() {
    const popup = document.createElement('div');
    popup.id = 'tip-popup';
    document.body.appendChild(popup);

    function show(tip) {
        const text = tip.getAttribute('data-tip') || '';
        if (!text) return;
        popup.textContent = text;
        popup.style.display = 'block';
        // Measure popup AFTER content is set so getBoundingClientRect is accurate
        const tipR = tip.getBoundingClientRect();
        const popR = popup.getBoundingClientRect();
        const vw = window.innerWidth;
        const vh = window.innerHeight;
        const margin = 4;
        const gap = 6;
        // Default: centred horizontally on tip, opening downward.
        let left = tipR.left + tipR.width / 2 - popR.width / 2;
        let top  = tipR.bottom + gap;
        // Flip upward if the popup would clip the viewport bottom AND there's
        // more room above the tip.
        if (top + popR.height > vh - margin) {
            const aboveTop = tipR.top - gap - popR.height;
            if (aboveTop >= margin) top = aboveTop;
        }
        // Clamp into viewport horizontally.
        if (left < margin) left = margin;
        if (left + popR.width > vw - margin) left = vw - margin - popR.width;
        // Clamp vertically as a final safety net.
        if (top < margin) top = margin;
        if (top + popR.height > vh - margin) top = vh - margin - popR.height;
        popup.style.left = left + 'px';
        popup.style.top  = top  + 'px';
    }
    function hide() { popup.style.display = 'none'; }

    document.addEventListener('mouseover', (e) => {
        const tip = e.target.closest && e.target.closest('.tip');
        if (tip) show(tip);
    });
    document.addEventListener('mouseout', (e) => {
        const tip = e.target.closest && e.target.closest('.tip');
        if (tip) hide();
    });
    window.addEventListener('scroll', hide, true);
    window.addEventListener('resize', hide);
}
