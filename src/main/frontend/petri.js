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

  const chatPanel = document.querySelector('[data-chat-id]');
  if (chatPanel?.dataset.chatId) {
    const chatId = chatPanel.dataset.chatId;
    const atBottom = () => !scroller || scroller.scrollHeight - scroller.scrollTop - scroller.clientHeight < 80;
    const ensureOutput = (runId) => {
      let output = document.querySelector(`[data-run-output="${runId}"]`);
      if (output) return output;
      const shell = document.createElement('article');
      shell.className = 'mr-auto max-w-3xl rounded-2xl rounded-bl-md border border-ink-700 bg-ink-800 px-5 py-4';
      shell.innerHTML = '<div class="mb-3 text-xs font-bold uppercase tracking-wider text-brand-400">Petri · writing…</div>';
      output = document.createElement('p');
      output.className = 'whitespace-pre-wrap break-words text-sm leading-6 text-ink-300';
      output.dataset.runOutput = runId;
      shell.append(output);
      scroller?.append(shell);
      return output;
    };
    const stream = new EventSource(`/chat/${chatId}/stream`);
    stream.addEventListener('assistant-delta', event => {
      try {
        const value = JSON.parse(event.data);
        const follow = atBottom();
        ensureOutput(value.runId).textContent += value.delta;
        if (follow && scroller) scroller.scrollTop = scroller.scrollHeight;
      } catch { /* ignore malformed stream data; state polling recovers */ }
    });
    const timer = window.setInterval(async () => {
      if (document.hidden) return;
      try {
        const response = await fetch(`/chat/${chatId}/state`, { headers: { Accept: 'application/json' }, cache: 'no-store' });
        if (!response.ok) return;
        const state = await response.json();
        const signature = JSON.stringify(state.runs.map(run => [run.id, run.status, run.turns.length, run.partialOutput]));
        if (chatPanel.dataset.chatSignature && chatPanel.dataset.chatSignature !== signature) window.location.reload();
        chatPanel.dataset.chatSignature = signature;
      } catch { /* EventSource reconnects and polling retries */ }
    }, 1200);
    window.addEventListener('beforeunload', () => { window.clearInterval(timer); stream.close(); }, { once: true });
  }

  document.querySelectorAll('[data-model-provider]').forEach(provider => {
    const form = provider.closest('form');
    const updateModelControls = () => {
      const option = provider.selectedOptions[0];
      if (!option) return;
      const modelInput = form?.querySelector('[data-model-input]');
      const tokenInput = form?.querySelector('[data-token-input]');
      const reasoningInput = form?.querySelector('[data-reasoning-input]');
      const verbosityInput = form?.querySelector('[data-verbosity-input]');
      const contextInput = form?.querySelector('[data-context-input]');
      if (modelInput) modelInput.value = option.dataset.model || '';
      if (tokenInput) tokenInput.value = option.dataset.tokens || '';
      if (reasoningInput) reasoningInput.value = option.dataset.reasoning || '';
      if (verbosityInput) verbosityInput.value = option.dataset.verbosity || '';
      if (contextInput) contextInput.value = option.dataset.context || '';
      const summary = form?.querySelector('[data-model-summary]');
      if (summary) {
        summary.textContent = `${option.textContent} · ${option.dataset.model} · ${option.dataset.tokens} output tokens · approvals enabled`;
      }
    };
    provider.addEventListener('change', updateModelControls);
    updateModelControls();
  });
});

window.Alpine = Alpine;
Alpine.start();
