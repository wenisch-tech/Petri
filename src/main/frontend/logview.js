// Read-only CI log viewer: ANSI colours, collapsible groups, highlighted levels and file references.
const ESC = String.fromCharCode(27);
const SGR = new RegExp(`${ESC}\\[([0-9;]*)m`, 'g');
const OTHER_ESC = new RegExp(`${ESC}\\[[0-9;?]*[A-LN-Za-z]`, 'g');
const TIMESTAMP = /^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z?)\s/;
const GROUP_START = /^(?:##\[group\]|::group::)(.*)$/;
const GROUP_END = /^(?:##\[endgroup\]|::endgroup::)/;
const SECTION_MARK = new RegExp(`section_(?:start|end):\\d+:[^\\r\\n${ESC}]*\\r?(?:${ESC}\\[0K)?`, 'g');
const ERROR_LINE = /^(?:##\[error\]|::error|\[(?:ERROR|FATAL)\]|ERROR\b|FATAL\b)|\b(?:error|fatal|exception|panic|failed|failure|traceback)\b[:!]?/i;
const WARN_LINE = /^(?:##\[warning\]|::warning|\[WARN(?:ING)?\])|\bwarn(?:ing)?\b[:!]?/i;
const TOKENS = [
  ['log-url', /https?:\/\/[^\s)'"]+/],
  ['log-ref', /(?:[\w.-]+\/)*[\w.-]+\.(?:java|kt|js|jsx|mjs|ts|tsx|py|go|rs|rb|php|cs|c|h|cpp|yml|yaml|json|xml|sh|gradle|toml)(?::\d+(?::\d+)?)?/],
  ['log-level-error', /\[(?:ERROR|FATAL)\]|\b(?:ERROR|FATAL|FAILED|FAIL)\b/],
  ['log-level-warn', /\[WARN(?:ING)?\]|\bWARN(?:ING)?\b/],
  ['log-level-info', /\[INFO\]|\bINFO\b|\b(?:PASSED|PASS|OK|SUCCESS)\b/],
  ['log-duration', /\b\d+(?:\.\d+)?\s?(?:ms|s|min)\b/]
];
const TOKEN_PATTERN = new RegExp(TOKENS.map(([, re]) => `(${re.source})`).join('|'), 'g');
const MAX_LINES = 20000;

const BASIC = ['black', 'red', 'green', 'yellow', 'blue', 'magenta', 'cyan', 'white'];

/** Applies the codes of one SGR sequence to the running style. */
function applyCodes(style, codes) {
  const list = codes === '' ? [0] : codes.split(';').map(Number);
  for (let i = 0; i < list.length; i++) {
    const code = list[i];
    if (code === 0) { style.fg = null; style.bold = false; style.dim = false; }
    else if (code === 1) style.bold = true;
    else if (code === 2) style.dim = true;
    else if (code === 22) { style.bold = false; style.dim = false; }
    else if (code === 39) style.fg = null;
    else if (code >= 30 && code <= 37) style.fg = BASIC[code - 30];
    else if (code >= 90 && code <= 97) style.fg = `bright-${BASIC[code - 90]}`;
    else if (code === 38) i += list[i + 1] === 5 ? 2 : 4; // 256-colour and RGB are left uncoloured
  }
}

function paint(node, text) {
  TOKEN_PATTERN.lastIndex = 0;
  let last = 0;
  let match;
  while ((match = TOKEN_PATTERN.exec(text))) {
    if (match[0] === '') { TOKEN_PATTERN.lastIndex++; continue; }
    if (match.index > last) node.append(text.slice(last, match.index));
    const group = match.findIndex((value, index) => index > 0 && value !== undefined) - 1;
    const span = document.createElement('span');
    span.className = TOKENS[group][0];
    span.textContent = match[0];
    node.append(span);
    last = match.index + match[0].length;
  }
  if (last < text.length) node.append(text.slice(last));
}

/** One line's text as ANSI-coloured segments; the style carries over to the next line like in a terminal. */
function segments(raw, style) {
  const parts = [];
  let last = 0;
  SGR.lastIndex = 0;
  let match;
  const push = text => { if (text) parts.push({ text, fg: style.fg, bold: style.bold, dim: style.dim }); };
  while ((match = SGR.exec(raw))) {
    push(raw.slice(last, match.index));
    applyCodes(style, match[1]);
    last = match.index + match[0].length;
  }
  push(raw.slice(last));
  return parts;
}

function lineElement(number, raw, style) {
  let text = raw.replace(SECTION_MARK, '');
  // A carriage return rewrites the line (progress bars); only what is left on screen counts.
  const rewrite = text.lastIndexOf('\r');
  if (rewrite >= 0) text = text.slice(rewrite + 1);
  text = text.replace(OTHER_ESC, '');
  const row = document.createElement('div');
  row.className = 'log-line';
  row.dataset.n = String(number);
  const stamp = TIMESTAMP.exec(text);
  if (stamp) {
    const time = document.createElement('span');
    time.className = 'log-time';
    time.textContent = stamp[1].replace(/^\d{4}-\d{2}-\d{2}T/, '').replace(/\.\d+Z?$|Z$/, '');
    time.title = stamp[1];
    row.append(time);
    text = text.slice(stamp[0].length);
  }
  const plain = text.replace(SGR, '');
  if (ERROR_LINE.test(plain) && !/^\s*(?:0 |no )(?:errors?|failures?)\b/i.test(plain)) row.classList.add('is-error');
  else if (WARN_LINE.test(plain)) row.classList.add('is-warn');
  text = text.replace(/^##\[(?:error|warning|notice|debug|command|section)\]/, '');
  const body = document.createElement('span');
  body.className = 'log-text';
  for (const part of segments(text, style)) {
    const span = document.createElement('span');
    if (part.fg) span.className = `ansi-${part.fg}`;
    if (part.bold) span.classList.add('ansi-bold');
    if (part.dim) span.classList.add('ansi-dim');
    paint(span, part.text);
    body.append(span);
  }
  if (!body.firstChild) body.append('​');
  row.append(body);
  return row;
}

/** Turns the raw log text into line elements, folding `##[group]` blocks into <details>. */
export function renderLog(text, target) {
  const lines = text.replace(/\r\n/g, '\n').split('\n');
  if (lines.at(-1) === '') lines.pop();
  const skipped = Math.max(0, lines.length - MAX_LINES);
  const visible = skipped ? lines.slice(skipped) : lines;
  const fragment = document.createDocumentFragment();
  const style = { fg: null, bold: false, dim: false };
  let group = null;
  let errors = 0;
  visible.forEach((raw, index) => {
    const number = skipped + index + 1;
    const clean = raw.replace(SGR, '').replace(TIMESTAMP, '');
    const start = GROUP_START.exec(clean);
    if (start) {
      group = document.createElement('details');
      group.className = 'log-group';
      const summary = document.createElement('summary');
      summary.textContent = start[1].trim() || 'Group';
      group.append(summary);
      fragment.append(group);
      return;
    }
    if (GROUP_END.test(clean)) { group = null; return; }
    const row = lineElement(number, raw, style);
    if (row.classList.contains('is-error')) {
      errors++;
      // Errors hide inside folded groups otherwise.
      if (group) group.open = true;
    }
    (group || fragment).append(row);
  });
  target.replaceChildren(fragment);
  return { lines: visible.length, skipped, errors };
}

function hydrate(box) {
  if (box.dataset.logReady) return;
  const source = box.querySelector('[data-log-source]');
  const output = box.querySelector('[data-log-output]');
  if (!source || !output) return;
  box.dataset.logReady = 'true';
  const text = source.textContent || '';
  const result = renderLog(text, output);
  const info = box.querySelector('[data-log-info]');
  if (info) {
    const parts = [`${result.lines.toLocaleString()} lines`];
    if (result.skipped) parts.push(`first ${result.skipped.toLocaleString()} not shown`);
    if (result.errors) parts.push(`${result.errors.toLocaleString()} with errors`);
    info.textContent = parts.join(' · ');
  }
  const scroller = box.querySelector('[data-log-scroll]') || output;
  const firstError = () => output.querySelector('.log-line.is-error');
  const jump = () => {
    const target = firstError();
    if (target) target.scrollIntoView({ block: 'center' });
  };
  box.querySelector('[data-log-first-error]')?.toggleAttribute('hidden', !result.errors);
  box.querySelector('[data-log-first-error]')?.addEventListener('click', jump);
  box.querySelector('[data-log-only-errors]')?.addEventListener('click', event => {
    const on = box.classList.toggle('is-only-errors');
    event.currentTarget.setAttribute('aria-pressed', String(on));
  });
  box.querySelector('[data-log-wrap]')?.addEventListener('click', event => {
    const on = box.classList.toggle('is-wrapped');
    event.currentTarget.setAttribute('aria-pressed', String(on));
  });
  box.querySelector('[data-log-copy]')?.addEventListener('click', event => {
    const button = event.currentTarget;
    navigator.clipboard?.writeText(text.replace(SGR, '')).then(() => {
      const label = button.querySelector('span');
      if (!label) return;
      const previous = label.textContent;
      label.textContent = 'Copied';
      setTimeout(() => { label.textContent = previous; }, 1500);
    }).catch(() => {});
  });
  if (box.dataset.logFollow === 'true') scroller.scrollTop = scroller.scrollHeight;
  else if (result.errors && box.dataset.logFocus === 'error') requestAnimationFrame(jump);
}

export function hydrateLogs(root = document) {
  root.querySelectorAll?.('[data-log-view]').forEach(hydrate);
}
