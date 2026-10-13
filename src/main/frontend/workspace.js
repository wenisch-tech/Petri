import { hydrateIcons, renderIcon } from './icons';
import { renderNet, goalStatusLabels, stageLabel } from './petrinet';
import { applyMarkdown, scheduleMarkdown } from './markdown';

const DB = 'petri-workspace', STORE = 'worktree-state';
const csrfHeaders = () => { const token = document.querySelector('meta[name="_csrf"]')?.content; const header = document.querySelector('meta[name="_csrf_header"]')?.content; return token && header ? { [header]: token } : {}; };
const monacoLanguageFor = path => {
  const name = path.split('/').at(-1)?.toLowerCase() || '';
  const ext = name.includes('.') ? name.split('.').at(-1) : '';
  if (ext === 'java') return 'java';
  if (['js', 'mjs', 'cjs', 'jsx'].includes(ext)) return 'javascript';
  if (['ts', 'tsx'].includes(ext)) return 'typescript';
  if (['json', 'jsonl'].includes(ext)) return 'json';
  if (['html', 'htm'].includes(ext)) return 'html';
  if (ext === 'css') return 'css';
  if (['xml', 'svg', 'pom'].includes(ext)) return 'xml';
  if (['yaml', 'yml'].includes(ext)) return 'yaml';
  if (['md', 'markdown'].includes(ext)) return 'markdown';
  if (['sh', 'bash', 'zsh', 'fish'].includes(ext) || name === 'dockerfile' || name === 'makefile') return 'shell';
  if (ext === 'sql') return 'sql';
  return 'plaintext';
};
function el(tag, classes, value) { const node = document.createElement(tag); if (classes) node.className = classes; if (value !== undefined) node.textContent = value; return node; }
function filePresentation(path) {
  const name = path.split('/').at(-1)?.toLowerCase() || '';
  const extension = name.includes('.') ? name.split('.').at(-1) : '';
  if (['package.json', 'package-lock.json', 'pom.xml', 'build.gradle', 'build.gradle.kts'].includes(name)) return ['FileCog', 'is-config'];
  if (['dockerfile', 'makefile', 'gradlew', 'mvnw'].includes(name)) return ['FileTerminal', 'is-script'];
  if (name.startsWith('readme') || ['md', 'markdown', 'adoc', 'txt'].includes(extension)) return ['FileText', 'is-document'];
  if (['java', 'kt', 'kts', 'groovy'].includes(extension)) return ['FileCode2', 'is-java'];
  if (['js', 'mjs', 'cjs', 'jsx'].includes(extension)) return ['FileBraces', 'is-javascript'];
  if (['ts', 'tsx'].includes(extension)) return ['FileBraces', 'is-typescript'];
  if (['json', 'jsonl'].includes(extension)) return ['FileJson', 'is-json'];
  if (['html', 'htm', 'xml', 'svg'].includes(extension)) return ['FileCode2', 'is-markup'];
  if (['css', 'scss', 'sass', 'less'].includes(extension)) return ['FileType2', 'is-style'];
  if (['yml', 'yaml', 'toml', 'properties', 'conf', 'ini', 'env'].includes(extension) || name.startsWith('.')) return ['FileCog', 'is-config'];
  if (['png', 'jpg', 'jpeg', 'gif', 'webp', 'ico', 'bmp'].includes(extension)) return ['FileImage', 'is-image'];
  if (['zip', 'jar', 'war', 'tar', 'gz', '7z'].includes(extension)) return ['FileArchive', 'is-archive'];
  if (['sql', 'db', 'sqlite'].includes(extension)) return ['Database', 'is-database'];
  if (['csv', 'tsv', 'xls', 'xlsx'].includes(extension)) return ['FileSpreadsheet', 'is-sheet'];
  if (['sh', 'bash', 'zsh', 'fish', 'ps1', 'bat'].includes(extension)) return ['FileTerminal', 'is-script'];
  return ['File', 'is-generic'];
}
function fileIcon(path) { const [name, color] = filePresentation(path); return renderIcon(name, { class: `workspace-file-icon ${color}` }); }
async function request(url, options = {}) { const response = await fetch(url, { cache: 'no-store', headers: { Accept: 'application/json', ...options.headers }, ...options }); if (!response.ok) { let message = `Request failed (${response.status})`; try { message = (await response.json()).detail || message; } catch {} const error = new Error(message); error.status = response.status; throw error; } return response.status === 204 ? null : response.json(); }
function store() { return new Promise((resolve, reject) => { const open = indexedDB.open(DB, 1); open.onupgradeneeded = () => open.result.createObjectStore(STORE); open.onsuccess = () => resolve(open.result); open.onerror = () => reject(open.error); }); }
async function localRead(key) { try { const db = await store(); return await new Promise((resolve, reject) => { const get = db.transaction(STORE).objectStore(STORE).get(key); get.onsuccess = () => resolve(get.result || { tabs: [], drafts: {} }); get.onerror = () => reject(get.error); }); } catch { return { tabs: [], drafts: {} }; } }
async function localWrite(key, value) { try { const db = await store(); await new Promise((resolve, reject) => { const put = db.transaction(STORE, 'readwrite').objectStore(STORE).put(value, key); put.onsuccess = resolve; put.onerror = () => reject(put.error); }); } catch {} }

export function initializeWorkspace() {
  const root = document.querySelector('[data-workspace]');
  if (!root || root.dataset.workspaceInitialized === 'true') return;
  root.dataset.workspaceInitialized = 'true';
  const worktreeId = root.dataset.worktreeId;
  const state = {
    root, projectId: root.dataset.projectId, worktreeId, api: worktreeId ? `/workspace/api/worktrees/${worktreeId}` : null,
    branch: root.dataset.branch || null, detached: root.dataset.detached === 'true', gitStatus: null,
    changes: new Map(), mode: 'files', tabs: [], active: null, drafts: {}, chatId: root.dataset.chatId || null,
    snapshot: null, monaco: null, monacoPromise: null, models: new Map(), diffModels: new Map(),
    editor: null, diffEditor: null, editorToken: 0, resizeObserver: null
  };
  const start = (name, initializer, report) => {
    try { initializer(); }
    catch (error) {
      console.error(`Petri ${name} initialization failed`, error);
      const target = root.querySelector(report);
      if (target) target.textContent = `${name} could not start: ${error.message}`;
    }
  };
  start('layout', () => initializeLayout(state), '[data-editor-status]');
  start('git', () => initializeGit(state), '[data-git-indicators]');
  start('views', () => initializeEmbeddedViews(state), '[data-workspace-embedded]');
  if (!worktreeId) return;
  const key = `project:${state.projectId}:worktree:${worktreeId}`;
  try { localStorage.setItem(`petri:last-worktree:${state.projectId}`, worktreeId); } catch {}
  start('editor', () => initializeEditor(state, key), '[data-editor-status]');
  start('explorer', () => initializeExplorer(state), '[data-file-tree]');
  start('changes', () => initializeChanges(state), '[data-change-count]');
  start('chat', () => initializeChat(state), '[data-chat-status]');
  localRead(key).then(value => { state.local = value; state.tabs = (value.tabs || []).slice(0, 12); state.drafts = value.drafts || {}; if (root.dataset.selectedFile) openFile(state, root.dataset.selectedFile); else if (state.tabs[0]) openFile(state, state.tabs[0]); });
}

// Project pages (automations, settings) open in place of the explorer and editor. The standalone
// pages keep working; this loads the same content and keeps forms and links inside the workspace.
function initializeEmbeddedViews(state) {
  const panel = state.root.querySelector('[data-workspace-embedded]');
  const content = panel?.querySelector('[data-workspace-embedded-content]');
  if (!panel || !content) return;
  const base = `/projects/${state.projectId}`;
  const urls = { automations: `${base}/automations`, runs: `${base}/runs`, settings: `${base}/settings` };
  const titles = { automations: 'Automations', runs: 'Runs', settings: 'Settings' };
  const runPath = /^\/runs\/([0-9a-fA-F-]{36})$/;
  const buttons = [...document.querySelectorAll('.workspace-view-tab[data-workspace-view]')];
  const gitBar = document.querySelector('[data-git-bar]');
  const trail = document.querySelector('[data-breadcrumb-trail]');
  // `current` is the project view; `detail` is the run shown inside the Runs view, if any.
  let current = null;
  let detail = null;
  let currentSearch = '';

  const separator = () => el('span', 'text-ink-700', '/');
  // Breadcrumb after the project name: "Workspace", "Runs" or "Runs / Run 1a2b3c4d".
  const renderCrumbs = () => {
    if (!trail) return;
    const items = [];
    if (!current) items.push({ label: 'Workspace' });
    else if (detail) {
      items.push({ label: titles[current], view: current });
      items.push({ label: `Run ${detail.slice(0, 8)}` });
    } else items.push({ label: titles[current] });
    trail.replaceChildren();
    items.forEach((item, index) => {
      if (index > 0) trail.append(separator());
      if (item.view) {
        const link = el('a', 'text-ink-500 transition hover:text-ink-100', item.label);
        link.href = urls[item.view];
        link.dataset.crumbView = item.view;
        trail.append(link);
      } else trail.append(el('span', 'truncate font-medium text-ink-100', item.label));
    });
  };
  const markActive = () => {
    buttons.forEach(button => {
      const active = button.dataset.workspaceView === (current || 'workspace');
      button.classList.toggle('is-active', active);
      button.setAttribute('aria-selected', String(active));
    });
    gitBar?.classList.toggle('is-page-view', Boolean(current));
    renderCrumbs();
  };
  const setUrl = () => {
    try {
      const url = new URL(location.href);
      if (current) url.searchParams.set('view', current); else url.searchParams.delete('view');
      if (current && detail) url.searchParams.set('run', detail); else url.searchParams.delete('run');
      history.replaceState(null, '', url);
    } catch { /* the address bar is cosmetic */ }
  };
  const pagePath = () => (detail ? `/runs/${detail}` : urls[current]);
  const close = () => {
    clearTimeout(liveTimer);
    current = null;
    detail = null;
    currentSearch = '';
    panel.hidden = true;
    state.root.classList.remove('is-embedded-view');
    content.replaceChildren();
    markActive();
    setUrl();
    // The editor was hidden while the page was open and must measure itself again.
    requestAnimationFrame(() => window.dispatchEvent(new Event('resize')));
  };
  const render = html => {
    const doc = new DOMParser().parseFromString(html, 'text/html');
    const nodes = [...doc.querySelectorAll('main.page-narrow, main.page, dialog')]
      .filter(node => !node.parentElement?.closest('dialog'));
    content.replaceChildren(...nodes.map(node => document.importNode(node, true)));
    hydrateIcons(content);
    // Server-side validation re-renders with the failing dialog; reopen it.
    content.querySelectorAll('dialog.dialog').forEach(dialog => {
      if (dialog.querySelector('.field-error, .alert-error')) dialog.showModal();
    });
  };
  let liveTimer = null;
  // A page that marks itself live (an agent run, automation runs in progress) reloads in place
  // every few seconds, keeping scroll position and open sections, unless the user is editing.
  const scheduleLive = () => {
    clearTimeout(liveTimer);
    if (!current || !content.querySelector('[data-live-refresh]')) return;
    const token = `${current}|${detail}`;
    liveTimer = setTimeout(async () => {
      if (token !== `${current}|${detail}` || document.hidden
          || content.querySelector('dialog[open]') || content.contains(document.activeElement)
          && /^(INPUT|TEXTAREA|SELECT)$/.test(document.activeElement.tagName)) {
        scheduleLive();
        return;
      }
      const scroll = content.scrollTop;
      const open = [...content.querySelectorAll('details')].map(node => node.open);
      await load(true);
      if (token !== `${current}|${detail}`) return;
      content.querySelectorAll('details').forEach((node, index) => { if (open[index]) node.open = true; });
      content.scrollTop = scroll;
    }, 3000);
  };
  const load = async (silent = false) => {
    const token = `${current}|${detail}`;
    if (!silent) content.setAttribute('aria-busy', 'true');
    try {
      const extra = currentSearch ? `&${currentSearch.replace(/^\?/, '')}` : '';
      const response = await fetch(`${pagePath()}?embedded=true${extra}`,
        { headers: { Accept: 'text/html' }, cache: 'no-store', credentials: 'same-origin' });
      if (!response.ok) throw new Error(`The page could not be loaded (HTTP ${response.status})`);
      const html = await response.text();
      if (token === `${current}|${detail}`) { render(html); scheduleLive(); }
    } catch (error) {
      if (token === `${current}|${detail}`) {
        if (silent) scheduleLive();
        else content.replaceChildren(el('div', 'workspace-error', error.message));
      }
    } finally { content.removeAttribute('aria-busy'); }
  };
  const show = (name, runId = null, search = '') => {
    if (name === 'workspace' || !name) { if (current) close(); return; }
    if (!urls[name]) return;
    current = name;
    detail = runId;
    currentSearch = search;
    panel.hidden = false;
    state.root.classList.add('is-embedded-view');
    markActive();
    content.replaceChildren(el('div', 'workspace-loading', 'Loading…'));
    setUrl();
    load();
  };

  buttons.forEach(button => button.addEventListener('click', event => {
    if (event.metaKey || event.ctrlKey || event.shiftKey || event.button === 1) return;
    event.preventDefault();
    const name = button.dataset.workspaceView;
    if (name === (current || 'workspace') && !detail) return;
    show(name);
  }));
  // The project name and the breadcrumb trail navigate inside the workspace as well.
  document.querySelector('[data-crumb-workspace]')?.addEventListener('click', event => {
    event.preventDefault();
    close();
  });
  trail?.addEventListener('click', event => {
    const link = event.target.closest?.('a[data-crumb-view]');
    if (!link) return;
    event.preventDefault();
    show(link.dataset.crumbView);
  });

  // Links of the embedded pages switch the view (or open a run) instead of leaving the workspace.
  content.addEventListener('click', event => {
    const link = event.target.closest?.('a[href]');
    if (!link || link.target === '_blank' || event.metaKey || event.ctrlKey) return;
    const url = new URL(link.href, location.href);
    if (url.origin !== location.origin) return;
    const name = Object.keys(urls).find(key => urls[key] === url.pathname);
    const run = runPath.exec(url.pathname);
    if (name) {
      event.preventDefault();
      show(name, null, url.search);
    } else if (run) {
      event.preventDefault();
      show('runs', run[1]);
    } else if (url.pathname === '/runs') {
      event.preventDefault();
      show('runs');
    } else if (url.pathname === `/workspace/projects/${state.projectId}`) {
      event.preventDefault();
      close();
    }
  });

  // Forms post in the background and the page is refreshed in place, flash message included.
  content.addEventListener('submit', async event => {
    const form = event.target;
    if (!(form instanceof HTMLFormElement) || !current) return;
    if ((form.getAttribute('method') || 'get').toLowerCase() !== 'post') return;
    if (event.submitter?.getAttribute('formmethod') === 'dialog') return;
    const message = form.dataset.confirm;
    event.preventDefault();
    event.stopPropagation();
    if (message && !window.confirm(message)) return;
    const token = `${current}|${detail}`;
    const submitter = event.submitter;
    if (submitter) submitter.disabled = true;
    try {
      const response = await fetch(form.action, {
        method: 'POST', body: new URLSearchParams(new FormData(form)), redirect: 'manual', credentials: 'same-origin',
        headers: { Accept: 'text/html' }
      });
      if (token !== `${current}|${detail}`) return;
      if (response.type === 'opaqueredirect' || response.status === 0 || response.redirected) await load();
      else if (response.ok) render(await response.text());
      else throw new Error(`The change could not be saved (HTTP ${response.status})`);
    } catch (error) {
      content.prepend(el('div', 'alert alert-error mx-4 mt-4', error.message));
    } finally { if (submitter?.isConnected) submitter.disabled = false; }
  });

  const params = new URLSearchParams(location.search);
  const requested = params.get('view');
  const requestedRun = params.get('run');
  if (requested && urls[requested]) show(requested, runPath.test(`/runs/${requestedRun}`) ? requestedRun : null);
  else renderCrumbs();
}

function initializeLayout(state) {
  document.querySelector('[data-toggle-chat]')?.addEventListener('click', () => state.root.classList.toggle('chat-collapsed'));
  initializeResizers(state);
  initializeTerminal(state);
}

const resizeLimits = {
  explorer: () => [180, Math.min(600, innerWidth * 0.4)],
  chat: () => [320, Math.min(1000, innerWidth * 0.65)]
};

function initializeResizers(state) {
  state.root.querySelectorAll('[data-resize]').forEach(handle => {
    const kind = handle.dataset.resize;
    const limits = () => (resizeLimits[kind] || (() => [180, 600]))();
    const setWidth = width => {
      const [min, max] = limits();
      state.root.style.setProperty(`--${kind}-width`, `${Math.round(Math.max(min, Math.min(max, width)))}px`);
    };
    const currentWidth = () => parseFloat(getComputedStyle(state.root).getPropertyValue(`--${kind}-width`)) || 0;
    const save = () => {
      try { localStorage.setItem(`petri:workspace:${kind}:width`, getComputedStyle(state.root).getPropertyValue(`--${kind}-width`).trim()); } catch {}
    };
    try {
      const stored = parseFloat(localStorage.getItem(`petri:workspace:${kind}:width`) || '');
      if (Number.isFinite(stored)) setWidth(stored);
    } catch {}
    handle.addEventListener('pointerdown', event => {
      event.preventDefault();
      handle.setPointerCapture(event.pointerId);
      handle.classList.add('is-dragging');
      document.body.style.userSelect = 'none';
      const move = pointer => setWidth(kind === 'explorer' ? pointer.clientX : innerWidth - pointer.clientX);
      const done = () => {
        handle.classList.remove('is-dragging');
        document.body.style.userSelect = '';
        handle.removeEventListener('pointermove', move);
        save();
      };
      handle.addEventListener('pointermove', move);
      handle.addEventListener('pointerup', done, { once: true });
      handle.addEventListener('pointercancel', done, { once: true });
    });
    handle.addEventListener('keydown', event => {
      const step = event.shiftKey ? 60 : 20;
      const direction = kind === 'explorer' ? 1 : -1;
      if (event.key === 'ArrowLeft') { setWidth(currentWidth() - direction * step); save(); event.preventDefault(); }
      if (event.key === 'ArrowRight') { setWidth(currentWidth() + direction * step); save(); event.preventDefault(); }
    });
    handle.addEventListener('dblclick', () => {
      state.root.style.removeProperty(`--${kind}-width`);
      try { localStorage.removeItem(`petri:workspace:${kind}:width`); } catch {}
    });
  });
}

