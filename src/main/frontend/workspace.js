import { basicSetup, EditorView } from 'codemirror';
import { EditorState } from '@codemirror/state';
import { keymap } from '@codemirror/view';
import { defaultKeymap, indentWithTab } from '@codemirror/commands';
import { java } from '@codemirror/lang-java';
import { javascript } from '@codemirror/lang-javascript';
import { json } from '@codemirror/lang-json';
import { html } from '@codemirror/lang-html';
import { css } from '@codemirror/lang-css';
import { markdown } from '@codemirror/lang-markdown';
import { xml } from '@codemirror/lang-xml';
import { yaml } from '@codemirror/lang-yaml';

const DB_NAME = 'petri-workspace';
const STORE_NAME = 'worktree-state';

function csrfHeaders() {
  const token = document.querySelector('meta[name="_csrf"]')?.content;
  const header = document.querySelector('meta[name="_csrf_header"]')?.content;
  return token && header ? { [header]: token } : {};
}

function openStore() {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, 1);
    request.onupgradeneeded = () => request.result.createObjectStore(STORE_NAME);
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

async function readLocalState(key) {
  try {
    const db = await openStore();
    return await new Promise((resolve, reject) => {
      const request = db.transaction(STORE_NAME, 'readonly').objectStore(STORE_NAME).get(key);
      request.onsuccess = () => resolve(request.result || { tabs: [], drafts: {} });
      request.onerror = () => reject(request.error);
    });
  } catch { return { tabs: [], drafts: {} }; }
}

async function writeLocalState(key, value) {
  try {
    const db = await openStore();
    await new Promise((resolve, reject) => {
      const request = db.transaction(STORE_NAME, 'readwrite').objectStore(STORE_NAME).put(value, key);
      request.onsuccess = () => resolve();
      request.onerror = () => reject(request.error);
    });
  } catch { /* Browser storage is optional; the editor remains usable. */ }
}

function languageFor(path) {
  const extension = path.split('.').pop()?.toLowerCase();
  if (extension === 'java') return java();
  if (['js', 'mjs', 'cjs', 'ts', 'tsx', 'jsx'].includes(extension)) return javascript({ jsx: ['jsx', 'tsx'].includes(extension), typescript: ['ts', 'tsx'].includes(extension) });
  if (extension === 'json') return json();
  if (['html', 'htm'].includes(extension)) return html();
  if (extension === 'css') return css();
  if (['md', 'markdown'].includes(extension)) return markdown();
  if (['xml', 'svg', 'pom'].includes(extension)) return xml();
  if (['yml', 'yaml'].includes(extension)) return yaml();
  return [];
}

function text(value) { return document.createTextNode(value); }

