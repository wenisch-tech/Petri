import { renderIcon } from './icons';

const POLL_MS = 5000;
const STATES = {
  ATTENTION: { label: 'Needs attention', icon: 'TriangleAlert' },
  WAITING: { label: 'Waiting for you', icon: 'Hourglass' },
  WORKING: { label: 'Working', icon: 'LoaderCircle' },
  IDLE: { label: 'Idle', icon: 'Check' }
};
const RESULTS = {
  COMPLETED: ['Completed', 'good'], FAILED: ['Failed', 'danger'], CANCELLED: ['Cancelled', 'muted'],
  BUDGET_EXHAUSTED: ['Budget exhausted', 'danger'], POSTED: ['Posted', 'good'], REVIEWED: ['Reviewed', 'good'], IMPROVING: ['Improving', 'brand'], SKIPPED: ['Skipped', 'muted'],
  RUNNING: ['Running', 'brand'], QUEUED: ['Queued', 'brand'], AWAITING_APPROVAL: ['Awaiting approval', 'warn']
};

function el(tag, className = '', text = '') {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text) node.textContent = text;
  return node;
}

/** Writes only what changed, so the DOM (and its animations) stays calm between polls. */
function setText(node, value) {
  const text = value == null ? '' : String(value);
  if (node.textContent !== text) node.textContent = text;
}

function setAttr(node, name, value) {
  if (node.getAttribute(name) !== String(value)) node.setAttribute(name, String(value));
}

function setClass(node, className) {
  if (node.dataset.cls !== className) { node.className = className; node.dataset.cls = className; }
}

function relative(value) {
  if (!value) return '';
  const seconds = Math.max(0, Math.round((Date.now() - new Date(value).getTime()) / 1000));
  if (seconds < 45) return 'just now';
  if (seconds < 3600) return `${Math.round(seconds / 60)} min ago`;
  if (seconds < 86400) return `${Math.round(seconds / 3600)} h ago`;
  return `${Math.round(seconds / 86400)} d ago`;
}

function worktreeHref(projectId, worktree) {
  const url = new URL(`/workspace/projects/${projectId}`, location.origin);
  url.searchParams.set('worktree', worktree.id);
  if (worktree.chatId) url.searchParams.set('chat', worktree.chatId);
  return url.pathname + url.search;
}

function icon(name) { return renderIcon(name); }