function initializeTerminal(state) {
  const panel = state.root.querySelector('[data-terminal]');
  if (!panel) return;
  const resizer = state.root.querySelector('[data-terminal-resizer]');
  const output = panel.querySelector('[data-terminal-output]');
  const form = panel.querySelector('[data-terminal-form]');
  const input = panel.querySelector('[data-terminal-input]');
  const toggle = state.root.querySelector('[data-terminal-toggle]');
  const history = [];
  let cursor = 0;
  let busy = false;
  const setOpen = open => {
    panel.hidden = !open;
    resizer.hidden = !open;
    toggle?.classList.toggle('is-active', open);
    try { localStorage.setItem('petri:workspace:terminal:open', open ? '1' : '0'); } catch {}
    if (open) { requestAnimationFrame(() => input.focus()); }
    requestAnimationFrame(() => layoutEditor(state));
  };
  const setHeight = height => {
    const max = Math.max(120, state.root.clientHeight - 160);
    panel.style.setProperty('--terminal-height', `${Math.round(Math.max(96, Math.min(max, height)))}px`);
  };
  try {
    const stored = parseFloat(localStorage.getItem('petri:workspace:terminal:height') || '');
    if (Number.isFinite(stored)) setHeight(stored);
    if (localStorage.getItem('petri:workspace:terminal:open') !== '0') setOpen(true);
  } catch { setOpen(true); }
  toggle?.addEventListener('click', () => setOpen(panel.hidden));
  panel.querySelector('[data-terminal-close]').addEventListener('click', () => setOpen(false));
  panel.querySelector('[data-terminal-clear]').addEventListener('click', () => output.replaceChildren());
  document.addEventListener('keydown', event => {
    if (event.ctrlKey && event.key === '`') { event.preventDefault(); setOpen(panel.hidden); }
  });
  resizer.addEventListener('pointerdown', event => {
    event.preventDefault();
    resizer.setPointerCapture(event.pointerId);
    resizer.classList.add('is-dragging');
    const bottom = panel.getBoundingClientRect().bottom;
    const move = pointer => { setHeight(bottom - pointer.clientY); layoutEditor(state); };
    const done = () => {
      resizer.classList.remove('is-dragging');
      resizer.removeEventListener('pointermove', move);
      try { localStorage.setItem('petri:workspace:terminal:height', parseFloat(panel.style.getPropertyValue('--terminal-height'))); } catch {}
    };
    resizer.addEventListener('pointermove', move);
    resizer.addEventListener('pointerup', done, { once: true });
    resizer.addEventListener('pointercancel', done, { once: true });
  });
  resizer.addEventListener('keydown', event => {
    const current = panel.getBoundingClientRect().height;
    if (event.key === 'ArrowUp') { setHeight(current + 24); event.preventDefault(); }
    if (event.key === 'ArrowDown') { setHeight(current - 24); event.preventDefault(); }
  });
  const append = (command, result, failed) => {
    const entry = el('div', 'workspace-terminal-entry');
    entry.append(el('div', 'workspace-terminal-command', `$ ${command}`));
    if (result.output) entry.append(el('div', 'workspace-terminal-result', result.output));
    const meta = result.status === 'COMPLETED' ? `exit 0`
      : result.status === 'TIMED_OUT' ? 'timed out'
        : result.exitCode != null ? `exit ${result.exitCode}` : (result.status || 'failed').toLowerCase();
    entry.append(el('div', `workspace-terminal-meta${failed ? ' is-failed' : ''}`,
      `${meta}${result.outputTruncated ? ' · output truncated' : ''}`));
    output.append(entry);
    output.scrollTop = output.scrollHeight;
  };
  form.addEventListener('submit', async event => {
    event.preventDefault();
    const command = input.value.trim();
    if (!command || busy) return;
    if (!state.worktreeId) { append(command, { output: 'Prepare a branch worktree first.', status: 'ERROR' }, true); return; }
    history.push(command);
    cursor = history.length;
    input.value = '';
    busy = true;
    input.disabled = true;
    const pending = el('div', 'workspace-terminal-meta', `running: ${command}`);
    output.append(pending);
    output.scrollTop = output.scrollHeight;
    try {
      const result = await request(`/workspace/api/worktrees/${state.worktreeId}/terminal`, {
        method: 'POST', headers: { 'Content-Type': 'application/json', ...csrfHeaders() },
        body: JSON.stringify({ command })
      });
      pending.remove();
      append(command, result, result.status !== 'COMPLETED');
      loadTree(state);
      refreshChanges(state);
    } catch (error) {
      pending.remove();
      append(command, { output: error.message, status: 'ERROR' }, true);
    } finally {
      busy = false;
      input.disabled = false;
      input.focus();
    }
  });
  input.addEventListener('keydown', event => {
    if (event.key === 'ArrowUp' && history.length) { cursor = Math.max(0, cursor - 1); input.value = history[cursor]; event.preventDefault(); }
    if (event.key === 'ArrowDown' && history.length) {
      cursor = Math.min(history.length, cursor + 1);
      input.value = history[cursor] ?? '';
      event.preventDefault();
    }
  });
}

function initializeGit(state) {
  const bar = document.querySelector('[data-git-bar]');
  if (!bar) return;
  state.gitBar = bar;
  state.branchSelector = bar.querySelector('[data-branch-selector]');
  state.branchSelector?.addEventListener('change', async event => {
    const [kind, ...parts] = event.target.value.split(':');
    const name = parts.join(':');
    if (!kind || !name) return;
    try {
      const worktree = await request(`/workspace/api/projects/${state.projectId}/branches/open`, {
        method: 'POST', headers: { 'Content-Type': 'application/json', ...csrfHeaders() },
        body: JSON.stringify({ name, kind })
      });
      navigateToWorktree(state, worktree.id);
    } catch (error) { showGitError(state, error); event.target.value = state.branch ? `LOCAL:${state.branch}` : ''; }
  });
  bar.querySelector('[data-git-fetch]')?.addEventListener('click', async () => {
    await performGit(state, `/workspace/api/projects/${state.projectId}/git/fetch`, {}, branches => renderBranches(state, branches), false);
  });
  bar.querySelector('[data-git-pull]')?.addEventListener('click', () => performWorktreeGit(state, 'pull'));
  bar.querySelector('[data-git-push]')?.addEventListener('click', () => performWorktreeGit(state, 'push', { forceWithLease: false }));
  bar.querySelector('[data-git-commit]')?.addEventListener('click', () => openGitDialog(state, 'commit'));
  bar.querySelector('[data-git-rebase-action]')?.addEventListener('click', () => openGitDialog(state, 'rebase'));
  const menu = bar.querySelector('[data-git-menu]');
  bar.querySelector('[data-git-more]')?.addEventListener('click', () => { menu.hidden = !menu.hidden; });
  bar.querySelector('[data-git-pull-request]')?.addEventListener('click', () => { menu.hidden = true; openPullRequestFlow(state); });
  bar.querySelector('[data-git-new-branch]')?.addEventListener('click', () => { menu.hidden = true; openGitDialog(state, 'branch'); });
  bar.querySelector('[data-git-force-push]')?.addEventListener('click', () => { menu.hidden = true; openGitConfirmation(state, 'force-push'); });
  bar.querySelector('[data-git-delete-branch]')?.addEventListener('click', () => { menu.hidden = true; openGitConfirmation(state, 'delete-local'); });
  bar.querySelector('[data-git-delete-remote]')?.addEventListener('click', () => { menu.hidden = true; openGitConfirmation(state, 'delete-remote'); });
  bar.querySelector('[data-git-rebase-continue]')?.addEventListener('click', () => performWorktreeGit(state, 'rebase/continue'));
  bar.querySelector('[data-git-rebase-abort]')?.addEventListener('click', () => performWorktreeGit(state, 'rebase/abort'));
  wireGitDialogs(state);
  request(`/workspace/api/projects/${state.projectId}/branches`).then(branches => renderBranches(state, branches)).catch(error => showGitError(state, error));
  if (state.api) refreshGitStatus(state);
}

function navigateToWorktree(state, worktreeId) {
  const url = new URL(`/workspace/projects/${state.projectId}`, location.origin);
  url.searchParams.set('worktree', worktreeId);
  location.assign(url);
}

function renderBranches(state, branches) {
  state.branches = branches || [];
  const selector = state.branchSelector;
  if (selector) {
    selector.replaceChildren(new Option('Select branch…', ''));
    for (const [kind, label] of [['LOCAL', 'Local branches'], ['REMOTE', 'Remote branches']]) {
      const group = document.createElement('optgroup');
      group.label = label;
      for (const branch of state.branches.filter(value => value.kind === kind)) {
        const option = new Option(kind === 'REMOTE' ? `origin/${branch.name}` : branch.name, `${kind}:${branch.name}`);
        option.selected = kind === 'LOCAL' && branch.name === state.branch;
        group.append(option);
      }
      selector.append(group);
    }
  }
  for (const select of state.root.querySelectorAll('[data-git-branch-start],[data-git-rebase-target]')) {
    const includeDefault = select.matches('[data-git-rebase-target]');
    select.replaceChildren();
    if (includeDefault) select.append(new Option('Configured upstream', ''));
    for (const branch of state.branches) select.append(new Option(
      branch.kind === 'REMOTE' ? `origin/${branch.name}` : branch.name,
      branch.kind === 'REMOTE' ? `origin/${branch.name}` : branch.name));
  }
  fillDeleteTargets(state, null);
}

async function refreshGitStatus(state) {
  if (!state.api) return;
  try { applyGitStatus(state, await request(`${state.api}/git/status`)); }
  catch (error) { showGitError(state, error); }
}

function renderGitIndicators(state, status) {
  const bar = state.gitBar;
  if (!bar || !status) return;
  bar.querySelector('[data-git-sync]').textContent = `↑${status.ahead} ↓${status.behind}`;
  // Also next to the branch name in the branch selector, where it is easy to see.
  const selected = state.root.querySelector('[data-branch-selector]')?.selectedOptions?.[0];
  if (selected && selected.value) {
    if (selected.dataset.baseLabel === undefined) selected.dataset.baseLabel = selected.textContent;
    const parts = [];
    if (status.ahead > 0) parts.push(`↑${status.ahead}`);
    if (status.behind > 0) parts.push(`↓${status.behind}`);
    selected.textContent = parts.length ? `${selected.dataset.baseLabel}  ${parts.join(' ')}` : selected.dataset.baseLabel;
  }
  const dirty = bar.querySelector('[data-git-dirty]');
  dirty.textContent = status.clean ? 'Clean' : 'Changes';
  dirty.classList.toggle('is-dirty', !status.clean);
  const rebase = status.operation === 'REBASE_IN_PROGRESS';
  bar.querySelector('[data-git-rebase]').hidden = !rebase;
  bar.querySelector('[data-git-rebase-controls]').hidden = !rebase;
}

function ensureNoDrafts(state) {
  const dirty = Object.values(state.drafts || {}).some(value => value?.dirty);
  if (dirty) showGitError(state, new Error('Save or discard browser drafts before running Git actions.'));
  return !dirty;
}

async function performGit(state, url, body, success, checkDrafts = true) {
  if (checkDrafts && !ensureNoDrafts(state)) return;
  setGitBusy(state, true);
  try {
    const result = await request(url, {
      method: 'POST', headers: { 'Content-Type': 'application/json', ...csrfHeaders() },
      body: JSON.stringify(body || {})
    });
    hideGitError(state);
    success?.(result);
    return result;
  } catch (error) {
    showGitError(state, error);
    throw error;
  } finally { setGitBusy(state, false); }
}

async function performWorktreeGit(state, action, body = {}) {
  if (!state.api) return;
  try {
    await performGit(state, `${state.api}/git/${action}`, body, status => applyGitStatus(state, status));
  } catch {}
}

function setGitBusy(state, busy) {
  state.gitBar?.querySelectorAll('button,select').forEach(control => {
    if (busy) { control.dataset.gitWasDisabled = control.disabled ? 'true' : 'false'; control.disabled = true; }
    else if (control.dataset.gitWasDisabled !== 'true') control.disabled = false;
  });
}

function showGitError(state, error) {
  const output = state.gitBar?.querySelector('[data-git-output]');
  if (!output) return;
  output.hidden = false;
  if (!output.dataset.wired) {
    output.dataset.wired = 'true';
    output.querySelector('[data-git-output-close]')?.addEventListener('click', () => hideGitError(state));
  }
  output.querySelector('[data-git-output-text]').textContent = error?.message || String(error);
}
function hideGitError(state) { const output = state.gitBar?.querySelector('[data-git-output]'); if (output) output.hidden = true; }

function wireGitDialogs(state) {
  const branch = state.root.querySelector('[data-git-branch-dialog]');
  branch.querySelector('form').addEventListener('submit', async event => {
    if (event.submitter?.value !== 'confirm') return;
    event.preventDefault();
    clearDialogError(branch);
    try {
      const worktree = await performGit(state, `/workspace/api/projects/${state.projectId}/branches`, {
        name: branch.querySelector('[data-git-branch-name]').value,
        startPoint: branch.querySelector('[data-git-branch-start]').value
      });
      branch.close();
      navigateToWorktree(state, worktree.id);
    } catch (error) { dialogError(branch, error); }
  });
  const commit = state.root.querySelector('[data-git-commit-dialog]');
  commit.querySelector('[data-git-stage-all]').addEventListener('change', () => {
    renderCommitFiles(state, commit);
    // An untouched generated message follows the choice of what goes into the commit.
    const area = commit.querySelector('[data-git-commit-message]');
    if (!area.value.trim() || commit.dataset.generated === '1') generateCommitMessage(state, commit, true);
  });
  commit.querySelector('[data-git-commit-message]').addEventListener('input', () => { commit.dataset.generated = ''; });
  commit.querySelector('[data-git-generate-message]').addEventListener('click', () => generateCommitMessage(state, commit, false));
  commit.querySelector('form').addEventListener('submit', async event => {
    if (event.submitter?.value !== 'confirm') return;
    event.preventDefault();
    clearDialogError(commit);
    try {
      const status = await performGit(state, `${state.api}/git/commit`, {
        message: commit.querySelector('[data-git-commit-message]').value,
        stageAll: commit.querySelector('[data-git-stage-all]').checked
      });
      commit.close();
      applyGitStatus(state, status);
      commit.querySelector('[data-git-commit-message]').value = '';
      commit.dataset.generated = '';
    } catch (error) { dialogError(commit, error); }
  });
  const rebase = state.root.querySelector('[data-git-rebase-dialog]');
  rebase.querySelector('form').addEventListener('submit', async event => {
    if (event.submitter?.value !== 'confirm') return;
    event.preventDefault();
    clearDialogError(rebase);
    try {
      const status = await performGit(state, `${state.api}/git/rebase`, { target: rebase.querySelector('[data-git-rebase-target]').value || null });
      rebase.close();
      applyGitStatus(state, status);
    } catch (error) { dialogError(rebase, error); }
  });
  const confirm = state.root.querySelector('[data-git-confirm-dialog]');
  confirm.querySelector('form').addEventListener('submit', async event => {
    if (event.submitter?.value !== 'confirm') return;
    event.preventDefault();
    clearDialogError(confirm);
    const mode = confirm.dataset.mode;
    const target = mode === 'force-push' ? state.branch : confirm.querySelector('[data-git-confirm-target]').value;
    const typed = confirm.querySelector('[data-git-confirm-input]').value;
    if (!target || typed !== target) { dialogError(confirm, new Error('Type the exact branch name to confirm.')); return; }
    try {
      if (mode === 'force-push') {
        const status = await performGit(state, `${state.api}/git/push`, { forceWithLease: true });
        applyGitStatus(state, status);
      } else {
        await performGit(state, `/workspace/api/projects/${state.projectId}/branches/delete`, {
          name: target, kind: mode === 'delete-remote' ? 'REMOTE' : 'LOCAL',
          force: confirm.querySelector('[data-git-force]').checked, confirmation: typed
        }, branches => renderBranches(state, branches));
      }
      confirm.close();
    } catch (error) { dialogError(confirm, error); }
  });
}

function openGitDialog(state, kind) {
  if (kind !== 'branch' && !state.api) return;
  if (kind !== 'branch' && !ensureNoDrafts(state)) return;
  const dialog = state.root.querySelector(`[data-git-${kind}-dialog]`);
  clearDialogError(dialog);
  if (kind === 'branch') dialog.querySelector('[data-git-branch-name]').value = '';
  if (kind === 'commit') {
    renderCommitFiles(state, dialog);
    // Preview a suggested message as soon as the dialog opens; the user can edit or replace it.
    refreshChanges(state).then(() => {
      if (!dialog.open) return;
      renderCommitFiles(state, dialog);
      if (!dialog.querySelector('[data-git-commit-message]').value.trim()) generateCommitMessage(state, dialog, true);
    });
  }
  dialog.showModal();
}

