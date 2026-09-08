/* ── State ────────────────────────────────────────────────────────────────── */
const state = {
  track: null,
  lyrics: null,
  progress_ms: 0,
  activeIndex: -1,
  lineElements: [],
};

const LyricsEngine = Capacitor.Plugins.LyricsEngine;

/* ── DOM refs ─────────────────────────────────────────────────────────────── */
const $ = id => document.getElementById(id);
const dom = {
  cover:         $('track-cover'),
  coverPH:       $('track-cover-placeholder'),
  name:          $('track-name'),
  artist:        $('track-artist'),
  progress:      $('progress-bar'),
  idle:          $('state-idle'),
  loading:       $('state-loading'),
  noLyrics:      $('state-no-lyrics'),
  noLyricsHint:  $('no-lyrics-hint'),
  rateLimited:     $('state-rate-limited'),
  rateLimitedHint: $('rate-limited-hint'),
  syncedWrap:    $('lyrics-synced'),
  syncedScroll:  $('lyrics-scroll'),   // scrollable overflow-y:auto container
  syncedInner:   $('lyrics-inner'),    // inner flex column with actual lines
  plainWrap:     $('lyrics-plain'),
  plainText:     $('lyrics-plain-text'),
};

/* ── Smooth scroll state ──────────────────────────────────────────────────── */
let _scrollCurrent = 0;
let _scrollTarget  = 0;
let _scrollAnimating = false;

function animateScrollTo(target) {
  _scrollTarget = target;
  if (_scrollAnimating) return;
  _scrollAnimating = true;

  function step() {
    const diff = _scrollTarget - _scrollCurrent;
    if (Math.abs(diff) < 0.5) {
      _scrollCurrent = _scrollTarget;
      dom.syncedScroll.scrollTop = _scrollCurrent;
      _scrollAnimating = false;
      return;
    }
    _scrollCurrent += diff * 0.14;
    dom.syncedScroll.scrollTop = _scrollCurrent;
    requestAnimationFrame(step);
  }

  requestAnimationFrame(step);
}

/* ── Engine-Events (Capacitor-Plugin statt WebSocket) ────────────────────── */
function connect() {
  LyricsEngine.addListener('track', (track) => updateTrack(track));
  LyricsEngine.addListener('lyrics', (data) => data.loading ? showScreen('loading') : setLyrics(data));
  LyricsEngine.addListener('progress', (data) => updateProgress(data.progress_ms));
  LyricsEngine.addListener('stopped', () => handleStopped());

  // Beim (Wieder-)Öffnen der WebView lief der Foreground-Service evtl. schon -
  // sofort den letzten bekannten Stand holen statt auf das nächste Event zu warten
  LyricsEngine.getCurrentTrack().then(({ track, lyrics }) => {
    if (track) {
      updateTrack(track);
      if (lyrics) setLyrics(lyrics);
    }
  }).catch(() => {});
}

/* ── Track update ─────────────────────────────────────────────────────────── */
function updateTrack(track) {
  state.track = track;
  dom.name.textContent   = track.name;
  dom.artist.textContent = track.artist;
  document.title         = `${track.name} – ${track.artist}`;

  if (track.cover_small) {
    dom.cover.src = track.cover_small;
    dom.cover.classList.remove('hidden');
    dom.coverPH.classList.add('hidden');
  } else {
    dom.cover.classList.add('hidden');
    dom.coverPH.classList.remove('hidden');
  }

  updateProgress(track.progress_ms, track.duration_ms);
}

/* ── Progress ─────────────────────────────────────────────────────────────── */
function updateProgress(progress_ms, duration_ms) {
  state.progress_ms = progress_ms;
  const dur = duration_ms || state.track?.duration_ms || 1;
  dom.progress.style.width = Math.min(100, (progress_ms / dur) * 100) + '%';
  if (state.lyrics?.synced) syncLyrics(progress_ms);
}

