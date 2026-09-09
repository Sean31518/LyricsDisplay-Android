/* ── Lyrics-Verwaltung ────────────────────────────────────────────────────── */
const $ = id => document.getElementById(id);
const dom = {
  authWarning: $('auth-warning'),
  lrclibLink:  $('lrclib-link'),
  clientId:      $('f-client-id'),
  saveClientId:  $('save-client-id-btn'),
  clientIdMsg:   $('client-id-msg'),
  logoutBtn:     $('logout-btn'),
  diagDetails:     $('diagnostics-details'),
  diagRefreshBtn:  $('diag-refresh-btn'),
  diagLastPoll:    $('diag-last-poll'),
  diagLastSuccess: $('diag-last-success'),
  diagRateLimit:   $('diag-rate-limit'),
  diagLastError:   $('diag-last-error'),
  editorTitle: $('editor-title'),
  name:        $('f-name'),
  artist:      $('f-artist'),
  text:        $('f-text'),
  detectBadge: $('detect-badge'),
  useCurrent:  $('use-current'),
  saveBtn:     $('save-btn'),
  cancelBtn:   $('cancel-btn'),
  formMsg:     $('form-msg'),
  list:        $('entry-list'),
  empty:       $('entry-empty'),
  count:       $('entry-count'),
};

let editingId = null;

const LyricsEngine = Capacitor.Plugins.LyricsEngine;

/* ── Auth-Check (einmalig beim Laden - kein Server mehr, der pro Request prüft) */
async function checkAuth() {
  try {
    const { authenticated } = await LyricsEngine.getStatus();
    dom.authWarning.classList.toggle('hidden', authenticated);
  } catch {
    dom.authWarning.classList.remove('hidden');
  }
}

/* ── Spotify Client ID ───────────────────────────────────────────────────── */
async function loadClientId() {
  try {
    const { clientId } = await LyricsEngine.getClientId();
    if (clientId) dom.clientId.value = clientId;
  } catch {}
}

async function saveClientId() {
  const clientId = dom.clientId.value.trim();
  if (!clientId) {
    dom.clientIdMsg.textContent = 'Client ID fehlt.';
    dom.clientIdMsg.className = 'form-msg is-error';
    return;
  }
  try {
    await LyricsEngine.setClientId({ clientId });
    dom.clientIdMsg.textContent = 'Gespeichert.';
    dom.clientIdMsg.className = 'form-msg is-ok';
    setTimeout(() => { dom.clientIdMsg.textContent = ''; }, 4000);
  } catch (err) {
    dom.clientIdMsg.textContent = `Speichern fehlgeschlagen: ${err.message}`;
    dom.clientIdMsg.className = 'form-msg is-error';
  }
}

/* ── Diagnose ─────────────────────────────────────────────────────────────
   Zeigt den zuletzt vom nativen Poller beobachteten Status direkt in der
   App (Rate-Limit, Token-/Netzwerkfehler) - Pendant zum Mitschauen per
   adb logcat, nur ohne Rechner/USB-Kabel nötig. */
function fmtTimestamp(ms) {
  if (!ms) return '–';
  const diffSec = Math.max(0, Math.round((Date.now() - ms) / 1000));
  const rel = diffSec < 60 ? `vor ${diffSec}s`
    : diffSec < 3600 ? `vor ${Math.round(diffSec / 60)}min`
    : diffSec < 86400 ? `vor ${Math.round(diffSec / 3600)}h`
    : `vor ${Math.round(diffSec / 86400)}d`;
  return `${new Date(ms).toLocaleTimeString('de-DE')} (${rel})`;
}

function fmtRateLimit(untilMs) {
  if (!untilMs || untilMs <= Date.now()) return { text: 'kein aktives Rate-Limit', warn: false };
  const remainingSec = Math.round((untilMs - Date.now()) / 1000);
  const h = Math.floor(remainingSec / 3600);
  const m = Math.floor((remainingSec % 3600) / 60);
  const s = remainingSec % 60;
  const parts = [h && `${h}h`, (h || m) && `${m}min`, `${s}s`].filter(Boolean);
  const until = new Date(untilMs).toLocaleString('de-DE');
  return { text: `aktiv, noch ${parts.join(' ')} (bis ${until})`, warn: true };
}

