/* ── palazikVPN — main.js ─────────────────────────────────────────────── */

/* ── Theme Toggle ──────────────────────────────────────────────────────── */
(function () {
  const root = document.documentElement;
  const STORAGE_KEY = 'palazikVPN-theme';

  function applyTheme(theme) {
    root.setAttribute('data-theme', theme);
    const icon = document.getElementById('theme-icon');
    if (icon) icon.textContent = theme === 'dark' ? 'light_mode' : 'dark_mode';
    localStorage.setItem(STORAGE_KEY, theme);
  }

  function toggleTheme() {
    const current = root.getAttribute('data-theme') || 'light';
    applyTheme(current === 'dark' ? 'light' : 'dark');
  }

  // Apply saved or system preference on load
  const saved = localStorage.getItem(STORAGE_KEY);
  const prefersDark = window.matchMedia('(prefers-color-scheme: dark)').matches;
  applyTheme(saved || (prefersDark ? 'dark' : 'light'));

  window.toggleTheme = toggleTheme;
})();

/* ── Navigation Scroll Shadow ──────────────────────────────────────────── */
(function () {
  const nav = document.getElementById('nav');
  if (!nav) return;
  const onScroll = () => nav.classList.toggle('scrolled', window.scrollY > 10);
  window.addEventListener('scroll', onScroll, { passive: true });
  onScroll();
})();

/* ── Mobile Hamburger ──────────────────────────────────────────────────── */
(function () {
  const btn = document.getElementById('hamburger');
  const menu = document.getElementById('mobile-menu');
  if (!btn || !menu) return;
  btn.addEventListener('click', () => {
    const open = menu.classList.toggle('open');
    btn.setAttribute('aria-expanded', open);
  });
  // Close on nav link click
  menu.querySelectorAll('a').forEach(a => {
    a.addEventListener('click', () => {
      menu.classList.remove('open');
      btn.setAttribute('aria-expanded', 'false');
    });
  });
})();

/* ── Particle System ───────────────────────────────────────────────────── */
(function () {
  const container = document.getElementById('particles');
  if (!container) return;
  const count = 18;
  for (let i = 0; i < count; i++) {
    const p = document.createElement('div');
    p.className = 'particle';
    p.style.cssText = `
      left: ${Math.random() * 100}%;
      --duration: ${6 + Math.random() * 8}s;
      --delay: ${Math.random() * 8}s;
      opacity: 0;
    `;
    container.appendChild(p);
  }
})();

/* ── Intersection Observer — Animate on Scroll ─────────────────────────── */
(function () {
  const cards = document.querySelectorAll('.feature-card, .tech-card');
  if (!cards.length) return;
  const obs = new IntersectionObserver((entries) => {
    entries.forEach(e => {
      if (e.isIntersecting) {
        e.target.classList.add('visible');
        obs.unobserve(e.target);
      }
    });
  }, { threshold: 0.12 });
  cards.forEach(c => obs.observe(c));
})();

/* ── Screens Track — Duplicate for seamless scroll ─────────────────────── */
(function () {
  const track = document.getElementById('screens-track');
  if (!track) return;
  // Clone the cards so the two halves are identical — the CSS marquee loops with
  // translateX(-50%), which only looks seamless when the second half matches the first.
  track.innerHTML += track.innerHTML;
})();