// ---- Pull request ---------------------------------------------------------------------------
// Flow: check what is not on the remote yet (commit and/or push), then open the dialog, which
// previews the commits that will be added and pre-fills title and description with the model.
async function openPullRequestFlow(state) {
  if (!state.api || !state.branch) {
    showGitError(state, new Error('Open a branch worktree first: pull requests are opened for a branch.'));
    return;
  }
  if (!ensureNoDrafts(state)) return;
  try {
    hideGitError(state);
    const preview = await request(`${state.api}/pull-request/preview`);
    if (preview.unavailable) { showGitError(state, new Error(preview.unavailable)); return; }
    if (preview.uncommittedChanges > 0 || !preview.published || preview.unpushedCommits > 0) {
      openPullRequestPreflight(state, preview);
    } else {
      openPullRequestDialog(state, preview);
    }
  } catch (error) { showGitError(state, error); }
}

function openPullRequestPreflight(state, preview) {
  const dialog = state.root.querySelector('[data-pr-preflight-dialog]');
  const list = dialog.querySelector('[data-pr-preflight-list]');
  const commitBox = dialog.querySelector('[data-pr-preflight-commit]');
  const message = dialog.querySelector('[data-pr-preflight-message]');
  const push = dialog.querySelector('[data-pr-preflight-push]');
  const commitPush = dialog.querySelector('[data-pr-preflight-commit-push]');
  const proceed = dialog.querySelector('[data-pr-preflight-continue]');
  const error = dialog.querySelector('[data-pr-preflight-error]');
  dialog.querySelector('form').onsubmit = event => { if (event.submitter?.value !== 'cancel') event.preventDefault(); };
  const dirty = preview.uncommittedChanges > 0;
  const needsPush = !preview.published || preview.unpushedCommits > 0;
  error.textContent = '';
  list.replaceChildren();
  const item = (icon, text) => { const li = el('li'); li.append(renderIcon(icon), el('span', '', text)); list.append(li); };
  if (dirty) item('FilePlus2', `${preview.uncommittedChanges} uncommitted change${preview.uncommittedChanges === 1 ? '' : 's'}. They are not part of the pull request until they are committed and pushed.`);
  if (!preview.published) item('CloudUpload', `${preview.sourceBranch} has not been pushed to the remote yet.`);
  else if (preview.unpushedCommits > 0) item('CloudUpload', `${preview.unpushedCommits} commit${preview.unpushedCommits === 1 ? '' : 's'} on ${preview.sourceBranch} not pushed yet.`);
  commitBox.hidden = !dirty;
  commitPush.hidden = !dirty;
  push.hidden = dirty || !needsPush;
  // Leaving local changes out only works when everything committed is already on the remote.
  proceed.hidden = !(dirty && !needsPush);
  const generate = async () => {
    const button = dialog.querySelector('[data-pr-preflight-generate]');
    button.disabled = true;
    try {
      const result = await request(`${state.api}/git/commit-message`, {
        method: 'POST', headers: { 'Content-Type': 'application/json', ...csrfHeaders() },
        body: JSON.stringify({ stageAll: true })
      });
      if (!message.value.trim()) message.value = result.message || '';
    } catch (failure) { error.textContent = `Could not suggest a message: ${failure.message}`; }
    finally { button.disabled = false; }
  };
  const reopen = async () => {
    dialog.close();
    try {
      const refreshed = await request(`${state.api}/pull-request/preview`);
      if (refreshed.uncommittedChanges > 0 && needsPush) openPullRequestPreflight(state, refreshed);
      else if (!refreshed.published || refreshed.unpushedCommits > 0) openPullRequestPreflight(state, refreshed);
      else openPullRequestDialog(state, refreshed);
    } catch (failure) { showGitError(state, failure); }
  };
  const busy = value => dialog.querySelectorAll('button').forEach(button => { button.disabled = value; });
  dialog.querySelector('[data-pr-preflight-generate]').onclick = generate;
  push.onclick = async () => {
    busy(true); error.textContent = '';
    try {
      applyGitStatus(state, await performGit(state, `${state.api}/git/push`, { forceWithLease: false }));
      await reopen();
    } catch (failure) { error.textContent = failure.message; }
    finally { busy(false); }
  };
  commitPush.onclick = async () => {
    if (!message.value.trim()) { error.textContent = 'Enter a commit message first.'; return; }
    busy(true); error.textContent = '';
    try {
      applyGitStatus(state, await performGit(state, `${state.api}/git/commit`, { message: message.value, stageAll: true }));
      applyGitStatus(state, await performGit(state, `${state.api}/git/push`, { forceWithLease: false }));
      message.value = '';
      await reopen();
    } catch (failure) { error.textContent = failure.message; }
    finally { busy(false); }
  };
  proceed.onclick = () => { dialog.close(); openPullRequestDialog(state, preview); };
  dialog.showModal();
  if (dirty && !message.value.trim()) generate();
}

function openPullRequestDialog(state, preview) {
  const dialog = state.root.querySelector('[data-pr-dialog]');
  const field = name => dialog.querySelector(`[data-pr-${name}]`);
  const title = field('title');
  const description = field('description');
  const target = field('target');
  const error = field('error');
  const submit = field('submit');
  const generate = field('generate');
  let touched = false;
  let token = 0;
  let current = preview;

  error.textContent = '';
  field('result').hidden = true;
  field('body').hidden = false;
  submit.hidden = false;
  submit.disabled = false;
  field('source').textContent = preview.sourceBranch;
  const gitlab = preview.provider === 'GITLAB';
  dialog.querySelectorAll('[data-pr-gitlab]').forEach(node => { node.hidden = !gitlab; });
  dialog.querySelector('h2').textContent = gitlab ? 'Open merge request' : 'Open pull request';
  submit.textContent = gitlab ? 'Open merge request' : 'Open pull request';
  field('subtitle').textContent = `${preview.repository || 'Repository'} · opened with your connected Git account.`;
  field('draft').checked = false;
  field('assign').checked = false;
  field('squash').checked = false;
  field('remove-source').checked = false;
  field('labels').value = '';
  title.value = '';
  description.value = '';
  title.oninput = () => { touched = true; };
  description.oninput = () => { touched = true; };
  field('close').textContent = 'Cancel';
  // Enter in a field must not close the dialog like a form submission would.
  dialog.querySelector('form').onsubmit = event => { if (event.submitter?.value !== 'cancel') event.preventDefault(); };

  const fillTarget = () => {
    target.replaceChildren(...current.remoteBranches.filter(name => name !== current.sourceBranch)
      .map(name => { const option = el('option', '', name); option.value = name; option.selected = name === current.targetBranch; return option; }));
  };
  const renderCommits = () => {
    const commits = field('commits');
    commits.replaceChildren();
    const count = current.commits.length;
    field('commits-title').textContent = count
      ? `${count} commit${count === 1 ? '' : 's'} will be added to ${current.targetBranch}`
      : `No commits to add to ${current.targetBranch}`;
    for (const commit of current.commits) {
      const item = el('li');
      item.append(el('span', 'workspace-change-badge', commit.sha), el('span', 'workspace-commit-file-path', commit.subject));
      item.append(el('small', 'text-ink-500', commit.author));
      commits.append(item);
    }
    const files = field('files');
    files.replaceChildren();
    let added = 0; let removed = 0;
    for (const file of current.files) {
      added += file.additions; removed += file.deletions;
      const item = el('li');
      const badge = el('span', `workspace-change-badge is-${({ A: 'added', D: 'deleted', M: 'modified' }[file.status] || 'modified')}`,
        `${file.status} +${file.additions} −${file.deletions}`);
      item.append(badge, el('span', 'workspace-commit-file-path', file.path));
      files.append(item);
    }
    field('files-summary').textContent = `${current.files.length} file${current.files.length === 1 ? '' : 's'} · +${added} −${removed}`;
    submit.disabled = count === 0;
  };
  const suggest = async (automatic) => {
    const mine = ++token;
    generate.disabled = true;
    generate.querySelector('span').textContent = 'Generating…';
    if (automatic) { title.placeholder = 'Generating a title…'; description.placeholder = 'Generating a description…'; }
    try {
      const result = await request(`${state.api}/pull-request/draft`, {
        method: 'POST', headers: { 'Content-Type': 'application/json', ...csrfHeaders() },
        body: JSON.stringify({ target: current.targetBranch })
      });
      if (mine !== token) return;
      if (!automatic || !touched) {
        title.value = result.title || title.value;
        description.value = result.description || '';
        touched = false;
      }
    } catch (failure) {
      if (mine === token) {
        if (!title.value && current.commits.length) title.value = current.commits.length === 1 ? current.commits[0].subject : current.sourceBranch.replace(/[-_/]+/g, ' ');
        error.textContent = `Could not suggest a description: ${failure.message}`;
      }
    } finally {
      if (mine === token) {
        generate.disabled = false;
        generate.querySelector('span').textContent = 'Generate';
        title.placeholder = '';
        description.placeholder = '';
      }
    }
  };

  fillTarget();
  renderCommits();
  generate.onclick = () => { error.textContent = ''; suggest(false); };
  target.onchange = async () => {
    error.textContent = '';
    try {
      current = await request(`${state.api}/pull-request/preview?target=${encodeURIComponent(target.value)}`);
      renderCommits();
      if (!touched) suggest(true);
    } catch (failure) { error.textContent = failure.message; }
  };
  submit.onclick = async () => {
    error.textContent = '';
    if (!title.value.trim()) { error.textContent = 'A title is required.'; title.focus(); return; }
    submit.disabled = true;
    try {
      const created = await request(`${state.api}/pull-request`, {
        method: 'POST', headers: { 'Content-Type': 'application/json', ...csrfHeaders() },
        body: JSON.stringify({
          title: title.value, description: description.value, target: current.targetBranch,
          draft: field('draft').checked, assignToMe: field('assign').checked,
          labels: field('labels').value, squash: field('squash').checked,
          removeSourceBranch: field('remove-source').checked
        })
      });
      const result = field('result');
      result.className = 'workspace-pr-result';
      result.replaceChildren();
      result.append(el('strong', '', `${gitlab ? 'Merge request' : 'Pull request'} #${created.number} opened`));
      if (created.webUrl && /^https?:\/\//i.test(created.webUrl)) {
        const link = el('a', 'underline', created.webUrl);
        link.href = created.webUrl; link.target = '_blank'; link.rel = 'noopener noreferrer';
        result.append(link);
      }
      (created.warnings || []).forEach(warning => result.append(el('small', 'text-amber-300', warning)));
      field('body').hidden = true;
      result.hidden = false;
      submit.hidden = true;
      field('close').textContent = 'Close';
    } catch (failure) {
      error.textContent = failure.message;
      submit.disabled = current.commits.length === 0;
    }
  };
  dialog.showModal();
  if (current.commits.length) suggest(true);
}

// Fills the commit message with a generated Conventional Commits suggestion. `automatic` runs are
// silent about "nothing to describe" and never overwrite text the user typed in the meantime.
let commitMessageRequest = 0;
async function generateCommitMessage(state, dialog, automatic) {
  const area = dialog.querySelector('[data-git-commit-message]');
  const button = dialog.querySelector('[data-git-generate-message]');
  const label = button.querySelector('span');
  const stageAll = dialog.querySelector('[data-git-stage-all]').checked;
  const status = state.gitStatus || {};
  const hasChanges = (status.staged || []).length > 0
    || (stageAll && ((status.unstaged || []).length > 0 || (status.untracked || []).length > 0));
  if (!hasChanges) {
    if (!automatic) dialogError(dialog, new Error('There are no changes to describe. Stage changes or tick “Stage all changes”.'));
    return;
  }
  const token = ++commitMessageRequest;
  const previousPlaceholder = area.placeholder;
  clearDialogError(dialog);
  button.disabled = true;
  label.textContent = 'Generating…';
  if (automatic) area.placeholder = 'Generating a commit message…';
  try {
    const result = await request(`${state.api}/git/commit-message`, {
      method: 'POST', headers: { 'Content-Type': 'application/json', ...csrfHeaders() },
      body: JSON.stringify({ stageAll })
    });
    if (token !== commitMessageRequest) return;
    const untouched = !area.value.trim() || dialog.dataset.generated === '1';
    if (!automatic || untouched) {
      area.value = result.message || '';
      dialog.dataset.generated = '1';
      if (!automatic) area.focus();
    }
  } catch (error) {
    if (token === commitMessageRequest && !automatic) dialogError(dialog, error);
    else if (token === commitMessageRequest) dialogError(dialog, new Error(`Could not suggest a message: ${error.message}`));
  } finally {
    if (token === commitMessageRequest) {
      button.disabled = false;
      label.textContent = 'Generate';
      area.placeholder = previousPlaceholder;
    }
  }
}

// Lists what the commit will contain: staged changes, plus unstaged and untracked ones when
// "stage all" is ticked.
function renderCommitFiles(state, dialog) {
  const target = dialog.querySelector('[data-git-commit-files]');
  const stageAll = dialog.querySelector('[data-git-stage-all]').checked;
  const status = state.gitStatus || {};
  const staged = status.staged || [];
  const stagedPaths = new Set(staged.map(change => change.path));
  const extra = stageAll
    ? [...(status.unstaged || []), ...(status.untracked || [])].filter((change, index, all) =>
        !stagedPaths.has(change.path) && all.findIndex(other => other.path === change.path) === index)
    : [];
  const hidden = stageAll ? 0 : new Set([...(status.unstaged || []), ...(status.untracked || [])]
    .map(change => change.path).filter(path => !stagedPaths.has(path))).size;
  target.replaceChildren();
  const section = (title, changes, note) => {
    const heading = el('div', 'workspace-commit-files-title');
    heading.append(el('strong', '', title), el('small', '', `${changes.length}`));
    target.append(heading);
    if (note) target.append(el('div', 'workspace-empty', note));
    const list = el('ul', 'workspace-commit-file-list');
    for (const change of changes) {
      const item = el('li');
      item.append(changeBadge(change), el('span', 'workspace-commit-file-path',
        change.previousPath ? `${change.previousPath} → ${change.path}` : change.path));
      list.append(item);
    }
    if (changes.length) target.append(list);
  };
  if (!staged.length && !extra.length) {
    target.append(el('div', 'workspace-empty', hidden
      ? `Nothing is staged. Tick “Stage all changes” to include ${hidden} change${hidden === 1 ? '' : 's'}.`
      : 'There are no changes to commit.'));
    return;
  }
  if (staged.length) section('Staged', staged);
  if (extra.length) section('Will be staged', extra);
  if (hidden) target.append(el('div', 'workspace-empty', `${hidden} more change${hidden === 1 ? ' is' : 's are'} not staged and will stay out of this commit.`));
}

function openGitConfirmation(state, mode) {
  if (mode === 'force-push' && (!state.api || !state.branch || !ensureNoDrafts(state))) return;
  const dialog = state.root.querySelector('[data-git-confirm-dialog]');
  dialog.dataset.mode = mode;
  clearDialogError(dialog);
  dialog.querySelector('[data-git-confirm-input]').value = '';
  const targetLabel = dialog.querySelector('[data-git-target-label]');
  const forceLabel = dialog.querySelector('[data-git-force-label]');
  targetLabel.hidden = mode === 'force-push';
  forceLabel.hidden = mode !== 'delete-local';
  dialog.querySelector('[data-git-force]').checked = false;
  if (mode === 'force-push') {
    dialog.querySelector('[data-git-confirm-title]').textContent = 'Force push with lease';
    dialog.querySelector('[data-git-confirm-description]').textContent = `Type ${state.branch} to push rewritten history safely with --force-with-lease.`;
  } else {
    fillDeleteTargets(state, mode === 'delete-remote' ? 'REMOTE' : 'LOCAL');
    dialog.querySelector('[data-git-confirm-title]').textContent = mode === 'delete-remote' ? 'Delete remote branch' : 'Delete local branch';
    dialog.querySelector('[data-git-confirm-description]').textContent = 'Select a branch and type its exact name. Protected branches cannot be deleted.';
  }
  dialog.showModal();
}

function fillDeleteTargets(state, kind) {
  const select = state.root.querySelector('[data-git-confirm-target]');
  if (!select || !kind) return;
  select.replaceChildren();
  for (const branch of (state.branches || []).filter(value => value.kind === kind && !value.protectedBranch)) {
    select.append(new Option(kind === 'REMOTE' ? `origin/${branch.name}` : branch.name, branch.name));
  }
}
function clearDialogError(dialog) { const error = dialog?.querySelector('[data-git-dialog-error]'); if (error) error.textContent = ''; }
function dialogError(dialog, error) { const target = dialog.querySelector('[data-git-dialog-error]'); target.textContent = error?.message || String(error); }

function initializeEditor(state, storageKey) {
  state.tabsEl = state.root.querySelector('[data-editor-tabs]');
  state.host = state.root.querySelector('[data-editor-host]');
  state.status = state.root.querySelector('[data-editor-status]');
  state.pathStatus = state.root.querySelector('[data-editor-path]');
  state.cursor = state.root.querySelector('[data-editor-cursor]');
  state.persist = () => localWrite(storageKey, { tabs: state.tabs, active: state.active, drafts: state.drafts });
  state.resizeObserver = new ResizeObserver(() => layoutEditor(state));
  state.resizeObserver.observe(state.host);
  document.addEventListener('keydown', event => {
    if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 's' && state.root.contains(document.activeElement)) {
      event.preventDefault();
      saveActive(state);
    }
  });
  window.addEventListener('petri-theme-change', event => {
    if (state.monaco) state.monaco.editor.setTheme(event.detail?.theme === 'light' ? 'petri-light' : 'petri-dark');
  });
  window.addEventListener('beforeunload', () => disposeEditor(state), { once: true });
}

function renderTabs(state) {
  state.tabsEl.replaceChildren();
  if (!state.tabs.length) {
    state.tabsEl.append(el('span', 'px-4 text-xs text-ink-500', 'No file open'));
    return;
  }
  for (const tab of state.tabs) {
    const button = el('button', `workspace-tab${tab === state.active ? ' is-active' : ''}`);
    button.type = 'button';
    const path = state.drafts[tab]?.path || tab.replace(/^diff:/, '');
    button.append(tab.startsWith('diff:') ? renderIcon('GitCompareArrows') : fileIcon(path), document.createTextNode(path.split('/').at(-1)));
    if (state.drafts[tab]?.dirty) button.append(el('span', 'workspace-dirty-dot', '•'));
    const close = el('span', 'workspace-tab-close', '×');
    close.addEventListener('click', event => { event.stopPropagation(); closeTab(state, tab); });
    button.append(close);
    button.addEventListener('click', () => activateTab(state, tab));
    state.tabsEl.append(button);
  }
}

