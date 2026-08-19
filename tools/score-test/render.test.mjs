/*
 * Pixel test for the score pane, run on the host.
 *
 *     npm --prefix tools/score-test install
 *     node tools/score-test/render.test.mjs [--save out.png]
 *
 * The jsdom suite next door builds the DOM and drives the timemap, and it
 * passed happily through two releases in which the pane on the tablet was a
 * blank rectangle — because jsdom has no layout and paints nothing, so
 * "engraved correctly" and "visible" were the same thing to it. They are not.
 * A page can engrave perfectly and still be unreadable: rendered at 40% of the
 * pane in a corner, pushed below the fold by a cursor-centring scroll, or drawn
 * so dim it disappears into the background.
 *
 * So this loads the same three files into headless Chromium — the engine family
 * the Android WebView is built on — and measures the actual painted result:
 * where the ink is, how much of it there is, and how large the notation comes
 * out at the tablet's real pane size.
 */

import { chromium } from 'playwright';
import fs from 'node:fs';
import path from 'node:path';
import { PNG } from 'pngjs';
import { fileURLToPath } from 'node:url';
import { globSync } from 'node:fs';

const here = path.dirname(fileURLToPath(import.meta.url));
const pane = path.resolve(here, '../../app/src/main/assets/score');

// The Galaxy Tab S11 Ultra in landscape, at the density the WebView reports,
// with the score taking its default share of the height.
const PANE_WIDTH = 1973;
const PANE_HEIGHT = 517;
const BACKGROUND = { r: 0x0d, g: 0x0f, b: 0x14 };

let failures = 0;
function check(condition, what) {
  console.log((condition ? 'ok   ' : 'FAIL ') + what);
  if (!condition) failures++;
}

function sampleScore(bars) {
  const steps = ['C', 'D', 'E', 'F', 'G', 'A', 'B'];
  const attributes =
    '<attributes><divisions>4</divisions><key><fifths>2</fifths></key>' +
    '<time><beats>4</beats><beat-type>4</beat-type></time><staves>2</staves>' +
    '<clef number="1"><sign>G</sign><line>2</line></clef>' +
    '<clef number="2"><sign>F</sign><line>4</line></clef></attributes>';

  const measures = [];
  for (let m = 1; m <= bars; m++) {
    const root = steps[m % 7];
    measures.push(`<measure number="${m}">${m === 1 ? attributes : ''}
      <note><pitch><step>${root}</step><octave>4</octave></pitch><duration>2</duration><voice>1</voice><type>eighth</type><staff>1</staff></note>
      <note><pitch><step>${steps[(m + 1) % 7]}</step><octave>4</octave></pitch><duration>2</duration><voice>1</voice><type>eighth</type><staff>1</staff></note>
      <note><pitch><step>${steps[(m + 2) % 7]}</step><octave>5</octave></pitch><duration>4</duration><voice>1</voice><type>quarter</type><staff>1</staff></note>
      <note><pitch><step>${steps[(m + 3) % 7]}</step><octave>4</octave></pitch><duration>8</duration><voice>1</voice><type>half</type><staff>1</staff></note>
      <backup><duration>16</duration></backup>
      <note><pitch><step>${root}</step><octave>2</octave></pitch><duration>8</duration><voice>5</voice><type>half</type><staff>2</staff></note>
      <note><pitch><step>${steps[(m + 4) % 7]}</step><octave>3</octave></pitch><duration>8</duration><voice>5</voice><type>half</type><staff>2</staff></note>
    </measure>`);
  }
  return `<?xml version="1.0" encoding="UTF-8"?>
<score-partwise version="4.0">
 <part-list><score-part id="P1"><score-part-name/></score-part></part-list>
 <part id="P1">${measures.join('')}</part>
</score-partwise>`.replace('<score-part-name/>', '<part-name>Piano</part-name>');
}

/** Fraction of pixels that differ from the pane background, and where they are. */
function inkProfile(buffer) {
  const png = PNG.sync.read(buffer);
  let inked = 0;
  let topmost = png.height;
  let leftmost = png.width;
  let rightmost = 0;
  for (let y = 0; y < png.height; y++) {
    for (let x = 0; x < png.width; x++) {
      const i = (png.width * y + x) << 2;
      const distance =
        Math.abs(png.data[i] - BACKGROUND.r) +
        Math.abs(png.data[i + 1] - BACKGROUND.g) +
        Math.abs(png.data[i + 2] - BACKGROUND.b);
      // 24 total across three channels is about 3% brightness — below that a
      // reader on a dark screen sees nothing, so it does not count as drawn.
      if (distance > 24) {
        inked++;
        if (y < topmost) topmost = y;
        if (x < leftmost) leftmost = x;
        if (x > rightmost) rightmost = x;
      }
    }
  }
  return {
    coverage: inked / (png.width * png.height),
    topmost: inked ? topmost : -1,
    leftmost: inked ? leftmost : -1,
    rightmost: inked ? rightmost : -1,
    width: png.width,
    height: png.height,
  };
}

