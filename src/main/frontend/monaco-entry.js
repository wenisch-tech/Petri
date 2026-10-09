import * as monaco from 'monaco-editor/esm/vs/editor/editor.api.js';
import 'monaco-editor/esm/vs/language/json/monaco.contribution.js';
import 'monaco-editor/esm/vs/language/css/monaco.contribution.js';
import 'monaco-editor/esm/vs/language/html/monaco.contribution.js';
import 'monaco-editor/esm/vs/language/typescript/monaco.contribution.js';
import 'monaco-editor/esm/vs/basic-languages/java/java.contribution.js';
import 'monaco-editor/esm/vs/basic-languages/markdown/markdown.contribution.js';
import 'monaco-editor/esm/vs/basic-languages/xml/xml.contribution.js';
import 'monaco-editor/esm/vs/basic-languages/yaml/yaml.contribution.js';
import 'monaco-editor/esm/vs/basic-languages/shell/shell.contribution.js';
import 'monaco-editor/esm/vs/basic-languages/sql/sql.contribution.js';

const workers = {
  json: '/js/monaco/json.worker.js',
  css: '/js/monaco/css.worker.js',
  scss: '/js/monaco/css.worker.js',
  less: '/js/monaco/css.worker.js',
  html: '/js/monaco/html.worker.js',
  handlebars: '/js/monaco/html.worker.js',
  razor: '/js/monaco/html.worker.js',
  javascript: '/js/monaco/ts.worker.js',
  typescript: '/js/monaco/ts.worker.js'
};

globalThis.MonacoEnvironment = {
  getWorker(_moduleId, label) {
    return new Worker(workers[label] || '/js/monaco/editor.worker.js', {
      type: 'module',
      name: `petri-monaco-${label || 'editor'}`
    });
  }
};

export { monaco };