function editorEmpty(state, message = 'Select a file from the explorer, or inspect a change from the Changes view.') {
  disposeVisibleEditor(state);
  state.host.replaceChildren();
  const box = el('div', 'workspace-editor-empty');
  box.append(renderIcon('FileCode2', { width: 28, height: 28 }), el('h1', '', 'Select a file'), el('p', '', message));
  state.host.append(box);
  state.pathStatus.textContent = '—';
  state.cursor.textContent = 'Ln 1, Col 1';
}

function loadMonacoStyles() {
  if (document.querySelector('link[data-petri-monaco-styles]')) return;
  const stylesheet = document.createElement('link');
  stylesheet.rel = 'stylesheet';
  stylesheet.href = '/js/monaco/monaco.css?v=22';
  stylesheet.dataset.petriMonacoStyles = 'true';
  document.head.append(stylesheet);
}

async function ensureMonaco(state) {
  if (state.monaco) return state.monaco;
  if (!state.monacoPromise) {
    loadMonacoStyles();
    const moduleUrl = '/js/monaco/' + 'monaco.js';
    state.monacoPromise = import(moduleUrl).then(module => {
      const monaco = module.monaco;
      monaco.editor.defineTheme('petri-dark', {
        base: 'vs-dark', inherit: true,
        colors: { 'editor.background': '#070b12', 'editorGutter.background': '#0d1420', 'editor.lineHighlightBackground': '#172235', 'editorCursor.foreground': '#818cf8' },
        rules: []
      });
      monaco.editor.defineTheme('petri-light', {
        base: 'vs', inherit: true,
        colors: { 'editor.background': '#f6f8fc', 'editorGutter.background': '#ffffff', 'editor.lineHighlightBackground': '#edf1f7', 'editorCursor.foreground': '#4f46e5' },
        rules: []
      });
      monaco.editor.setTheme(document.documentElement.dataset.theme === 'light' ? 'petri-light' : 'petri-dark');
      state.monaco = monaco;
      return monaco;
    });
  }
  return state.monacoPromise;
}

function layoutEditor(state) {
  if (!state.host) return;
  const dimension = { width: state.host.clientWidth, height: state.host.clientHeight };
  if (dimension.width < 1 || dimension.height < 1) return;
  state.editor?.layout(dimension);
  if (state.diffEditor) {
    state.diffEditor.updateOptions({ renderSideBySide: dimension.width >= 840 });
    state.diffEditor.layout(dimension);
  }
}

function disposeVisibleEditor(state) {
  if (state.editor) {
    const path = state.visibleTab;
    if (path && !path.startsWith('diff:') && state.drafts[path]) state.drafts[path].viewState = { monaco: true, value: state.editor.saveViewState() };
    state.editor.dispose();
    state.editor = null;
  }
  if (state.diffEditor) {
    state.diffEditor.dispose();
    state.diffEditor = null;
  }
  state.visibleTab = null;
}

function disposeEditor(state) {
  disposeVisibleEditor(state);
  state.resizeObserver?.disconnect();
  state.models.forEach(model => model.dispose());
  state.diffModels.forEach(pair => { pair.original.dispose(); pair.modified.dispose(); });
  state.models.clear();
  state.diffModels.clear();
}

function activateTab(state, tab) {
  state.active = tab;
  state.editorToken += 1;
  renderTabs(state);
  const draft = state.drafts[tab];
  if (tab.startsWith('diff:')) mountDiff(state, draft?.path || tab.slice(5), draft, state.editorToken, tab);
  else mountEditor(state, tab, draft, state.editorToken);
  state.persist();
}

function modelUri(state, path) {
  return state.monaco.Uri.parse(`petri://worktree/${state.worktreeId}/${path.split('/').map(encodeURIComponent).join('/')}`);
}

async function mountEditor(state, path, draft, token) {
  disposeVisibleEditor(state);
  state.host.replaceChildren();
  if (!draft) return editorEmpty(state);
  state.status.textContent = 'Loading editor…';
  try {
    const monaco = await ensureMonaco(state);
    if (token !== state.editorToken || state.active !== path) return;
    let model = state.models.get(path);
    if (!model) {
      model = monaco.editor.createModel(draft.content, monacoLanguageFor(path), modelUri(state, path));
      state.models.set(path, model);
    } else {
      monaco.editor.setModelLanguage(model, monacoLanguageFor(path));
      if (model.getValue() !== draft.content) model.setValue(draft.content);
    }
    state.editor = monaco.editor.create(state.host, {
      model, automaticLayout: false, lineNumbers: 'on', minimap: { enabled: state.host.clientWidth >= 720 },
      folding: true, bracketPairColorization: { enabled: true }, selectionHighlight: true,
      scrollBeyondLastLine: false, renderWhitespace: 'selection', fontSize: 13, tabSize: 4,
      smoothScrolling: true, padding: { top: 10, bottom: 10 }
    });
    state.visibleTab = path;
    if (draft.viewState?.monaco === true && draft.viewState.value) state.editor.restoreViewState(draft.viewState.value);
    state.editor.onDidChangeModelContent(() => {
      draft.content = model.getValue();
      draft.dirty = draft.content !== draft.original;
      state.status.textContent = draft.dirty ? 'Unsaved changes' : 'Saved';
      renderTabs(state);
      state.persist();
    });
    state.editor.onDidChangeCursorPosition(event => {
      state.cursor.textContent = `Ln ${event.position.lineNumber}, Col ${event.position.column}`;
      draft.viewState = { monaco: true, value: state.editor.saveViewState() };
    });
    state.editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.KeyS, () => saveActive(state));
    state.pathStatus.textContent = `${path} · ${monacoLanguageFor(path)}`;
    state.status.textContent = draft.dirty ? 'Unsaved changes' : 'Saved';
    const position = state.editor.getPosition();
    if (position) state.cursor.textContent = `Ln ${position.lineNumber}, Col ${position.column}`;
    layoutEditor(state);
    state.editor.focus();
  } catch (error) {
    if (token !== state.editorToken) return;
    editorEmpty(state, `Monaco could not load: ${error.message}`);
    state.status.textContent = 'Editor unavailable';
    console.error('Petri Monaco editor failed to load', error);
  }
}

async function mountDiff(state, path, diff, token, tab) {
  disposeVisibleEditor(state);
  state.host.replaceChildren();
  if (!diff || diff.binary || diff.truncated) return editorEmpty(state, 'This changed file is binary or too large to display safely.');
  state.status.textContent = 'Loading diff…';
  try {
    const monaco = await ensureMonaco(state);
    if (token !== state.editorToken || state.active !== tab) return;
    let pair = state.diffModels.get(tab);
    if (pair) { pair.original.dispose(); pair.modified.dispose(); }
    pair = {
      original: monaco.editor.createModel(diff.beforeContent || '', monacoLanguageFor(path), monaco.Uri.parse(`petri-diff://worktree/${state.worktreeId}/${encodeURIComponent(tab)}/original/${path.split('/').map(encodeURIComponent).join('/')}`)),
      modified: monaco.editor.createModel(diff.afterContent || '', monacoLanguageFor(path), monaco.Uri.parse(`petri-diff://worktree/${state.worktreeId}/${encodeURIComponent(tab)}/modified/${path.split('/').map(encodeURIComponent).join('/')}`))
    };
    state.diffModels.set(tab, pair);
    state.diffEditor = monaco.editor.createDiffEditor(state.host, {
      readOnly: true, originalEditable: false, renderSideBySide: state.host.clientWidth >= 840,
      enableSplitViewResizing: false, renderOverviewRuler: true, renderIndicators: true,
      minimap: { enabled: false }, scrollBeyondLastLine: false, fontSize: 13, automaticLayout: false,
      padding: { top: 10, bottom: 10 }
    });
    state.visibleTab = tab;
    state.diffEditor.setModel(pair);
    state.pathStatus.textContent = `${path} · Git diff`;
    state.status.textContent = `${diff.status || 'Changed'} · read only`;
    state.cursor.textContent = 'Read only';
    layoutEditor(state);
  } catch (error) {
    if (token !== state.editorToken) return;
    editorEmpty(state, `Monaco diff could not load: ${error.message}`);
    state.status.textContent = 'Diff unavailable';
    console.error('Petri Monaco diff failed to load', error);
  }
}

async function openFile(state, path) {
  state.root.querySelectorAll('[data-tree-path]').forEach(entry => entry.classList.toggle('is-selected', entry.dataset.treePath === path));
  if (state.tabs.includes(path)) return activateTab(state, path);
  try {
    state.status.textContent = `Opening ${path}…`;
    const file = await request(`${state.api}/files?path=${encodeURIComponent(path)}`);
    const saved = state.drafts[path];
    state.drafts[path] = saved?.originalSha256 === file.sha256 ? saved : { content: file.content, original: file.content, originalSha256: file.sha256, dirty: false };
    state.tabs.push(path);
    activateTab(state, path);
  } catch (error) { state.status.textContent = error.message; }
}

async function openDiff(state, path, proposalId, scope = 'COMBINED') {
  const tab = proposalId ? `diff:proposal:${proposalId}:${path}` : `diff:git:${scope}:${path}`;
  const endpoint = proposalId ? `${state.api}/proposals/${proposalId}/diff` : `${state.api}/git/diff?path=${encodeURIComponent(path)}&scope=${scope}`;
  try {
    state.drafts[tab] = { ...await request(endpoint), path };
    if (!state.tabs.includes(tab)) state.tabs.push(tab);
    activateTab(state, tab);
  } catch (error) { state.status.textContent = error.message; }
}

function closeTab(state, tab) {
  if (state.drafts[tab]?.dirty && !confirm(`Discard unsaved changes in ${tab}?`)) return;
  if (state.active === tab) disposeVisibleEditor(state);
  const model = state.models.get(tab);
  model?.dispose();
  state.models.delete(tab);
  const pair = state.diffModels.get(tab);
  if (pair) { pair.original.dispose(); pair.modified.dispose(); }
  state.diffModels.delete(tab);
  state.tabs = state.tabs.filter(value => value !== tab);
  delete state.drafts[tab];
  state.active = state.tabs.at(-1) || null;
  renderTabs(state);
  if (state.active) activateTab(state, state.active); else editorEmpty(state);
  state.persist();
}

async function saveActive(state) {
  const draft = state.drafts[state.active];
  if (!draft || state.active?.startsWith('diff:') || !draft.dirty) return;
  try {
    const saved = await request(`${state.api}/files`, { method: 'PUT', headers: { 'Content-Type': 'application/json', ...csrfHeaders() }, body: JSON.stringify({ path: state.active, content: draft.content, expectedSha256: draft.originalSha256 }) });
    draft.original = draft.content;
    draft.originalSha256 = saved.sha256;
    draft.dirty = false;
    state.status.textContent = 'Saved';
    renderTabs(state);
    state.persist();
    refreshChanges(state);
  } catch (error) {
    state.status.textContent = error.status === 409 ? 'Conflict: disk file changed. Keep draft or reload the file.' : error.message;
  }
}

function initializeExplorer(state) { const panel = state.root.querySelector('[data-file-tree]'); state.tree = panel; panel.querySelectorAll('[data-server-entry]').forEach(button => { button.addEventListener('click', () => { const path = button.dataset.serverEntry; if (button.dataset.entryType !== 'DIRECTORY') { openFile(state, path); return; } let children = button.nextElementSibling; if (children?.classList.contains('workspace-tree-children')) { children.remove(); return; } children = el('div', 'workspace-tree-children'); button.after(children); loadTree(state, path, children); }); }); state.root.querySelectorAll('[data-explorer-mode]').forEach(button => button.addEventListener('click', () => { state.mode = button.dataset.explorerMode; state.root.querySelectorAll('[data-explorer-mode]').forEach(item => item.classList.toggle('is-active', item === button)); state.mode === 'files' ? loadTree(state) : renderChanges(state); })); state.root.querySelector('[data-refresh-explorer]')?.addEventListener('click', () => { loadTree(state); refreshChanges(state); }); state.root.querySelectorAll('[data-create-entry]').forEach(button => button.addEventListener('click', () => entryDialog(state, button.dataset.createEntry))); loadTree(state); }
// Reloads one directory level. A top-level reload keeps the folders the user has expanded and swaps
// the content in one step, so a refresh after a file was created never collapses or blanks the tree.
async function loadTree(state, path = '', target = state.tree, expanded = null) {
  if (state.mode !== 'files') return;
  const top = target === state.tree;
  if (top && !expanded) expanded = new Set([...state.tree.querySelectorAll('.workspace-tree-entry.is-expanded')].map(button => button.dataset.treePath));
  if (top && !state.tree.querySelector('.workspace-tree-row')) target.replaceChildren(el('div', 'workspace-loading', 'Loading files…'));
  try {
    const tree = await request(`${state.api}/tree?path=${encodeURIComponent(path)}`);
    const rows = [];
    if (!tree.entries.length) rows.push(el('div', 'workspace-empty', path ? 'Empty directory' : 'No files in this worktree.'));
    const pending = [];
    for (const item of tree.entries) {
      const row = treeRow(state, item);
      rows.push(row);
      if (item.type === 'DIRECTORY' && expanded?.has(item.path)) pending.push([row, item]);
    }
    target.replaceChildren(...rows);
    await Promise.all(pending.map(([row, item]) => {
      const button = row.querySelector(':scope > .workspace-tree-entry');
      button.classList.add('is-expanded');
      button.querySelector('.workspace-tree-twisty')?.replaceChildren(renderIcon('ChevronDown'));
      button.querySelector('.workspace-tree-primary-icon')?.replaceChildren(renderIcon('FolderOpen', { class: 'workspace-folder-icon is-open' }));
      const children = el('div', 'workspace-tree-children');
      row.append(children);
      return loadTree(state, item.path, children, expanded);
    }));
  } catch (error) {
    if (top && state.tree.querySelector('.workspace-tree-row')) return; // keep the stale tree rather than an error box
    target.replaceChildren();
    const retry = el('button', 'workspace-error', `Could not load files: ${error.message}. Retry`);
    retry.addEventListener('click', () => loadTree(state, path, target, expanded));
    target.append(retry);
  }
}

// Debounced explorer refresh for changes made outside the explorer: agents applying patches or
// creating files, approved commands, test runs, installs and Git operations.
function refreshExplorerSoon(state, delay = 500) {
  if (!state.api || state.mode !== 'files') return;
  clearTimeout(state.explorerRefresh);
  state.explorerRefresh = setTimeout(() => {
    if (document.hidden) { state.explorerStale = true; return; }
    state.explorerStale = false;
    loadTree(state);
  }, delay);
}
function treeRow(state, entry) {
  const row = el('div', 'workspace-tree-row');
  const button = el('button', 'workspace-tree-entry');
  button.type = 'button';
  button.dataset.treePath = entry.path;
  const isDirectory = entry.type === 'DIRECTORY';
  const name = entry.path.split('/').filter(Boolean).at(-1) || entry.path;
  const twisty = el('span', 'workspace-tree-twisty');
  const primaryIcon = el('span', 'workspace-tree-primary-icon');
  if (isDirectory) {
    twisty.append(renderIcon('ChevronRight'));
    primaryIcon.append(renderIcon('Folder', { class: 'workspace-folder-icon' }));
  } else primaryIcon.append(fileIcon(entry.path));
  button.append(twisty, primaryIcon, el('span', 'workspace-tree-name', name));
  const decoration = state.changes.get(entry.path);
  if (decoration) button.append(changeBadge(decoration));
  button.addEventListener('click', () => {
    if (!isDirectory) return openFile(state, entry.path);
    const children = row.querySelector(':scope > .workspace-tree-children');
    if (children) {
      children.remove();
      button.classList.remove('is-expanded');
      twisty.replaceChildren(renderIcon('ChevronRight'));
      primaryIcon.replaceChildren(renderIcon('Folder', { class: 'workspace-folder-icon' }));
      return;
    }
    button.classList.add('is-expanded');
    twisty.replaceChildren(renderIcon('ChevronDown'));
    primaryIcon.replaceChildren(renderIcon('FolderOpen', { class: 'workspace-folder-icon is-open' }));
    const next = el('div', 'workspace-tree-children');
    row.append(next);
    loadTree(state, entry.path, next);
  });
  const actions = el('span', 'workspace-tree-actions');
  actions.append(
    treeAction('Pencil', `Rename ${name}`, () => entryActionDialog(state, 'rename', entry)),
    treeAction('Trash2', `Delete ${name}`, () => entryActionDialog(state, 'delete', entry))
  );
  row.append(button, actions);
  return row;
}
function treeAction(icon, label, action) {
  const button = el('button', 'workspace-tree-action');
  button.type = 'button';
  button.title = label;
  button.setAttribute('aria-label', label);
  button.append(renderIcon(icon));
  button.addEventListener('click', event => { event.stopPropagation(); action(); });
  return button;
}
function changeBadge(change) { const badge = el('span', `workspace-change-badge is-${change.status.toLowerCase()}`); badge.textContent = `${change.status[0]} ${change.additions == null ? '' : `+${change.additions}`} ${change.deletions == null ? '' : `−${change.deletions}`}`.trim(); return badge; }
function entryDialog(state, type) { const dialog = state.root.querySelector('[data-workspace-dialog]'); const form = dialog.querySelector('[data-dialog-form]'); dialog.querySelector('[data-dialog-title]').textContent = type === 'FILE' ? 'Create file' : 'Create folder'; dialog.querySelector('[data-dialog-description]').textContent = 'Use a relative path inside this worktree.'; const input = dialog.querySelector('[data-dialog-path]'); dialog.querySelector('[data-dialog-path-label]').hidden = false; input.required = true; input.value = ''; const confirmationLabel = dialog.querySelector('[data-dialog-confirmation-label]'); confirmationLabel.hidden = true; const confirmation = dialog.querySelector('[data-dialog-confirmation]'); confirmation.required = false; confirmation.value = ''; dialog.querySelector('[data-dialog-confirm]').textContent = 'Create'; dialog.showModal(); form.onsubmit = async event => { if (event.submitter?.value !== 'confirm') return; event.preventDefault(); try { await request(`${state.api}/entries`, { method: 'POST', headers: { 'Content-Type': 'application/json', ...csrfHeaders() }, body: JSON.stringify({ path: input.value, type, content: '' }) }); dialog.close(); loadTree(state); refreshChanges(state); if (type === 'FILE') openFile(state, input.value); } catch (error) { dialog.querySelector('[data-dialog-description]').textContent = error.message; } }; }

