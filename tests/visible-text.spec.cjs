// Run with Node.js and Playwright installed; PLAYWRIGHT_CHROMIUM_EXECUTABLE is optional.
const { chromium } = require('playwright');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');

(async () => {
  const browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE });
  try {
    const page = await browser.newPage({ viewport: { width: 400, height: 600 } });
    await page.route('**/*', route => route.fulfill({ contentType: 'text/html', body: '<html><body></body></html>' }));
    await page.goto('https://readium/publication/one.xhtml');
    const script = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/reader/visible-text.js'), 'utf8');
    const read = async () => JSON.parse(await page.evaluate(script));
    const set = async html => page.setContent('<style>body { margin: 0; font: 16px/24px serif; }</style>' + html);
    await set('<p>Visible text</p><p style="display:none">Hidden</p><p style="position:absolute;top:900px">Future</p>');
    assert.deepEqual((await read()).nodes.map(n => n.text), ['Visible text']);
    await set('<p style="position:absolute;top:580px">Partly clipped</p>');
    assert.equal((await read()).nodes.length, 0);
    await set('<div style="height:12px;overflow:hidden"><p>Clipped by ancestor</p></div>');
    assert.equal((await read()).nodes.length, 0);
    await set('<p style="opacity:0">Transparent</p><p style="visibility:hidden">Invisible</p>');
    assert.equal((await read()).nodes.length, 0);
    await set('<p>Covered text</p><div style="position:fixed;inset:0;background:white;z-index:9"></div>');
    assert.equal((await read()).nodes.length, 0);
    await set('<p>First</p><p style="position:absolute;top:1000px">Later</p>');
    const before = await read();
    await page.evaluate(() => window.scrollTo(0, 900));
    const after = await read();
    assert.deepEqual(before.nodes.map(n => n.text), ['First']);
    assert.deepEqual(after.nodes.map(n => n.text), ['Later']);
    assert.notEqual(before.nodes[0].path, after.nodes[0].path);
    console.log('PASS: visible, hidden, offscreen, partial, clipped, transparent, covered and disjoint viewport cases');
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
