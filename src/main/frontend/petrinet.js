// Petri-net view of a gated workflow.
//
// Classic notation: places are circles, transitions are rectangles, a token is a dot inside a place
// and arcs are arrows. The workflow's current stage is the marking; every entry of
// `workflow.transitions` is a firing; each acceptance criterion of the plan becomes its own small
// sub-net (open -> verify -> verified) that the final gate consumes, so the net shows why the
// workflow cannot finish while a goal is unverified.

const SVG = 'http://www.w3.org/2000/svg';
let uid = 0;

// Main line, left to right: place, transition, place, ... Index = column.
const MAIN = [
  { id: 'p_request', kind: 'place', label: 'Request', stage: 'ORCHESTRATOR_INTAKE' },
  { id: 't_intake', kind: 'transition', label: 'Intake', from: 'ORCHESTRATOR_INTAKE', to: 'PLANNING' },
  { id: 'p_brief', kind: 'place', label: 'Brief', stage: 'PLANNING' },
  { id: 't_plan', kind: 'transition', label: 'Plan', from: 'PLANNING', to: 'ORCHESTRATOR_PLAN_REVIEW' },
  { id: 'p_plan', kind: 'place', label: 'Plan', stage: 'ORCHESTRATOR_PLAN_REVIEW' },
  { id: 't_gate1', kind: 'transition', label: 'Plan gate', from: 'ORCHESTRATOR_PLAN_REVIEW', to: 'WAITING_FOR_PLAN_APPROVAL' },
  { id: 'p_checked', kind: 'place', label: 'Checked', stage: 'WAITING_FOR_PLAN_APPROVAL' },
  { id: 't_approve', kind: 'transition', label: 'Approve', from: 'WAITING_FOR_PLAN_APPROVAL', to: 'IMPLEMENTING', user: true },
  { id: 'p_approved', kind: 'place', label: 'Approved', stage: 'IMPLEMENTING' },
  { id: 't_impl', kind: 'transition', label: 'Implement', from: 'IMPLEMENTING', to: 'ORCHESTRATOR_IMPLEMENTATION_REVIEW' },
  { id: 'p_impl', kind: 'place', label: 'Implemented', stage: 'ORCHESTRATOR_IMPLEMENTATION_REVIEW' },
  { id: 't_gate2', kind: 'transition', label: 'Evidence gate', from: 'ORCHESTRATOR_IMPLEMENTATION_REVIEW', to: 'REVIEWING' },
  { id: 'p_evidence', kind: 'place', label: 'Evidence', stage: 'REVIEWING' },
  { id: 't_review', kind: 'transition', label: 'Review', from: 'REVIEWING', to: 'ORCHESTRATOR_FINAL_REVIEW' },
  { id: 'p_review', kind: 'place', label: 'Reviewed', stage: 'ORCHESTRATOR_FINAL_REVIEW' },
  { id: 't_final', kind: 'transition', label: 'Final gate', from: 'ORCHESTRATOR_FINAL_REVIEW', to: 'COMPLETED', user: true },
  { id: 'p_done', kind: 'place', label: 'Done', stage: 'COMPLETED' }
];
const COLUMN = Object.fromEntries(MAIN.map((node, index) => [node.id, index]));

// Rework and exception arcs, drawn dashed. `match` selects the firings that are counted.
const BACK_ARCS = [
  { id: 'b_gate1', from: 't_gate1', to: 'p_brief', height: 30, match: ['ORCHESTRATOR_PLAN_REVIEW', 'PLANNING'], label: 'revise' },
  { id: 'b_revise', from: 't_approve', to: 'p_brief', height: 46, match: ['WAITING_FOR_PLAN_APPROVAL', 'PLANNING'], label: 'replan' },
  { id: 'b_gate2', from: 't_gate2', to: 'p_approved', height: 30, match: ['ORCHESTRATOR_IMPLEMENTATION_REVIEW', 'IMPLEMENTING'], label: 'rework' },
  { id: 'b_final', from: 't_final', to: 'p_approved', height: 46, match: ['ORCHESTRATOR_FINAL_REVIEW', 'IMPLEMENTING'], label: 'rework' }
];