function entryActionDialog(state, action, entry) {
  const affectedTabs = state.tabs.filter(tab => !tab.startsWith('diff:')
    && (tab === entry.path || (entry.type === 'DIRECTORY' && tab.startsWith(`${entry.path}/`))));
  if (affectedTabs.some(tab => state.drafts[tab]?.dirty)) {
    state.status.textContent = 'Save or discard drafts below this path before renaming or deleting it.';
    return;
  }
  const dialog = state.root.querySelector('[data-workspace-dialog]');
  const form = dialog.querySelector('[data-dialog-form]');
  const description = dialog.querySelector('[data-dialog-description]');
  const input = dialog.querySelector('[data-dialog-path]');
  const confirmationLabel = dialog.querySelector('[data-dialog-confirmation-label]');
  const confirmation = dialog.querySelector('[data-dialog-confirmation]');
  const deletingDirectory = action === 'delete' && entry.type === 'DIRECTORY';
  dialog.querySelector('[data-dialog-title]').textContent = action === 'rename' ? 'Rename entry' : 'Delete entry';
  description.textContent = action === 'rename'
    ? `Choose a new relative path for ${entry.path}.`
    : deletingDirectory
      ? `This recursively deletes ${entry.path}. This cannot be undone.`
      : `Delete ${entry.path}? This cannot be undone.`;
  dialog.querySelector('[data-dialog-path-label]').hidden = action === 'delete';
  input.required = action === 'rename';
  input.value = action === 'rename' ? entry.path : '';
  confirmationLabel.hidden = !deletingDirectory;
  confirmation.required = deletingDirectory;
  confirmation.value = '';
  dialog.querySelector('[data-dialog-confirm]').textContent = action === 'rename' ? 'Rename' : 'Delete';
  dialog.showModal();
  form.onsubmit = async event => {
    if (event.submitter?.value !== 'confirm') return;
    event.preventDefault();
    try {
      const metadata = await request(`${state.api}/entries/metadata?path=${encodeURIComponent(entry.path)}`);
      const targetPath = input.value.trim();
      const endpoint = action === 'rename' ? 'move' : 'delete';
      const body = action === 'rename'
        ? { sourcePath: entry.path, targetPath, expectedSha256: metadata.sha256 }
        : { path: entry.path, expectedSha256: metadata.sha256, recursive: deletingDirectory, confirmationPath: confirmation.value };
      await request(`${state.api}/entries/${endpoint}`, {
        method: 'POST', headers: { 'Content-Type': 'application/json', ...csrfHeaders() }, body: JSON.stringify(body)
      });
      dialog.close();
      const reopen = action === 'rename' ? affectedTabs.map(tab => targetPath + tab.slice(entry.path.length)) : [];
      for (const tab of affectedTabs) closeTab(state, tab);
      for (const tab of reopen) await openFile(state, tab);
      loadTree(state);
      refreshChanges(state);
      state.status.textContent = action === 'rename' ? `Renamed ${entry.path}` : `Deleted ${entry.path}`;
    } catch (error) { description.textContent = error.message; }
  };
}

function initializeChanges(state) { refreshChanges(state); window.addEventListener('focus', () => { refreshChanges(state); if (state.explorerStale) refreshExplorerSoon(state, 0); }); document.addEventListener('visibilitychange', () => { if (!document.hidden && state.explorerStale) refreshExplorerSoon(state, 0); }); setInterval(() => { if (!document.hidden) refreshChanges(state); }, 8000); }
async function refreshChanges(state) {
  if (!state.api) return;
  try {
    applyGitStatus(state, await request(`${state.api}/git/status`));
  } catch (error) {
    showGitError(state, error);
  }
}
function applyGitStatus(state, status) {
  const previousSignature = state.changeSignature;
  state.gitStatus = status;
  const values = [...(status.conflicts || []).map(path => ({ path, status: 'CONFLICT', staged: false, unstaged: true })), ...(status.staged || []), ...(status.unstaged || []), ...(status.untracked || [])];
  state.changes = new Map();
  for (const value of values) if (!state.changes.has(value.path) || value.status === 'CONFLICT') state.changes.set(value.path, value);
  const count = state.root.querySelector('[data-change-count]');
  if (count) count.textContent = state.changes.size;
  const lineChanges = [...(status.staged || []), ...(status.unstaged || []), ...(status.untracked || [])];
  const additions = lineChanges.reduce((total, value) => total + finiteCount(value.additions), 0);
  const deletions = lineChanges.reduce((total, value) => total + finiteCount(value.deletions), 0);
  const added = state.root.querySelector('[data-change-additions]');
  const deleted = state.root.querySelector('[data-change-deletions]');
  if (added) { added.textContent = `+${additions}`; added.title = `${additions} added line${additions === 1 ? '' : 's'}`; }
  if (deleted) { deleted.textContent = `−${deletions}`; deleted.title = `${deletions} removed line${deletions === 1 ? '' : 's'}`; }
  renderGitIndicators(state, status);
  // A different set of changed paths means files appeared, disappeared or were renamed.
  state.changeSignature = [...state.changes.entries()].map(([path, value]) => `${value.status}:${path}`).sort().join('\n');
  if (previousSignature !== undefined && previousSignature !== state.changeSignature) refreshExplorerSoon(state, 150);
  if (state.mode === 'changes' && state.tree) renderChanges(state);
}
function finiteCount(value) { const number = Number(value); return Number.isFinite(number) && number > 0 ? number : 0; }
function renderChanges(state) {
  state.tree.replaceChildren();
  const status = state.gitStatus;
  if (!status || !state.changes.size) { state.tree.append(el('div', 'workspace-empty', 'Working tree is clean.')); return; }
  const revertAll = el('div', 'workspace-change-revert-all');
  const revertButton = el('button', 'workspace-change-section-action', 'Revert all changes');
  revertButton.type = 'button';
  revertButton.addEventListener('click', () => discardChanges(state, [...state.changes.keys()], 'all changes'));
  revertAll.append(revertButton);
  state.tree.append(revertAll);
  const conflictPaths = new Set(status.conflicts || []);
  appendChangeSection(state, 'Merge Conflicts', [...conflictPaths].map(path => state.changes.get(path)).filter(Boolean), 'UNSTAGED', true);
  appendChangeSection(state, 'Staged Changes', (status.staged || []).filter(value => !conflictPaths.has(value.path)), 'STAGED', false);
  appendChangeSection(state, 'Changes', (status.unstaged || []).filter(value => !conflictPaths.has(value.path)), 'UNSTAGED', true);
  appendChangeSection(state, 'Untracked', status.untracked || [], 'UNSTAGED', true);
}
function appendChangeSection(state, title, values, scope, stage) {
  if (!values.length) return;
  const section = el('section', 'workspace-change-section');
  const header = el('div', 'workspace-change-section-header');
  header.append(el('span', '', `${title} (${values.length})`));
  const all = el('button', 'workspace-change-section-action', stage ? 'Stage all' : 'Unstage all');
  all.type = 'button';
  all.addEventListener('click', event => { event.stopPropagation(); updateIndex(state, stage, values.map(value => value.path)); });
  const revert = el('button', 'workspace-change-section-action', 'Revert all');
  revert.type = 'button';
  revert.addEventListener('click', event => { event.stopPropagation(); discardChanges(state, values.map(value => value.path), `${values.length} change${values.length === 1 ? '' : 's'}`); });
  header.append(all, revert);
  section.append(header);
  for (const change of values) {
    const row = el('button', 'workspace-change-row');
    row.type = 'button';
    row.append(renderIcon('FileDiff'), el('span', 'workspace-change-path', change.path), changeBadge(change));
    const action = el('span', 'workspace-change-row-action', stage ? '+' : '−');
    action.title = stage ? 'Stage' : 'Unstage';
    action.addEventListener('click', event => { event.stopPropagation(); updateIndex(state, stage, [change.path]); });
    const discard = el('span', 'workspace-change-row-action', '↺');
    discard.title = 'Revert this change';
    discard.addEventListener('click', event => { event.stopPropagation(); discardChanges(state, [change.path], change.path); });
    row.append(action, discard);
    row.addEventListener('click', () => openDiff(state, change.path, null, scope));
    section.append(row);
  }
  state.tree.append(section);
}
async function discardChanges(state, paths, label) {
  if (!paths.length || !ensureNoDrafts(state)) return;
  if (!confirm(`Revert ${label}? Staged and unstaged edits are lost and untracked files are deleted. This cannot be undone.`)) return;
  try {
    applyGitStatus(state, await request(`${state.api}/git/discard`, {
      method: 'POST', headers: { 'Content-Type': 'application/json', ...csrfHeaders() }, body: JSON.stringify({ paths: [...new Set(paths)] })
    }));
  } catch (error) { showGitError(state, error); }
}
async function updateIndex(state, stage, paths) {
  if (!ensureNoDrafts(state)) return;
  try {
    applyGitStatus(state, await request(`${state.api}/git/${stage ? 'stage' : 'unstage'}`, {
      method: 'POST', headers: { 'Content-Type': 'application/json', ...csrfHeaders() }, body: JSON.stringify({ paths })
    }));
  } catch (error) { showGitError(state, error); }
}

function initializeChat(state) {
  const chat = state.root.querySelector('[data-workspace-chat]');
  const scroller = chat.querySelector('[data-chat-scroll]');
  const form = chat.querySelector('[data-chat-form]');
  const input = chat.querySelector('[data-chat-input]');
  const selector = chat.querySelector('[data-chat-selector]');
  const model = chat.querySelector('[data-model-selector]');
  const mode = chat.querySelector('[data-chat-mode]');
  const sendButton = chat.querySelector('[data-chat-send]');
  state.chat = chat;
  state.scroller = scroller;
  state.chatMode = selector.selectedOptions[0]?.dataset.mode || 'ORCHESTRATED';
  mode.value = state.chatMode;
  mode.disabled = Boolean(state.chatId);
  chat.querySelector('[data-chat-automation-group]').hidden = state.chatMode !== 'DIRECT';
  const nearBottom = () => scroller.scrollHeight - scroller.scrollTop - scroller.clientHeight < 100;
  const bottom = () => { scroller.scrollTop = scroller.scrollHeight; chat.querySelector('[data-new-response]').hidden = true; };
  chat.querySelector('[data-new-response]').addEventListener('click', bottom);
  scroller.addEventListener('scroll', () => { if (nearBottom()) chat.querySelector('[data-new-response]').hidden = true; });

  const sendMessage = async () => {
    const prompt = input.value.trim();
    if (!prompt || sendButton.disabled) return;
    sendButton.disabled = true;
    try {
      if (state.chatMode === 'ORCHESTRATED') {
        const waiting = [...(state.snapshot?.workflows || [])].reverse().find(value =>
          value.status === 'WAITING_FOR_USER' && value.currentStage === 'WAITING_FOR_CLARIFICATION');
        if (waiting) {
          await request(`/workspace/api/workflows/${waiting.id}/clarifications`, {
            method: 'POST', headers: { 'Content-Type': 'application/json', ...csrfHeaders() },
            body: JSON.stringify({ answers: { freeText: prompt } })
          });
          input.value = '';
          await snapshot(state);
          requestAnimationFrame(bottom);
          return;
        }
        const active = activeGuidableWorkflow(state);
        const guide = chat.querySelector('[data-guide-active]');
        if (active && guide?.checked) {
          await request(`/workspace/api/workflows/${active.id}/guidance`, {
            method: 'POST', headers: { 'Content-Type': 'application/json', ...csrfHeaders() },
            body: JSON.stringify({ text: prompt })
          });
          input.value = '';
          await snapshot(state, true);
          requestAnimationFrame(bottom);
          return;
        }
        const started = await startWorkflow(state, prompt);
        if (!started) return;
        if (started.worktreeId !== state.worktreeId) {
          location.assign(started.navigationUrl);
          return;
        }
        state.chatId = started.chatId;
        ensureChatOption(selector, started.chatId, prompt, 'ORCHESTRATED');
        selector.value = state.chatId;
        input.value = '';
        updateChatUrl(state.chatId);
        await snapshot(state);
      } else {
        const option = model?.selectedOptions[0];
        const body = { prompt, providerInstanceId: model?.value || null, provider: option?.dataset.kind || null, model: option?.dataset.model,
          maxModelTurns: 20, maxOutputTokens: option?.dataset.tokens ? Number(option.dataset.tokens) : null,
          contextWindowTokens: option?.dataset.context ? Number(option.dataset.context) : null,
          reasoningEffort: option?.dataset.reasoning || null, textVerbosity: option?.dataset.verbosity || null };
        const endpoint = state.chatId ? `/workspace/api/chats/${state.chatId}/messages` : `${state.api}/chats`;
        const result = await request(endpoint, { method: 'POST', headers: { 'Content-Type': 'application/json', ...csrfHeaders() }, body: JSON.stringify(body) });
        state.chatId = result.chat.id;
        ensureChatOption(selector, state.chatId, result.chat.title || prompt, 'DIRECT');
        selector.value = state.chatId;
        input.value = '';
        updateChatUrl(state.chatId);
        renderSnapshot(state, result);
      }
      mode.disabled = true;
      requestAnimationFrame(bottom);
      connectStream(state);
    } catch (error) {
      chat.querySelector('[data-chat-status]').textContent = error.message;
    } finally { sendButton.disabled = false; }
  };
  form.addEventListener('submit', event => { event.preventDefault(); sendMessage(); });
  sendButton.addEventListener('click', sendMessage);
  mode.addEventListener('change', () => {
    if (!state.chatId) state.chatMode = mode.value;
    chat.querySelector('[data-chat-automation-group]').hidden = state.chatMode !== 'DIRECT';
  });
  selector.addEventListener('change', () => {
    state.chatId = selector.value || null;
    state.chatMode = selector.selectedOptions[0]?.dataset.mode || 'ORCHESTRATED';
    mode.value = state.chatMode;
    mode.disabled = Boolean(state.chatId);
    chat.querySelector('[data-chat-automation-group]').hidden = state.chatMode !== 'DIRECT';
    updateChatUrl(state.chatId);
    snapshot(state, true);
    connectStream(state);
  });
  chat.querySelector('[data-new-chat]').addEventListener('click', () => {
    selector.value = '';
    selector.dispatchEvent(new Event('change'));
    input.focus();
  });
  model?.addEventListener('change', async () => {
    const option = model.selectedOptions[0];
    try {
      await request(`/workspace/api/projects/${state.projectId}/model-preference`, { method: 'PUT', headers: { 'Content-Type': 'application/json', ...csrfHeaders() }, body: JSON.stringify({ providerInstanceId: model.value, provider: option.dataset.kind, model: option.dataset.model }) });
      chat.querySelector('[data-chat-status]').textContent = 'Model saved for this project';
    } catch (error) { chat.querySelector('[data-chat-status]').textContent = error.message; }
  });
  chat.querySelector('[data-orchestration-settings]').addEventListener('click', () => openOrchestrationProfiles(state));
  wireOrchestrationDialog(state);
  initializeApprovalSettings(state, chat);
  for (const inputAuto of chat.querySelectorAll('[data-auto-changes],[data-auto-tests]')) inputAuto.addEventListener('change', async event => {
    if (!state.chatId || state.chatMode !== 'DIRECT') return;
    if (event.target.matches('[data-auto-tests]') && event.target.checked && !confirm('Auto-run tests executes approved commands in this worktree. Continue?')) { event.target.checked = false; return; }
    await saveAutomation(state);
  });
  if (state.chatId) { snapshot(state, true); connectStream(state); }
}

function activeGuidableWorkflow(state) {
  return [...(state.snapshot?.workflows || [])].reverse().find(value =>
    ['RUNNING', 'PENDING', 'WAITING_FOR_USER', 'HUMAN_HOLD'].includes(value.status)
    && value.currentStage !== 'WAITING_FOR_CLARIFICATION') || null;
}

function updateGuideToggle(state) {
  const toggle = state.chat.querySelector('[data-guide-toggle]');
  if (toggle) toggle.hidden = state.chatMode !== 'ORCHESTRATED' || !activeGuidableWorkflow(state);
}

async function startWorkflow(state, prompt) {
  let branch = { branchMode: 'CURRENT', branchName: null, confirmedDefaultBranch: false };
  if (state.branch && state.branch === state.root.dataset.defaultBranch) {
    branch = await chooseWorkflowBranch(state, prompt);
    if (!branch) return null;
  }
  return request(`${state.api}/workflows`, {
    method: 'POST', headers: { 'Content-Type': 'application/json', ...csrfHeaders() },
    body: JSON.stringify({ chatId: state.chatId, prompt, ...branch })
  });
}

