import Alpine from 'alpinejs';
import { hydrateIcons } from './icons';
import { initializeWorkspace } from './workspace';
import { hydrateMarkdown, scheduleMarkdown } from './markdown';

const storedTheme = (() => {
  try { return localStorage.getItem('petri-theme'); } catch { return null; }
})();
const initialTheme = storedTheme || (matchMedia('(prefers-color-scheme: light)').matches ? 'light' : 'dark');

function applyTheme(theme) {
  document.documentElement.dataset.theme = theme;
  document.documentElement.classList.toggle('light', theme === 'light');
  window.dispatchEvent(new CustomEvent('petri-theme-change', { detail: { theme } }));
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

function initializeDialogs() {
  document.addEventListener('click', event => {
    const opener = event.target.closest?.('[data-dialog-open]');
    if (opener) {
      document.getElementById(opener.dataset.dialogOpen)?.showModal();
      return;
    }
    // A click that lands on the <dialog> element itself is a click on its backdrop.
    if (event.target instanceof HTMLDialogElement && event.target.classList.contains('dialog')) event.target.close();
  });
  document.addEventListener('submit', event => {
    const message = event.target.dataset?.confirm;
    if (message && !window.confirm(message)) event.preventDefault();
  });
  // Server-side validation re-renders the page; reopen the dialog that holds the failing form.
  document.querySelectorAll('dialog.dialog').forEach(dialog => {
    if (dialog.hasAttribute('data-open-on-load') || dialog.querySelector('.field-error, .alert-error')) dialog.showModal();
  });
  document.querySelectorAll('[data-copy-target]').forEach(button => button.addEventListener('click', async () => {
    const source = document.getElementById(button.dataset.copyTarget);
    if (!source) return;
    try {
      await navigator.clipboard.writeText(source.value ?? source.textContent);
      const label = button.querySelector('[data-copy-label]');
      if (label) { label.textContent = 'Copied'; setTimeout(() => { label.textContent = 'Copy'; }, 1500); }
    } catch { source.select?.(); }
  }));
}

function localizeTimes(scope = document) {
  const format = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' });
  const dateOnly = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium' });
  scope.querySelectorAll('time[data-local]').forEach(element => {
    const value = new Date(element.getAttribute('datetime'));
    if (Number.isNaN(value.getTime())) return;
    element.textContent = (element.dataset.local === 'date' ? dateOnly : format).format(value);
    element.title = value.toISOString();
  });
}

function bootPetri() {
  hydrateIcons();
  initializeDialogs();
  localizeTimes();
  hydrateMarkdown(document);

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

  const legacyChatPanel = document.querySelector('[data-chat-id]');
  const chatPanel = legacyChatPanel?.closest('[data-workspace]') ? null : legacyChatPanel;
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
        const output = ensureOutput(value.runId);
        scheduleMarkdown(output, (output.dataset.raw ?? output.textContent ?? '') + value.delta);
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

  initializeWorkspace();
}

if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', bootPetri, { once: true });
else bootPetri();

window.Alpine = Alpine;
Alpine.start();
