import { marked } from 'marked';
import DOMPurify from 'dompurify';
import hljs from 'highlight.js/lib/common';

const escapeHtml = value => String(value).replace(/[&<>"']/g, c => (
  { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]
));

// marked >=14 calls renderer hooks with token objects; code is handled here, tables/links via DOM post-processing.
const renderer = new marked.Renderer();

renderer.code = (codeOrToken, infostring) => {
  const token = typeof codeOrToken === 'object' && codeOrToken !== null ? codeOrToken : null;
  const code = token ? codeOrToken.text : codeOrToken;
  const info = token ? codeOrToken.lang : infostring;
  const lang = String(info || '').split(/\s+/)[0].toLowerCase();
  let highlighted;
  try {
    if (lang && hljs.getLanguage(lang)) highlighted = hljs.highlight(code, { language: lang, ignoreIllegals: true }).value;
    else if (code.length <= 5000) highlighted = hljs.highlightAuto(code).value;
    else highlighted = escapeHtml(code);
  } catch {
    highlighted = escapeHtml(code);
  }
  const label = escapeHtml(lang || 'code');
  return `<div class="petri-code"><div class="petri-code-head"><span class="petri-code-lang">${label}</span>`
    + '<button type="button" class="petri-code-copy" data-code-copy>Copy</button></div>'
    + `<pre class="petri-code-pre"><code class="hljs">${highlighted}</code></pre></div>`;
};

marked.use({ gfm: true, breaks: true, renderer });

const purifyConfig = { ADD_ATTR: ['target', 'rel', 'data-code-copy'] };

export function renderMarkdown(text) {
  let html = '';
  try {
    const parsed = marked.parse(String(text ?? ''), { async: false });
    html = typeof parsed === 'string' ? parsed : '';
  } catch {
    html = `<p>${escapeHtml(text ?? '').replace(/\n/g, '<br>')}</p>`;
  }
  return DOMPurify.sanitize(html, purifyConfig);
}

export function applyMarkdown(element, text) {
  element.classList.remove('whitespace-pre-wrap', 'break-words');
  element.classList.add('petri-md');
  element.innerHTML = renderMarkdown(text);
  for (const table of element.querySelectorAll('table')) {
    const wrapper = document.createElement('div');
    wrapper.className = 'petri-md-scroll';
    table.className = 'petri-md-table';
    element.replaceChild(wrapper, table);
    wrapper.appendChild(table);
  }
  for (const link of element.querySelectorAll('a[href]')) {
    link.target = '_blank';
    link.rel = 'noopener noreferrer';
  }
}

export function hydrateMarkdown(root = document) {
  bindCopyOnce();
  for (const element of root.querySelectorAll('[data-markdown]')) {
    const text = element.textContent || '';
    element.dataset.raw = text;
    element.removeAttribute('data-markdown');
    applyMarkdown(element, text);
  }
}

let copyBound = false;
function bindCopyOnce() {
  if (copyBound) return;
  copyBound = true;
  document.addEventListener('click', event => {
    const button = event.target.closest('[data-code-copy]');
    if (!button) return;
    const code = button.closest('.petri-code')?.querySelector('code')?.textContent ?? '';
    const done = () => {
      button.textContent = 'Copied';
      window.setTimeout(() => { button.textContent = 'Copy'; }, 1500);
    };
    if (navigator.clipboard?.writeText) navigator.clipboard.writeText(code).then(done).catch(() => {});
    else done();
  });
}

const renderTimers = new WeakMap();
export function scheduleMarkdown(element, raw) {
  element.dataset.raw = raw;
  if (renderTimers.has(element)) return;
  const timer = window.setTimeout(() => {
    renderTimers.delete(element);
    applyMarkdown(element, element.dataset.raw || '');
  }, 120);
  renderTimers.set(element, timer);
}
