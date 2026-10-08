import Alpine from 'alpinejs';
import { Bot, Boxes, GitBranch, KeyRound, LogOut, Moon, ShieldCheck, Sun } from 'lucide';

const icons = { Bot, Boxes, GitBranch, KeyRound, LogOut, Moon, ShieldCheck, Sun };
const storedTheme = (() => {
  try { return localStorage.getItem('petri-theme'); } catch { return null; }
})();
const initialTheme = storedTheme || (matchMedia('(prefers-color-scheme: light)').matches ? 'light' : 'dark');

function applyTheme(theme) {
  document.documentElement.dataset.theme = theme;
  document.documentElement.classList.toggle('light', theme === 'light');
}

applyTheme(initialTheme);

Alpine.store('theme', {
  mode: initialTheme,
  toggle() {
    this.mode = this.mode === 'dark' ? 'light' : 'dark';
    applyTheme(this.mode);
    try { localStorage.setItem('petri-theme', this.mode); } catch { /* page-local fallback */ }
  }
});

document.addEventListener('DOMContentLoaded', () => {
  document.querySelectorAll('[data-lucide]').forEach(element => {
    const icon = icons[element.dataset.lucide];
    if (!icon) return;
    const wrapper = document.createElement('span');
    wrapper.innerHTML = icon.toSvg({ width: 18, height: 18, 'stroke-width': 1.8 });
    element.replaceWith(wrapper.firstElementChild);
  });
});

window.Alpine = Alpine;
Alpine.start();

