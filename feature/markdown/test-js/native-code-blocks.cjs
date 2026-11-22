const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');

async function run(page, root) {
  await page.goto('about:blank');
  const assets = path.join(root, 'feature/markdown/assets/github-markdown/webview');
  const scripts = await Promise.all(
    ['markdown.js', 'android-adapter.js'].map(name => fs.readFile(path.join(assets, name), 'utf8'))
  );
  await page.route('https://fixture.invalid/**', route => route.fulfill({
    contentType:'image/svg+xml',
    body:'<svg xmlns="http://www.w3.org/2000/svg" width="80" height="20"><text y="15">badge</text></svg>'
  }));
  await page.setContent('<html><head><style>body{margin:0}pre{padding:16px;margin:16px 0;overflow:auto}'
    + '.highlight{margin:16px 0}table{width:100%}td{width:100%}</style></head>'
    + '<body><article id="content"></article></body></html>');
  await page.evaluate(() => {
    window.messages = [];
    window.native = {sendMessage: message => window.messages.push(JSON.parse(message))};
  });
  for (const script of scripts) await page.addScriptTag({content:script});
  const source = 'val name = "<云>"\n  println(name)\n';
  const html = '<p><img id="badge" width="80" height="20" src="https://fixture.invalid/badge.svg"></p>'
    + '<img id="floating" style="float:right" width="100" height="120" src="https://fixture.invalid/floating.svg">'
    + '<div class="highlight highlight-source-kotlin"><pre><span class="pl-k">val</span> name = "&lt;云&gt;"\n'
    + '  println(name)\n</pre></div>'
    + '<details><summary>More</summary><pre><code class="language-shell">echo hello\n</code></pre></details>'
    + '<blockquote><pre><code class="language-html">&lt;p&gt;hello&lt;/p&gt;\n</code></pre></blockquote>'
    + '<table style="width:auto"><tr><td><pre><code class="language-python">x = 1\n</code></pre></td></tr></table>'
    + '<pre>\n\n\tpreserve whitespace &amp; &lt;tag&gt;\n</pre>'
    + '<p id="after">After code</p>';
  await page.evaluate(html => {
    window.fixtureHtml = html;
    window.androidMarkdown.load('one', html, false, false,
      'https://github.com/fixture/example', null, false, false, null, true);
  }, html);
  await page.waitForFunction(() => window.messages.some(message => message.messageName === 'code_layout'));
  await page.waitForFunction(() => window.messages.some(message => message.messageName === 'layout'));
  const initialMessages = await page.evaluate(() => window.messages);
  const layoutIndex = initialMessages.findIndex(message => message.messageName === 'layout');
  assert.ok(initialMessages.slice(0, layoutIndex).some(message => message.messageName === 'height'));
  assert.ok(initialMessages.slice(0, layoutIndex).some(message => message.messageName === 'code_layout'));
  assert.equal(initialMessages[layoutIndex].id, 'one');
  assert.equal(initialMessages.filter(message => message.messageName === 'layout').length, 1);
  let sources = await page.evaluate(() =>
    window.messages.find(message => message.messageName === 'code_blocks').blocks);
  assert.deepEqual(await page.evaluate(blocks => blocks.map(({html}) => {
    const fragment = document.createElement('template');
    fragment.innerHTML = html;
    return fragment.content.querySelector('pre').textContent;
  }), sources), [source, 'echo hello\n', '<p>hello</p>\n', 'x = 1\n', '\n\tpreserve whitespace & <tag>\n']);
  assert.ok(sources[0].html.includes('<span class="pl-k">val</span>'),
    'the library must receive original GitHub syntax spans');
  assert.ok(sources[1].html.includes('class="language-shell"'));
  assert.equal(await page.locator('pre').first().textContent(), '');
  assert.equal(await page.locator('#badge').getAttribute('src'), 'https://fixture.invalid/badge.svg');
  assert.equal(await page.locator('#badge').getAttribute('width'), '80');
  const hidden = await page.evaluate(() =>
    window.messages.filter(message => message.messageName === 'code_layout').at(-1).layout[1][3]);
  assert.equal(hidden, 0);
  assert.ok(await page.locator('pre').first().evaluate(element =>
    element.getBoundingClientRect().right <= document.getElementById('floating').getBoundingClientRect().left),
    'native code placeholder must keep the original float avoidance');
  assert.ok(await page.locator('td pre').evaluate(element => element.getBoundingClientRect().width > 32),
    'code in an auto-sized table must keep a usable width after the HTML text is removed');

  await page.evaluate(() => {
    window.androidMarkdown.codeHeight('one', 0, 180);
    window.androidMarkdown.codeHeight('one', 2, 120);
    document.querySelector('details').open = true;
  });
  await page.waitForFunction(() => {
    const boxes = window.messages.filter(message => message.messageName === 'code_layout').at(-1)?.layout;
    return boxes?.[0][4] === 180 && boxes[1][3] > 0 && boxes[2][4] === 120;
  });
  assert.equal(await page.evaluate(() => window.messages.filter(message =>
    message.messageName === 'layout' && message.id === 'one').length), 1,
    'later code and details measurements must not restart first presentation');
  const before = await page.locator('pre').first().evaluate(element => element.getBoundingClientRect().height);
  await page.evaluate(() => window.androidMarkdown.codeHeight('stale', 0, 999));
  assert.equal(await page.locator('pre').first().evaluate(element => element.getBoundingClientRect().height), before);
  const boxes = await page.evaluate(() =>
    window.messages.filter(message => message.messageName === 'code_layout').at(-1).layout);
  assert.ok(boxes[2][2] >= boxes[0][2] + boxes[0][4], 'native height must move following blocks');

  await page.evaluate(() => {
    window.savedImage = document.getElementById('badge');
    window.messages = [];
    window.androidMarkdown.setViewport(true);
    window.androidMarkdown.load('two', window.fixtureHtml, false, false,
      'https://github.com/fixture/example', null, false, false, null, true);
  });
  await page.waitForFunction(() => window.messages.some(message => message.messageName === 'code_layout'));
  assert.equal(await page.evaluate(() => window.savedImage === document.getElementById('badge')), true);
  assert.equal(await page.evaluate(() =>
    window.messages.find(message => message.messageName === 'code_blocks').blocks[0].html), sources[0].html);
  assert.equal(await page.evaluate(() => window.messages.some(message => message.messageName === 'height')), false);
  await page.waitForFunction(() => window.messages.some(message =>
    message.messageName === 'layout' && message.id === 'two'));

  await page.evaluate(() => {
    window.androidMarkdown.load('reference', window.fixtureHtml, false, false,
      'https://github.com/fixture/example', null, false, false, null, false);
  });
  assert.equal(await page.locator('pre').first().textContent(), source);
  assert.equal(await page.locator('pre').last().textContent(), '\n\tpreserve whitespace & <tag>\n');
  assert.equal(await page.locator('.android-native-code').count(), 0);
  await page.evaluate(() => {
    window.messages = [];
    window.androidMarkdown.setViewport(false);
    window.androidMarkdown.load('superseded', '<p>Old</p>', false, false,
      'https://github.com/fixture/example', null, false, false, null, true);
    window.androidMarkdown.load('plain', '<p>Current without code</p>', false, false,
      'https://github.com/fixture/example', null, false, false, null, true);
  });
  await page.waitForFunction(() => window.messages.some(message =>
    message.messageName === 'layout' && message.id === 'plain'));
  const plainMessages = await page.evaluate(() => window.messages);
  assert.equal(plainMessages.some(message =>
    message.messageName === 'layout' && message.id === 'superseded'), false);
  const plainLayout = plainMessages.findIndex(message => message.messageName === 'layout');
  assert.ok(plainMessages.slice(0, plainLayout).some(message =>
    message.messageName === 'height' && message.id === 'plain'));
  return 'code layout, height feedback, layout generations, no-code readiness, stale version, DOM reuse and preview checks passed';
}

module.exports = {run};
if (require.main === module) {
  (async () => {
    const {chromium} = require('playwright');
    const browser = await chromium.launch({
      headless:true,
      ...(process.env.CHROMIUM_PATH ? {executablePath:process.env.CHROMIUM_PATH} : {})
    });
    try {
      const page = await browser.newPage({viewport:{width:390, height:700}, deviceScaleFactor:2});
      console.log(await run(page, path.resolve(__dirname, '../../..')));
    } finally {
      await browser.close();
    }
  })().catch(error => { console.error(error); process.exitCode = 1; });
}