function chooseWorkflowBranch(state, prompt) {
  const dialog = state.root.querySelector('[data-workflow-branch-dialog]');
  const branchInput = dialog.querySelector('[data-workflow-branch-name]');
  const error = dialog.querySelector('[data-workflow-dialog-error]');
  const slug = prompt.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '').slice(0, 42) || 'task';
  branchInput.value = `petri/${slug}`;
  error.textContent = '';
  dialog.returnValue = 'cancel';
  dialog.showModal();
  return new Promise(resolve => {
    const closed = () => {
      dialog.removeEventListener('close', closed);
      if (dialog.returnValue !== 'confirm') { resolve(null); return; }
      const branchMode = dialog.querySelector('[name="workflow-branch-mode"]:checked').value;
      const branchName = branchMode === 'NEW' ? branchInput.value.trim() : null;
      if (branchMode === 'NEW' && !branchName) { resolve(null); return; }
      resolve({ branchMode, branchName, confirmedDefaultBranch: branchMode === 'CURRENT' });
    };
    dialog.addEventListener('close', closed);
  });
}

function ensureChatOption(selector, id, title, mode) {
  let option = [...selector.options].find(value => value.value === id);
  if (!option) {
    option = new Option(title.replace(/\s+/g, ' ').slice(0, 80), id);
    selector.append(option);
  }
  option.dataset.mode = mode;
}

function updateChatUrl(chatId) {
  const url = new URL(location);
  if (chatId) url.searchParams.set('chat', chatId); else url.searchParams.delete('chat');
  history.replaceState(null, '', url);
}
function initializeApprovalSettings(state, chat) {
  const panel = chat.querySelector('[data-automation-panel]');
  const button = chat.querySelector('[data-automation-menu]');
  const packages = panel.querySelector('[data-approve-packages]');
  const terminal = panel.querySelector('[data-approve-terminal]');
  const api = `/workspace/api/projects/${state.projectId}/approval-settings`;
  const load = async () => {
    try {
      const value = await request(api);
      packages.checked = Boolean(value.autoApprovePackageInstalls);
      terminal.checked = Boolean(value.autoApproveTerminalCommands);
    } catch (error) { chat.querySelector('[data-chat-status]').textContent = error.message; }
  };
  const save = async () => {
    const confirmed = !terminal.checked || state.terminalConfirmed
      || confirm('Auto-approve lets agents run shell commands in this worktree without asking. Commands are confined to the worktree. Continue?');
    if (!confirmed) { terminal.checked = false; return; }
    state.terminalConfirmed = true;
    try {
      await request(api, {
        method: 'PUT', headers: { 'Content-Type': 'application/json', ...csrfHeaders() },
        body: JSON.stringify({ autoApprovePackageInstalls: packages.checked, autoApproveTerminalCommands: terminal.checked })
      });
      chat.querySelector('[data-chat-status]').textContent = 'Approval settings saved';
    } catch (error) { chat.querySelector('[data-chat-status]').textContent = error.message; await load(); }
  };
  packages.addEventListener('change', save);
  terminal.addEventListener('change', save);
  button.addEventListener('click', () => {
    panel.hidden = !panel.hidden;
    button.classList.toggle('is-active', !panel.hidden);
    if (!panel.hidden) load();
  });
}
async function saveAutomation(state) { const chat = state.chat; try { const result = await request(`/workspace/api/chats/${state.chatId}/automation`, { method: 'PUT', headers: { 'Content-Type': 'application/json', ...csrfHeaders() }, body: JSON.stringify({ autoApproveChanges: chat.querySelector('[data-auto-changes]').checked, autoApproveTests: chat.querySelector('[data-auto-tests]').checked }) }); renderSnapshot(state, result); } catch (error) { chat.querySelector('[data-chat-status]').textContent = error.message; } }
async function snapshot(state, preservePosition = false) { if (!state.chatId) { renderSnapshot(state, null); return; } try { renderSnapshot(state, await request(`/workspace/api/chats/${state.chatId}/snapshot`), preservePosition); refreshExplorerSoon(state, 800); } catch (error) { state.chat.querySelector('[data-chat-status]').textContent = error.message; } }
function renderSnapshot(state, data, preservePosition = false) {
  const chat = state.chat, timeline = state.scroller, previousTop = timeline.scrollTop;
  const wasBottom = timeline.scrollHeight - previousTop - timeline.clientHeight < 100;
  const empty = chat.querySelector('[data-chat-empty]');
  state.snapshot = data;
  timeline.replaceChildren();
  if (!data) {
    if (empty) { timeline.append(empty); empty.hidden = false; }
    chat.querySelector('[data-review-tray]').hidden = true;
    renderActiveWorkflowPanel(state, null, timeline);
    updateGuideToggle(state);
    chat.querySelector('[data-chat-mode]').disabled = false;
    return;
  }
  state.chatMode = data.chat.executionMode || 'DIRECT';
  chat.querySelector('[data-chat-mode]').value = state.chatMode;
  chat.querySelector('[data-chat-mode]').disabled = true;
  const direct = state.chatMode === 'DIRECT';
  chat.querySelector('[data-auto-changes]').checked = data.chat.autoApproveChanges;
  chat.querySelector('[data-auto-tests]').checked = data.chat.autoApproveTests;
  chat.querySelector('[data-chat-automation-group]').hidden = !direct;
  const workingWorkflow = (data.workflows || []).some(value =>
    ['RUNNING', 'PENDING', 'CANCELLING'].includes(value.status));
  const waitingWorkflow = (data.workflows || []).some(value => value.status === 'WAITING_FOR_USER');
  chat.querySelector('[data-chat-status]').textContent = waitingWorkflow
    ? 'Workflow needs your input' : workingWorkflow || data.chat.active
      ? 'Petri is working…' : data.chat.queuePaused ? 'Queue paused' : 'Ready';
  const entries = [
    ...(data.runs || []).map(value => ({ index: value.run.chatMessageIndex, kind: 'run', value })),
    ...(data.workflows || []).map(value => ({ index: value.chatMessageIndex, kind: 'workflow', value }))
  ].sort((a, b) => a.index - b.index);
  const latestWorkflow = (data.workflows || []).at(-1);
  const pinnedWorkflow = [...(data.workflows || [])].reverse().find(value =>
    ['RUNNING', 'PENDING', 'WAITING_FOR_USER', 'HUMAN_HOLD', 'CANCELLING'].includes(value.status))
    || (latestWorkflow?.status === 'COMPLETED' ? latestWorkflow : null);
  renderActiveWorkflowPanel(state, pinnedWorkflow, timeline);
  for (const entry of entries) {
    if (entry.kind === 'run') renderRun(state, entry.value, timeline);
    else renderWorkflow(state, entry.value, timeline);
  }
  renderReviews(state);
  updateGuideToggle(state);
  expandLatestOutput(timeline);
  scheduleRunningPoll(state, data);
  if (!preservePosition || wasBottom) timeline.scrollTop = timeline.scrollHeight;
  else timeline.scrollTop = previousTop;
}
function scheduleRunningPoll(state, data) {
  const running = value => value?.status === 'RUNNING';
  const items = [
    ...(data.runs || []).flatMap(value => [...(value.tests || []), ...(value.packageInstalls || []), ...(value.terminalCommands || [])]),
    ...(data.workflows || []).flatMap(value => [...(value.tests || []), ...(value.packageInstalls || []), ...(value.terminalCommands || [])])
  ];
  clearTimeout(state.runningPoll);
  if (items.some(running)) state.runningPoll = setTimeout(() => snapshot(state, true), 1500);
}
function renderRun(state, entry, timeline) { const run = entry.run; const user = el('article', 'workspace-message workspace-message-user'); user.append(el('p', '', run.prompt)); const meta = el('div', 'workspace-message-meta', `Turn ${run.chatMessageIndex + 1} · ${run.status}`); user.append(meta); timeline.append(user); for (const turn of run.turns || []) { if (turn.kind === 'USER_PROMPT') continue; if (turn.kind === 'MODEL_RESPONSE') { const assistant = el('article', 'workspace-message workspace-message-assistant'); const body = el('p'); body.dataset.raw = turn.content || ''; applyMarkdown(body, turn.content || ''); assistant.append(body); timeline.append(assistant); } else if (turn.kind === 'ERROR') timeline.append(el('article', 'workspace-message-error', turn.content || 'Agent error')); else { const details = el('details', 'workspace-tool-turn'); details.append(el('summary', '', turn.toolName || turn.kind), el('pre', '', turn.content || '')); timeline.append(details); } } if (run.partialOutput) { const partial = el('article', 'workspace-message workspace-message-assistant'); const body = el('p'); body.dataset.runOutput = run.id; body.dataset.raw = run.partialOutput; applyMarkdown(body, run.partialOutput); partial.append(el('small', 'workspace-writing', 'Petri · writing…'), body); timeline.append(partial); } for (const proposal of entry.proposals || []) timeline.append(proposalCard(state, proposal)); for (const test of entry.tests || []) timeline.append(testCard(state, test)); for (const install of entry.packageInstalls || []) timeline.append(packageCard(state, install)); for (const command of entry.terminalCommands || []) timeline.append(terminalCard(state, command)); }

const workflowStages = [
  ['ORCHESTRATOR_INTAKE', 'Intake'], ['PLANNING', 'Plan'],
  ['ORCHESTRATOR_PLAN_REVIEW', 'Plan gate'], ['WAITING_FOR_PLAN_APPROVAL', 'Approval'],
  ['IMPLEMENTING', 'Implement'], ['ORCHESTRATOR_IMPLEMENTATION_REVIEW', 'Evidence gate'],
  ['REVIEWING', 'Review'], ['ORCHESTRATOR_FINAL_REVIEW', 'Final gate'], ['COMPLETED', 'Done']
];

function renderWorkflow(state, workflow, timeline) {
  const user = el('article', 'workspace-message workspace-message-user');
  user.append(el('p', '', workflow.prompt));
  user.append(el('div', 'workspace-message-meta', `Task ${workflow.chatMessageIndex + 1} · gated workflow`));
  timeline.append(user);
  renderWorkflowFeed(state, workflow, timeline);
  const card = el('article', 'workspace-workflow');
  card.dataset.workflowCard = workflow.id;
  const artifacts = workflow.artifacts || [];
  if (workflow.currentStage === 'WAITING_FOR_CLARIFICATION') {
    const questions = latestArtifact(artifacts, 'QUESTIONS');
    if (questions) card.append(questionForm(state, workflow, questions));
  }
  if (workflow.status === 'WAITING_FOR_USER' && workflow.currentStage === 'ORCHESTRATOR_FINAL_REVIEW') {
    card.append(finalApprovalPanel(state, workflow));
  }
  const pending = pendingPlanArtifact(workflow);
  if (pending) card.append(planPanel(state, workflow, pending, latestArtifact(artifacts, 'PLAN_VERDICT'), true));
  if (workflow.status === 'HUMAN_HOLD' && workflow.holdReason) card.append(el('div', 'workspace-workflow-hold', workflow.holdReason));
  const controls = workflowControls(state, workflow);
  if (controls.childElementCount) card.append(controls);
  if (card.childElementCount) timeline.append(card);
}

function summaryPanel(artifact) {
  const panel = el('section', 'workspace-workflow-panel workspace-workflow-summary');
  panel.append(el('h3', '', 'Summary'));
  const body = el('div');
  applyMarkdown(body, (artifactJson(artifact).summary || artifact.displayMarkdown || '').trim());
  panel.append(body);
  return panel;
}

function finalApprovalPanel(state, workflow) {
  const panel = el('section', 'workspace-workflow-panel');
  panel.append(el('h3', '', 'Final approval'));
  if (workflow.holdReason) panel.append(el('p', '', workflow.holdReason));
  const goals = workflow.goals || [];
  const open = goals.filter(goal => goal.status !== 'VERIFIED');
  const noPassedTest = !(workflow.tests || []).some(test => test.status === 'PASSED');
  if (open.length) {
    panel.append(el('p', '', `${open.length} of ${goals.length} acceptance criteria are not backed by evidence Petri could confirm:`));
    const list = el('ul', 'workspace-goal-list');
    for (const goal of open) {
      const item = el('li');
      item.append(el('code', '', goal.id), document.createTextNode(` ${goal.text}`), goalBadge(goal));
      list.append(item);
    }
    panel.append(list);
  }
  if (noPassedTest) panel.append(el('p', '', 'No executed test passed.'));
  panel.append(el('p', '', 'You can finish the workflow as it is, or send it back with guidance.'));
  const approve = el('button', 'btn-primary !min-h-8 !px-3 text-xs', 'Approve and finish'); approve.type = 'button';
  approve.addEventListener('click', () => workflowAction(state, approve, workflow.id, 'final/approve'));
  panel.append(el('div', 'workspace-workflow-actions'));
  panel.lastChild.append(approve);
  return panel;
}

function pendingPlanArtifact(workflow) {
  if (workflow.currentStage !== 'WAITING_FOR_PLAN_APPROVAL') return null;
  return (workflow.artifacts || []).find(value =>
    value.kind === 'PLAN' && value.revision === workflow.currentPlanRevision) || null;
}

function openPetriDialog(state, workflow) {
  const dialog = state.root.querySelector('[data-petrinet-dialog]');
  if (!dialog) return;
  dialog.dataset.workflowId = workflow.id;
  fillPetriDialog(state, dialog, workflow);
  if (!dialog.open) dialog.showModal();
}

/** Keeps an open net dialog live: every snapshot re-renders it from the same workflow. */
function refreshPetriDialog(state, workflow) {
  const dialog = state.root.querySelector('[data-petrinet-dialog]');
  if (dialog?.open && dialog.dataset.workflowId === workflow.id) fillPetriDialog(state, dialog, workflow);
}

function fillPetriDialog(state, dialog, workflow) {
  const body = dialog.querySelector('[data-petrinet-body]');
  const scroll = body.scrollTop;
  dialog.querySelector('[data-petrinet-subtitle]').textContent =
    `${stageLabel(workflow.currentStage)} · ${workflow.status.replaceAll('_', ' ').toLowerCase()}`;
  const net = el('div', 'overflow-x-auto');
  const holder = el('div', 'petrinet-canvas');
  holder.append(renderNet(workflow, { compact: false, previousStage: state.petriPrevious ?? null }));
  net.append(holder);

  const legend = el('div', 'petrinet-legend');
  for (const [cls, text] of [['', 'place'], ['is-square', 'transition (gate, agent or you)'],
    ['', 'token: where the workflow is'], ['', 'dashed arc: rework or exception']]) {
    const item = el('span');
    item.append(el('i', cls), document.createTextNode(text));
    legend.append(item);
  }
  const parts = [net, legend];

  const goals = workflow.goals || [];
  if (goals.length) {
    const verified = goals.filter(goal => goal.status === 'VERIFIED').length;
    parts.push(el('h3', 'section-title mt-5', `Acceptance criteria · ${verified}/${goals.length} verified`));
    parts.push(el('p', 'section-description', 'A criterion counts as verified only when a cited test passed or a cited file was really changed. The workflow cannot finish on its own until all are verified.'));
    const shell = el('div', 'table-shell mt-3');
    const table = el('table', 'data-table');
    const head = el('thead'); const headRow = el('tr');
    for (const name of ['Goal', 'Status', 'Evidence']) headRow.append(el('th', '', name));
    head.append(headRow); table.append(head);
    const rows = el('tbody');
    for (const goal of goals) {
      const row = el('tr');
      const text = el('td'); text.append(el('code', '', goal.id), document.createTextNode(` ${goal.text}`));
      if (goal.note) text.append(el('small', 'workspace-goal-reason', goal.note));
      const status = el('td'); status.append(goalBadge(goal));
      for (const reason of goal.reasons || []) status.append(el('small', 'workspace-goal-reason', reason));
      const evidence = el('td');
      if (!(goal.evidence || []).length) evidence.append(el('span', 'text-ink-500', '—'));
      for (const cited of goal.evidence || []) {
        const line = el('div', 'flex items-center gap-2');
        line.append(el('span', 'badge badge-neutral', cited.type === 'TEST' ? 'Test' : 'File'),
          el('span', 'min-w-0 truncate', cited.label || cited.ref), el('small', 'text-ink-500', cited.state.toLowerCase()));
        const proposal = cited.type === 'FILE'
          ? (workflow.proposals || []).filter(value => value.filePath === cited.ref && value.status === 'APPLIED').at(-1) : null;
        if (proposal) {
          const view = el('button', 'btn-ghost !min-h-7 !px-2 text-xs', 'Diff'); view.type = 'button';
          view.addEventListener('click', () => { dialog.close(); openDiff(state, cited.ref, proposal.id); });
          line.append(view);
        }
        evidence.append(line);
      }
      row.append(text, status, evidence); rows.append(row);
    }
    table.append(rows); shell.append(table); parts.push(shell);
  }

  const history = [...(workflow.transitions || [])].filter(value => value.fromStage !== value.toStage).reverse();
  if (history.length) {
    parts.push(el('h3', 'section-title mt-5', 'Firing history'));
    const list = el('ol', 'petrinet-history');
    for (const value of history) {
      const item = el('li');
      const time = el('time', '', new Date(value.createdAt).toLocaleString());
      item.append(time, el('span', '', `${stageLabel(value.fromStage)} → ${stageLabel(value.toStage)}`),
        el('span', 'badge badge-neutral', String(value.outcome).replaceAll('_', ' ').toLowerCase()));
      if (value.reason) item.append(el('small', 'text-ink-500', value.reason));
      list.append(item);
    }
    parts.push(list);
  }
  body.replaceChildren(...parts);
  body.scrollTop = scroll;
}

