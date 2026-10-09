import Alpine from 'alpinejs';
import {
  ArrowLeft,
  Bot,
  Boxes,
  CircleCheck,
  Eye,
  FileCode2,
  FolderGit2,
  GitBranch,
  History,
  KeyRound,
  LockKeyhole,
  LogOut,
  MessageSquare,
  Moon,
  Plus,
  RefreshCw,
  Save,
  Send,
  Settings,
  ShieldCheck,
  Square,
  Sun,
  Trash2,
  Workflow
} from 'lucide';

const icons = {
  ArrowLeft,
  Bot,
  Boxes,
  CircleCheck,
  Eye,
  FileCode2,
  FolderGit2,
  GitBranch,
  History,
  KeyRound,
  LockKeyhole,
  LogOut,
  MessageSquare,
  Moon,
  Plus,
  RefreshCw,
  Save,
  Send,
  Settings,
  ShieldCheck,
  Square,
  Sun,
  Trash2,
  Workflow
};
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

  const chat = document.querySelector('[data-chat-run]');
  const scroller = document.querySelector('[data-chat-scroll]');
  if (scroller) scroller.scrollTop = scroller.scrollHeight;
  if (chat?.dataset.chatRun) {
    const active = ['PENDING', 'RUNNING', 'CANCELLATION_REQUESTED'];
    const currentStatus = chat.dataset.chatStatus;
    const currentTurns = Number(chat.dataset.chatTurnCount || 0);
    if (active.includes(currentStatus)) {
      const timer = window.setInterval(async () => {
        if (document.hidden) return;
        try {
          const response = await fetch(`/chat/runs/${chat.dataset.chatRun}/state`, {
            headers: { Accept: 'application/json' },
            cache: 'no-store'
          });
          if (!response.ok) return;
          const state = await response.json();
          if (state.status !== currentStatus || state.turns.length !== currentTurns) {
            window.clearInterval(timer);
            window.location.reload();
          }
        } catch { /* retry while the page remains open */ }
      }, 1200);
    }
  }

  const provider = document.querySelector('[data-model-provider]');
  if (provider) {
    const updateModelControls = () => {
      const option = provider.selectedOptions[0];
      if (!option) return;
      const modelInput = document.querySelector('[data-model-input]');
      const tokenInput = document.querySelector('[data-token-input]');
      const reasoningInput = document.querySelector('[data-reasoning-input]');
      const verbosityInput = document.querySelector('[data-verbosity-input]');
      if (modelInput) modelInput.value = option.dataset.model || '';
      if (tokenInput) tokenInput.value = option.dataset.tokens || '';
      if (reasoningInput) reasoningInput.value = option.dataset.reasoning || '';
      if (verbosityInput) verbosityInput.value = option.dataset.verbosity || '';
      const summary = document.querySelector('[data-model-summary]');
      if (summary) {
        summary.textContent = `${option.textContent} · ${option.dataset.model} · ${option.dataset.tokens} output tokens · approvals enabled`;
      }
    };
    provider.addEventListener('change', updateModelControls);
    updateModelControls();
  }
});

window.Alpine = Alpine;
Alpine.start();
