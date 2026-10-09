import * as esbuild from 'esbuild';

const target = 'target/classes/static/js';
const monacoTarget = `${target}/monaco`;
const common = { bundle: true, minify: true, logLevel: 'info' };

const builds = [
  {
    ...common,
    entryPoints: ['src/main/frontend/petri.js'],
    format: 'iife',
    outfile: `${target}/petri.js`
  },
  {
    ...common,
    entryPoints: { monaco: 'src/main/frontend/monaco-entry.js' },
    format: 'esm',
    splitting: true,
    outdir: monacoTarget,
    entryNames: '[name]',
    chunkNames: 'chunks/[name]-[hash]',
    assetNames: 'assets/[name]-[hash]',
    loader: { '.ttf': 'file' }
  },
  ...[
    ['editor.worker', 'monaco-editor/esm/vs/editor/editor.worker.js'],
    ['json.worker', 'monaco-editor/esm/vs/language/json/json.worker.js'],
    ['css.worker', 'monaco-editor/esm/vs/language/css/css.worker.js'],
    ['html.worker', 'monaco-editor/esm/vs/language/html/html.worker.js'],
    ['ts.worker', 'monaco-editor/esm/vs/language/typescript/ts.worker.js']
  ].map(([name, entry]) => ({
    ...common,
    entryPoints: [entry],
    format: 'esm',
    outfile: `${monacoTarget}/${name}.js`
  }))
];

async function build() {
  for (const options of builds) await esbuild.build(options);
}

if (process.argv.includes('--watch')) {
  const contexts = await Promise.all(builds.map(options => esbuild.context(options)));
  await Promise.all(contexts.map(context => context.watch()));
  console.log('Watching Petri frontend bundles');
} else {
  await build();
}