function workflowPin(state, workflow, timeline) {
  const pin = el('aside', 'workspace-workflow-pin');
  const heading = el('div', 'workspace-workflow-pin-heading');
  const title = el('div');
  title.append(renderIcon('Workflow'));
  const copy = el('span');
  copy.append(el('small', '', workflow.status === 'COMPLETED' ? 'Completed workflow'
    : workflow.status === 'WAITING_FOR_USER' ? 'Action required' : 'Active workflow'));
  copy.append(el('strong', '', workflowLabel(workflow.currentStage)));
  title.append(copy);
  const status = el('span', `workspace-workflow-status is-${workflow.status.toLowerCase()}`,
    workflow.status.replaceAll('_', ' '));
  const side = el('div', 'workspace-workflow-pin-side');
  const attempt = (workflow.stageRuns || []).at(-1)?.workflowAttempt;
  const runCount = (workflow.stageRuns || []).length;
  if (attempt) side.append(el('small', 'workspace-workflow-attempt', `Attempt ${attempt} · ${runCount} run${runCount === 1 ? '' : 's'}`));
  side.append(status);
  if (workflow.status === 'COMPLETED') {
    const merge = el('button', 'btn-primary !min-h-7 !px-2 text-xs', 'Open pull request'); merge.type = 'button';
    merge.title = 'Review the commits and open a pull request for this branch';
    merge.addEventListener('click', event => { event.stopPropagation(); openPullRequestFlow(state); });
    side.append(merge);
  } else if (!['CANCELLED', 'CANCELLING'].includes(workflow.status)) {
    const cancel = el('button', 'btn-secondary !min-h-7 !px-2 text-xs', 'Cancel task'); cancel.type = 'button';
    cancel.addEventListener('click', event => { event.stopPropagation(); workflowAction(state, cancel, workflow.id, 'cancel'); });
    side.append(cancel);
  }
  heading.append(title, side);
  const net = el('div', 'workspace-net');
  net.title = 'Open the workflow Petri net';
  net.tabIndex = 0;
  net.setAttribute('role', 'button');
  net.append(renderNet(workflow, { compact: true, previousStage: state.petriPrevious ?? null }));
  const openNet = event => { event.stopPropagation(); openPetriDialog(state, workflow); };
  net.addEventListener('click', openNet);
  net.addEventListener('keydown', event => {
    if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); openNet(event); }
  });
  pin.append(heading, net);
  pin.addEventListener('click', () => {
    const card = timeline.querySelector(`[data-workflow-card="${workflow.id}"]`);
    card?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  });
  pin.title = 'Open the active workflow details';
  return pin;
}

function renderActiveWorkflowPanel(state, workflow, timeline) {
  const panel = document.querySelector('[data-active-workflow-panel]');
  if (!panel) return;
  panel.replaceChildren();
  if (workflow) {
    // The net animates the token's arrival when the stage moved since the previous snapshot.
    state.petriPrevious = state.petriNet && state.petriNet.id === workflow.id ? state.petriNet.stage : null;
    panel.append(workflowPin(state, workflow, timeline));
    state.petriNet = { id: workflow.id, stage: workflow.currentStage };
    refreshPetriDialog(state, workflow);
    return;
  }
  const empty = el('div', 'workspace-workflow-top-empty');
  const copy = el('span');
  copy.append(el('small', '', 'Workflow'), el('strong', '', 'No active workflow'));
  empty.append(renderIcon('Workflow'), copy);
  panel.append(empty);
}

const workflowToolPresentation = {
  list_files: ['Folder', 'Listing files'],
  read_file: ['Eye', 'Reading file'],
  search_files: ['Search', 'Searching files'],
  show_diff: ['FileDiff', 'Inspecting proposal diff'],
  create_patch: ['FileDiff', 'Preparing file change'],
  create_file: ['FilePlus2', 'Preparing new file'],
  run_tests: ['Play', 'Preparing test run'],
  install_packages: ['Package', 'Requesting package install'],
  run_command: ['SquareTerminal', 'Requesting terminal command'],
  list_skills: ['List', 'Listing skills'],
  load_skill: ['BookOpen', 'Loading skill']
};

function workflowFeed(workflow) {
  const events = [];
  for (const run of workflow.stageRuns || []) {
    const role = roleLabel(run.workflowRole);
    const phase = workflowLabel(run.workflowPhase);
    if (run.startedAt || run.createdAt) events.push({ kind: 'info', icon: 'Play', text: `${role} started`, detail: phase, state: 'running', time: run.startedAt || run.createdAt, order: -1 });
    if (run.completedAt) {
      const failed = ['FAILED', 'CANCELLED', 'BUDGET_EXHAUSTED'].includes(run.status);
      events.push({ kind: 'info', icon: failed ? 'CircleAlert' : 'Check', text: `${role} ${run.status.replaceAll('_', ' ').toLowerCase()}`, detail: failed ? truncate(run.errorMessage || '') : phase, state: failed ? 'failed' : 'completed', time: run.completedAt, order: 1e9 });
    }
  }
  for (const run of workflow.stageRuns || []) {
    for (const turn of run.turns || []) {
      if (turn.kind === 'MODEL_RESPONSE' && turn.content) events.push({ kind: 'message', role: roleLabel(run.workflowRole), text: turn.content, time: turn.createdAt, order: turn.index });
    }
    if (run.partialOutput) events.push({ kind: 'message', role: roleLabel(run.workflowRole), text: run.partialOutput, live: true, runId: run.id, time: '9999', order: 0 });
  }
  for (const run of workflow.stageRuns || []) {
    for (const turn of run.turns || []) {
      if (turn.kind !== 'LOOP_GUARD') continue;
      const info = parsedObject(turn.content);
      const withdrawn = info.action === 'TOOLS_WITHDRAWN';
      events.push({ kind: 'tool', icon: 'TriangleAlert', text: withdrawn ? 'Loop guard intervention: tools withdrawn' : 'Loop guard intervention: repeat blocked',
        detail: roleLabel(run.workflowRole), state: 'interrupted', time: turn.createdAt, order: turn.index });
    }
  }
  for (const action of workflowToolActivities(workflow.stageRuns || [])) {
    events.push({ kind: 'tool', icon: action.icon, text: action.detail ? `${action.label}: ${action.detail}` : action.label, detail: action.role, state: action.state, time: action.createdAt, order: action.index });
  }
  const pending = pendingPlanArtifact(workflow);
  for (const artifact of workflow.artifacts || []) {
    if (artifact === pending || !['PLAN', 'TASK_DIFF', 'REVIEW', 'FINAL_VERDICT'].includes(artifact.kind)) continue;
    events.push({ kind: 'node', time: artifact.createdAt, order: 0.5, build: state => {
      if (artifact.kind === 'PLAN') return planPanel(state, workflow, artifact, null, false);
      if (artifact.kind === 'FINAL_VERDICT') return summaryPanel(artifact);
      return artifact.kind === 'TASK_DIFF' ? diffArtifactPanel(artifact) : reviewPanel(artifact, workflow);
    } });
  }
  for (const proposal of workflow.proposals || []) events.push({ kind: 'node', time: proposal.createdAt, order: 0.5, build: state => workflowProposal(state, proposal) });
  for (const command of workflow.terminalCommands || []) events.push({ kind: 'node', time: command.requestedAt, order: 0.5, build: state => terminalCard(state, command) });
  for (const install of workflow.packageInstalls || []) events.push({ kind: 'node', time: install.requestedAt, order: 0.5, build: state => packageCard(state, install) });
  for (const test of workflow.tests || []) events.push({ kind: 'node', time: test.requestedAt, order: 0.5, build: () => workflowTest(test) });
  events.sort((a, b) => String(a.time || '').localeCompare(String(b.time || '')) || a.order - b.order);
  return events;
}

function renderWorkflowFeed(state, workflow, timeline) {
  for (const event of workflowFeed(workflow)) {
    if (event.kind === 'node') { timeline.append(event.build(state)); continue; }
    if (event.kind === 'message') {
      const message = el('article', 'workspace-message workspace-message-assistant');
      const meta = el('small', 'workspace-writing', event.live ? `${event.role} · writing…` : event.role);
      if (!event.live) meta.append(' · ', timestampEl(event.time));
      const body = el('p');
      if (event.live) body.dataset.runOutput = event.runId;
      body.dataset.raw = event.text;
      applyMarkdown(body, event.text);
      message.append(meta, body);
      timeline.append(message);
      continue;
    }
    const row = el('div', `workspace-workflow-event is-${event.state}`);
    row.append(renderIcon(event.icon), el('span', '', event.text));
    if (event.detail) row.append(el('small', '', event.detail));
    row.append(el('i', `workspace-workflow-activity-state is-${event.state}`));
    row.append(timestampEl(event.time));
    timeline.append(row);
  }
}

function timestampEl(value) {
  const time = el('time', 'workspace-workflow-event-time');
  const date = value ? new Date(value) : null;
  if (!date || Number.isNaN(date.getTime())) return time;
  time.dateTime = date.toISOString();
  time.title = date.toLocaleString();
  time.textContent = date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
  return time;
}

function workflowToolActivities(runs) {
  const activities = [];
  for (const run of runs) {
    const turns = run.turns || [];
    const results = new Map(turns.filter(turn => turn.kind === 'TOOL_RESULT').map(turn => [turn.toolCallId, turn]));
    for (const turn of turns.filter(value => value.kind === 'TOOL_CALL')) {
      const result = results.get(turn.toolCallId);
      let state = result ? 'completed' : ['FAILED', 'CANCELLED', 'BUDGET_EXHAUSTED'].includes(run.status) ? 'interrupted' : 'running';
      if (result && parsedObject(result.content).error) state = 'failed';
      const [icon, label] = workflowToolPresentation[turn.toolName] || ['Wrench', humanizeToolName(turn.toolName)];
      activities.push({ icon, label, detail: workflowToolDetail(turn), state, role: roleLabel(run.workflowRole), createdAt: turn.createdAt, index: turn.index });
    }
  }
  return activities.sort((left, right) => String(left.createdAt || '').localeCompare(String(right.createdAt || '')) || left.index - right.index);
}

function workflowToolDetail(turn) {
  const input = parsedObject(turn.content);
  const path = stringValue(input.path);
  if (turn.toolName === 'search_files') {
    const query = stringValue(input.query);
    return truncate([query ? `“${query}”` : '', path && path !== '.' ? `in ${path}` : ''].filter(Boolean).join(' '));
  }
  if (turn.toolName === 'load_skill') return truncate(stringValue(input.name));
  if (turn.toolName === 'show_diff') return truncate(stringValue(input.proposalId));
  return truncate(path && path !== '.' ? path : '');
}

function parsedObject(value) { try { const parsed = JSON.parse(value || '{}'); return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : {}; } catch { return {}; } }
function stringValue(value) { return typeof value === 'string' ? value : ''; }
function truncate(value, length = 72) { return value.length > length ? `${value.slice(0, length - 1)}…` : value; }
function roleLabel(role) { return humanizeToolName(role || 'agent'); }
function humanizeToolName(value) { return String(value || 'tool').replaceAll('_', ' ').toLowerCase().replace(/^./, letter => letter.toUpperCase()); }

function workflowLabel(stage) {
  return workflowStages.find(value => value[0] === stage)?.[1]
    || stage.replaceAll('_', ' ').toLowerCase().replace(/^./, value => value.toUpperCase());
}

function latestArtifact(artifacts, kind) {
  return artifacts.filter(value => value.kind === kind).sort((a, b) => b.revision - a.revision)[0] || null;
}

function artifactJson(artifact) {
  try { return JSON.parse(artifact.contentJson); } catch { return {}; }
}

function questionForm(state, workflow, artifact) {
  const data = artifactJson(artifact);
  const form = el('form', 'workspace-workflow-panel workspace-question-form');
  form.append(el('h3', '', 'Planner needs your input'));
  for (const question of data.questions || []) {
    const field = el('fieldset');
    field.dataset.questionId = question.id;
    field.dataset.questionType = question.type;
    field.append(el('legend', '', question.prompt));
    if (question.type === 'FREE_TEXT') {
      const input = el('textarea', 'field'); input.required = true; input.rows = 3; field.append(input);
    } else {
      for (const option of question.options || []) {
        const label = el('label', 'workspace-check');
        const input = document.createElement('input'); input.type = question.type === 'MULTI_SELECT' ? 'checkbox' : 'radio'; input.name = question.id; input.value = option;
        label.append(input, document.createTextNode(option)); field.append(label);
      }
    }
    form.append(field);
  }
  const submit = el('button', 'btn-primary !min-h-8 !px-3 text-xs', 'Send answers'); submit.type = 'submit'; form.append(submit);
  form.addEventListener('submit', async event => {
    event.preventDefault();
    const answers = {};
    for (const field of form.querySelectorAll('fieldset')) {
      if (field.dataset.questionType === 'FREE_TEXT') answers[field.dataset.questionId] = field.querySelector('textarea').value.trim();
      else if (field.dataset.questionType === 'MULTI_SELECT') answers[field.dataset.questionId] = [...field.querySelectorAll('input:checked')].map(value => value.value);
      else answers[field.dataset.questionId] = field.querySelector('input:checked')?.value || '';
    }
    await workflowAction(state, submit, workflow.id, 'clarifications', { answers });
  });
  return form;
}

function planPanel(state, workflow, artifact, verdictArtifact, interactive = false) {
  const plan = artifactJson(artifact);
  const verdict = verdictArtifact ? artifactJson(verdictArtifact) : null;
  const panel = el('section', 'workspace-workflow-panel');
  panel.append(el('h3', '', plan.title || `Plan revision ${artifact.revision}`));
  if (plan.objective) panel.append(el('p', '', plan.objective));
  appendArtifactList(panel, 'Steps', plan.steps);
  appendGoalList(panel, workflow, plan.acceptanceCriteria);
  appendArtifactList(panel, 'Risks', plan.risks);
  if (interactive) {
    if (verdict && verdict.decision === 'PASS_WITH_WARNINGS') {
      const warning = el('section', 'workspace-plan-warning');
      warning.append(renderIcon('TriangleAlert'), el('strong', '', 'Review before approving'));
      if (verdict.reason) warning.append(el('p', '', verdict.reason));
      const messages = [...(verdict.warnings || []), ...(verdict.feedback || [])];
      if (messages.length) {
        const list = el('ul');
        for (const message of messages) list.append(el('li', '', message));
        warning.append(list);
      }
      panel.append(warning);
    }
    const actions = el('div', 'workspace-workflow-actions');
    const approve = el('button', 'btn-primary !min-h-8 !px-3 text-xs',
      verdict?.decision === 'PASS_WITH_WARNINGS' ? 'Approve despite warnings' : 'Approve plan'); approve.type = 'button';
    approve.addEventListener('click', () => workflowAction(state, approve, workflow.id, 'plan/approve', { revision: artifact.revision }));
    const feedback = el('textarea', 'field'); feedback.rows = 2; feedback.placeholder = 'What should the planner revise?';
    const revise = el('button', 'btn-secondary !min-h-8 !px-3 text-xs', 'Request revision'); revise.type = 'button';
    revise.addEventListener('click', () => workflowAction(state, revise, workflow.id, 'plan/revise', { text: feedback.value.trim() }, Boolean(feedback.value.trim())));
    actions.append(approve, feedback, revise); panel.append(actions);
  }
  return panel;
}

function goalBadge(goal) {
  const tone = { VERIFIED: '', CLAIMED: ' badge-warn', PARTIAL: ' badge-warn', NOT_MET: ' badge-danger', UNVERIFIED: ' badge-neutral' }[goal.status] ?? ' badge-neutral';
  const badge = el('span', `badge workspace-goal-badge${tone}`, goalStatusLabels[goal.status] || goal.status);
  if ((goal.reasons || []).length) badge.title = goal.reasons.join('\n');
  return badge;
}

function appendGoalList(parent, workflow, criteria) {
  if (!Array.isArray(criteria) || !criteria.length) return;
  const known = new Map((workflow.goals || []).map(goal => [goal.id, goal]));
  parent.append(el('h4', '', 'Acceptance criteria'));
  const list = el('ul', 'workspace-goal-list');
  criteria.forEach((value, index) => {
    const object = value && typeof value === 'object';
    const id = object && value.id ? value.id : `AC${index + 1}`;
    const text = typeof value === 'string' ? value : (object && value.text) || JSON.stringify(value);
    const item = el('li');
    item.append(el('code', '', id), document.createTextNode(` ${text}`));
    const goal = known.get(id);
    if (goal && goal.status !== 'UNVERIFIED') item.append(goalBadge(goal));
    list.append(item);
  });
  parent.append(list);
}

function appendArtifactList(parent, title, values) {
  if (!Array.isArray(values) || !values.length) return;
  parent.append(el('h4', '', title));
  const list = el('ol'); for (const value of values) list.append(el('li', '', typeof value === 'string' ? value : JSON.stringify(value))); parent.append(list);
}

function diffArtifactPanel(artifact) {
  const data = artifactJson(artifact);
  const details = el('details', 'workspace-workflow-panel workspace-workflow-evidence');
  details.append(el('summary', '', `Task diff · ${(data.paths || []).length} file${(data.paths || []).length === 1 ? '' : 's'}`));
  const body = el('div'); applyMarkdown(body, artifact.displayMarkdown || 'No workflow-owned file changes.'); details.append(body);
  return details;
}

function reviewPanel(artifact, workflow) {
  const review = artifactJson(artifact);
  const panel = el('section', 'workspace-workflow-panel workspace-review-result');
  panel.append(el('h3', '', `Review · ${review.decision || 'pending'}`), el('p', '', review.summary || ''));
  if ((workflow?.goals || []).length) {
    panel.append(el('h4', '', 'Acceptance criteria'));
    const list = el('ul', 'workspace-goal-list');
    for (const goal of workflow.goals) {
      const item = el('li');
      item.append(el('code', '', goal.id), document.createTextNode(` ${goal.text}`), goalBadge(goal));
      for (const reason of goal.reasons || []) item.append(el('small', 'workspace-goal-reason', reason));
      list.append(item);
    }
    panel.append(list);
  }
  for (const finding of review.findings || []) {
    const item = el('div', 'workspace-finding');
    item.append(el('strong', '', `${finding.severity || 'Finding'}${finding.path ? ` · ${finding.path}${finding.line ? `:${finding.line}` : ''}` : ''}`), el('p', '', finding.issue || finding.requiredChange || ''));
    panel.append(item);
  }
  return panel;
}

