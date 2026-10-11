import { renderIcon } from './icons';
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
async function request(url, options = {}) { const response = await fetch(url, { headers: { Accept: 'application/json', ...options.headers }, ...options }); if (!response.ok) { let message = `Request failed (${response.status})`; try { message = (await response.json()).detail || message; } catch {} const error = new Error(message); error.status = response.status; throw error; } return response.status === 204 ? null : response.json(); }
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
  if (!worktreeId) return;
  const key = `project:${state.projectId}:worktree:${worktreeId}`;
  try { localStorage.setItem(`petri:last-worktree:${state.projectId}`, worktreeId); } catch {}
  start('editor', () => initializeEditor(state, key), '[data-editor-status]');
  start('explorer', () => initializeExplorer(state), '[data-file-tree]');
  start('changes', () => initializeChanges(state), '[data-change-count]');
  start('chat', () => initializeChat(state), '[data-chat-status]');
  localRead(key).then(value => { state.local = value; state.tabs = (value.tabs || []).slice(0, 12); state.drafts = value.drafts || {}; if (root.dataset.selectedFile) openFile(state, root.dataset.selectedFile); else if (state.tabs[0]) openFile(state, state.tabs[0]); });
}

function initializeLayout(state) {
  document.querySelector('[data-worktree-selector]')?.addEventListener('change', event => { const query = new URLSearchParams({ worktree: event.target.value }); location.assign(`/workspace/projects/${state.projectId}?${query}`); });
  document.querySelector('[data-toggle-chat]')?.addEventListener('click', () => state.root.classList.toggle('chat-collapsed'));
  state.root.querySelectorAll('[data-resize]').forEach(handle => { const kind = handle.dataset.resize; try { const width = localStorage.getItem(`petri:workspace:${kind}:width`); if (width) state.root.style.setProperty(`--${kind}-width`, width); } catch {} handle.addEventListener('pointerdown', event => { handle.setPointerCapture(event.pointerId); const move = pointer => { const width = kind === 'explorer' ? pointer.clientX : innerWidth - pointer.clientX; state.root.style.setProperty(`--${kind}-width`, `${Math.max(220, Math.min(560, width))}px`); }; const done = () => { try { localStorage.setItem(`petri:workspace:${kind}:width`, getComputedStyle(state.root).getPropertyValue(`--${kind}-width`)); } catch {} handle.removeEventListener('pointermove', move); }; handle.addEventListener('pointermove', move); handle.addEventListener('pointerup', done, { once: true }); }); });
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
  output.open = true;
  output.querySelector('[data-git-output-text]').textContent = error?.message || String(error);
}
function hideGitError(state) { const output = state.gitBar?.querySelector('[data-git-output]'); if (output) { output.open = false; output.hidden = true; } }

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
  dialog.showModal();
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
async function loadTree(state, path = '', target = state.tree) { if (state.mode !== 'files') return; if (target === state.tree) target.replaceChildren(el('div', 'workspace-loading', 'Loading files…')); try { const tree = await request(`${state.api}/tree?path=${encodeURIComponent(path)}`); target.replaceChildren(); if (!tree.entries.length) target.append(el('div', 'workspace-empty', path ? 'Empty directory' : 'No files in this worktree.')); for (const item of tree.entries) target.append(treeRow(state, item)); } catch (error) { target.replaceChildren(); const retry = el('button', 'workspace-error', `Could not load files: ${error.message}. Retry`); retry.addEventListener('click', () => loadTree(state, path, target)); target.append(retry); } }
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