const saveTo = process.argv.includes('--save')
  ? process.argv[process.argv.indexOf('--save') + 1]
  : null;

/**
 * Finds a Chromium to drive.
 *
 * Playwright wants the exact build its own version pins, which is right on CI
 * where it downloads one, and wrong in a prepared container that already ships a
 * perfectly good Chromium under a different build number. Preferring what is
 * already installed avoids a download that would fail anyway when the version
 * does not line up.
 */
function findChromium() {
  if (process.env.CHROMIUM_PATH) return process.env.CHROMIUM_PATH;
  const candidates = globSync('/opt/pw-browsers/chromium-*/chrome-linux/chrome');
  return candidates.length ? candidates.sort().at(-1) : undefined;
}

const executablePath = findChromium();
const browser = await chromium.launch(executablePath ? { executablePath } : {});
const page = await browser.newPage({
  viewport: { width: PANE_WIDTH, height: PANE_HEIGHT },
  deviceScaleFactor: 1,
});

const events = [];
await page.exposeFunction('__mkEvent', (payload) => events.push(JSON.parse(payload)));
await page.addInitScript(() => {
  window.MasterKey = { onScoreEvent: (p) => window.__mkEvent(p) };
});

// Stands in for WebViewAssetLoader: same origin, same paths, local files only.
await page.route('**/*', async (route) => {
  const name = path.basename(new URL(route.request().url()).pathname) || 'index.html';
  const file = path.join(pane, name);
  if (!fs.existsSync(file)) return route.fulfill({ status: 404, body: '' });
  const contentType = name.endsWith('.js')
    ? 'application/javascript'
    : name.endsWith('.html')
      ? 'text/html'
      : 'text/plain';
  return route.fulfill({ status: 200, contentType, body: fs.readFileSync(file) });
});

await page.goto('https://appassets.androidplatform.net/assets/score/index.html');
await page.waitForFunction(
  () => window.MasterKeyScore && window.verovio && window.verovio.module.calledRun,
  null,
  { timeout: 60_000 },
);

await page.evaluate((xml) => window.MasterKeyScore.load(xml), sampleScore(40));
await page.waitForFunction(() => !!document.querySelector('#page svg'), null, { timeout: 30_000 });
await page.waitForTimeout(400);

const errors = events.filter((e) => e.type === 'error');
check(errors.length === 0, 'the page reports no error' + (errors.length ? `: ${JSON.stringify(errors)}` : ''));

const loaded = events.find((e) => e.type === 'loaded');
check(!!loaded, 'the page reports "loaded"');
check(
  !!loaded && loaded.width >= PANE_WIDTH - 2 && loaded.height > 0,
  `the page reports a drawn size filling the pane (${loaded && `${loaded.width}×${loaded.height}`})`,
);

const shot = await page.screenshot();
if (saveTo) fs.writeFileSync(saveTo, shot);
const ink = inkProfile(shot);

// The whole point. A blank pane is the failure this file exists to catch, and
// it is the one every other test in this repo is structurally unable to see.
check(
  ink.coverage > 0.01,
  `the pane is not blank (${(ink.coverage * 100).toFixed(2)}% of pixels are inked)`,
);

// Notation stranded in a corner is the near-blank case: it passes a naive
// "something was drawn" check while being unreadable on a tablet.
check(
  ink.rightmost > PANE_WIDTH * 0.75,
  `the notation spans the pane (ink reaches x=${ink.rightmost} of ${PANE_WIDTH})`,
);

// Centring bar one used to push the top of the score a third of the way down an
// empty pane, and off the bottom entirely on a short one.
check(
  ink.topmost >= 0 && ink.topmost < PANE_HEIGHT * 0.25,
  `the score starts near the top of the pane (first ink at y=${ink.topmost})`,
);

// Staff height is the readability measure that matters, and it is set by the
// zoom: at 40% a grand staff came out under 30px and read as a grey smudge.
const staffHeight = await page.evaluate(() => {
  const staff = document.querySelector('#page svg .staff');
  return staff ? Math.round(staff.getBoundingClientRect().height) : 0;
});
check(staffHeight >= 40, `a staff is large enough to read (${staffHeight}px tall)`);

// Zoom has to actually change the engraving, not just the box around it.
const before = await page.evaluate(() => document.querySelectorAll('#page svg .measure').length);
await page.evaluate(() => window.MasterKeyScore.setZoom(110));
await page.waitForTimeout(600);
const after = await page.evaluate(() => document.querySelectorAll('#page svg .measure').length);
const zoomedStaff = await page.evaluate(() => {
  const staff = document.querySelector('#page svg .staff');
  return staff ? Math.round(staff.getBoundingClientRect().height) : 0;
});
check(after < before, `zooming in fits fewer bars on a system (${before} → ${after})`);
check(zoomedStaff > staffHeight, `zooming in draws a larger staff (${staffHeight} → ${zoomedStaff}px)`);

await browser.close();
console.log(failures ? `\n${failures} check(s) failed` : '\nall checks passed');
process.exit(failures ? 1 : 0);
