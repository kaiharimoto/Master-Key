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
 *
 * Everything the SVG cannot tell us has to be asked of the toolkit rather than
 * inferred from the DOM, because the DOM only ever holds one page. That is why
 * the current bar comes from the timemap and the page it lives on comes from
 * getPageWithElement(): a note that has scrolled off the rendered page is not
 * findable by id, and treating "not on this page" as "nowhere" leaves the score
 * stuck on page one for the rest of the piece.
 */
(function () {
  'use strict';

  var toolkit = null;
  var timemap = [];       // [{ qstamp, tstamp, on: [ids], off: [ids], measureOn }]
  var measureAt = [];     // timemap index -> id of the measure in effect there
  var noteHand = {};      // element id -> 'right' | 'left', for the current page
  var currentPage = 0;
  var pageCount = 0;
  var sounding = {};      // element id -> true
  var currentMeasureId = null;
  var lastIndex = -1;

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

  /**
   * Engraving scale, as a percentage.
   *
   * This same number has to divide the page dimensions below, because Verovio
   * emits an SVG of `pageWidth * SCALE / 100` pixels. Passing the pane's pixel
   * width straight through as pageWidth (i.e. dividing by 100 instead of by the
   * scale) engraves the page at 40% of the pane and breaks the piece across two
   * and a half times as many pages as it needs.
   */
  var SCALE = 40;

  function options() {
    return {
      // One page per screenful, laid out to the viewport, rather than one huge
      // SVG — this is what keeps rendering cheap on a tablet.
      pageWidth: Math.max(600, Math.round(viewportEl.clientWidth * 100 / SCALE)),
      pageHeight: Math.max(400, Math.round(viewportEl.clientHeight * 100 / SCALE)),
      scale: SCALE,
      adjustPageHeight: true,
      breaks: 'auto',
      // Times is not on Android; Leipzig ships with Verovio.
      fontFallback: 'Leipzig',
      footer: 'none',
      header: 'none',
      spacingStaff: 10,
      spacingSystem: 8,
      // A viewBox instead of fixed px dimensions, so `width: 100%` scales the
      // notation itself rather than just the box around it. Without it the
      // content keeps its own coordinate system and sits in the top-left corner
      // whenever the layout and the pane disagree — which they do for the 250 ms
      // the resize debounce takes to catch up with a split-handle drag.
      svgViewBox: true,
      // Puts data-n on every <g class="staff">. Nothing else in the SVG says
      // which staff a notehead sits on, and the staff is how the two hands are
      // told apart — without this every note would be coloured right-hand.
      svgAdditionalAttribute: ['staff@n']
    };
  }

  /**
   * Verovio's JavaScript toolkit hands back an already-parsed timemap; older
   * builds returned the JSON text. Accept either.
   *
   * Assuming the string form throws inside load(), which is caught and reported
   * as "this score could not be read" — for a score that is perfectly fine. The
   * whole pane looks unimplemented rather than broken, so it is worth being
   * tolerant here.
   */
  function asTimemap(value) {
    if (Array.isArray(value)) return value;
    if (typeof value === 'string') {
      try {
        return JSON.parse(value) || [];
      } catch (e) {
        return [];
      }
    }
    return [];
  }

  window.MasterKeyScore = {

    init: function () {
      if (toolkit) return;
      try {
        toolkit = new verovio.toolkit();
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
        timemap = asTimemap(
          toolkit.renderToTimemap({ includeMeasures: true, includeRests: false })
        );
        indexMeasures();

        currentPage = 0;
        currentMeasureId = null;
        lastIndex = -1;
        sounding = {};
        renderPage(1);
        // Mark bar one straight away. Every measure is dimmed relative to the
        // current one, so leaving nothing current until the first cursor update
        // renders the entire score at the dimmed level.
        updateMeasure(measureAt[0]);

        post({
          type: 'loaded',
          pages: pageCount,
          events: timemap.length,
          // Verovio expands repeats for timemap output by default, so the score
          // timeline is already in performance order.
          lastQstamp: timemap.length ? timemap[timemap.length - 1].qstamp : 0
        });
        status(timemap.length ? '' : 'This score has no playable notes.');
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

      var next = {};
      var from = 0;
      if (index > lastIndex) {
        // Ordinary playback: carry the current set forward over the entries
        // crossed since the last update, rather than replaying the whole piece
        // twenty times a second.
        for (var id in sounding) next[id] = true;
        from = lastIndex + 1;
      }
      // Backwards — a scrub or a loop wrap — has to start over, because the
      // on/off bookkeeping in the timemap only runs one way.
      for (var i = from; i <= index; i++) accumulate(next, timemap[i]);

      lastIndex = index;
      applySounding(next);
      updateMeasure(measureAt[index]);
    },

    setPage: function (page) {
      renderPage(page);
    },

    /**
     * Toggles engraved fingering.
     *
     * A class on the body rather than a Verovio option: fingerings are ordinary
     * SVG elements once engraved, so hiding them is a CSS rule and needs no
     * relayout. (Verovio has no `showFingering` option — passing one is rejected
     * and silently changes nothing.)
     */
    setFingering: function (enabled) {
      if (enabled) {
        document.body.classList.remove('mk-hide-fingering');
      } else {
        document.body.classList.add('mk-hide-fingering');
      }
    },

    relayout: function () {
      if (!toolkit || !timemap.length) return;
      try {
        toolkit.setOptions(options());
        toolkit.redoLayout();
        pageCount = toolkit.getPageCount();
        // The bar being played may well have moved to a different page.
        var page = currentMeasureId ? toolkit.getPageWithElement(currentMeasureId) : 0;
        renderPage(page || currentPage || 1, true);
      } catch (e) {
        post({ type: 'error', message: String(e) });
      }
    },

    setTheme: function (dark) {
      document.documentElement.style.setProperty('--bg', dark ? '#0d0f14' : '#fdfdfd');
      document.documentElement.style.setProperty('--ink', dark ? '#e3e6ec' : '#1a1c22');
    }
  };

  /**
   * Which measure is in effect at each timemap index.
   *
   * `measureOn` only appears on the entry where a bar starts, so it is carried
   * forward. Reading the bar from the timemap rather than from the sounding
   * notes is what lets updateMeasure() turn the page: the notes it would have
   * looked up are, by definition, not in the DOM when they are on another page.
   */
  function indexMeasures() {
    measureAt = new Array(timemap.length);
    var current = null;
    for (var i = 0; i < timemap.length; i++) {
      if (timemap[i].measureOn) current = timemap[i].measureOn;
      measureAt[i] = current;
    }
  }

  function accumulate(set, entry) {
    if (!entry) return;
    var i;
    if (entry.off) {
      for (i = 0; i < entry.off.length; i++) delete set[entry.off[i]];
    }
    if (entry.on) {
      for (i = 0; i < entry.on.length; i++) set[entry.on[i]] = true;
    }
  }

  function renderPage(page, force) {
    if (!toolkit || page < 1) return;
    if (pageCount && page > pageCount) page = pageCount;
    if (page === currentPage && !force) return;
    currentPage = page;
    pageEl.innerHTML = toolkit.renderToSVG(page, {});
    // The manual scroll offset belonged to the page just replaced.
    pageEl.dataset.offset = '0';
    pageEl.style.transform = 'translateY(0px)';
    indexHands();
    reapplyClasses();
    post({ type: 'page', page: page, pages: pageCount });
  }

  /**
   * Tags each note on the current page with the hand that plays it.
   *
   * Staff 1 is the upper (right hand), staff 2 the lower. Taking this from the
   * notation rather than guessing from pitch is why the score view and the
   * highway always agree on colour. The `data-n` attribute comes from the
   * svgAdditionalAttribute option — the SVG carries no staff number without it.
   */
  function indexHands() {
    noteHand = {};
    var staves = pageEl.querySelectorAll('.staff');
    for (var s = 0; s < staves.length; s++) {
      var staff = staves[s];
      var hand = staff.getAttribute('data-n') === '2' ? 'left' : 'right';
      var notes = staff.querySelectorAll('.note');
      for (var i = 0; i < notes.length; i++) {
        var id = notes[i].id;
        if (!id) continue;
        noteHand[id] = hand;
        notes[i].classList.add(hand === 'left' ? 'mk-left' : 'mk-right');
      }
    }
  }

  /** Re-marks the current bar and the sounding notes after a page is rebuilt. */
  function reapplyClasses() {
    for (var id in sounding) {
      var note = document.getElementById(id);
      if (note) note.classList.add('mk-sounding');
    }
    if (currentMeasureId) {
      var measure = document.getElementById(currentMeasureId);
      if (measure) measure.classList.add('mk-current');
      markNeighbours(currentMeasureId);
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

  function updateMeasure(measureId) {
    if (!measureId || measureId === currentMeasureId) return;

    // Turn to the page the bar is on before touching the DOM. Verovio tells us
    // which page an element is on, so page turns need no bookkeeping of ours.
    var page = toolkit ? toolkit.getPageWithElement(measureId) : 0;
    if (page && page !== currentPage) renderPage(page);

    if (currentMeasureId) {
      var old = document.getElementById(currentMeasureId);
      if (old) old.classList.remove('mk-current');
    }
    currentMeasureId = measureId;

    var el = document.getElementById(measureId);
    if (!el) return;
    el.classList.add('mk-current');
    markNeighbours(measureId);
    scrollToElement(el);
  }

  /** The bars either side sit between "current" and "elsewhere" in the dimming. */
  function markNeighbours(measureId) {
    var all = pageEl.querySelectorAll('.measure');
    var index = -1;
    for (var i = 0; i < all.length; i++) {
      all[i].classList.remove('mk-near');
      if (all[i].id === measureId) index = i;
    }
    if (index < 0) return;
    if (index > 0) all[index - 1].classList.add('mk-near');
    if (index + 1 < all.length) all[index + 1].classList.add('mk-near');
  }

  function scrollToElement(el) {
    // Drive the scroll ourselves rather than using scrollIntoView, which is
    // documented to behave inconsistently inside Android's WebView.
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

  // Dragging the split handle resizes the pane continuously, and redoLayout is
  // the expensive half of engraving — so relayout only once the drag settles.
  var resizeTimer = null;
  window.addEventListener('resize', function () {
    if (resizeTimer) clearTimeout(resizeTimer);
    resizeTimer = setTimeout(function () {
      resizeTimer = null;
      window.MasterKeyScore.relayout();
    }, 250);
  });

  // verovio-toolkit-wasm.js runs its module as soon as the WASM instantiates,
  // which may already have happened by the time this file executes.
  if (typeof verovio === 'undefined' || !verovio.module) {
    status('Engraver failed to load.');
  } else if (verovio.module.calledRun) {
    window.MasterKeyScore.init();
  } else {
    verovio.module.onRuntimeInitialized = function () {
      window.MasterKeyScore.init();
    };
  }
})();
