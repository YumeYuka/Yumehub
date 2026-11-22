// NODE_PATH 指向含 vscode-textmate 和 vscode-oniguruma 的编辑器 node_modules。
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const tm = require('vscode-textmate');
const onig = require('vscode-oniguruma');
const ui = path.resolve(__dirname, '..');

async function test() {
  const wasm = fs.readFileSync(require.resolve('vscode-oniguruma/release/onig.wasm'));
  await onig.loadWASM(wasm.buffer.slice(wasm.byteOffset, wasm.byteOffset + wasm.byteLength));
  const index = JSON.parse(fs.readFileSync(path.join(ui, 'resources/textmate/languages.json')));
  const grammars = new Map(index.languages.map(g => [g.scopeName, g.grammar]));
  const registry = new tm.Registry({
    onigLib: Promise.resolve({
      createOnigScanner: patterns => new onig.OnigScanner(patterns),
      createOnigString: text => new onig.OnigString(text),
    }),
    loadGrammar: async scope => {
      const name = grammars.get(scope);
      return name ? tm.parseRawGrammar(fs.readFileSync(path.join(ui, 'assets', name), 'utf8'), name) : null;
    },
  });
  try {
    const grammar = await registry.loadGrammar('source.cpp');
    const line = 'template<class T> struct Widget {}; /* open';
    const first = grammar.tokenizeLine(line, tm.INITIAL);
    const at = i => first.tokens.find(t => t.startIndex <= i && t.endIndex > i).scopes;
    assert(at(0).includes('storage.type.template.cpp'));
    assert(at(line.indexOf('Widget')).includes('entity.name.type.struct.cpp'));
    const next = grammar.tokenizeLine('continued */ int value = 42;', first.ruleStack);
    assert(next.tokens[0].scopes.includes('comment.block.cpp'));
    assert(next.tokens.some(t => t.scopes.some(s => s.startsWith('constant.numeric'))));
  } finally {
    registry.dispose();
  }
}

test().catch(error => { console.error(error); process.exitCode = 1; });
