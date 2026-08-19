/*
 * End-to-end test for the score pane, run on the host.
 *
 *     npm --prefix tools/score-test install
 *     node tools/score-test/scorepane.test.mjs
 *
 * It loads the real index.html, the real Verovio build and the real score.js
 * into jsdom, then drives them exactly as ScorePane.kt does: hand over MusicXML,
 * then push a position in quarter notes about twenty times a second.
 *
 * This exists because the score pane can fail completely and quietly. Verovio's
 * JavaScript toolkit returns an already-parsed timemap; score.js used to
 * JSON.parse() it, which threw, which the catch turned into "this score could
 * not be read" for every file. Nothing crashed and nothing was logged — the
 * feature simply looked as though it had never been built. A rendering check
 * that actually walks a score from end to end is the only thing that catches
 * that class of failure.
 */

import { JSDOM } from 'jsdom';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';

const here = path.dirname(fileURLToPath(import.meta.url));
const pane = path.resolve(here, '../../app/src/main/assets/score');
const require = createRequire(import.meta.url);

let failures = 0;
function check(condition, what) {
  console.log((condition ? 'ok   ' : 'FAIL ') + what);
  if (!condition) failures++;
}

/** A grand-staff piece long enough to need several pages, with chords. */
function sampleScore(bars) {
  const steps = ['C', 'D', 'E', 'F', 'G', 'A', 'B'];
  const attributes =
    '<attributes><divisions>4</divisions><key><fifths>0</fifths></key>' +
    '<time><beats>4</beats><beat-type>4</beat-type></time><staves>2</staves>' +
    '<clef number="1"><sign>G</sign><line>2</line></clef>' +
    '<clef number="2"><sign>F</sign><line>4</line></clef></attributes>';

  const measures = [];
  for (let m = 1; m <= bars; m++) {
    const root = steps[m % 7];
    measures.push(`<measure number="${m}">
      ${m === 1 ? attributes : ''}
      <note><pitch><step>${root}</step><octave>4</octave></pitch><duration>4</duration><voice>1</voice><type>quarter</type><staff>1</staff><notations><technical><fingering>2</fingering></technical></notations></note>
      <note><chord/><pitch><step>${steps[(m + 2) % 7]}</step><octave>4</octave></pitch><duration>4</duration><voice>1</voice><type>quarter</type><staff>1</staff></note>
      <note><chord/><pitch><step>${steps[(m + 4) % 7]}</step><octave>4</octave></pitch><duration>4</duration><voice>1</voice><type>quarter</type><staff>1</staff></note>
      <note><pitch><step>${root}</step><octave>4</octave></pitch><duration>12</duration><voice>1</voice><type>half</type><staff>1</staff></note>
      <backup><duration>16</duration></backup>
      <note><pitch><step>${root}</step><octave>2</octave></pitch><duration>16</duration><voice>5</voice><type>whole</type><staff>2</staff></note>
    </measure>`);
  }
  return `<?xml version="1.0" encoding="UTF-8"?>
<score-partwise version="4.0">
 <part-list><score-part id="P1"><part-name>Piano</part-name></score-part></part-list>
 <part id="P1">${measures.join('')}</part>
</score-partwise>`;
}

const dom = new JSDOM(fs.readFileSync(path.join(pane, 'index.html'), 'utf8'), {
  runScripts: 'outside-only',
  pretendToBeVisual: true,
  url: 'https://appassets.androidplatform.net/assets/score/index.html',
});
const win = dom.window;
const doc = win.document;

// jsdom does no layout, so element sizes read zero. Give the pane a tablet-sized
// viewport, which is what decides how Verovio paginates.
const PANE_WIDTH = 1200;
const PANE_HEIGHT = 520;
Object.defineProperty(doc.getElementById('viewport'), 'clientWidth', { value: PANE_WIDTH });
Object.defineProperty(doc.getElementById('viewport'), 'clientHeight', { value: PANE_HEIGHT });

// score.js checks that what it engraved actually has a laid-out box, because
// "engraved fine, painted nothing" is a real failure and used to be invisible.
// jsdom measures everything as zero, so without this the check reads every run
// as that failure. What the *pixels* look like is the render test's job; this
// suite is about the DOM and the timemap.
win.Element.prototype.getBoundingClientRect = function () {
  const isViewport = this.id === 'viewport';
  const width = PANE_WIDTH;
  const height = isViewport ? PANE_HEIGHT : 200;
  return { x: 0, y: 0, top: 0, left: 0, right: width, bottom: height, width, height };
};

const events = [];
win.MasterKey = { onScoreEvent: (payload) => events.push(JSON.parse(payload)) };