const STAGE_LABELS = {
  QUEUED: 'Queued', ORCHESTRATOR_INTAKE: 'Intake', PLANNING: 'Planning',
  WAITING_FOR_CLARIFICATION: 'Clarification', ORCHESTRATOR_PLAN_REVIEW: 'Plan gate',
  WAITING_FOR_PLAN_APPROVAL: 'Plan approval', IMPLEMENTING: 'Implementing',
  ORCHESTRATOR_IMPLEMENTATION_REVIEW: 'Evidence gate', REVIEWING: 'Review',
  ORCHESTRATOR_FINAL_REVIEW: 'Final gate', HUMAN_HOLD: 'Human hold', COMPLETED: 'Done',
  CANCELLED: 'Cancelled'
};

export const goalStatusLabels = {
  UNVERIFIED: 'Not verified yet',
  CLAIMED: 'Claimed, not confirmed',
  PARTIAL: 'Partly met',
  NOT_MET: 'Not met',
  VERIFIED: 'Verified'
};

export function stageLabel(stage) {
  return STAGE_LABELS[stage] || String(stage || '').replaceAll('_', ' ').toLowerCase();
}

/** Pure description of the net and its marking; rendering is separate so it can be tested. */
export function buildNet(workflow) {
  const transitions = (workflow.transitions || []).filter(value => value.fromStage !== value.toStage);
  const reached = new Set([workflow.currentStage, ...transitions.map(value => value.toStage)]);
  const fired = (from, to) => transitions.filter(value => value.fromStage === from && value.toStage === to);
  const status = workflow.status;
  const stage = workflow.currentStage;
  const completed = status === 'COMPLETED' || stage === 'COMPLETED';
  const cancelled = status === 'CANCELLED' || stage === 'CANCELLED';

  // Where the single control token sits.
  let marked = null;
  if (stage === 'WAITING_FOR_CLARIFICATION') marked = 'p_questions';
  else if (stage === 'HUMAN_HOLD' || status === 'HUMAN_HOLD') marked = 'p_hold';
  else if (stage === 'QUEUED') marked = 'p_request';
  else marked = MAIN.find(node => node.kind === 'place' && node.stage === stage)?.id || null;
  if (completed) marked = 'p_done';

  let tone = 'running';
  if (completed) tone = 'done';
  else if (cancelled) tone = 'cancelled';
  else if (status === 'HUMAN_HOLD' || marked === 'p_hold') tone = 'hold';
  else if (status === 'WAITING_FOR_USER' || stage === 'WAITING_FOR_PLAN_APPROVAL'
      || stage === 'WAITING_FOR_CLARIFICATION') tone = 'waiting';
  else if (status === 'PENDING' || status === 'CANCELLING') tone = 'pending';

  const places = MAIN.filter(node => node.kind === 'place').map(node => ({
    ...node,
    marked: marked === node.id,
    visited: completed || reached.has(node.stage) || marked === node.id
  }));
  const transitionNodes = MAIN.filter(node => node.kind === 'transition').map(node => {
    const firings = fired(node.from, node.to);
    const inputPlace = MAIN[COLUMN[node.id] - 1].id;
    const pending = marked === inputPlace;
    return {
      ...node,
      count: firings.length,
      fired: firings.length > 0 || (completed && !cancelled),
      active: pending && !completed && !cancelled,
      last: firings.at(-1) || null,
      pendingTone: pending ? tone : null
    };
  });

  const side = {
    questions: { id: 'p_questions', kind: 'place', label: 'Clarification', marked: marked === 'p_questions',
      visited: reached.has('WAITING_FOR_CLARIFICATION') },
    hold: { id: 'p_hold', kind: 'place', label: 'Human hold', marked: marked === 'p_hold',
      visited: transitions.some(value => value.toStage === 'HUMAN_HOLD') || marked === 'p_hold',
      detail: workflow.holdReason || null }
  };

  const back = BACK_ARCS.map(arc => ({ ...arc, count: fired(arc.match[0], arc.match[1]).length }));

  const approved = workflow.approvedPlanRevision != null;
  const goals = (workflow.goals || []).map(goal => ({
    ...goal,
    // A goal becomes an obligation when the plan is approved; before that it is only a draft.
    draft: !approved,
    open: approved && goal.status !== 'VERIFIED',
    done: goal.status === 'VERIFIED'
  }));
  const goalSummary = {
    total: goals.length,
    verified: goals.filter(goal => goal.status === 'VERIFIED').length,
    failing: goals.some(goal => goal.status === 'NOT_MET'),
    claimed: goals.some(goal => goal.status === 'CLAIMED' || goal.status === 'PARTIAL')
  };

  return {
    places, transitions: transitionNodes, side, back, goals, goalSummary, marked, tone,
    stage, status, completed, cancelled, firings: transitions
  };
}