async function loadDiagnostics() {
  try {
    const d = await LyricsEngine.getDiagnostics();
    dom.diagLastPoll.textContent = fmtTimestamp(d.lastPollAt);
    dom.diagLastSuccess.textContent = fmtTimestamp(d.lastSuccessAt);

    const rl = fmtRateLimit(d.rateLimitedUntil);
    dom.diagRateLimit.textContent = rl.text;
    dom.diagRateLimit.className = rl.warn ? 'is-warn' : '';

    if (d.lastError) {
      dom.diagLastError.textContent = `${d.lastError} — ${fmtTimestamp(d.lastErrorAt)}`;
      dom.diagLastError.className = 'is-error';
    } else {
      dom.diagLastError.textContent = 'keiner';
      dom.diagLastError.className = '';
    }
  } catch (err) {
    dom.diagLastError.textContent = `Diagnose konnte nicht geladen werden: ${err.message}`;
    dom.diagLastError.className = 'is-error';
  }
}

async function logout() {
  try { await LyricsEngine.logout(); } catch {}
  location.href = 'login.html';
}

/* ── Format-Erkennung (nur Anzeige – der Server erkennt beim Speichern selbst) */
function detectFormat(text) {
  const timed = (text.match(/^\s*\[\d{1,2}:\d{2}(?:[.:]\d{1,3})?\]/gm) || []).length;
  if (timed >= 2) return { kind: 'synced', label: `🕐 Synced (LRC) – ${timed} getimte Zeilen` };
  if (text.trim()) return { kind: 'plain', label: '📄 Plain Text (ohne Sync)' };
  return { kind: 'empty', label: 'Format: –' };
}

function updateDetectBadge() {
  const { kind, label } = detectFormat(dom.text.value);
  dom.detectBadge.textContent = label;
  dom.detectBadge.className = 'detect-badge' + (kind === 'synced' ? ' is-synced' : kind === 'plain' ? ' is-plain' : '');
}

/* ── Liste ────────────────────────────────────────────────────────────────── */
async function loadList() {
  let entries = [];
  try {
    ({ entries } = await LyricsEngine.listLyrics());
  } catch {
    return;
  }

  dom.list.textContent = '';
  dom.count.textContent = entries.length ? `(${entries.length})` : '';
  dom.empty.classList.toggle('hidden', entries.length > 0);

  for (const e of entries) {
    const row = document.createElement('div');
    row.className = 'entry-row';

    const meta = document.createElement('div');
    meta.className = 'entry-meta';
    const title = document.createElement('div');
    title.className = 'entry-name';
    title.textContent = e.name;
    const artist = document.createElement('div');
    artist.className = 'entry-artist';
    artist.textContent = e.artist;
    meta.append(title, artist);

    const badges = document.createElement('div');
    badges.className = 'entry-badges';
    badges.append(
      makeBadge(e.hasSynced ? `synced · ${e.lineCount}` : 'plain', e.hasSynced ? 'badge-sync' : 'badge-plain'),
      makeBadge(e.source === 'custom' ? 'eigener Eintrag' : 'LRCLib', e.source === 'custom' ? 'badge-custom' : 'badge-lrclib'),
    );

    const actions = document.createElement('div');
    actions.className = 'entry-actions';
    const editBtn = document.createElement('button');
    editBtn.className = 'icon-btn';
    editBtn.title = 'Bearbeiten';
    editBtn.textContent = '✎';
    editBtn.addEventListener('click', () => startEdit(e.id));
    const delBtn = document.createElement('button');
    delBtn.className = 'icon-btn';
    delBtn.title = 'Löschen';
    delBtn.textContent = '🗑';
    delBtn.addEventListener('click', () => removeEntry(e));
    actions.append(editBtn, delBtn);

    row.append(meta, badges, actions);
    dom.list.appendChild(row);
  }
}

function makeBadge(text, cls) {
  const b = document.createElement('span');
  b.className = 'badge ' + cls;
  b.textContent = text;
  return b;
}