// Stands in for the two <script> tags: the toolkit, then score.js — which runs
// while the WASM is still instantiating, as it does in the WebView.
win.verovio = require(path.join(pane, 'verovio-toolkit-wasm.js'));
win.eval(fs.readFileSync(path.join(pane, 'score.js'), 'utf8'));

await new Promise((resolve, reject) => {
  let waited = 0;
  const wait = () => {
    if (events.some((e) => e.type === 'ready')) return resolve();
    if ((waited += 20) > 30_000) return reject(new Error('the engraver never reported ready'));
    setTimeout(wait, 20);
  };
  wait();
});

check(true, 'engraver reports ready');

const BARS = 80;
win.MasterKeyScore.load(sampleScore(BARS));

const loaded = events.find((e) => e.type === 'loaded');
const errors = events.filter((e) => e.type === 'error');
check(errors.length === 0, 'load reports no error' + (errors.length ? `: ${JSON.stringify(errors)}` : ''));
check(!!loaded, 'load reports "loaded"');
if (!loaded) {
  console.log('\nthe score never loaded; nothing further can be checked');
  process.exit(1);
}
check(loaded.events > 0, `the timemap has entries (${loaded.events})`);
check(loaded.pages > 1, `the score paginates (${loaded.pages} pages)`);
check(doc.getElementById('status').classList.contains('hidden'), 'the status message is cleared');

// The engraved page must be as wide as the pane it is being drawn into.
// Dividing pageWidth by 100 instead of by the scale engraved it at 40% of the
// pane and left it stranded in the top-left corner — which is most of why a
// linked score looked like an empty black rectangle.
const svg = doc.querySelector('#page svg');
const viewBox = svg && svg.getAttribute('viewBox');
check(!!viewBox, `the SVG carries a viewBox, so width:100% scales the notation (${viewBox})`);
const engravedWidth = viewBox ? Number(viewBox.split(/\s+/)[2]) : 0;
check(
  Math.abs(engravedWidth - PANE_WIDTH) <= 1,
  `the engraved page fills the pane (${engravedWidth} for a ${PANE_WIDTH}px pane)`,
);

// Every measure is dimmed relative to the current one, so a score with nothing
// current renders entirely at the dimmed level — dark enough on #0d0f14 to read
// as blank. Bar one has to be lit before the cursor ever moves.
check(
  doc.querySelector('.measure.mk-current') !== null,
  'a bar is highlighted on load, before any cursor update',
);

const right = doc.querySelectorAll('.note.mk-right').length;
const left = doc.querySelectorAll('.note.mk-left').length;
check(right > 0 && left > 0, `both hands are coloured (right ${right}, left ${left})`);

// Walk the whole piece the way the Kotlin cursor loop does.
const pagesVisited = new Set([1]);
const barsHighlighted = new Set();
let mostSoundingAtOnce = 0;
for (let quarter = 0; quarter <= loaded.lastQstamp; quarter += 0.25) {
  win.MasterKeyScore.seekQuarter(quarter);
  const current = doc.querySelector('.measure.mk-current');
  if (current) barsHighlighted.add(current.id);
  mostSoundingAtOnce = Math.max(mostSoundingAtOnce, doc.querySelectorAll('.note.mk-sounding').length);
  const lastPage = events.filter((e) => e.type === 'page').pop();
  if (lastPage) pagesVisited.add(lastPage.page);
}
check(pagesVisited.size === loaded.pages, `every page is turned to (${[...pagesVisited].join(', ')})`);
check(barsHighlighted.size === BARS, `the bar highlight advances through the piece (${barsHighlighted.size} bars)`);
check(mostSoundingAtOnce >= 4, `chords light up together (${mostSoundingAtOnce} noteheads at once)`);

// Scrubbing backwards has to rebuild the sounding set, not carry it forward.
win.MasterKeyScore.seekQuarter(0);
const atStart = doc.querySelectorAll('.note.mk-sounding').length;
check(doc.querySelector('.measure.mk-current') !== null, 'scrubbing back to the start re-highlights bar 1');
check(atStart > 0 && atStart <= 4, `only the opening chord stays lit after scrubbing back (${atStart})`);

check(doc.querySelectorAll('.fing').length > 0, 'fingering is engraved into the SVG');
win.MasterKeyScore.setFingering(false);
check(doc.body.classList.contains('mk-hide-fingering'), 'fingering is hidden by a class, without a relayout');
win.MasterKeyScore.setFingering(true);
check(!doc.body.classList.contains('mk-hide-fingering'), 'fingering comes back');

console.log(failures ? `\n${failures} check(s) failed` : '\nall checks passed');
process.exit(failures ? 1 : 0);