export function initializeWorkspace() {
  const root = document.querySelector('[data-workspace]');
  if (!root || !root.dataset.worktreeId || root.dataset.workspaceInitialized) return;
  root.dataset.workspaceInitialized = 'true';
  const projectId = root.dataset.projectId;
  const worktreeId = root.dataset.worktreeId;
  try {
    const remembered = localStorage.getItem(`petri:last-worktree:${projectId}`);
    const query = new URLSearchParams(window.location.search);
    if (!query.has('worktree') && remembered && remembered !== worktreeId) {
      window.location.replace(`/workspace/projects/${projectId}?worktree=${encodeURIComponent(remembered)}`);
      return;
    }
    localStorage.setItem(`petri:last-worktree:${projectId}`, worktreeId);
  } catch { /* The server's newest active worktree remains the fallback. */ }
  const api = `/workspace/api/worktrees/${worktreeId}`;
  const tree = root.querySelector('[data-file-tree]');
  const tabs = root.querySelector('[data-editor-tabs]');
  const host = root.querySelector('[data-editor-host]');
  const status = root.querySelector('[data-editor-status]');
  const cursor = root.querySelector('[data-editor-cursor]');
  const stateKey = `project:${projectId}:worktree:${worktreeId}`;
  const session = { tabs: [], active: null, drafts: {}, views: new Map(), local: { tabs: [], drafts: {} } };

  const setStatus = (message, error = false) => {
    if (!status) return;
    status.textContent = message;
    status.classList.toggle('text-red-300', error);
  };
  const saveLocal = () => writeLocalState(stateKey, {
    tabs: session.tabs,
    active: session.active,
    drafts: session.drafts
  });
  const fetchJson = async (url, options = {}) => {
    const controller = new AbortController();
    const timeout = window.setTimeout(() => controller.abort(), 12_000);
    let response;
    try {
      response = await fetch(url, {
        headers: { Accept: 'application/json', ...options.headers },
        signal: controller.signal,
        ...options
      });
    } catch (error) {
      if (error.name === 'AbortError') throw new Error('Workspace request timed out. Reload the page and try again.');
      throw error;
    } finally {
      window.clearTimeout(timeout);
    }
    if (!response.ok) {
      let message = `Request failed (${response.status})`;
      try { message = (await response.json()).detail || message; } catch { /* no problem payload */ }
      const error = new Error(message); error.status = response.status; throw error;
    }
    return response.status === 204 ? null : response.json();
  };
  const draftFor = (path) => session.drafts[path];
  const renderTabs = () => {
    tabs.replaceChildren();
    if (!session.tabs.length) { tabs.append(text('No file open')); return; }
    session.tabs.forEach(path => {
      const button = document.createElement('button'); button.type = 'button';
      button.className = `workspace-tab${path === session.active ? ' is-active' : ''}`;
      button.dataset.path = path;
      button.append(text(path.split('/').pop()));
      if (draftFor(path)?.dirty) button.append(text(' •'));
      const close = document.createElement('span'); close.className = 'workspace-tab-close'; close.textContent = '×';
      close.addEventListener('click', event => { event.stopPropagation(); closeTab(path); }); button.append(close);
      button.addEventListener('click', () => activate(path)); tabs.append(button);
    });
  };
  const updateCursor = (view) => {
    const line = view.state.doc.lineAt(view.state.selection.main.head);
    cursor.textContent = `Ln ${line.number}, Col ${view.state.selection.main.head - line.from + 1}`;
  };
  const mountEditor = (path) => {
    host.replaceChildren();
    const draft = draftFor(path);
    if (!draft) return;
    const view = new EditorView({
      state: EditorState.create({ doc: draft.content, extensions: [basicSetup, keymap.of([...defaultKeymap, indentWithTab]), languageFor(path), EditorView.updateListener.of(update => {
        if (!update.docChanged) { updateCursor(update.view); return; }
        const current = session.drafts[path]; if (!current) return;
        current.content = update.state.doc.toString(); current.dirty = current.content !== current.original;
        renderTabs(); saveLocal(); setStatus(current.dirty ? 'Unsaved changes' : 'Saved'); updateCursor(update.view);
      })] }), parent: host
    });
    session.views.set(path, view); updateCursor(view);
  };
  const activate = (path) => {
    session.active = path; renderTabs(); mountEditor(path); saveLocal();
  };
  const closeTab = (path) => {
    if (draftFor(path)?.dirty && !window.confirm(`Discard unsaved changes in ${path}?`)) return;
    delete session.drafts[path]; session.tabs = session.tabs.filter(item => item !== path); session.views.get(path)?.destroy(); session.views.delete(path);
    session.active = session.tabs.at(-1) || null; renderTabs();
    if (session.active) mountEditor(session.active); else host.innerHTML = '<div class="grid h-full place-items-center p-8 text-center text-ink-500">Select a text file in the explorer.</div>';
    saveLocal();
  };
  const openFile = async (path) => {
    if (session.tabs.includes(path)) return activate(path);
    setStatus(`Opening ${path}…`);
    try {
      const file = await fetchJson(`${api}/files?path=${encodeURIComponent(path)}`);
      const restored = session.local.drafts?.[path];
      session.drafts[path] = restored && restored.originalSha256 === file.sha256 ? restored : {
        content: file.content, original: file.content, originalSha256: file.sha256, dirty: false
      };
      session.tabs.push(path); activate(path); setStatus(session.drafts[path].dirty ? 'Restored unsaved draft' : 'Ready');
    } catch (error) { setStatus(error.message, true); }
  };
  const saveFile = async () => {
    const path = session.active; const draft = draftFor(path); if (!path || !draft || !draft.dirty) return;
    setStatus(`Saving ${path}…`);
    try {
      const file = await fetchJson(`${api}/files`, { method: 'PUT', headers: { 'Content-Type': 'application/json', ...csrfHeaders() }, body: JSON.stringify({ path, content: draft.content, expectedSha256: draft.originalSha256 }) });
      Object.assign(draft, { original: file.content, content: file.content, originalSha256: file.sha256, dirty: false });
      renderTabs(); saveLocal(); setStatus('Saved');
    } catch (error) {
      if (error.status === 409) setStatus('Conflict: file changed on disk. Keep draft or reload it.', true);
      else setStatus(error.message, true);
    }
  };
  const makeTreeEntry = (entry, parent) => {
    const row = document.createElement('div'); row.className = 'workspace-tree-row';
    const button = document.createElement('button'); button.type = 'button'; button.className = 'workspace-tree-entry';
    button.append(text(`${entry.type === 'DIRECTORY' ? '▸' : '•'} ${entry.path.split('/').pop()}`)); row.append(button);
    const actions = document.createElement('button'); actions.type = 'button'; actions.className = 'workspace-tree-actions'; actions.textContent = '⋯';
    actions.title = 'Move or delete'; actions.addEventListener('click', event => { event.stopPropagation(); entryActions(entry); }); row.append(actions);
    if (entry.type === 'DIRECTORY') {
      const children = document.createElement('div'); children.className = 'workspace-tree-children hidden'; row.append(children);
      button.addEventListener('click', async () => {
        if (!children.dataset.loaded) { await loadTree(entry.path, children); children.dataset.loaded = 'true'; }
        children.classList.toggle('hidden'); button.firstChild.textContent = `${children.classList.contains('hidden') ? '▸' : '▾'} ${entry.path.split('/').pop()}`;
      });
    } else if (entry.type === 'FILE') button.addEventListener('click', () => openFile(entry.path));
    parent.append(row);
  };
  const loadTree = async (path = '.', target = tree) => {
    try {
      const result = await fetchJson(`${api}/tree?path=${encodeURIComponent(path)}`);
      target.replaceChildren();
      result.entries.forEach(entry => makeTreeEntry(entry, target));
      if (!result.entries.length) target.append(text('Empty directory'));
    } catch (error) {
      target.replaceChildren(text(`Could not load files: ${error.message}`));
      setStatus(error.message, true);
    }
  };
  const currentHash = async (entry) => {
    if (entry.type !== 'FILE') return null;
    const open = draftFor(entry.path);
    if (open?.dirty) throw new Error('Save or discard the open draft before moving or deleting this file');
    if (open) return open.originalSha256;
    return (await fetchJson(`${api}/files?path=${encodeURIComponent(entry.path)}`)).sha256;
  };
  const entryActions = async (entry) => {
    const action = window.prompt(`Action for ${entry.path}: move or delete`); if (!action) return;
    try {
      if (action.toLowerCase() === 'move') {
        const targetPath = window.prompt('New relative path', entry.path); if (!targetPath || targetPath === entry.path) return;
        await fetchJson(`${api}/entries/move`, { method: 'POST', headers: { 'Content-Type': 'application/json', ...csrfHeaders() }, body: JSON.stringify({ sourcePath: entry.path, targetPath, expectedSha256: await currentHash(entry) }) });
        if (session.tabs.includes(entry.path)) { closeTab(entry.path); }
        await loadTree(); setStatus('Moved'); return;
      }
      if (action.toLowerCase() !== 'delete') { setStatus('Enter “move” or “delete”.', true); return; }
      let recursive = false; let confirmationPath = null;
      if (entry.type === 'DIRECTORY') {
        confirmationPath = window.prompt(`Type the exact path to recursively delete ${entry.path}`); if (!confirmationPath) return;
        recursive = true;
      } else if (!window.confirm(`Delete ${entry.path}?`)) return;
      await fetchJson(`${api}/entries/delete`, { method: 'POST', headers: { 'Content-Type': 'application/json', ...csrfHeaders() }, body: JSON.stringify({ path: entry.path, expectedSha256: await currentHash(entry), recursive, confirmationPath }) });
      if (session.tabs.includes(entry.path)) closeTab(entry.path); await loadTree(); setStatus('Deleted');
    } catch (error) { setStatus(error.message, true); }
  };
  const createEntry = async (type) => {
    const path = window.prompt(type === 'DIRECTORY' ? 'Directory path' : 'File path'); if (!path) return;
    try {
      await fetchJson(`${api}/entries`, { method: 'POST', headers: { 'Content-Type': 'application/json', ...csrfHeaders() }, body: JSON.stringify({ path, type, content: '' }) });
      await loadTree(); if (type === 'FILE') await openFile(path); setStatus('Created');
    } catch (error) { setStatus(error.message, true); }
  };
  root.querySelectorAll('[data-create-entry]').forEach(button => button.addEventListener('click', () => createEntry(button.dataset.createEntry)));
  document.addEventListener('keydown', event => {
    if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 's' && root.contains(document.activeElement)) { event.preventDefault(); saveFile(); }
  });
  document.querySelector('[data-worktree-selector]')?.addEventListener('change', event => {
    try { localStorage.setItem(`petri:last-worktree:${projectId}`, event.target.value); } catch { /* optional */ }
    window.location.assign(`/workspace/projects/${projectId}?worktree=${encodeURIComponent(event.target.value)}`);
  });
  root.querySelector('[data-chat-selector]')?.addEventListener('change', event => {
    const query = new URLSearchParams({ worktree: worktreeId }); if (event.target.value) query.set('chat', event.target.value);
    window.location.assign(`/workspace/projects/${projectId}?${query}`);
  });
  document.querySelector('[data-toggle-chat]')?.addEventListener('click', () => root.classList.toggle('chat-collapsed'));
  root.querySelectorAll('[data-resize]').forEach(handle => {
    handle.addEventListener('pointerdown', event => {
      const key = `petri:workspace:${handle.dataset.resize}:width`; const edge = handle.dataset.resize === 'explorer' ? 'left' : 'right';
      handle.setPointerCapture(event.pointerId);
      const move = pointer => { const width = edge === 'left' ? pointer.clientX : window.innerWidth - pointer.clientX; root.style.setProperty(`--${handle.dataset.resize}-width`, `${Math.max(180, Math.min(520, width))}px`); };
      const up = () => { try { localStorage.setItem(key, getComputedStyle(root).getPropertyValue(`--${handle.dataset.resize}-width`)); } catch { /* optional */ } handle.removeEventListener('pointermove', move); };
      handle.addEventListener('pointermove', move); handle.addEventListener('pointerup', up, { once: true });
    });
    try { const width = localStorage.getItem(`petri:workspace:${handle.dataset.resize}:width`); if (width) root.style.setProperty(`--${handle.dataset.resize}-width`, width); } catch { /* optional */ }
  });
  // File discovery must not depend on IndexedDB. Some privacy modes keep an IndexedDB open
  // request pending, which previously left the explorer at “Loading files…” indefinitely.
  loadTree();
  initializeWorkspaceChat(root);
  readLocalState(stateKey).then(local => {
    session.local = local;
    session.tabs = (local.tabs || []).slice(0, 12);
    if (root.dataset.selectedFile) openFile(root.dataset.selectedFile);
    else if (session.tabs[0]) openFile(session.tabs[0]);
  });
}