/* ── Lyrics ───────────────────────────────────────────────────────────────── */
function setLyrics(lyrics) {
  state.lyrics = lyrics;
  state.activeIndex = -1;
  state.lineElements = [];
  _scrollCurrent = 0;
  _scrollTarget  = 0;

  const trackLabel = state.track
    ? `${state.track.name} – ${state.track.artist}` : '';

  // API-Limit von "keine Lyrics gefunden" unterscheiden
  if (lyrics && lyrics.source === 'rate_limited') {
    showScreen('rate-limited');
    dom.rateLimitedHint.textContent = trackLabel;
    return;
  }

  if (!lyrics || lyrics.source === 'none') {
    showScreen('no-lyrics');
    dom.noLyricsHint.textContent = trackLabel;
    return;
  }

  if (lyrics.synced?.length > 0) {
    buildSyncedLyrics(lyrics.synced);
    showScreen('synced');
  } else if (lyrics.plain) {
    dom.plainText.textContent = lyrics.plain;
    showScreen('plain');
  } else {
    showScreen('no-lyrics');
  }
}

function buildSyncedLyrics(lines) {
  dom.syncedInner.innerHTML = '';
  state.lineElements = [];

  lines.forEach((line, i) => {
    const el = document.createElement('div');
    el.className = 'lyric-line' + (line.text === '' ? ' empty-line' : '');
    el.textContent = line.text || '';
    el.dataset.index = i;
    dom.syncedInner.appendChild(el);
    state.lineElements.push(el);
  });

  // Reset scroll position
  dom.syncedScroll.scrollTop = 0;
  _scrollCurrent = 0;
  _scrollTarget  = 0;
}

function syncLyrics(progress_ms) {
  const lines = state.lyrics.synced;
  if (!lines || !state.lineElements.length) return;

  let activeIdx = -1;
  for (let i = 0; i < lines.length; i++) {
    if (lines[i].time_ms <= progress_ms) activeIdx = i;
    else break;
  }

  if (activeIdx === state.activeIndex) return;
  state.activeIndex = activeIdx;

  state.lineElements.forEach((el, i) => {
    el.classList.remove('active', 'past', 'near-active');
    if      (i < activeIdx)          el.classList.add('past');
    else if (i === activeIdx)        el.classList.add('active');
    else if (i === activeIdx + 1)    el.classList.add('near-active');
  });

  scrollToActive(activeIdx);
}

function scrollToActive(idx) {
  if (idx < 0 || !state.lineElements[idx]) return;

  const viewport   = dom.syncedScroll;
  const el         = state.lineElements[idx];
  const viewportH  = viewport.clientHeight;

  // offsetTop is relative to syncedInner; syncedInner starts after top padding
  const elTop    = el.offsetTop;
  const elHeight = el.offsetHeight;

  // Target scrollTop so the active line is vertically centered
  const target = elTop - viewportH / 2 + elHeight / 2;

  animateScrollTo(Math.max(0, target));
}

/* ── Screen management ────────────────────────────────────────────────────── */
function showScreen(name) {
  dom.idle.classList.add('hidden');
  dom.loading.classList.add('hidden');
  dom.noLyrics.classList.add('hidden');
  dom.rateLimited.classList.add('hidden');
  dom.syncedWrap.classList.add('hidden');
  dom.plainWrap.classList.add('hidden');

  switch (name) {
    case 'idle':         dom.idle.classList.remove('hidden'); break;
    case 'loading':      dom.loading.classList.remove('hidden'); break;
    case 'no-lyrics':    dom.noLyrics.classList.remove('hidden'); break;
    case 'rate-limited': dom.rateLimited.classList.remove('hidden'); break;
    case 'synced':       dom.syncedWrap.classList.remove('hidden'); break;
    case 'plain':        dom.plainWrap.classList.remove('hidden'); break;
  }
}

function handleStopped() {
  state.track = null;
  state.lyrics = null;
  state.activeIndex = -1;
  state.progress_ms = 0;
  _scrollCurrent = 0;
  _scrollTarget  = 0;

  dom.name.textContent     = '–';
  dom.artist.textContent   = '–';
  dom.progress.style.width = '0%';
  dom.cover.classList.add('hidden');
  dom.coverPH.classList.remove('hidden');
  document.title = 'Spotify Lyrics Display';

  showScreen('idle');
}

/* ── Init ─────────────────────────────────────────────────────────────────── */
showScreen('idle');
connect();