function initializeChanges(state) { refreshChanges(state); window.addEventListener('focus', () => refreshChanges(state)); setInterval(() => { if (!document.hidden) refreshChanges(state); }, 8000); }
async function refreshChanges(state) {
  if (!state.api) return;
  try {
    applyGitStatus(state, await request(`${state.api}/git/status`));
  } catch (error) {
    showGitError(state, error);
  }
}
function applyGitStatus(state, status) {
  state.gitStatus = status;
  const values = [...(status.conflicts || []).map(path => ({ path, status: 'CONFLICT', staged: false, unstaged: true })), ...(status.staged || []), ...(status.unstaged || []), ...(status.untracked || [])];
  state.changes = new Map();
  for (const value of values) if (!state.changes.has(value.path) || value.status === 'CONFLICT') state.changes.set(value.path, value);
  const count = state.root.querySelector('[data-change-count]');
  if (count) count.textContent = state.changes.size;
  renderGitIndicators(state, status);
  if (state.mode === 'changes' && state.tree) renderChanges(state);
}
function renderChanges(state) {
  state.tree.replaceChildren();
  const status = state.gitStatus;
  if (!status || !state.changes.size) { state.tree.append(el('div', 'workspace-empty', 'Working tree is clean.')); return; }
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
  header.append(all);
  section.append(header);
  for (const change of values) {
    const row = el('button', 'workspace-change-row');
    row.type = 'button';
    row.append(renderIcon('FileDiff'), el('span', 'workspace-change-path', change.path), changeBadge(change));
    const action = el('span', 'workspace-change-row-action', stage ? '+' : '−');
    action.title = stage ? 'Stage' : 'Unstage';
    action.addEventListener('click', event => { event.stopPropagation(); updateIndex(state, stage, [change.path]); });
    row.append(action);
    row.addEventListener('click', () => openDiff(state, change.path, null, scope));
    section.append(row);
  }
  state.tree.append(section);
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
  const chat = state.root.querySelector('[data-workspace-chat]'), scroller = chat.querySelector('[data-chat-scroll]'), form = chat.querySelector('[data-chat-form]'), input = chat.querySelector('[data-chat-input]'), selector = chat.querySelector('[data-chat-selector]'), model = chat.querySelector('[data-model-selector]'), sendButton = chat.querySelector('[data-chat-send]'); state.chat = chat; state.scroller = scroller; const nearBottom = () => scroller.scrollHeight - scroller.scrollTop - scroller.clientHeight < 100; const bottom = () => { scroller.scrollTop = scroller.scrollHeight; chat.querySelector('[data-new-response]').hidden = true; }; chat.querySelector('[data-new-response]').addEventListener('click', bottom); scroller.addEventListener('scroll', () => { if (nearBottom()) chat.querySelector('[data-new-response]').hidden = true; });
  const sendMessage = async () => { const prompt = input.value.trim(); if (!prompt || sendButton.disabled) return; const option = model?.selectedOptions[0]; const body = { prompt, provider: model?.value || null, model: option?.dataset.model, maxModelTurns: 20, maxOutputTokens: option?.dataset.tokens ? Number(option.dataset.tokens) : null, contextWindowTokens: option?.dataset.context ? Number(option.dataset.context) : null, reasoningEffort: option?.dataset.reasoning || null, textVerbosity: option?.dataset.verbosity || null }; sendButton.disabled = true; try { const endpoint = state.chatId ? `/workspace/api/chats/${state.chatId}/messages` : `${state.api}/chats`; const result = await request(endpoint, { method: 'POST', headers: { 'Content-Type': 'application/json', ...csrfHeaders() }, body: JSON.stringify(body) }); state.chatId = result.chat.id; selector.value = state.chatId; input.value = ''; renderSnapshot(state, result); const url = new URL(location); url.searchParams.set('chat', state.chatId); history.replaceState(null, '', url); requestAnimationFrame(bottom); connectStream(state); } catch (error) { chat.querySelector('[data-chat-status]').textContent = error.message; } finally { sendButton.disabled = false; } };
  form.addEventListener('submit', event => { event.preventDefault(); sendMessage(); });
  sendButton.addEventListener('click', sendMessage);
  selector.addEventListener('change', () => { state.chatId = selector.value || null; const url = new URL(location); if (state.chatId) url.searchParams.set('chat', state.chatId); else url.searchParams.delete('chat'); history.replaceState(null, '', url); snapshot(state, true); connectStream(state); }); chat.querySelector('[data-new-chat]').addEventListener('click', () => { selector.value = ''; selector.dispatchEvent(new Event('change')); input.focus(); });
  model?.addEventListener('change', async () => { const option = model.selectedOptions[0]; try { await request(`/workspace/api/projects/${state.projectId}/model-preference`, { method: 'PUT', headers: { 'Content-Type': 'application/json', ...csrfHeaders() }, body: JSON.stringify({ provider: model.value, model: option.dataset.model }) }); chat.querySelector('[data-chat-status]').textContent = 'Model saved for this project'; } catch (error) { chat.querySelector('[data-chat-status]').textContent = error.message; } });
  chat.querySelector('[data-automation-menu]').addEventListener('click', () => { const panel = chat.querySelector('[data-automation-panel]'); panel.hidden = !panel.hidden; }); for (const inputAuto of chat.querySelectorAll('[data-auto-changes],[data-auto-tests]')) inputAuto.addEventListener('change', async event => { if (!state.chatId) return; if (event.target.matches('[data-auto-tests]') && event.target.checked && !confirm('Auto-run tests executes approved commands in this worktree. Continue?')) { event.target.checked = false; return; } await saveAutomation(state); }); if (state.chatId) { snapshot(state, true); connectStream(state); }
}
async function saveAutomation(state) { const chat = state.chat; try { const result = await request(`/workspace/api/chats/${state.chatId}/automation`, { method: 'PUT', headers: { 'Content-Type': 'application/json', ...csrfHeaders() }, body: JSON.stringify({ autoApproveChanges: chat.querySelector('[data-auto-changes]').checked, autoApproveTests: chat.querySelector('[data-auto-tests]').checked }) }); renderSnapshot(state, result); } catch (error) { chat.querySelector('[data-chat-status]').textContent = error.message; } }
async function snapshot(state, preservePosition = false) { if (!state.chatId) { renderSnapshot(state, null); return; } try { renderSnapshot(state, await request(`/workspace/api/chats/${state.chatId}/snapshot`), preservePosition); } catch (error) { state.chat.querySelector('[data-chat-status]').textContent = error.message; } }
function renderSnapshot(state, data, preservePosition = false) { const chat = state.chat, timeline = state.scroller, previousTop = timeline.scrollTop, wasBottom = timeline.scrollHeight - previousTop - timeline.clientHeight < 100; const empty = chat.querySelector('[data-chat-empty]'); state.snapshot = data; timeline.replaceChildren(); if (!data) { if (empty) { timeline.append(empty); empty.hidden = false; } chat.querySelector('[data-review-tray]').hidden = true; return; } chat.querySelector('[data-auto-changes]').checked = data.chat.autoApproveChanges; chat.querySelector('[data-auto-tests]').checked = data.chat.autoApproveTests; chat.querySelector('[data-chat-status]').textContent = data.chat.active ? 'Petri is working…' : data.chat.queuePaused ? 'Queue paused' : 'Ready'; for (const entry of data.runs) renderRun(state, entry, timeline); renderReviews(state); if (!preservePosition || wasBottom) timeline.scrollTop = timeline.scrollHeight; else timeline.scrollTop = previousTop; }
function renderRun(state, entry, timeline) { const run = entry.run; const user = el('article', 'workspace-message workspace-message-user'); user.append(el('p', '', run.prompt)); const meta = el('div', 'workspace-message-meta', `Turn ${run.chatMessageIndex + 1} · ${run.status}`); user.append(meta); timeline.append(user); for (const turn of run.turns || []) { if (turn.kind === 'USER_PROMPT') continue; if (turn.kind === 'MODEL_RESPONSE') { const assistant = el('article', 'workspace-message workspace-message-assistant'); const body = el('p'); body.dataset.raw = turn.content || ''; applyMarkdown(body, turn.content || ''); assistant.append(body); timeline.append(assistant); } else if (turn.kind === 'ERROR') timeline.append(el('article', 'workspace-message-error', turn.content || 'Agent error')); else { const details = el('details', 'workspace-tool-turn'); details.append(el('summary', '', turn.toolName || turn.kind), el('pre', '', turn.content || '')); timeline.append(details); } } if (run.partialOutput) { const partial = el('article', 'workspace-message workspace-message-assistant'); const body = el('p'); body.dataset.runOutput = run.id; body.dataset.raw = run.partialOutput; applyMarkdown(body, run.partialOutput); partial.append(el('small', 'workspace-writing', 'Petri · writing…'), body); timeline.append(partial); } for (const proposal of entry.proposals || []) timeline.append(proposalCard(state, proposal)); for (const test of entry.tests || []) timeline.append(testCard(state, test)); }
function proposalCard(state, proposal) { const card = el('article', 'workspace-review-card'); card.append(el('strong', '', proposal.filePath), el('span', 'workspace-review-state', proposal.status)); const diff = el('button', 'btn-secondary !min-h-8 !px-2 text-xs', 'View diff'); diff.type = 'button'; diff.addEventListener('click', () => openDiff(state, proposal.filePath, proposal.id)); card.append(diff); if (proposal.status === 'PROPOSED' || proposal.status === 'APPROVED') { const apply = el('button', 'btn-primary !min-h-8 !px-2 text-xs', 'Approve & apply'); apply.type = 'button'; apply.addEventListener('click', () => reviewAction(state, apply, async () => { await request(`/workspace/api/proposals/${proposal.id}/approve-and-apply`, { method: 'POST', headers: csrfHeaders() }); await snapshot(state, true); refreshChanges(state); })); const reject = el('button', 'btn-secondary !min-h-8 !px-2 text-xs', 'Reject'); reject.type = 'button'; reject.addEventListener('click', () => reviewAction(state, reject, async () => { await request(`/workspace/api/chats/${state.chatId}/proposals/${proposal.id}/reject`, { method: 'POST', headers: csrfHeaders() }); await snapshot(state, true); })); card.append(apply, reject); } return card; }
function testCard(state, test) { const card = el('article', 'workspace-review-card'); card.append(el('strong', '', test.command), el('span', 'workspace-review-state', test.status)); if (test.status === 'PROPOSED' || test.status === 'APPROVED') { const run = el('button', 'btn-primary !min-h-8 !px-2 text-xs', 'Run'); run.type = 'button'; run.addEventListener('click', () => reviewAction(state, run, async () => { await request(`/workspace/api/chats/${state.chatId}/tests/${test.id}/run`, { method: 'POST', headers: csrfHeaders() }); await snapshot(state, true); })); const reject = el('button', 'btn-secondary !min-h-8 !px-2 text-xs', 'Reject'); reject.type = 'button'; reject.addEventListener('click', () => reviewAction(state, reject, async () => { await request(`/workspace/api/chats/${state.chatId}/tests/${test.id}/reject`, { method: 'POST', headers: csrfHeaders() }); await snapshot(state, true); })); card.append(run, reject); } return card; }
async function reviewAction(state, button, operation) { button.disabled = true; try { await operation(); } catch (error) { state.chat.querySelector('[data-chat-status]').textContent = error.message; } finally { if (button.isConnected) button.disabled = false; } }
function renderReviews(state) { const tray = state.chat.querySelector('[data-review-tray]'), items = tray.querySelector('[data-review-items]'); const pending = state.snapshot.runs.flatMap(value => [...value.proposals, ...value.tests]).filter(value => value.status === 'PROPOSED' || value.status === 'APPROVED'); tray.hidden = pending.length === 0; tray.querySelector('[data-review-count]').textContent = `${pending.length} review${pending.length === 1 ? '' : 's'} waiting`; items.replaceChildren(); for (const proposal of pending.filter(value => value.filePath)) items.append(proposalCard(state, proposal)); for (const test of pending.filter(value => value.command)) items.append(testCard(state, test)); }
function connectStream(state) { state.stream?.close(); if (!state.chatId) return; const stream = new EventSource(`/chat/${state.chatId}/stream`); state.stream = stream; const refresh = () => { clearTimeout(state.streamRefresh); state.streamRefresh = setTimeout(() => snapshot(state, true), 150); }; ['snapshot','run-status','tool-lifecycle','summary-status'].forEach(name => stream.addEventListener(name, refresh)); stream.addEventListener('assistant-delta', event => { const follow = state.scroller.scrollHeight - state.scroller.scrollTop - state.scroller.clientHeight < 100; try { const value = JSON.parse(event.data); let output = state.scroller.querySelector(`[data-run-output="${value.runId}"]`); if (!output) { const card = el('article', 'workspace-message workspace-message-assistant'); card.append(el('small', 'workspace-writing', 'Petri · writing…')); output = el('p'); output.dataset.runOutput = value.runId; card.append(output); state.scroller.append(card); } const raw = (output.dataset.raw ?? output.textContent ?? '') + value.delta; scheduleMarkdown(output, raw); if (follow) state.scroller.scrollTop = state.scroller.scrollHeight; else state.chat.querySelector('[data-new-response]').hidden = false; } catch {} }); stream.onerror = () => { stream.close(); clearTimeout(state.poll); state.poll = setTimeout(() => { if (!document.hidden) snapshot(state, true); connectStream(state); }, 1800); }; }
