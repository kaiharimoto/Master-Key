/*
 * Score pane bridge.
 *
 * Verovio does two jobs here, and using one engine for both is the point:
 * it engraves the notation AND produces the timemap. Because both come from the
 * same internal representation, the element ids in the timemap are by definition
 * the ids in the rendered SVG — no cross-engine matching, nothing to drift.
 *
 * Position is pushed from Kotlin at about 20 Hz rather than every frame. A score
 * cursor only changes at note boundaries (rarely more than ~10 times a second),
 * so 20 Hz is imperceptibly smooth, while pumping a 60 Hz clock across the
 * JavaScript bridge is the classic way to make a WebView janky next to a
 * hardware-accelerated canvas.
 */
(function () {
  'use strict';

  var toolkit = null;
  var timemap = [];       // [{ qstamp, tstamp, on: [ids], off: [ids] }]
  var noteHand = {};      // element id -> 'right' | 'left'
  var measureOfNote = {}; // element id -> measure element id
  var currentPage = 0;
  var pageCount = 0;
  var sounding = {};      // element id -> true
  var currentMeasureId = null;
  var lastIndex = -1;
  var ready = false;
  var showFingering = true;

  var pageEl = document.getElementById('page');
  var statusEl = document.getElementById('status');
  var viewportEl = document.getElementById('viewport');

  function status(text) {
    if (!text) {
      statusEl.classList.add('hidden');
    } else {
      statusEl.classList.remove('hidden');
      statusEl.textContent = text;
    }
  }

  function post(message) {
    if (window.MasterKey && window.MasterKey.onScoreEvent) {
      window.MasterKey.onScoreEvent(JSON.stringify(message));
    }
  }

  function options() {
    return {
      // One page per screenful, laid out to the viewport, rather than one huge
      // SVG — this is what keeps rendering cheap on a tablet.
      pageWidth: Math.max(600, Math.round(viewportEl.clientWidth * 100 / zoom())),
      pageHeight: Math.max(400, Math.round(viewportEl.clientHeight * 100 / zoom())),
      scale: 40,
      adjustPageHeight: true,
      breaks: 'auto',
      // Times is not on Android; Liberation ships with Verovio.
      fontFallback: 'Leipzig',
      footer: 'none',
      header: 'none',
      spacingStaff: 10,
      spacingSystem: 8,
      // Fingering comes straight from <technical><fingering> — a free win, and
      // one of the highest value-for-effort reading aids there is.
      showFingering: showFingering
    };
  }

  function zoom() {
    return 100;
  }

  window.MasterKeyScore = {

    init: function () {
      if (toolkit) return;
      try {
        toolkit = new verovio.toolkit();
        ready = true;
        status('');
        post({ type: 'ready' });
      } catch (e) {
        status('Could not start the engraver.');
        post({ type: 'error', message: String(e) });
      }
    },

    /** Loads MusicXML (already decoded to a string on the Kotlin side). */
    load: function (xml) {
      if (!toolkit) {
        status('Engraver not ready.');
        return;
      }
      status('Engraving…');
      try {
        toolkit.setOptions(options());
        var ok = toolkit.loadData(xml);
        if (!ok) {
          status('This score could not be read.');
          post({ type: 'error', message: 'loadData returned false' });
          return;
        }

        pageCount = toolkit.getPageCount();

        // includeMeasures lets us highlight the whole current bar, which is the
        // single most useful aid for someone who loses their place while reading.
        timemap = JSON.parse(
          toolkit.renderToTimemap({ includeMeasures: true, includeRests: false })
        ) || [];

        currentPage = 0;
        lastIndex = -1;
        renderPage(1);
        indexHands();

        post({
          type: 'loaded',
          pages: pageCount,
          events: timemap.length,
          // Verovio expands repeats for timemap output by default, so the score
          // timeline is already in performance order.
          lastQstamp: timemap.length ? timemap[timemap.length - 1].qstamp : 0
        });
        status('');
      } catch (e) {
        status('This score could not be read.');
        post({ type: 'error', message: String(e) });
      }
    },

    /**
     * Moves the cursor. `qstamp` is quarter notes from the start — musical time,
     * not wall clock, so it stays correct at any playback speed and across tempo
     * changes without any conversion on this side.
     */
    seekQuarter: function (qstamp) {
      if (!timemap.length) return;

      var index = findIndex(qstamp);
      if (index === lastIndex) return;
      lastIndex = index;

      var nowSounding = {};
      // Walk forward from the start of the piece is too slow; instead rebuild the
      // sounding set from the events up to here using on/off bookkeeping.
      for (var i = 0; i <= index; i++) {
        var entry = timemap[i];
        if (entry.off) {
          for (var j = 0; j < entry.off.length; j++) delete nowSounding[entry.off[j]];
        }
        if (entry.on) {
          for (var k = 0; k < entry.on.length; k++) nowSounding[entry.on[k]] = true;
        }
      }

      applySounding(nowSounding);
      updateMeasure(index);
    },

    setPage: function (page) {
      renderPage(page);
    },

    /** Toggles engraved fingering. Needs a relayout, so it is not per-frame. */
    setFingering: function (enabled) {
      if (!toolkit) return;
      if (showFingering === enabled) return;
      showFingering = enabled;
      window.MasterKeyScore.relayout();
    },

    relayout: function () {
      if (!toolkit) return;
      try {
        toolkit.setOptions(options());
        toolkit.redoLayout();
        pageCount = toolkit.getPageCount();
        renderPage(currentPage || 1);
        indexHands();
      } catch (e) {
        post({ type: 'error', message: String(e) });
      }
    },

    setTheme: function (dark) {
      document.documentElement.style.setProperty('--bg', dark ? '#0d0f14' : '#fdfdfd');
      document.documentElement.style.setProperty('--ink', dark ? '#e3e6ec' : '#1a1c22');
    }
  };

  function renderPage(page) {
    if (!toolkit || page < 1) return;
    if (pageCount && page > pageCount) page = pageCount;
    if (page === currentPage) return;
    currentPage = page;
    pageEl.innerHTML = toolkit.renderToSVG(page, {});
    // Re-applying colours after a page change; the SVG is new DOM.
    applyHandClasses();
    post({ type: 'page', page: page, pages: pageCount });
  }

  /**
   * Tags each note with the hand that plays it, from the staff it sits on.
   *
   * Staff 1 is the upper (right hand), staff 2 the lower. Taking this from the
   * notation rather than guessing from pitch is why the score view and the
   * highway always agree on colour.
   */
  function indexHands() {
    noteHand = {};
    measureOfNote = {};
    var staves = pageEl.querySelectorAll('.staff');
    for (var s = 0; s < staves.length; s++) {
      var staff = staves[s];
      var n = staff.getAttribute('data-n') || staff.getAttribute('n');
      var hand = (n === '2') ? 'left' : 'right';
      var notes = staff.querySelectorAll('.note');
      for (var i = 0; i < notes.length; i++) {
        var id = notes[i].getAttribute('data-id') || notes[i].id;
        if (!id) continue;
        noteHand[id] = hand;
        var measure = notes[i].closest ? notes[i].closest('.measure') : null;
        if (measure) measureOfNote[id] = measure.id || measure.getAttribute('data-id');
      }
    }
    applyHandClasses();
  }

  function applyHandClasses() {
    for (var id in noteHand) {
      if (!Object.prototype.hasOwnProperty.call(noteHand, id)) continue;
      var el = document.getElementById(id);
      if (!el) continue;
      el.classList.add(noteHand[id] === 'left' ? 'mk-left' : 'mk-right');
    }
  }

  function applySounding(next) {
    for (var id in sounding) {
      if (!next[id]) {
        var was = document.getElementById(id);
        if (was) was.classList.remove('mk-sounding');
      }
    }
    for (var newId in next) {
      if (!sounding[newId]) {
        var el = document.getElementById(newId);
        if (el) el.classList.add('mk-sounding');
      }
    }
    sounding = next;
  }

  function updateMeasure(index) {
    // Find the measure this event belongs to by looking at any sounding note,
    // falling back to the nearest measure entry in the timemap.
    var measureId = null;
    for (var id in sounding) {
      if (measureOfNote[id]) { measureId = measureOfNote[id]; break; }
    }
    if (!measureId) return;
    if (measureId === currentMeasureId) return;

    if (currentMeasureId) {
      var old = document.getElementById(currentMeasureId);
      if (old) old.classList.remove('mk-current');
    }
    currentMeasureId = measureId;
    var el = document.getElementById(measureId);
    if (!el) {
      // The current measure is on another page — turn to it. Verovio tells us
      // which page an element is on, so page turns need no manual bookkeeping.
      var page = toolkit ? toolkit.getPageWithElement(measureId) : 0;
      if (page && page !== currentPage) {
        renderPage(page);
        el = document.getElementById(measureId);
      }
    }
    if (el) {
      el.classList.add('mk-current');
      scrollToElement(el);
    }
  }

  function scrollToElement(el) {
    // Drive the scroll ourselves rather than using scrollIntoView, which is
    // documented to behave inconsistently inside Android's WebView.
    var box = el.getBBox ? null : null;
    var rect = el.getBoundingClientRect();
    var viewRect = viewportEl.getBoundingClientRect();
    var offset = rect.top - viewRect.top;
    var target = offset - viewRect.height * 0.35;
    if (Math.abs(target) < 24) return;
    var current = parseFloat(pageEl.dataset.offset || '0');
    var next = current - target;
    pageEl.dataset.offset = String(next);
    pageEl.style.transform = 'translateY(' + next + 'px)';
  }

  function findIndex(qstamp) {
    var lo = 0;
    var hi = timemap.length - 1;
    if (qstamp <= timemap[0].qstamp) return 0;
    while (lo < hi) {
      var mid = (lo + hi + 1) >> 1;
      if (timemap[mid].qstamp <= qstamp) lo = mid; else hi = mid - 1;
    }
    return lo;
  }

  // verovio-toolkit-wasm.js exposes a promise-like `verovio.module.onRuntimeInitialized`.
  if (typeof verovio !== 'undefined' && verovio.module) {
    verovio.module.onRuntimeInitialized = function () {
      window.MasterKeyScore.init();
    };
  } else {
    status('Engraver failed to load.');
  }
})();