/** The repository as a short, readable link: host and path, no scheme or .git. */
function repositoryLink(uri) {
  if (!uri) return { label: '', href: null };
  const label = uri.replace(/^[a-z+]+:\/\//i, '').replace(/^[^@/]+@/, '').replace(/\.git$/, '');
  const web = /^https?:\/\//i.test(uri) ? uri.replace(/\.git$/, '') : null;
  return { label, href: web };
}

// ---- vulnerabilities --------------------------------------------------------------------------

const SEVERITY_ORDER = [['critical', 'Critical'], ['high', 'High'], ['medium', 'Medium'], ['low', 'Low']];

// Counts per severity of the latest scan. `compact` keeps only what needs attention first.
function patchVulnerabilities(container, summary, href, compact) {
  const signature = JSON.stringify(summary ? [summary.branch, summary.critical, summary.high, summary.medium, summary.low, summary.complete] : null);
  if (container.dataset.sig === signature) return;
  container.dataset.sig = signature;
  container.replaceChildren();
  if (!summary) {
    if (compact) { container.hidden = true; return; }
    container.hidden = false;
    const link = el('a', 'vuln-mini is-none', 'Not scanned');
    link.href = href;
    link.title = 'No vulnerability scan yet. Open the Vulnerabilities tab to run one.';
    container.append(el('span', 'dash-automations-label', 'Vulnerabilities'), link);
    return;
  }
  container.hidden = false;
  const link = el('a', 'dash-vuln-link');
  link.href = href;
  link.title = `Latest scan of ${summary.branch || 'the branch'}${summary.complete ? '' : ' (incomplete)'}`;
  if (!compact) container.append(el('span', 'dash-automations-label', `Vulnerabilities${summary.branch ? ` · ${summary.branch}` : ''}`));
  let shown = 0;
  for (const [key, label] of SEVERITY_ORDER) {
    const count = summary[key];
    if (!count) continue;
    const chip = el('span', `vuln-mini is-${key}`);
    chip.append(el('strong', '', String(count)), el('span', '', compact ? label[0] : label));
    chip.title = `${count} ${label.toLowerCase()}`;
    link.append(chip);
    shown++;
  }
  if (!shown) link.append(el('span', 'vuln-mini is-clean', compact ? '0' : 'No findings'));
  if (!summary.complete) link.append(el('span', 'vuln-mini is-none', compact ? '?' : 'incomplete'));
  container.append(link);
}

// ---- worktree row -----------------------------------------------------------------------------

function createWorktree() {
  const item = el('li');
  const link = el('a');
  const head = el('span', 'dash-worktree-head');
  const branch = el('span', 'dash-branch');
  const chip = el('span');
  const chipIcon = el('span', 'dash-chip-icon');
  const chipText = el('span');
  chip.append(chipIcon, chipText);
  const changes = el('span', 'dash-changes');
  changes.append(icon('FileDiff'), el('span'));
  const vulns = el('span', 'dash-vulns-inline');
  vulns.hidden = true;
  head.append(icon('GitBranch'), branch, chip, vulns, changes);
  const detail = el('span', 'dash-detail');
  const message = el('span', 'dash-message');
  message.append(icon('Bot'), el('span', 'dash-message-text'));
  const result = el('span', 'dash-result');
  link.append(head, detail, message, result);
  item.append(link);
  return { item, link, branch, chip, chipIcon, chipText, changes, vulns, detail, message, result, state: null };
}

function patchWorktree(ref, projectId, worktree) {
  setAttr(ref.link, 'href', worktreeHref(projectId, worktree));
  setClass(ref.link, `dash-worktree is-${worktree.state.toLowerCase()}`);
  setText(ref.branch, worktree.branch || `detached HEAD · ${worktree.id.slice(0, 6)}`);
  setClass(ref.chip, 'dash-chip');
  if (ref.state !== worktree.state) {
    ref.chipIcon.replaceChildren(icon(STATES[worktree.state].icon));
    ref.state = worktree.state;
  }
  setText(ref.chipText, worktree.headline);
  patchVulnerabilities(ref.vulns, worktree.vulnerabilities, `/workspace/projects/${projectId}?view=vulnerabilities`, true);
  if (ref.changes.hidden !== (worktree.pendingChanges === 0)) ref.changes.hidden = worktree.pendingChanges === 0;
  setText(ref.changes.lastChild, worktree.pendingChanges);
  setAttr(ref.changes, 'title', `${worktree.pendingChanges} change${worktree.pendingChanges === 1 ? '' : 's'} awaiting review`);
  if (ref.detail.hidden !== !worktree.detail) ref.detail.hidden = !worktree.detail;
  setText(ref.detail, worktree.detail);
  const message = worktree.lastMessage;
  if (ref.message.hidden !== !message) ref.message.hidden = !message;
  if (message) {
    setText(ref.message.querySelector('.dash-message-text'), message.text);
  }
  const last = worktree.lastResult;
  const [label, tone] = last ? (RESULTS[last.status] || [last.status, 'muted']) : ['', 'muted'];
  if (ref.result.hidden !== !last) ref.result.hidden = !last;
  const stale = last?.at && Date.now() - new Date(last.at).getTime() > 86400000;
  const finalTone = stale && tone === 'danger' ? 'muted' : tone;
  if (ref.result.dataset.tone !== finalTone) ref.result.dataset.tone = finalTone;
  setText(ref.result, last
    ? `Latest: ${label}${last.at ? ` ${relative(last.at)}` : ''} · ${worktree.runsTotal} run${worktree.runsTotal === 1 ? '' : 's'}`
    : '');
  for (const [key, value] of [['at', last?.at || ''], ['label', label], ['total', String(worktree.runsTotal)]]) {
    if (ref.result.dataset[key] !== value) ref.result.dataset[key] = value;
  }
}

// ---- project card -----------------------------------------------------------------------------

function stat(label, hint) {
  const item = el('div', 'dash-stat');
  const value = el('dd');
  item.append(value, el('dt', '', label));
  if (hint) item.title = hint;
  return { item, value };
}

function action(href, label, iconName, primary, projectName) {
  const link = el('a', primary ? 'dash-action is-primary' : 'dash-action');
  link.href = href;
  link.append(icon(iconName), el('span', '', label));
  link.setAttribute('aria-label', `${label} of ${projectName}`);
  return link;
}

function createCard(card) {
  const root = el('article', 'dash-card is-new');
  root.addEventListener('animationend', () => root.classList.remove('is-new'), { once: true });
  const header = el('header', 'dash-card-header');
  const orb = el('span', 'dash-orb');
  const titles = el('div', 'min-w-0 flex-1');
  const title = el('a', 'dash-title');
  const headline = el('a', 'dash-headline dash-repo');
  headline.target = '_blank';
  headline.rel = 'noopener noreferrer';
  titles.append(title, headline);
  const time = el('time', 'dash-time');
  header.append(orb, titles, time);

  const automations = el('div', 'dash-automations');
  const vulns = el('div', 'dash-automations dash-vulns');
  const stats = el('dl', 'dash-stats');
  const statRefs = {
    working: stat('Working', 'Worktrees with an agent running'),
    review: stat('To review', 'Proposed changes waiting for approval'),
    runs: stat('Runs', 'All runs on this project')
  };
  Object.values(statRefs).forEach(ref => stats.append(ref.item));

  const busy = el('ul', 'dash-worktrees');
  const idleBox = el('details', 'dash-idle');
  const idleSummary = el('summary');
  const idle = el('ul', 'dash-worktrees');
  idleBox.append(idleSummary, idle);

  const actions = el('footer', 'dash-actions');
  const open = action(`/workspace/projects/${card.id}`, 'Workspace', 'FileCode2', true, card.name);
  const auto = action(`/workspace/projects/${card.id}?view=automations`, 'Automations', 'Bot', false, card.name);
  const settings = action(`/workspace/projects/${card.id}?view=settings`, 'Settings', 'Settings', false, card.name);
  actions.append(open, auto, settings);

  root.append(header, automations, vulns, stats, busy, idleBox, actions);
  return {
    root, orb, title, headline, time, automations, vulns, statRefs, busy, idleBox, idleSummary, idle,
    worktrees: new Map(), state: null, empty: el('li', 'dash-empty')
  };
}

function patchAutomations(ref, list) {
  if (ref.automations.hidden !== (list.length === 0)) ref.automations.hidden = list.length === 0;
  const signature = JSON.stringify(list.map(item => [item.name, item.trigger, item.lastStatus]));
  if (ref.automations.dataset.sig === signature) return;
  ref.automations.dataset.sig = signature;
  ref.automations.replaceChildren(el('span', 'dash-automations-label', 'Automations'));
  for (const item of list) {
    const chip = el('a', 'dash-automation');
    chip.href = `/workspace/projects/${ref.root.dataset.id}?view=automations`;
    const [label, tone] = item.lastStatus ? (RESULTS[item.lastStatus] || [item.lastStatus, 'muted']) : ['No run yet', 'muted'];
    chip.dataset.tone = tone;
    chip.title = `${item.name} ${item.trigger}. Latest: ${label}${item.lastAt ? ` ${relative(item.lastAt)}` : ''}`;
    chip.append(el('i', 'dash-dot'), el('span', '', `${item.name} · ${item.trigger}`));
    ref.automations.append(chip);
  }
}

function syncList(container, nodes) {
  nodes.forEach((node, index) => {
    if (container.children[index] !== node) container.insertBefore(node, container.children[index] || null);
  });
  while (container.children.length > nodes.length) container.lastElementChild.remove();
}

function patchCard(ref, card) {
  if (ref.root.dataset.id !== card.id) ref.root.dataset.id = card.id;
  setClass(ref.root, `dash-card is-${card.state.toLowerCase()}${ref.root.classList.contains('is-new') ? ' is-new' : ''}`);
  if (ref.state !== card.state) {
    ref.orb.replaceChildren(icon(STATES[card.state].icon));
    ref.orb.title = STATES[card.state].label;
    ref.state = card.state;
  }
  setAttr(ref.title, 'href', `/workspace/projects/${card.id}`);
  setText(ref.title, card.name);
  const repo = repositoryLink(card.repositoryUri);
  setText(ref.headline, repo.label);
  if (repo.href) { setAttr(ref.headline, 'href', repo.href); setAttr(ref.headline, 'title', `Open ${repo.href}`); }
  else { ref.headline.removeAttribute('href'); }
  if (ref.time.dataset.at !== (card.lastActivityAt || '')) ref.time.dataset.at = card.lastActivityAt || '';
  setText(ref.time, relative(card.lastActivityAt) || 'no activity yet');
  patchAutomations(ref, card.automations || []);
  patchVulnerabilities(ref.vulns, card.vulnerabilities, `/workspace/projects/${card.id}?view=vulnerabilities`, false);
  setText(ref.statRefs.working.value, card.working);
  setText(ref.statRefs.review.value, card.pendingChanges);
  setText(ref.statRefs.runs.value, card.runsTotal);
  ref.statRefs.working.item.classList.toggle('is-zero', card.working === 0);
  ref.statRefs.review.item.classList.toggle('is-zero', card.pendingChanges === 0);
  ref.statRefs.runs.item.classList.toggle('is-zero', card.runsTotal === 0);

  const seen = new Set();
  const busy = [];
  const idle = [];
  for (const worktree of card.worktrees) {
    seen.add(worktree.id);
    let row = ref.worktrees.get(worktree.id);
    if (!row) { row = createWorktree(); ref.worktrees.set(worktree.id, row); }
    patchWorktree(row, card.id, worktree);
    (worktree.state === 'IDLE' ? idle : busy).push(row.item);
  }
  for (const id of [...ref.worktrees.keys()]) if (!seen.has(id)) ref.worktrees.delete(id);
  if (!card.worktrees.length) setText(ref.empty, 'No active worktree. Open the workspace to prepare a branch.');
  else if (!busy.length) setText(ref.empty, 'Nothing is running. All worktrees are idle.');
  syncList(ref.busy, card.worktrees.length && busy.length ? busy : [ref.empty]);
  if (ref.idleBox.hidden !== (idle.length === 0)) ref.idleBox.hidden = idle.length === 0;
  setText(ref.idleSummary, `${idle.length} idle worktree${idle.length === 1 ? '' : 's'}`);
  syncList(ref.idle, idle);
}

// ---- page -------------------------------------------------------------------------------------

function pulse(root, totals) {
  const items = [
    ['working', totals.working, 'working', 'LoaderCircle'],
    ['waiting', totals.waiting, 'waiting for you', 'Hourglass'],
    ['attention', totals.attention, 'need attention', 'TriangleAlert'],
    ['review', totals.pendingChanges, 'changes to review', 'FileDiff']
  ];
  const bar = root.querySelector('[data-dashboard-pulse]');
  if (!bar.children.length) {
    for (const [key, , label, iconName] of items) {
      const chip = el('span', 'dash-pulse-item');
      chip.dataset.key = key;
      chip.append(icon(iconName), el('strong'), el('span', '', label));
      bar.append(chip);
    }
    bar.append(el('span', 'dash-pulse-note'));
  }
  for (const [key, count] of items) {
    const chip = bar.querySelector(`[data-key="${key}"]`);
    setText(chip.querySelector('strong'), count);
    chip.classList.toggle('is-zero', count === 0);
    chip.className = `dash-pulse-item is-${key}${count === 0 ? ' is-zero' : ''}`;
  }
  setText(bar.querySelector('.dash-pulse-note'), `${totals.runsToday} run${totals.runsToday === 1 ? '' : 's'} in 24 h`);
}

export function initializeDashboard() {
  const root = document.querySelector('[data-dashboard]');
  if (!root) return;
  const grid = root.querySelector('[data-dashboard-projects]');
  const live = root.querySelector('[data-dashboard-live]');
  const cards = new Map();
  let timer = null;
  let failures = 0;
  let loadedAt = 0;
  let first = true;

  const setLive = ok => {
    live.classList.toggle('is-offline', !ok);
    setText(live.querySelector('span'), ok ? 'Live' : 'Reconnecting…');
  };

  const apply = overview => {
    pulse(root, overview.totals);
    if (!overview.projects.length) return;
    if (first) grid.querySelectorAll(':scope > :not(.dash-card)').forEach(node => node.remove());
    const seen = new Set();
    const order = [];
    overview.projects.forEach((card, index) => {
      seen.add(card.id);
      let ref = cards.get(card.id);
      if (!ref) {
        ref = createCard(card);
        ref.root.style.setProperty('--dash-delay', `${first ? Math.min(index, 8) * 45 : 0}ms`);
        cards.set(card.id, ref);
      }
      patchCard(ref, card);
      order.push(ref.root);
    });
    for (const [id, ref] of cards) if (!seen.has(id)) { ref.root.remove(); cards.delete(id); }
    syncList(grid, order);
    first = false;
  };

  const load = async () => {
    clearTimeout(timer);
    try {
      const response = await fetch('/dashboard/overview', { headers: { Accept: 'application/json' }, credentials: 'same-origin', cache: 'no-store' });
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      apply(await response.json());
      failures = 0;
      loadedAt = Date.now();
      setLive(true);
    } catch {
      failures += 1;
      setLive(false);
    }
    timer = setTimeout(load, Math.min(POLL_MS * (1 + failures), 30000));
  };

  document.addEventListener('visibilitychange', () => {
    if (document.hidden) clearTimeout(timer);
    else if (Date.now() - loadedAt > 1000) load();
  });
  // Relative times keep ticking between polls.
  setInterval(() => {
    root.querySelectorAll('time[data-at]').forEach(time => { if (time.dataset.at) setText(time, relative(time.dataset.at)); });
    root.querySelectorAll('.dash-result[data-at]').forEach(node => {
      if (node.dataset.at && node.dataset.label) setText(node, `Latest: ${node.dataset.label} ${relative(node.dataset.at)} · ${node.dataset.total} run${node.dataset.total === '1' ? '' : 's'}`);
    });
  }, 15000);
  load();
}