function workflowProposal(state, proposal) {
  const card = el('div', 'workspace-workflow-action');
  card.append(renderIcon('FileDiff'), el('span', '', proposal.filePath), el('small', '', proposal.status));
  const view = el('button', 'btn-secondary !min-h-7 !px-2 text-xs', 'View diff'); view.type = 'button'; view.addEventListener('click', () => openDiff(state, proposal.filePath, proposal.id)); card.append(view);
  return card;
}

function workflowTest(test) {
  const card = el('div', 'workspace-workflow-action');
  card.append(renderIcon('Play'), el('span', '', test.command), el('small', '', test.status));
  if (test.output || test.status === 'RUNNING') {
    card.classList.add('is-block');
    card.append(outputDetails(test.output, test.status === 'RUNNING' ? 'Running…'
      : (test.exitCode != null ? `Output (exit ${test.exitCode})` : 'Output'), test.status === 'RUNNING'));
  }
  return card;
}

function workflowControls(state, workflow) {
  const controls = el('div', 'workspace-workflow-controls');
  if (workflow.status === 'HUMAN_HOLD') {
    const retry = el('button', 'btn-primary !min-h-8 !px-3 text-xs', 'Retry'); retry.type = 'button'; retry.addEventListener('click', () => workflowAction(state, retry, workflow.id, 'retry'));
    controls.append(retry);
    // Once something was implemented the user may overrule the hold and finish the workflow.
    const implemented = (workflow.artifacts || []).some(artifact => artifact.kind === 'IMPLEMENTATION_REPORT');
    if (workflow.approvedPlanRevision != null && implemented) {
      const accept = el('button', 'btn-secondary !min-h-8 !px-3 text-xs', 'Accept anyway'); accept.type = 'button';
      accept.title = 'Complete this workflow with the current result, despite the hold';
      accept.addEventListener('click', () => {
        if (confirm('Complete this workflow with the current result? The reason it was held stays in the history.')) workflowAction(state, accept, workflow.id, 'accept');
      });
      controls.append(accept);
    }
  }
  return controls;
}

async function workflowAction(state, button, workflowId, action, body, valid = true) {
  if (!valid) { state.chat.querySelector('[data-chat-status]').textContent = 'Enter the required text first.'; return; }
  button.disabled = true;
  try {
    await request(`/workspace/api/workflows/${workflowId}/${action}`, { method: 'POST', headers: { ...(body ? { 'Content-Type': 'application/json' } : {}), ...csrfHeaders() }, body: body ? JSON.stringify(body) : undefined });
    await snapshot(state, true);
  } catch (error) { state.chat.querySelector('[data-chat-status]').textContent = error.message; }
  finally { if (button.isConnected) button.disabled = false; }
}
function proposalCard(state, proposal) { const card = el('article', 'workspace-review-card'); card.append(el('strong', '', proposal.filePath), el('span', 'workspace-review-state', proposal.status)); const diff = el('button', 'btn-secondary !min-h-8 !px-2 text-xs', 'View diff'); diff.type = 'button'; diff.addEventListener('click', () => openDiff(state, proposal.filePath, proposal.id)); card.append(diff); if (proposal.status === 'PROPOSED' || proposal.status === 'APPROVED') { const apply = el('button', 'btn-primary !min-h-8 !px-2 text-xs', 'Approve & apply'); apply.type = 'button'; apply.addEventListener('click', () => reviewAction(state, apply, async () => { await request(`/workspace/api/proposals/${proposal.id}/approve-and-apply`, { method: 'POST', headers: csrfHeaders() }); await snapshot(state, true); refreshChanges(state); })); const reject = el('button', 'btn-secondary !min-h-8 !px-2 text-xs', 'Reject'); reject.type = 'button'; reject.addEventListener('click', () => reviewAction(state, reject, async () => { await request(`/workspace/api/chats/${state.chatId}/proposals/${proposal.id}/reject`, { method: 'POST', headers: csrfHeaders() }); await snapshot(state, true); })); card.append(apply, reject); } return card; }
function terminalCard(state, command) {
  const card = el('article', 'workspace-review-card');
  card.append(renderIcon('SquareTerminal'), el('strong', '', `$ ${command.command}`),
    el('span', 'workspace-review-state', command.status));
  if (command.reason) card.append(el('small', 'workspace-package-reason', command.reason));
  if (command.status === 'PROPOSED' || command.status === 'APPROVED') {
    const approve = el('button', 'btn-primary !min-h-8 !px-2 text-xs', 'Approve & run'); approve.type = 'button';
    approve.addEventListener('click', () => reviewAction(state, approve, async () => {
      await request(`/workspace/api/chats/${state.chatId}/terminal/${command.id}/approve`, { method: 'POST', headers: csrfHeaders() });
      await snapshot(state, true);
    }));
    const reject = el('button', 'btn-secondary !min-h-8 !px-2 text-xs', 'Decline'); reject.type = 'button';
    reject.addEventListener('click', () => reviewAction(state, reject, async () => {
      await request(`/workspace/api/chats/${state.chatId}/terminal/${command.id}/reject`, { method: 'POST', headers: csrfHeaders() });
      await snapshot(state, true);
    }));
    card.append(approve, reject);
  } else if (command.output || command.status === 'RUNNING') {
    card.append(outputDetails(command.output, command.status === 'RUNNING' ? 'Running…'
      : command.exitCode != null ? `Output (exit ${command.exitCode})` : 'Output', command.status === 'RUNNING'));
  }
  return card;
}
// Collapsible output block; the live/most recent one is shown at full size (see expandLatestOutput).
function outputDetails(output, label, live) {
  const details = el('details', 'workspace-package-output');
  details.classList.toggle('is-live', Boolean(live));
  details.open = Boolean(live);
  details.append(el('summary', '', label), el('pre', '', output || ''));
  return details;
}
// The newest step shows its output expanded until the next step (message, tool call, card) follows.
function expandLatestOutput(timeline) {
  const outputs = [...timeline.querySelectorAll('details.workspace-package-output')];
  const last = outputs.at(-1);
  if (!last) return;
  let node = last.closest('.workspace-review-card, .workspace-workflow-action') || last;
  while (node.parentElement && node.parentElement !== timeline) node = node.parentElement;
  const following = [];
  for (let next = node.nextElementSibling; next; next = next.nextElementSibling) following.push(next);
  if (following.every(value => value.classList.contains('workspace-workflow'))) {
    last.open = true;
    last.classList.add('is-live');
  }
}
function packageCard(state, install) {
  const card = el('article', 'workspace-review-card');
  card.append(renderIcon('Package'), el('strong', '', `Install packages: ${(install.packages || []).join(', ')}`),
    el('span', 'workspace-review-state', install.status));
  if (install.reason) card.append(el('small', 'workspace-package-reason', install.reason));
  if (install.status === 'PROPOSED' || install.status === 'APPROVED') {
    const approve = el('button', 'btn-primary !min-h-8 !px-2 text-xs', 'Approve & install'); approve.type = 'button';
    approve.addEventListener('click', () => reviewAction(state, approve, async () => {
      await request(`/workspace/api/chats/${state.chatId}/packages/${install.id}/approve`, { method: 'POST', headers: csrfHeaders() });
      await snapshot(state, true);
    }));
    const reject = el('button', 'btn-secondary !min-h-8 !px-2 text-xs', 'Reject'); reject.type = 'button';
    reject.addEventListener('click', () => reviewAction(state, reject, async () => {
      await request(`/workspace/api/chats/${state.chatId}/packages/${install.id}/reject`, { method: 'POST', headers: csrfHeaders() });
      await snapshot(state, true);
    }));
    card.append(approve, reject);
  } else if (install.output || install.status === 'RUNNING') {
    card.append(outputDetails(install.output, install.status === 'RUNNING' ? 'Installing…' : 'Output',
      install.status === 'RUNNING'));
  }
  return card;
}
function testCard(state, test) { const card = el('article', 'workspace-review-card'); card.append(el('strong', '', test.command), el('span', 'workspace-review-state', test.status)); if (test.status === 'PROPOSED' || test.status === 'APPROVED') { const run = el('button', 'btn-primary !min-h-8 !px-2 text-xs', 'Run'); run.type = 'button'; run.addEventListener('click', () => reviewAction(state, run, async () => { await request(`/workspace/api/chats/${state.chatId}/tests/${test.id}/run`, { method: 'POST', headers: csrfHeaders() }); await snapshot(state, true); })); const reject = el('button', 'btn-secondary !min-h-8 !px-2 text-xs', 'Reject'); reject.type = 'button'; reject.addEventListener('click', () => reviewAction(state, reject, async () => { await request(`/workspace/api/chats/${state.chatId}/tests/${test.id}/reject`, { method: 'POST', headers: csrfHeaders() }); await snapshot(state, true); })); card.append(run, reject); } else if (test.output || test.status === 'RUNNING') { card.append(outputDetails(test.output, test.status === 'RUNNING' ? 'Running…' : (test.exitCode != null ? `Output (exit ${test.exitCode})` : 'Output'), test.status === 'RUNNING')); } return card; }
async function reviewAction(state, button, operation) { button.disabled = true; try { await operation(); } catch (error) { state.chat.querySelector('[data-chat-status]').textContent = error.message; } finally { if (button.isConnected) button.disabled = false; } }
function renderReviews(state) { const tray = state.chat.querySelector('[data-review-tray]'), items = tray.querySelector('[data-review-items]'); const pending = state.snapshot.runs.flatMap(value => [...value.proposals, ...value.tests, ...(value.packageInstalls || []), ...(value.terminalCommands || [])]).filter(value => value.status === 'PROPOSED' || value.status === 'APPROVED'); tray.hidden = pending.length === 0; tray.querySelector('[data-review-count]').textContent = `${pending.length} review${pending.length === 1 ? '' : 's'} waiting`; items.replaceChildren(); for (const proposal of pending.filter(value => value.filePath)) items.append(proposalCard(state, proposal)); for (const test of pending.filter(value => value.command && !value.source)) items.append(testCard(state, test)); for (const command of pending.filter(value => value.command && value.source)) items.append(terminalCard(state, command)); for (const install of pending.filter(value => value.packages)) items.append(packageCard(state, install)); }

async function openOrchestrationProfiles(state) {
  const dialog = state.root.querySelector('[data-orchestration-dialog]');
  const host = dialog.querySelector('[data-role-profiles]');
  const error = dialog.querySelector('[data-orchestration-error]');
  error.textContent = '';
  host.replaceChildren(el('div', 'workspace-skeleton', 'Loading role profiles…'));
  dialog.showModal();
  try {
    const profiles = await request(`/workspace/api/projects/${state.projectId}/orchestration-profile`);
    host.replaceChildren();
    for (const profile of profiles) host.append(roleProfileRow(state, profile));
    updateRoleWarning(dialog);
  } catch (failure) { error.textContent = failure.message; host.replaceChildren(); }
}

function roleProfileRow(state, profile) {
  const row = el('section', 'workspace-role-profile'); row.dataset.role = profile.role;
  row.append(el('h3', '', profile.role.charAt(0) + profile.role.slice(1).toLowerCase()));
  const grid = el('div', 'workspace-role-grid');
  const modelOptions = [...state.chat.querySelector('[data-model-selector]').options];
  // The picker lists one entry per instance and model; roles choose the instance and a model separately.
  const providerOptions = modelOptions.filter((option, index) => modelOptions.findIndex(other => other.value === option.value) === index);
  // Rows saved before provider instances only know the kind; map those to the first matching instance.
  const selectedProvider = profile.providerInstanceId
    || providerOptions.find(option => option.dataset.kind === profile.provider)?.value || '';
  const provider = labeledSelect('Provider', 'provider', providerOptions.map(option => [option.value, option.dataset.name || option.textContent]), selectedProvider);
  const model = labeledInput('Model', 'model', profile.model, 'text');
  // Offer the provider's known models while still accepting any free-text model ID.
  const modelList = document.createElement('datalist');
  modelList.id = `role-models-${profile.role}`;
  model.querySelector('input').setAttribute('list', modelList.id);
  const fillModels = () => modelList.replaceChildren(...[...new Set(modelOptions
    .filter(option => option.value === provider.querySelector('select').value).map(option => option.dataset.model))]
    .map(value => new Option(value)));
  provider.querySelector('select').addEventListener('change', fillModels);
  fillModels();
  model.append(modelList);
  const attempts = labeledInput('Attempts', 'maxAttempts', profile.maxAttempts, 'number', 1, 10);
  const turns = labeledInput('Turn budget', 'maxModelTurns', profile.maxModelTurns, 'number', 1, 100);
  const output = labeledInput('Output tokens', 'maxOutputTokens', profile.maxOutputTokens, 'number', 16, 1000000);
  const context = labeledInput('Context tokens', 'contextWindowTokens', profile.contextWindowTokens, 'number', 2048, 10000000);
  const reasoning = labeledSelect('Reasoning', 'reasoningEffort', [['', 'Provider default'], ...['NONE','MINIMAL','LOW','MEDIUM','HIGH','XHIGH','MAX'].map(value => [value, value])], profile.reasoningEffort || '');
  const verbosity = labeledSelect('Verbosity', 'textVerbosity', [['', 'Provider default'], ...['LOW','MEDIUM','HIGH'].map(value => [value, value])], profile.textVerbosity || '');
  grid.append(provider, model, attempts, turns, output, context, reasoning, verbosity);
  const addendum = document.createElement('label'); addendum.textContent = 'Project-specific role addendum';
  const textarea = el('textarea', 'field'); textarea.dataset.field = 'promptAddendum'; textarea.rows = 3; textarea.maxLength = 20000; textarea.value = profile.promptAddendum || ''; addendum.append(textarea);
  row.append(grid, addendum);
  row.addEventListener('change', () => updateRoleWarning(row.closest('dialog')));
  return row;
}

function labeledInput(labelText, field, value, type, min, max) {
  const label = document.createElement('label'); label.textContent = labelText;
  const input = el('input', 'field'); input.type = type; input.dataset.field = field; input.value = value ?? '';
  if (min != null) input.min = min; if (max != null) input.max = max; input.required = true; label.append(input); return label;
}

function labeledSelect(labelText, field, options, selected) {
  const label = document.createElement('label'); label.textContent = labelText;
  const select = el('select', 'field'); select.dataset.field = field;
  for (const [value, textValue] of options) select.append(new Option(textValue, value, false, value === selected));
  label.append(select); return label;
}

function updateRoleWarning(dialog) {
  const rows = [...dialog.querySelectorAll('[data-role]')];
  const implementer = rows.find(row => row.dataset.role === 'IMPLEMENTER');
  const reviewer = rows.find(row => row.dataset.role === 'REVIEWER');
  const same = implementer && reviewer
    && implementer.querySelector('[data-field="provider"]').value === reviewer.querySelector('[data-field="provider"]').value
    && implementer.querySelector('[data-field="model"]').value === reviewer.querySelector('[data-field="model"]').value;
  dialog.querySelector('[data-role-warning]').hidden = !same;
}

function wireOrchestrationDialog(state) {
  const dialog = state.root.querySelector('[data-orchestration-dialog]');
  dialog.querySelector('form').addEventListener('submit', async event => {
    if (event.submitter?.value !== 'confirm') return;
    event.preventDefault();
    const error = dialog.querySelector('[data-orchestration-error]'); error.textContent = '';
    const values = [...dialog.querySelectorAll('[data-role]')].map(row => {
      const get = field => row.querySelector(`[data-field="${field}"]`).value;
      const number = field => Number(get(field));
      return { role: row.dataset.role, providerInstanceId: get('provider'), model: get('model'),
        maxAttempts: number('maxAttempts'), maxModelTurns: number('maxModelTurns'),
        maxOutputTokens: number('maxOutputTokens'), contextWindowTokens: number('contextWindowTokens'),
        reasoningEffort: get('reasoningEffort') || null, textVerbosity: get('textVerbosity') || null,
        promptAddendum: get('promptAddendum') || null };
    });
    event.submitter.disabled = true;
    try {
      await request(`/workspace/api/projects/${state.projectId}/orchestration-profile`, { method: 'PUT', headers: { 'Content-Type': 'application/json', ...csrfHeaders() }, body: JSON.stringify(values) });
      dialog.close(); state.chat.querySelector('[data-chat-status]').textContent = 'Workflow role profiles saved';
    } catch (failure) { error.textContent = failure.message; }
    finally { event.submitter.disabled = false; }
  });
}

function connectStream(state) { state.stream?.close(); if (!state.chatId) return; const stream = new EventSource(`/chat/${state.chatId}/stream`); state.stream = stream; const refresh = () => { clearTimeout(state.streamRefresh); state.streamRefresh = setTimeout(() => snapshot(state, true), 150); }; ['snapshot','run-status','tool-lifecycle','summary-status','workflow-status','workflow-stage','workflow-artifact','workflow-question','workflow-transition'].forEach(name => stream.addEventListener(name, refresh)); stream.addEventListener('assistant-delta', event => { const follow = state.scroller.scrollHeight - state.scroller.scrollTop - state.scroller.clientHeight < 100; try { const value = JSON.parse(event.data); let output = state.scroller.querySelector(`[data-run-output="${value.runId}"]`); if (!output) { const card = el('article', 'workspace-message workspace-message-assistant'); card.append(el('small', 'workspace-writing', 'Petri · writing…')); output = el('p'); output.dataset.runOutput = value.runId; card.append(output); state.scroller.append(card); } const raw = (output.dataset.raw ?? output.textContent ?? '') + value.delta; clearTimeout(state.deltaSettle); state.deltaSettle = setTimeout(() => snapshot(state, true), 2500); scheduleMarkdown(output, raw); if (follow) state.scroller.scrollTop = state.scroller.scrollHeight; else state.chat.querySelector('[data-new-response]').hidden = false; } catch {} }); stream.onerror = () => { stream.close(); clearTimeout(state.poll); state.poll = setTimeout(() => { if (!document.hidden) snapshot(state, true); connectStream(state); }, 1800); }; }