function initializeWorkspaceChat(root) {
  const panel = root.querySelector('[data-chat-id]');
  const scroller = root.querySelector('[data-chat-scroll]');
  const chatId = panel?.dataset.chatId;
  if (!chatId || panel.dataset.workspaceChatInitialized) return;
  panel.dataset.workspaceChatInitialized = 'true';
  const atBottom = () => !scroller || scroller.scrollHeight - scroller.scrollTop - scroller.clientHeight < 80;
  const ensureOutput = (runId) => {
    let output = root.querySelector(`[data-run-output="${runId}"]`);
    if (output) return output;
    const shell = document.createElement('article');
    shell.className = 'mr-5 rounded-xl rounded-bl-sm border border-ink-700 bg-ink-800 px-3 py-2.5 text-sm text-ink-300';
    const title = document.createElement('p'); title.className = 'mb-1 text-[11px] font-bold uppercase tracking-wider text-brand-400'; title.textContent = 'Petri · writing…';
    output = document.createElement('p'); output.className = 'whitespace-pre-wrap break-words'; output.dataset.runOutput = runId;
    shell.append(title, output); scroller?.append(shell); return output;
  };
  const stream = new EventSource(`/chat/${chatId}/stream`);
  stream.addEventListener('assistant-delta', event => {
    try {
      const update = JSON.parse(event.data); const follow = atBottom();
      ensureOutput(update.runId).textContent += update.delta;
      if (follow && scroller) scroller.scrollTop = scroller.scrollHeight;
    } catch { /* A polling refresh below recovers from a malformed event. */ }
  });
  let signature = null;
  const timer = window.setInterval(async () => {
    if (document.hidden) return;
    try {
      const response = await fetch(`/chat/${chatId}/state`, { headers: { Accept: 'application/json' }, cache: 'no-store' });
      if (!response.ok) return;
      const state = await response.json();
      const nextSignature = JSON.stringify(state.runs.map(run => [run.id, run.status, run.turns.length, run.partialOutput]));
      if (signature !== null && signature !== nextSignature) window.location.reload();
      signature = nextSignature;
    } catch { /* Keep the existing timeline until the next poll succeeds. */ }
  }, 1200);
  window.addEventListener('beforeunload', () => { window.clearInterval(timer); stream.close(); }, { once: true });
}

// This listener is registered by the workspace module itself, before the page bundle's
// compatibility code. Explorer availability must not depend on another page feature.
window.addEventListener('DOMContentLoaded', initializeWorkspace);