function el(tag, attributes = {}, children = []) {
  const node = document.createElementNS(SVG, tag);
  for (const [name, value] of Object.entries(attributes)) {
    if (value !== null && value !== undefined && value !== false) node.setAttribute(name, String(value));
  }
  for (const child of [].concat(children)) if (child) node.append(child);
  return node;
}

function title(text) {
  const node = document.createElementNS(SVG, 'title');
  node.textContent = text;
  return node;
}

function clip(text, max) {
  const value = String(text || '');
  return value.length > max ? `${value.slice(0, max - 1)}…` : value;
}

function when(value) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '' : date.toLocaleString();
}

/**
 * @param workflow the workflow view from the snapshot
 * @param options.compact single-row net for the top bar
 * @param options.previousStage stage of the previous snapshot, to animate the token's arrival
 * @param options.maxGoals goals drawn in the expanded net before collapsing into "+n more"
 */
export function renderNet(workflow, { compact = false, previousStage = null, maxGoals = 12 } = {}) {
  const net = buildNet(workflow);
  const id = `pn${++uid}`;
  const geometry = compact
    ? { step: 34, x0: 16, y: 22, r: 7, tw: 9, th: 16, token: 3, arrow: 5 }
    : { step: 58, x0: 36, y: 86, r: 12, tw: 14, th: 28, token: 5, arrow: 7 };
  const x = column => geometry.x0 + column * geometry.step;
  const width = geometry.x0 * 2 + (MAIN.length - 1) * geometry.step;
  const shownGoals = compact ? [] : net.goals.slice(0, maxGoals);
  const hiddenGoals = compact ? 0 : Math.max(0, net.goals.length - shownGoals.length);
  const rowGap = 38;
  const sideY = geometry.y + (compact ? 28 : 58);
  const goalsTop = geometry.y + 112;
  const height = compact
    ? 58
    : (net.goals.length ? goalsTop + shownGoals.length * rowGap + (hiddenGoals ? 22 : 8) : sideY + 30);

  const svg = el('svg', {
    class: `petrinet ${compact ? 'is-compact' : 'is-expanded'} tone-${net.tone}`,
    viewBox: `0 0 ${width} ${height}`,
    role: 'img',
    'aria-label': `Workflow Petri net, ${stageLabel(net.stage)}`,
    preserveAspectRatio: 'xMidYMid meet'
  });
  svg.append(el('defs', {}, el('marker', {
    id: `${id}-arrow`, viewBox: '0 0 10 10', refX: 9, refY: 5,
    markerWidth: geometry.arrow, markerHeight: geometry.arrow, orient: 'auto-start-reverse'
  }, el('path', { d: 'M0,0 L10,5 L0,10 z', fill: 'context-stroke' }))));

  const arcs = el('g', { class: 'pn-arcs' });
  const nodes = el('g', { class: 'pn-nodes' });
  svg.append(arcs, nodes);

  const position = {};
  MAIN.forEach((node, index) => { position[node.id] = { x: x(index), y: geometry.y, kind: node.kind }; });
  position.p_questions = { x: x(COLUMN.t_plan), y: sideY, kind: 'place' };
  position.p_hold = { x: x(COLUMN.t_gate1), y: sideY, kind: 'place' };

  // Point where a line from `node` toward (tx, ty) leaves the node's outline.
  const edge = (node, tx, ty) => {
    const dx = tx - node.x;
    const dy = ty - node.y;
    const length = Math.hypot(dx, dy) || 1;
    if (node.kind === 'place') {
      return { x: node.x + (dx / length) * geometry.r, y: node.y + (dy / length) * geometry.r };
    }
    const hw = geometry.tw / 2;
    const hh = geometry.th / 2;
    const scale = Math.min(hw / (Math.abs(dx) || 1e-6), hh / (Math.abs(dy) || 1e-6));
    return { x: node.x + dx * scale, y: node.y + dy * scale };
  };
  const arc = (from, to, className = '', label = null) => {
    const a = edge(from, to.x, to.y);
    const b = edge(to, from.x, from.y);
    arcs.append(el('line', {
      x1: a.x, y1: a.y, x2: b.x, y2: b.y, class: `pn-arc ${className}`,
      'marker-end': `url(#${id}-arrow)`
    }));
    if (label && !compact) {
      arcs.append(el('text', { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 - 4, 'text-anchor': 'middle', class: 'pn-label' }, label));
    }
  };
  const curve = (from, to, height, className, label) => {
    const x1 = from.x;
    const y1 = from.y - geometry.th / 2;
    const x2 = to.x;
    const y2 = to.y - geometry.r;
    arcs.append(el('path', {
      d: `M${x1},${y1} C${x1},${y1 - height} ${x2},${y2 - height} ${x2},${y2}`,
      class: `pn-arc is-back ${className}`, 'marker-end': `url(#${id}-arrow)`
    }));
    if (label && !compact) {
      arcs.append(el('text', {
        x: (x1 + x2) / 2, y: y1 - height * 0.75 - 3, 'text-anchor': 'middle', class: 'pn-label'
      }, label));
    }
  };

  // Main line arcs.
  for (let index = 0; index < MAIN.length - 1; index++) {
    const from = MAIN[index];
    const to = MAIN[index + 1];
    const used = from.kind === 'transition' ? net.transitions.find(node => node.id === from.id)?.fired
      : net.transitions.find(node => node.id === to.id)?.fired;
    arc(position[from.id], position[to.id], used ? 'is-used' : '');
  }

  // Rework arcs, drawn above the line.
  if (!compact || net.back.some(value => value.count > 0)) {
    for (const back of net.back) {
      const label = back.count > 0 ? `${back.label} ×${back.count}` : back.label;
      if (compact && back.count === 0) continue;
      curve(position[back.from], position[back.to], compact ? back.height * 0.45 : back.height,
        back.count > 0 ? 'is-used' : '', label);
    }
  }

  // Clarification loop and hold, below the line. In compact mode only when relevant.
  const showQuestions = !compact || net.side.questions.marked || net.side.questions.visited;
  const showHold = !compact || net.side.hold.marked || net.side.hold.visited;
  if (showQuestions) {
    arc(position.t_plan, position.p_questions, net.side.questions.visited ? 'is-used' : 'is-back');
    arc(position.p_questions, position.p_brief, net.side.questions.visited ? 'is-used' : 'is-back');
  }
  if (showHold) {
    // An exception arc is only drawn from the transitions that actually ended in a hold.
    const heldFrom = new Set(net.firings.filter(value => value.toStage === 'HUMAN_HOLD').map(value => value.fromStage));
    for (const node of net.transitions) {
      if (heldFrom.has(node.from)) arc(position[node.id], position.p_hold, 'is-back is-used', null);
    }
  }

  const place = (node, extra = {}) => {
    const p = position[node.id];
    const tone = node.marked ? net.tone : (node.visited ? 'visited' : 'idle');
    const group = el('g', { class: `pn-node pn-place is-${tone}${node.marked ? ' is-marked' : ''}`, 'data-place': node.id });
    group.append(title(`${node.label}${node.marked ? ` — ${stageLabel(net.stage)}` : ''}${extra.detail ? `\n${extra.detail}` : ''}`));
    group.append(el('circle', { cx: p.x, cy: p.y, r: geometry.r, class: 'pn-shape' }));
    if (node.marked) {
      const arriving = previousStage !== null && previousStage !== net.stage;
      group.append(el('circle', {
        cx: p.x, cy: p.y, r: geometry.token, class: `pn-token${arriving ? ' is-arriving' : ''}`
      }));
    }
    if (!compact || node.marked) {
      group.append(el('text', {
        x: p.x, y: p.y + geometry.r + (compact ? 9 : 13), 'text-anchor': 'middle', class: 'pn-label'
      }, clip(node.label, compact ? 11 : 16)));
    }
    nodes.append(group);
  };
  const transition = node => {
    const p = position[node.id];
    const tone = node.active ? net.tone : (node.fired ? 'fired' : 'idle');
    const group = el('g', { class: `pn-node pn-transition is-${tone}${node.user ? ' is-user' : ''}`, 'data-transition': node.id });
    const last = node.last
      ? `\nLast: ${node.last.outcome}${node.last.reason ? ` — ${node.last.reason}` : ''} (${when(node.last.createdAt)})`
      : '';
    group.append(title(`${node.label}${node.user ? ' (needs you)' : ''} — fired ${node.count}×${last}`));
    group.append(el('rect', {
      x: p.x - geometry.tw / 2, y: p.y - geometry.th / 2, width: geometry.tw, height: geometry.th,
      rx: 2, class: 'pn-shape'
    }));
    if (node.count > 1) {
      group.append(el('text', { x: p.x, y: p.y - geometry.th / 2 - 3, 'text-anchor': 'middle', class: 'pn-count' }, `×${node.count}`));
    }
    if (!compact) {
      group.append(el('text', { x: p.x, y: p.y + geometry.th / 2 + 27, 'text-anchor': 'middle', class: 'pn-label' }, node.label));
    }
    nodes.append(group);
  };
  net.places.forEach(node => place(node));
  net.transitions.forEach(transition);
  if (showQuestions) place(net.side.questions);
  if (showHold) place(net.side.hold, { detail: net.side.hold.detail });

  // Goals.
  if (compact) {
    if (net.goalSummary.total > 0) {
      const { verified, total, failing, claimed } = net.goalSummary;
      const tone = verified === total ? 'done' : failing ? 'hold' : claimed ? 'waiting' : 'idle';
      const text = el('text', {
        x: width - 8, y: height - 4, 'text-anchor': 'end', class: `pn-goals is-${tone}`
      }, `goals ${verified}/${total}`);
      text.append(title(`${verified} of ${total} acceptance criteria are backed by confirmed evidence`));
      svg.append(text);
    }
    return svg;
  }

  // Goals hang off two buses: the approval feeds every obligation, the review drives every
  // verification. Elbow connectors keep the main line readable with many goals.
  const busApprove = position.t_approve.x;
  const busReview = position.t_review.x;
  const lastRow = goalsTop + Math.max(0, shownGoals.length - 1) * rowGap;
  if (shownGoals.length) {
    arcs.append(el('line', {
      x1: busApprove, y1: geometry.y + geometry.th / 2, x2: busApprove, y2: lastRow,
      class: `pn-arc ${net.goals.some(goal => !goal.draft) ? 'is-used' : 'is-back'}`
    }));
    arcs.append(el('line', {
      x1: busReview, y1: geometry.y + geometry.th / 2 + 26, x2: busReview, y2: lastRow,
      class: 'pn-arc is-back'
    }));
    arcs.append(el('text', {
      x: busApprove + 6, y: goalsTop - 22, class: 'pn-label'
    }, 'obligations'));
    arcs.append(el('text', {
      x: busReview + 6, y: goalsTop - 22, class: 'pn-label'
    }, 'verified by review'));
  }
  shownGoals.forEach((goal, index) => {
    const rowY = goalsTop + index * rowGap;
    const open = { x: x(COLUMN.p_approved) + geometry.step, y: rowY, kind: 'place' };
    const verify = { x: busReview, y: rowY, kind: 'transition' };
    const ok = { x: x(COLUMN.t_final), y: rowY, kind: 'place' };
    const stub = (fromX, toNode, className) => {
      const target = edge(toNode, fromX, rowY);
      arcs.append(el('line', {
        x1: fromX, y1: rowY, x2: target.x, y2: target.y, class: `pn-arc ${className}`,
        'marker-end': `url(#${id}-arrow)`
      }));
    };
    stub(busApprove, open, goal.draft ? 'is-back' : 'is-used');
    arc(open, verify, goal.done ? 'is-used' : '');
    arc(verify, ok, goal.done ? 'is-used' : '');
    arcs.append(el('line', {
      x1: ok.x, y1: rowY - geometry.r + 3, x2: ok.x, y2: geometry.y + geometry.th / 2,
      class: `pn-arc ${goal.done ? 'is-used' : ''}`, 'marker-end': index === 0 ? `url(#${id}-arrow)` : null
    }));

    const labelText = el('text', { x: geometry.x0 - 12, y: rowY + 3, 'text-anchor': 'start', class: 'pn-goal-text' },
      `${goal.id} · ${clip(goal.text, 54)}`);
    labelText.append(title(`${goal.id}: ${goal.text}\n${goalStatusLabels[goal.status] || goal.status}`));
    nodes.append(labelText);

    const status = String(goal.status || 'UNVERIFIED').toLowerCase();
    const openGroup = el('g', { class: `pn-node pn-place is-goal is-${goal.draft ? 'draft' : goal.done ? 'visited' : 'idle'}` });
    openGroup.append(title(`${goal.id}: ${goalStatusLabels[goal.status] || goal.status}${goal.draft ? ' (plan not approved yet)' : ''}`));
    openGroup.append(el('circle', { cx: open.x, cy: open.y, r: geometry.r - 3, class: 'pn-shape' }));
    if (!goal.draft && !goal.done) {
      openGroup.append(goalToken(open, geometry.token - 1, status));
    }
    nodes.append(openGroup);

    const verifyGroup = el('g', { class: `pn-node pn-transition is-${goal.done ? 'fired' : 'idle'}` });
    verifyGroup.append(title(`Verify ${goal.id}`));
    verifyGroup.append(el('rect', {
      x: verify.x - geometry.tw / 2, y: verify.y - geometry.th / 3, width: geometry.tw, height: geometry.th * 2 / 3,
      rx: 2, class: 'pn-shape'
    }));
    nodes.append(verifyGroup);

    const okGroup = el('g', { class: `pn-node pn-place is-goal is-${goal.done ? 'done is-marked' : 'idle'}` });
    okGroup.append(title(`${goal.id}: verified place`));
    okGroup.append(el('circle', { cx: ok.x, cy: ok.y, r: geometry.r - 3, class: 'pn-shape' }));
    if (goal.done) okGroup.append(el('circle', { cx: ok.x, cy: ok.y, r: geometry.token - 1, class: 'pn-token is-goal-ok' }));
    nodes.append(okGroup);
  });
  if (hiddenGoals) {
    nodes.append(el('text', {
      x: geometry.x0 - 12, y: goalsTop + shownGoals.length * rowGap - 6,
      'text-anchor': 'start', class: 'pn-goal-text'
    }, `+${hiddenGoals} more goals`));
  }
  return svg;
}

// A goal's token shows how far the evidence goes, not only whether it exists.
function goalToken(at, r, status) {
  const group = el('g', { class: `pn-goal-token is-${status}` });
  if (status === 'not_met') {
    const d = r;
    group.append(el('path', { d: `M${at.x - d},${at.y - d} L${at.x + d},${at.y + d} M${at.x + d},${at.y - d} L${at.x - d},${at.y + d}` }));
  } else if (status === 'claimed') {
    group.append(el('circle', { cx: at.x, cy: at.y, r, class: 'ring' }));
  } else if (status === 'partial') {
    group.append(el('path', { d: `M${at.x - r},${at.y} A${r},${r} 0 0 1 ${at.x + r},${at.y} Z` }));
    group.append(el('circle', { cx: at.x, cy: at.y, r, class: 'ring' }));
  } else {
    group.append(el('circle', { cx: at.x, cy: at.y, r }));
  }
  return group;
}