/* ── Editor ───────────────────────────────────────────────────────────────── */
function resetForm() {
  editingId = null;
  dom.name.value = '';
  dom.artist.value = '';
  dom.text.value = '';
  dom.editorTitle.textContent = 'Eigene Lyrics hinzufügen';
  dom.cancelBtn.classList.add('hidden');
  updateDetectBadge();
}

function showMsg(text, isError) {
  dom.formMsg.textContent = text;
  dom.formMsg.className = 'form-msg ' + (isError ? 'is-error' : 'is-ok');
  if (!isError) setTimeout(() => { dom.formMsg.textContent = ''; }, 4000);
}

async function startEdit(id) {
  try {
    const entry = await LyricsEngine.getLyricsEntry({ id });
    editingId = id;
    dom.name.value = entry.name;
    dom.artist.value = entry.artist;
    // raw = Originaltext (LRC oder plain); Fallback für Alt-Einträge
    dom.text.value = entry.raw
      || (entry.synced ? entry.synced.map(l => `[${fmtTime(l.time_ms)}] ${l.text}`).join('\n') : entry.plain || '');
    dom.editorTitle.textContent = `Eintrag bearbeiten: ${entry.name}`;
    dom.cancelBtn.classList.remove('hidden');
    updateDetectBadge();
    dom.name.scrollIntoView({ behavior: 'smooth', block: 'center' });
  } catch (err) {
    showMsg(`Laden fehlgeschlagen: ${err.message}`, true);
  }
}

function fmtTime(ms) {
  const m = Math.floor(ms / 60000);
  const s = Math.floor((ms % 60000) / 1000);
  const cs = Math.floor((ms % 1000) / 10);
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}.${String(cs).padStart(2, '0')}`;
}

async function save() {
  const body = {
    name: dom.name.value.trim(),
    artist: dom.artist.value.trim(),
    text: dom.text.value,
  };
  if (!body.name || !body.artist) return showMsg('Titel und Interpret sind Pflicht.', true);
  if (!body.text.trim()) return showMsg('Lyrics fehlen.', true);

  try {
    if (editingId) {
      await LyricsEngine.updateLyrics({ id: editingId, ...body });
    } else {
      await LyricsEngine.saveLyrics(body);
    }
    showMsg(editingId ? 'Gespeichert.' : 'Hinzugefügt.');
    resetForm();
    loadList();
  } catch (err) {
    showMsg(`Speichern fehlgeschlagen: ${err.message}`, true);
  }
}

async function removeEntry(e) {
  if (!confirm(`„${e.name} – ${e.artist}“ wirklich löschen?`)) return;
  try {
    await LyricsEngine.deleteLyrics({ id: e.id });
    if (editingId === e.id) resetForm();
    loadList();
  } catch (err) {
    showMsg(`Löschen fehlgeschlagen: ${err.message}`, true);
  }
}

async function useCurrent() {
  try {
    const { track } = await LyricsEngine.getCurrentTrack();
    if (!track) return showMsg('Gerade läuft nichts.', true);
    dom.name.value = track.name;
    dom.artist.value = track.artist;
  } catch (err) {
    showMsg(`Konnte aktuellen Song nicht laden: ${err.message}`, true);
  }
}

/* ── Init ─────────────────────────────────────────────────────────────────── */
dom.text.addEventListener('input', updateDetectBadge);
dom.saveBtn.addEventListener('click', save);
dom.cancelBtn.addEventListener('click', resetForm);
dom.useCurrent.addEventListener('click', useCurrent);
dom.saveClientId.addEventListener('click', saveClientId);
dom.logoutBtn.addEventListener('click', logout);
dom.lrclibLink.addEventListener('click', () => LyricsEngine.openUrl({ url: 'https://lrclib.net' }));
dom.diagRefreshBtn.addEventListener('click', loadDiagnostics);
dom.diagDetails.addEventListener('toggle', () => { if (dom.diagDetails.open) loadDiagnostics(); });

updateDetectBadge();
checkAuth();
loadClientId();
loadList();
if (dom.diagDetails.open) loadDiagnostics();
