/*
 * Quarkus Almanac: app.js
 *
 * UI only: routing, data loading, rendering. All data logic lives in core.js.
 * Routes: #/  #/compare/<from>/<to>  #/property/<key>  #/artifact/<id>  #/class/<binary name>  #/versions
 *   #/version/<v>
 * The route is kept in JS state; location.hash is best effort (read on load, written inside try/catch),
 * because embedded previews may ignore or refuse hash changes.
 */
import * as core from './core.js';

/* ========================================================================== */
/* Constants                                                                   */
/* ========================================================================== */

const ROW_CAP = 8; // config rows shown per extension before "show all"
const ROW_CAP_FILTERED = 40; // per extension while a text filter is active
const GROUP_PAGE = 30; // extension (or jar) groups rendered per page
const DEP_GROUP_PAGE = 50; // dependency groups rendered per page
const DEP_ITEMS_CAP = 12; // artifacts listed per dependency group before "show all"
const LIST_CAP = 30; // rows in simple lists before "show all"
const FILTER_DEBOUNCE = 160;
const SEARCH_DEBOUNCE = 110;
const SVG_NS = 'http://www.w3.org/2000/svg';
/** quarkus.io's logo for dark backgrounds, as its own navigation bar shows it. */
const QUARKUS_LOGO = 'https://quarkus.io/assets/images/quarkus_logo_horizontal_rgb_reverse.svg';
/** The Commonhaus Foundation's logo for dark backgrounds, from the same place quarkus.io's footer takes it. */
const COMMONHAUS_LOGO = 'https://raw.githubusercontent.com/commonhaus/artwork/main/foundation/brand/svg/CF_logo_horizontal_single_reverse.svg';
const ALMANAC_REPO = 'https://github.com/quarkusio/almanac';
/**
 * The oldest stream the site lists by default. The releases of older streams are "old releases": they stay in the
 * data and are listed with the "Show old releases" switch. Raise it as streams age out.
 */
const OLDEST_SHOWN_STREAM = '3.8';

const KIND_LABEL = {
  added: 'added', removed: 'removed', changed: 'changed', default: 'default', type: 'type', phase: 'phase',
  deprecated: 'deprecated', undeprecated: 'undeprecated', enum: 'enum', extension: 'extension',
  major: 'major', minor: 'minor', patch: 'patch', other: 'qualifier', downgrade: 'downgrade', same: 'unchanged',
  relocated: 'relocated', unrelocated: 'relocation dropped', status: 'status', renamed: 'renamed',
  moved: 'moved', 'moved-in': 'moved in', 'moved-out': 'moved out', introduced: 'introduced', reintroduced: 'reintroduced',
};
const KIND_TONE = {
  added: 'add', removed: 'rem', changed: 'chg', default: 'chg', type: 'chg', phase: 'chg', enum: 'chg', extension: 'neu',
  deprecated: 'dep', undeprecated: 'dep-o', major: 'maj', minor: 'chg', patch: 'neu', other: 'neu', downgrade: 'rem',
  same: 'neu', relocated: 'chg', unrelocated: 'neu', status: 'chg', renamed: 'neu',
  moved: 'chg', 'moved-in': 'chg', 'moved-out': 'chg', introduced: 'add', reintroduced: 'add',
  lts: 'lts', pre: 'pre', cfg: 'neu',
};
const KIND_TITLE = {
  default: 'The default value changed', type: 'The type changed (including list or map shape)', phase: 'The configuration phase changed',
  deprecated: 'Newly deprecated', undeprecated: 'No longer deprecated', enum: 'The accepted values changed',
  extension: 'Now attributed to another extension', major: 'Major version change', minor: 'Minor version change',
  patch: 'Patch version change', other: 'Only the version qualifier changed, or a version kept as written (${...})',
  downgrade: 'The newer release manages an older version',
  moved: 'In other jars than before', 'moved-in': 'Now in this jar, which did not have it; another jar had it before',
  'moved-out': 'No longer in this jar; another jar has it now',
};
/** An extension's status takes quarkus.io's tag colour for it, as on quarkus.io/extensions. */
const STATUS_TONE = { stable: 'stable', preview: 'preview', experimental: 'experimental', deprecated: 'deprecated' };
const TYPE_LABEL = { property: 'prop', artifact: 'dep', extension: 'ext', class: 'class' };
const SOURCE_TITLE = {
  model: 'model: read from the official config model shipped in the jars (3.25+)',
  bytecode: 'bytecode: reconstructed from the compiled config classes (before 3.25)',
};

const ICONS = {
  lock: 'M4.5 7.5h7v6h-7zM6 7.5V5.5a2 2 0 0 1 4 0v2',
  copy: 'M5.5 5.5h7v8h-7zM3.5 10.5v-8h7',
  check: 'M3 8.5l3 3 7-7',
  swap: 'M2.5 5.5h10.5L10.5 3M13.5 10.5H3L5.5 13',
  search: 'M7 11.5a4.5 4.5 0 1 0 0-9 4.5 4.5 0 0 0 0 9zM10.5 10.5L14 14',
  arrow: 'M2.5 8h10.5M9.5 4.5L13 8l-3.5 3.5',
  chevron: 'M4.5 6l3.5 3.5L11.5 6',
  external: 'M9.5 2.5h4v4M13.5 2.5L7.5 8.5M11.5 9v4.5h-9v-9H7',
  theme: 'M8 2a6 6 0 1 0 0 12A6 6 0 0 0 8 2zM8 2v12',
  sun: 'M8 5.25a2.75 2.75 0 1 0 0 5.5 2.75 2.75 0 0 0 0-5.5zM8 1.5V3M8 13v1.5M1.5 8H3M13 8h1.5M3.4 3.4l1.06 1.06M11.54 11.54l1.06 1.06M3.4 12.6l1.06-1.06M11.54 4.46l1.06-1.06',
  moon: 'M13.5 9.6A5.75 5.75 0 0 1 6.4 2.5a5.75 5.75 0 1 0 7.1 7.1z',
  globe: 'M8 1.5a6.5 6.5 0 1 0 0 13 6.5 6.5 0 0 0 0-13zM1.5 8h13M8 1.5c1.8 1.9 2.7 4.1 2.7 6.5S9.8 12.6 8 14.5M8 1.5C6.2 3.4 5.3 5.6 5.3 8s.9 4.6 2.7 6.5',
  menu: 'M2.5 4.5h11M2.5 8h11M2.5 11.5h11',
};

/**
 * quarkus.io's main menu, copied from its own navigation bar (#main-nav on https://quarkus.io/, last compared on
 * 2026-10-28). quarkus.io/extensions keeps a copy of its own too (src/components/headers/navigation.js in
 * quarkusio/extensions), and copies drift: compare again when quarkus.io changes its menu. An entry is
 * [label, link, current]: a path is on quarkus.io; the almanac is listed under Learn, next to the release notes.
 * quarkus.io folds "Using Spring?" and "AI" into submenus; here they head their groups.
 */
const QUARKUS_IO = 'https://quarkus.io';
const QUARKUS_MENU = [
  { title: 'Why', groups: [
    { items: [['What is Quarkus?', '/about'], ['Developer Joy', '/developer-joy'], ['Performance', '/performance'],
      ['Kubernetes Native', '/kubernetes-native'], ['Standards', '/standards'], ['Versatility', '/versatility'],
      ['Container First', '/container-first']] },
    { title: 'Using Spring?', items: [['Spring Overview', '/spring'], ['Migrate to Quarkus', '/spring/migrate/']] },
    { title: 'AI', items: [['AI Overview', '/ai'], ['Java for AI', '/java-for-ai'], ['Why Quarkus for AI', '/quarkus-for-ai'],
      ['AI Blueprints', '/ai-blueprints']] },
  ] },
  { title: 'Learn', groups: [
    { items: [['Get Started', '/get-started'], ['Documentation', '/guides'], ['Releases', '/releases/'],
      ['Release Almanac', '#/', true], ['User Stories', '/userstories/'], ['"Q" Tip Videos', '/qtips'], ['Books', '/books'],
      ['Workshops', '/workshops']] },
  ] },
  { title: 'Extensions', groups: [
    { items: [['Browse Extensions', '/extensions/'], ['Use Extensions', '/faq/#what-is-a-quarkus-extension'],
      ['Create Extensions', '/guides/writing-extensions'], ['Share Extensions', 'https://hub.quarkiverse.io']] },
  ] },
  { title: 'Community', groups: [
    { items: [['Support', '/support/'], ['Blog', '/blog'], ['Discussion', '/discussion'], ['Working Groups', '/working-groups'],
      ['Podcast', '/insights'], ['Events', '/events'], ['Newsletter', '/newsletter'],
      ['Roadmap', 'https://github.com/orgs/quarkusio/projects/13/views/1'], ['Benefactors', '/benefactors']] },
  ] },
];
const QUARKUS_LANGUAGES = [['Official (English)', 'https://quarkus.io/'], ['Português (BR)', 'https://pt.quarkus.io/'],
  ['Español', 'https://es.quarkus.io/'], ['简体中文', 'https://cn.quarkus.io/'], ['日本語', 'https://ja.quarkus.io/']];

/* ========================================================================== */
/* Small DOM helpers                                                           */
/* ========================================================================== */

function h(tag, attrs, ...children) {
  const el = document.createElement(tag);
  if (attrs) {
    for (const [k, v] of Object.entries(attrs)) {
      if (v == null || v === false) continue;
      if (k === 'class') el.className = v;
      else if (k === 'hidden') el.hidden = true;
      else if (k === 'text') el.textContent = v;
      else if (k.startsWith('on') && typeof v === 'function') el.addEventListener(k.slice(2), v);
      else el.setAttribute(k, v === true ? '' : String(v));
    }
  }
  return add(el, children);
}

function add(parent, children) {
  for (const c of children) {
    if (c == null || c === false || c === true) continue;
    if (Array.isArray(c)) add(parent, c);
    else parent.append(c instanceof Node ? c : String(c));
  }
  return parent;
}

function frag(...children) {
  return add(document.createDocumentFragment(), children);
}

function icon(name, cls) {
  const svg = document.createElementNS(SVG_NS, 'svg');
  svg.setAttribute('viewBox', '0 0 16 16');
  svg.setAttribute('width', '16');
  svg.setAttribute('height', '16');
  svg.setAttribute('aria-hidden', 'true');
  svg.setAttribute('focusable', 'false');
  svg.setAttribute('class', cls ? `ico ${cls}` : 'ico');
  const path = document.createElementNS(SVG_NS, 'path');
  path.setAttribute('d', ICONS[name]);
  svg.append(path);
  return svg;
}

function debounce(fn, ms) {
  let t;
  return (...args) => {
    clearTimeout(t);
    t = setTimeout(() => fn(...args), ms);
  };
}

const fmt = (n) => Number(n).toLocaleString('en-US');
const short = (v) => core.shortVersion(v);
const plural = (n, one, many = `${one}s`) => `${fmt(n)} ${n === 1 ? one : many}`;
const reducedMotion = () => {
  try {
    return window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  } catch {
    return false;
  }
};

/** Renders a description: backtick spans become <code>, everything else stays text. */
function richText(text) {
  const out = document.createDocumentFragment();
  for (const part of String(text).split(/(`[^`]+`)/g)) {
    if (!part) continue;
    if (part.length > 2 && part.startsWith('`') && part.endsWith('`')) out.append(h('code', null, part.slice(1, -1)));
    else out.append(part);
  }
  return out;
}

/** Wraps the first occurrence of each query word in <mark>. */
function highlight(label, query) {
  const lower = label.toLowerCase();
  const ranges = [];
  for (const w of query.toLowerCase().split(/\s+/).filter(Boolean)) {
    const i = lower.indexOf(w);
    if (i >= 0) ranges.push([i, i + w.length]);
  }
  ranges.sort((a, b) => a[0] - b[0]);
  const merged = [];
  for (const r of ranges) {
    const last = merged[merged.length - 1];
    if (last && r[0] <= last[1]) last[1] = Math.max(last[1], r[1]);
    else merged.push([...r]);
  }
  const out = document.createDocumentFragment();
  let pos = 0;
  for (const [a, b] of merged) {
    if (a > pos) out.append(label.slice(pos, a));
    out.append(h('mark', null, label.slice(a, b)));
    pos = b;
  }
  if (pos < label.length) out.append(label.slice(pos));
  return out;
}

/* ========================================================================== */
/* Data loading                                                                */
/* ========================================================================== */

const store = {
  dir: null, // directory the data was loaded from
  sample: false, // true when falling back to dev/sample
  override: null, // ?data=<dir>
  V: null, // prepared versions.json
  versionsPromise: null,
  files: new Map(), // 'bom' | 'config' | 'extensions' | 'classes-<major>' | 'classes:<majors>' → Promise
  diffs: new Map(), // `${a}:${b}` → comparison (computed once per pair)
  searchIndex: null, // Promise<index>
  searchLines: '', // the major lines whose classes the search index has, e.g. '3,4'
};

class DataError extends Error {
  constructor(message, attempts = []) {
    super(message);
    this.attempts = attempts;
  }
}

function dataOverride() {
  try {
    const raw = new URLSearchParams(window.location.search).get('data');
    if (!raw) return null;
    const dir = raw.trim().replace(/\/+$/, '');
    // Relative directories only: no scheme, no protocol-relative URLs.
    if (!dir || /^[a-z][a-z0-9+.-]*:/i.test(dir) || dir.startsWith('//') || dir.startsWith('\\')) return null;
    return dir;
  } catch {
    return null;
  }
}

async function fetchJson(url) {
  let res;
  try {
    res = await fetch(url, { cache: 'no-cache' });
  } catch (e) {
    throw new Error(`${url}: ${e && e.message ? e.message : 'network error'}`);
  }
  if (!res.ok) throw new Error(`${url}: HTTP ${res.status}`);
  try {
    return await res.json();
  } catch {
    throw new Error(`${url}: not valid JSON`);
  }
}

function prepareVersions(json) {
  const list = json.versions;
  const ltsStreams = new Set(json.lts ?? []);
  const byStream = new Map();
  // the four states of the two switches, as visBy[showPre][showOld]; visOff and visOn list every stream
  const visBy = [false, true].map((showPre) => [false, true].map((showOld) =>
    core.releaseVisibility(list, { showPre, showOld, oldBefore: OLDEST_SHOWN_STREAM })));
  const visOff = visBy[0][1];
  const visOn = visBy[1][1];
  list.forEach((x, i) => {
    const s = streamOfEntry(x);
    if (!byStream.has(s)) byStream.set(s, { stream: s, lts: ltsStreams.has(s), upcoming: visOff.upcomingStreams.has(s), items: [] });
    const g = byStream.get(s);
    g.items.push(i);
    if (x.lts) g.lts = true;
  });
  const streams = [...byStream.values()].sort((a, b) => core.compareVersions(b.stream, a.stream)); // newest first
  const configVis = core.configVersionsOf(list);
  // releases with extension snapshots: every config snapshot, plus releases with extensions only
  const extVis = core.extensionVersionsOf(list);
  // releases with classes snapshots; their classes are in one file per major line (classes-3.json, ...), which only
  // the pages that show classes load, for the lines they show
  const clsVis = core.classVersionsOf(list);
  // streams with a final release (items are in version order), and how many have config at a final
  const finals = streams.map((g) => g.items.filter((i) => !core.isPreRelease(list[i]))).filter((f) => f.length);
  return {
    json,
    list,
    streams,
    configVis,
    extVis,
    clsVis,
    clsLines: core.classLinesOf(list, clsVis),
    finalStreamCount: finals.length,
    configStreamCount: finals.filter((f) => f.some((i) => list[i].config)).length,
    configAtNewestFinals: finals.every((f) => list[f[f.length - 1]].config),
    configAtEveryFinal: finals.every((f) => f.every((i) => list[i].config)),
    // streams with more than one final, each of which has a config snapshot (newest first)
    configEveryPatch: finals.filter((f) => f.length > 1 && f.every((i) => list[i].config)).map((f) => streamOfEntry(list[f[0]])),
    ltsStreams: [...ltsStreams],
    visBy,
    visOff,
    visOn,
    generatedAt: json.generatedAt ?? null,
  };
}

const streamOfEntry = (x) => x.stream || core.stream(x.v);

function ensureVersions() {
  if (store.V) return Promise.resolve(store.V);
  if (!store.versionsPromise) {
    store.versionsPromise = (async () => {
      const override = dataOverride();
      const dirs = override ? [override] : ['data', 'dev/sample'];
      const attempts = [];
      for (const dir of dirs) {
        try {
          const json = await fetchJson(`${dir}/versions.json`);
          if (!json || !Array.isArray(json.versions) || json.versions.length === 0) throw new Error(`${dir}/versions.json: no releases listed`);
          store.dir = dir;
          store.override = override;
          store.sample = dir.replace(/^\.\//, '') === 'dev/sample';
          store.V = prepareVersions(json);
          return store.V;
        } catch (e) {
          attempts.push(e.message);
        }
      }
      throw new DataError('The release list could not be loaded.', attempts);
    })();
    store.versionsPromise.catch(() => {
      store.versionsPromise = null;
    });
  }
  return store.versionsPromise;
}

const PREPARE = {
  bom(json) {
    if (!json || !Array.isArray(json.artifacts)) throw new Error('bom.json: missing "artifacts"');
    return { raw: json, byId: new Map(json.artifacts.map((a) => [a.id, a])) };
  },
  config(json) {
    if (!json || !Array.isArray(json.props) || !Array.isArray(json.states) || !Array.isArray(json.versions)) {
      throw new Error('config.json: missing "versions", "states" or "props"');
    }
    json.desc = json.desc ?? {};
    return { raw: json, byKey: new Map(json.props.map((p) => [p.k, p])) };
  },
  extensions(json) {
    if (!json || !Array.isArray(json.exts) || !Array.isArray(json.states)) throw new Error('extensions.json: missing "states" or "exts"');
    return { raw: json, byId: new Map(json.exts.map((x) => [x.id, x])) };
  },
  classes(json) {
    if (!json || !Array.isArray(json.classes) || !Array.isArray(json.artifacts) || !Array.isArray(json.packages) || !Array.isArray(json.versions)) {
      throw new Error('classes-<major>.json: missing "versions", "artifacts", "packages" or "classes"');
    }
    return { raw: json, byName: new Map(json.classes.map((row) => [core.className(json, row), row])) };
  },
};

function loadFile(name) {
  let p = store.files.get(name);
  if (!p) {
    p = ensureVersions()
      .then(() => fetchJson(`${store.dir}/${name}.json`))
      .then((json) => PREPARE[name](json));
    store.files.set(name, p);
    p.catch(() => store.files.delete(name));
  }
  return p;
}

/** The classes file of a major line, `classes-<major>.json`, fetched once. */
function loadClassesFile(major) {
  const name = `classes-${major}`;
  let p = store.files.get(name);
  if (!p) {
    p = ensureVersions().then(() => fetchJson(`${store.dir}/${name}.json`));
    store.files.set(name, p);
    p.catch(() => store.files.delete(name));
  }
  return p;
}

/** The classes of the given major lines (ascending), merged into one classes object (core.mergeClasses), once per set. */
function loadClasses(lines) {
  const key = `classes:${lines.join(',')}`;
  let p = store.files.get(key);
  if (!p) {
    p = Promise.all(lines.map(loadClassesFile)).then((parts) => PREPARE.classes(core.mergeClasses(parts)));
    store.files.set(key, p);
    p.catch(() => store.files.delete(key));
  }
  return p;
}

/** The major lines of the classes snapshots that the switches show now: what the pages that list classes load. */
function shownClassLines() {
  return core.classLinesOf(store.V.list, core.visibleOnly(store.V.clsVis, currentVis()));
}

async function loadSettled(names) {
  const results = await Promise.allSettled(names.map((n) => loadFile(n)));
  const out = { errors: {} };
  names.forEach((n, i) => {
    if (results[i].status === 'fulfilled') out[n] = results[i].value;
    else {
      out[n] = null;
      out.errors[n] = results[i].reason?.message ?? String(results[i].reason);
    }
  });
  return out;
}

/** The search index; its classes are those of the major lines the switches show, so it is rebuilt when they change. */
function getSearchIndex() {
  const lines = shownClassLines();
  if (!store.searchIndex || store.searchLines !== lines.join(',')) {
    store.searchLines = lines.join(',');
    store.searchIndex = Promise.all([loadSettled(['config', 'bom', 'extensions']), loadClasses(lines).catch(() => null)]).then(([d, classes]) => {
      if (!d.config && !d.bom && !d.extensions && !classes) throw new Error('no data files could be loaded');
      return core.buildSearchIndex({
        config: d.config?.raw, bom: d.bom?.raw, extensions: d.extensions?.raw, classes: classes?.raw, versions: store.V.list,
      });
    });
    store.searchIndex.catch(() => {
      store.searchIndex = null;
    });
  }
  return store.searchIndex;
}

/* ========================================================================== */
/* "Show pre-releases" and "Show old releases" preferences                     */
/* ========================================================================== */

const PRE_KEY = 'quarkus-almanac-prereleases';
const OLD_KEY = 'quarkus-almanac-old-releases';
const prefs = { showPre: readPref(PRE_KEY), showOld: readPref(OLD_KEY) };
let prefHooks = []; // {anchor, fn}: run while `anchor` is in the document

function readPref(key) {
  try {
    return window.localStorage.getItem(key) === '1';
  } catch {
    return false; // storage may be blocked: off by default
  }
}

function setPref(name, key, on, said) {
  const next = !!on;
  if (prefs[name] === next) return;
  prefs[name] = next;
  try {
    window.localStorage.setItem(key, next ? '1' : '0');
  } catch {
    /* storage may be blocked: the choice lasts until the page is reloaded */
  }
  announce(said(next));
  for (const hook of [...prefHooks]) if (hook.anchor.isConnected) hook.fn();
}

function setShowPre(on) {
  setPref('showPre', PRE_KEY, on, (next) => (next ? 'Pre-releases shown.' : 'Pre-releases hidden, except those of upcoming streams.'));
}

function setShowOld(on) {
  setPref('showOld', OLD_KEY, on, (next) => (next ? 'Old releases shown.' : `Old releases hidden: the streams from ${OLDEST_SHOWN_STREAM} on are listed.`));
}

/** Runs `fn` when the preference changes, as long as `anchor` (an element of the page) is on screen. */
function onPrefChange(anchor, fn) {
  prefHooks.push({ anchor, fn });
}

/** Release visibility for the current preferences (see core.releaseVisibility). */
function currentVis() {
  return store.V.visBy[prefs.showPre ? 1 : 0][prefs.showOld ? 1 : 0];
}

/** What the switches hide now, in words: "pre-releases", "releases before 3.8", or both joined with "or". */
function hiddenKinds() {
  const kinds = [prefs.showPre ? null : 'pre-releases', prefs.showOld ? null : `releases before ${OLDEST_SHOWN_STREAM}`];
  return kinds.filter(Boolean).join(' or ');
}

/**
 * "34 pre-releases, 49 old releases and 16 old pre-releases" among the hidden release indices `hidden` (`what`:
 * releases or snapshots): hidden by one switch, by the other, or by both, so the parts add up to all of them.
 */
function hiddenCount(hidden, what = 'release') {
  const vis = currentVis();
  let pre = 0;
  let old = 0;
  let both = 0;
  for (const i of hidden) {
    const byPre = vis.pre[i] && !vis.upcoming[i] && !vis.showPre;
    const byOld = vis.old[i] && !vis.showOld;
    if (byPre && byOld) both++;
    else if (byPre) pre++;
    else old++;
  }
  const preNoun = what === 'release' ? 'pre-release' : `pre-release ${what}`;
  return andList([
    pre ? plural(pre, preNoun) : null,
    old ? plural(old, `old ${what}`) : null,
    both ? plural(both, `old ${preNoun}`) : null,
  ].filter(Boolean));
}

/** The ways to widen a history whose item the switches hide (see core.propertyHistory): old releases first, then pre-releases. */
function widenSteps() {
  const V = store.V;
  const p = prefs.showPre ? 1 : 0;
  const o = prefs.showOld ? 1 : 0;
  return [o ? null : { vis: V.visBy[p][1], kind: 'old' }, p ? null : { vis: V.visBy[1][o], kind: 'pre' }].filter(Boolean);
}

/** The releases a widened history shows although the switches hide them, by the step core took. */
function widenedKinds(by) {
  return by === 'old' ? `releases before ${OLDEST_SHOWN_STREAM}` : by === 'pre' ? 'pre-releases' : hiddenKinds();
}

/** Re-renders the page after a preference change, keeping the focused switch in place on screen. */
function rerenderInPlace() {
  const active = document.activeElement;
  const slot = active instanceof HTMLElement ? active.dataset.prefSlot ?? null : null;
  render({ keep: { slot, top: slot ? active.getBoundingClientRect().top : null, scrollY: window.scrollY } });
}

/* ========================================================================== */
/* Routing                                                                     */
/* ========================================================================== */

let route = { page: 'home' };
let renderSeq = 0;
let firstRender = true;

const dec = (s) => {
  try {
    return decodeURIComponent(s);
  } catch {
    return s;
  }
};
const enc = (s) => encodeURIComponent(String(s)).replace(/%3A/gi, ':').replace(/%40/g, '@').replace(/%2C/gi, ',')
  .replace(/%24/g, () => '$');

function parseRoute(hash) {
  const raw = String(hash || '').replace(/^#/, '').replace(/^\/+/, '');
  const parts = raw.split('/').map(dec);
  const [page, ...rest] = parts;
  switch (page) {
    case '':
      return { page: 'home' };
    case 'compare':
      return { page: 'compare', from: rest[0] ?? '', to: rest[1] ?? '' };
    case 'property':
      return { page: 'property', key: rest.join('/') };
    case 'artifact':
      return { page: 'artifact', id: rest.join('/') };
    case 'class':
      return { page: 'class', name: rest.join('/') };
    case 'versions':
      return { page: 'versions' };
    case 'version':
      return { page: 'version', v: rest[0] ?? '' };
    default:
      return { page: 'notfound', path: raw };
  }
}

function hrefFor(r) {
  switch (r.page) {
    case 'compare':
      return `#/compare/${enc(r.from)}/${enc(r.to)}`;
    case 'property':
      return `#/property/${enc(r.key)}`;
    case 'artifact':
      return `#/artifact/${enc(r.id)}`;
    case 'class':
      return `#/class/${enc(r.name)}`;
    case 'versions':
      return '#/versions';
    case 'version':
      return `#/version/${enc(r.v)}`;
    case 'notfound':
      return `#/${r.path ?? ''}`;
    default:
      return '#/';
  }
}

const link = {
  home: () => '#/',
  compare: (a, b) => hrefFor({ page: 'compare', from: short(a), to: short(b) }),
  property: (key) => hrefFor({ page: 'property', key }),
  artifact: (id) => hrefFor({ page: 'artifact', id }),
  extension: (artifactId) => hrefFor({ page: 'artifact', id: `io.quarkus:${artifactId}` }),
  class: (name) => hrefFor({ page: 'class', name }),
  versions: () => '#/versions',
  version: (v) => hrefFor({ page: 'version', v: short(v) }),
};

function writeHash(hash, replace) {
  try {
    if (window.location.hash === hash) return;
    if (replace) window.history.replaceState(null, '', hash);
    else window.location.hash = hash;
  } catch {
    /* embedded previews may refuse hash writes; the route lives in JS state */
  }
}

function navigate(next, { replace = false } = {}) {
  route = next;
  writeHash(hrefFor(next), replace);
  render();
}

/** Updates the route to its canonical form without re-rendering (e.g. after resolving an alias). */
function canonicalize(next) {
  route = next;
  writeHash(hrefFor(next), true);
}

window.addEventListener('hashchange', () => {
  const next = parseRoute(window.location.hash);
  if (hrefFor(next) === hrefFor(route)) return;
  route = next;
  render();
});

document.addEventListener('click', (e) => {
  if (e.defaultPrevented || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
  const a = e.target instanceof Element ? e.target.closest('a[href^="#/"]') : null;
  if (!a || a.target === '_blank') return;
  e.preventDefault();
  navigate(parseRoute(a.getAttribute('href')));
});

const PAGES = {
  home: renderHome,
  compare: renderCompare,
  property: renderProperty,
  artifact: renderArtifact,
  class: renderClass,
  versions: renderVersions,
  version: renderVersion,
};

function pageTitle(r) {
  const base = 'Quarkus Almanac';
  switch (r.page) {
    case 'compare':
      return `${r.from} → ${r.to} · ${base}`;
    case 'property':
      return `${r.key} · ${base}`;
    case 'artifact':
      return `${r.id} · ${base}`;
    case 'class':
      return `${core.simpleClassName(r.name)} · ${base}`;
    case 'versions':
      return `Releases · ${base}`;
    case 'version':
      return `${r.v} · ${base}`;
    default:
      return base;
  }
}

/**
 * Renders the current route. `keep` (after a preference change) keeps the reader's place: the element with
 * the same `data-pref-slot` gets focus back at the same height on screen, instead of scrolling to the top.
 */
async function render({ keep = null } = {}) {
  const seq = ++renderSeq;
  const r = route;
  if (!keep) shell.closeNav?.();
  updateNav(r);
  const main = shell.main;
  main.setAttribute('aria-busy', 'true');
  const slow = setTimeout(() => {
    if (seq === renderSeq) main.replaceChildren(loadingView());
  }, 150);
  let view;
  try {
    await ensureVersions();
    updateDataNotice();
    view = await (PAGES[r.page] ?? renderNotFound)(r);
  } catch (err) {
    view = errorView(err);
  }
  clearTimeout(slow);
  if (seq !== renderSeq) return;
  main.replaceChildren(view);
  main.removeAttribute('aria-busy');
  prefHooks = prefHooks.filter((hook) => hook.anchor.isConnected);
  try {
    document.title = pageTitle(route);
  } catch {
    /* ignore */
  }
  if (keep) {
    const el = keep.slot ? main.querySelector(`[data-pref-slot="${keep.slot}"]`) : null;
    try {
      if (el) {
        el.focus({ preventScroll: true });
        if (keep.top != null) window.scrollBy(0, el.getBoundingClientRect().top - keep.top);
      } else {
        window.scrollTo(0, keep.scrollY);
      }
    } catch {
      /* ignore */
    }
  } else if (!firstRender) {
    try {
      window.scrollTo(0, 0);
    } catch {
      /* ignore */
    }
    const h1 = main.querySelector('h1');
    if (h1) h1.focus({ preventScroll: true });
  }
  firstRender = false;
}

/* ========================================================================== */
/* Shell                                                                       */
/* ========================================================================== */

const shell = {};

function buildShell() {
  const app = document.getElementById('app');
  shell.main = h('main', { id: 'main-content', class: 'main wrap', tabindex: '-1' });
  const skip = h('a', { class: 'skip', href: '#main-content' }, 'Skip to content');
  skip.addEventListener('click', (e) => {
    e.preventDefault();
    shell.main.focus();
  });
  shell.navHome = h('a', { href: '#/', class: 'nav-link' }, 'Compare');
  shell.navVersions = h('a', { href: '#/versions', class: 'nav-link' }, 'Releases');
  shell.searchWrap = h('div', { class: 'mast-search' }, searchBox({ id: 'qs-top', compact: true }));
  shell.notice = h('div', { class: 'notice', role: 'note', hidden: true });
  shell.footData = h('p', { class: 'foot-data' });
  shell.live = h('div', { class: 'sr-only', 'aria-live': 'polite', 'aria-atomic': 'true' });
  // quarkus.io's navigation bar, then the almanac's own bar
  const header = h('header', { class: 'mast' },
    globalNav(),
    h('div', { class: 'lbar' },
      h('div', { class: 'wrap lbar-in' },
        h('a', { class: 'lbar-name', href: '#/' }, 'Almanac'),
        h('nav', { class: 'mast-nav', 'aria-label': 'Almanac' }, shell.navHome, shell.navVersions),
        shell.searchWrap)));
  const footer = h('footer', { class: 'foot' },
    h('div', { class: 'wrap foot-in' },
      h('p', null, 'Data extracted from artifacts published on Maven Central. Source and issues on GitHub: ',
        h('a', { href: ALMANAC_REPO }, 'quarkusio/almanac'), '.'),
      shell.footData),
    legalBar());
  app.replaceChildren(skip, header, shell.notice, shell.main, footer, shell.live);
}

/**
 * The foot of every quarkus.io page: the Commonhaus Foundation's logo and the trademark notice, copied here as
 * quarkus.io/extensions copies it (its own src/components/footer.js), without quarkus.io's columns of links above it.
 */
function legalBar() {
  return h('div', { class: 'wrap foot-legal' },
    h('a', { class: 'foot-cf', href: 'https://www.commonhaus.org/' },
      h('img', { src: COMMONHAUS_LOGO, alt: 'Commonhaus Foundation logo', width: '300', height: '32', loading: 'lazy' })),
    h('p', null, 'Copyright © ', h('a', { href: `${QUARKUS_IO}/` }, 'Quarkus'),
      '. All rights reserved. For details on our trademarks, please visit our ',
      h('a', { href: 'https://www.commonhaus.org/policies/trademark-policy/' }, 'Trademark Policy'), ' and ',
      h('a', { href: 'https://www.commonhaus.org/trademarks/' }, 'Trademark List'),
      '. Trademarks of third parties are owned by their respective holders and their mention here does not suggest any '
      + 'endorsement or association.'));
}

/**
 * quarkus.io's navigation bar, as quarkus.io and quarkus.io/extensions have it: the logo, the main menu, Start Coding,
 * the languages and the theme. Each menu is a disclosure button: a click (or Enter) opens it and keeps it open, and a
 * mouse resting on a menu opens it too, as on quarkus.io, for as long as it stays there (from 1024 px, where the menu
 * is on the bar; below, it folds behind one button). Whatever is open closes on Escape (back to its button when the
 * focus was in it), on a click elsewhere, when the focus leaves it, and on a change of page.
 */
function globalNav() {
  const menus = [];
  const pinned = new Set(); // menus opened by a click, which stay open when the mouse leaves them
  const wide = window.matchMedia ? window.matchMedia('(min-width: 1024px)') : null;
  let hoverTimer = 0;
  const isOpen = (li) => li.classList.contains('is-open');
  const setOpen = (li, open, pin = false) => {
    li.classList.toggle('is-open', open);
    li.firstElementChild.setAttribute('aria-expanded', String(open));
    if (open && pin) pinned.add(li);
    else pinned.delete(li);
  };
  // a menu that closes around the focus hands it to its button, so that the focus never falls back to the page
  const close = (li) => {
    if (li.contains(document.activeElement)) li.firstElementChild.focus();
    setOpen(li, false);
  };
  const closeAll = (except) => {
    for (const li of menus) if (li !== except && isOpen(li)) close(li);
  };
  const link = (href) => (href.startsWith('/') ? QUARKUS_IO + href : href);
  const menu = (id, label, groups) => {
    const btn = h('button', { type: 'button', class: 'gnav-btn', 'aria-expanded': 'false', 'aria-controls': `gnav-${id}` },
      label, icon('chevron', 'gnav-chev'));
    const panel = h('div', { class: 'gnav-menu', id: `gnav-${id}` },
      groups.map((g, n) => frag(
        g.title ? h('p', { class: 'gnav-group', id: `gnav-${id}-${n}` }, g.title) : null,
        h('ul', { 'aria-labelledby': g.title ? `gnav-${id}-${n}` : null }, g.items.map(([text, href, current]) =>
          h('li', null, h('a', { href: link(href), 'aria-current': current ? 'page' : null }, text)))))));
    const li = h('li', { class: 'gnav-item' }, btn, panel);
    btn.addEventListener('click', () => {
      clearTimeout(hoverTimer);
      const open = !pinned.has(li); // a menu the mouse opened stays open once clicked
      closeAll(li);
      setOpen(li, open, true);
    });
    li.addEventListener('pointerenter', (e) => {
      if (e.pointerType !== 'mouse' || !wide?.matches) return;
      clearTimeout(hoverTimer);
      if (isOpen(li)) return;
      closeAll(li);
      setOpen(li, true);
    });
    li.addEventListener('pointerleave', (e) => {
      if (e.pointerType !== 'mouse' || !isOpen(li) || pinned.has(li)) return;
      hoverTimer = setTimeout(() => {
        if (!pinned.has(li) && !li.contains(document.activeElement)) setOpen(li, false);
      }, 250);
    });
    li.addEventListener('focusout', (e) => {
      if (isOpen(li) && e.relatedTarget instanceof Node && !li.contains(e.relatedTarget)) setOpen(li, false);
    });
    menus.push(li);
    return li;
  };
  const list = h('ul', { class: 'gnav', id: 'gnav-list' },
    QUARKUS_MENU.map((m) => menu(m.title.toLowerCase(), m.title, m.groups)),
    h('li', { class: 'gnav-cta-item' }, h('a', { class: 'gnav-cta', href: 'https://code.quarkus.io/' }, 'Start Coding')),
    menu('languages', frag(icon('globe', 'gnav-globe'), h('span', { class: 'sr-only' }, 'Languages')),
      [{ items: QUARKUS_LANGUAGES }]),
    h('li', { class: 'gnav-theme-item' }, themeToggle()));
  const burger = h('button', { type: 'button', class: 'gnav-burger', 'aria-expanded': 'false', 'aria-controls': 'gnav-list' },
    icon('menu'), h('span', { class: 'sr-only' }, 'Quarkus menu'));
  const bar = h('nav', { class: 'gbar', 'aria-label': 'Quarkus' },
    h('div', { class: 'gbar-in' },
      h('a', { class: 'brand-logo', href: `${QUARKUS_IO}/` }, h('img', { src: QUARKUS_LOGO, alt: 'Quarkus', width: '208', height: '32' })),
      burger, list));
  const panelOpen = () => bar.classList.contains('is-open');
  const setPanel = (open) => {
    if (!open && list.contains(document.activeElement)) burger.focus();
    bar.classList.toggle('is-open', open);
    burger.setAttribute('aria-expanded', String(open));
    if (!open) closeAll();
  };
  const closeNav = () => {
    clearTimeout(hoverTimer);
    closeAll();
    setPanel(false);
  };
  shell.closeNav = closeNav;
  burger.addEventListener('click', () => setPanel(!panelOpen()));
  // a link followed from the menu, even to the page already shown, leaves it closed (not one opened in another tab)
  list.addEventListener('click', (e) => {
    if (e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
    if (e.target instanceof Element && e.target.closest('a')) closeNav();
  });
  bar.addEventListener('focusout', (e) => {
    if (panelOpen() && e.relatedTarget instanceof Node && !bar.contains(e.relatedTarget)) setPanel(false);
  });
  // in the capture phase, so that a control that stops its clicks (the copy buttons) still closes the menus, and before
  // a control replaces what was clicked (the theme's icon)
  document.addEventListener('click', (e) => {
    const path = e.composedPath();
    for (const li of menus) if (isOpen(li) && !path.includes(li)) close(li);
    if (panelOpen() && !path.includes(list) && !path.includes(burger)) setPanel(false);
  }, true);
  document.addEventListener('keydown', (e) => {
    if (e.key !== 'Escape' || e.defaultPrevented) return;
    const open = menus.find(isOpen);
    if (open) close(open);
    else if (panelOpen()) setPanel(false);
  });
  // the menu moves between the bar and the folded panel: start closed in either
  wide?.addEventListener?.('change', closeNav);
  return bar;
}

function updateNav(r) {
  const active = r.page === 'home' || r.page === 'compare' ? shell.navHome : r.page === 'versions' || r.page === 'version' ? shell.navVersions : null;
  for (const a of [shell.navHome, shell.navVersions]) {
    if (a === active) a.setAttribute('aria-current', 'page');
    else a.removeAttribute('aria-current');
  }
  shell.searchWrap.hidden = r.page === 'home';
}

function updateDataNotice() {
  const n = shell.notice;
  if (store.sample) {
    const why = store.override
      ? 'The ?data= parameter selects the synthetic development dataset in dev/sample/. '
      : 'data/versions.json was not found, so the site is showing the synthetic development dataset from dev/sample/. ';
    n.replaceChildren(h('div', { class: 'wrap notice-in' },
      h('strong', { class: 'notice-tag' }, 'Sample data'),
      h('span', null, `${why}Release numbers look real, but every history in it is made up.`)));
    n.hidden = false;
  } else if (store.override) {
    n.replaceChildren(h('div', { class: 'wrap notice-in' },
      h('strong', { class: 'notice-tag notice-tag--info' }, 'Custom data'),
      h('span', null, `Loaded from ${store.override}/ (set with ?data=).`)));
    n.hidden = false;
  } else {
    n.hidden = true;
  }
  const V = store.V;
  shell.footData.textContent = [
    V.generatedAt ? `Data generated ${core.formatDate(V.generatedAt)}` : null,
    `${plural(V.list.length, 'release')} from ${short(V.list[0].v)} to ${short(V.list[V.list.length - 1].v)}`,
  ].filter(Boolean).join(' · ');
}

/**
 * The colour theme, kept as quarkus.io keeps it: light, dark or the system's, stored as 'color-theme' (light when
 * nothing is stored), so that one choice holds on quarkus.io and here alike once the almanac is served from there.
 * theme.js applies it before the page paints; the button steps through light, dark and system, as quarkus.io's does.
 */
function themeToggle() {
  const modes = ['light', 'dark', 'system'];
  const names = { light: 'light', dark: 'dark', system: 'system preferences' };
  const glyphs = { light: 'sun', dark: 'moon', system: 'theme' };
  const root = document.documentElement;
  let mode = modes.includes(root.dataset.theme) ? root.dataset.theme : 'light';
  // one name; the title (also its description) says the scheme and the next one, and a change is announced once
  const btn = h('button', { type: 'button', class: 'gnav-theme', 'aria-label': 'Colour scheme' });
  const apply = () => {
    root.dataset.theme = mode;
    btn.replaceChildren(icon(glyphs[mode]));
    btn.title = `Colour scheme: ${names[mode]}; next: ${names[modes[(modes.indexOf(mode) + 1) % modes.length]]}`;
  };
  btn.addEventListener('click', () => {
    mode = modes[(modes.indexOf(mode) + 1) % modes.length];
    try {
      window.localStorage.setItem('color-theme', mode);
    } catch {
      /* storage may be blocked */
    }
    apply();
    announce(`Colour scheme: ${names[mode]}`);
  });
  apply();
  return btn;
}

function announce(message) {
  shell.live.textContent = '';
  setTimeout(() => {
    shell.live.textContent = message;
  }, 30);
}

/* ========================================================================== */
/* Shared components                                                           */
/* ========================================================================== */

function chip(kind, text, title) {
  return h('span', { class: `chip tone-${KIND_TONE[kind] ?? 'neu'}`, title: title ?? KIND_TITLE[kind] ?? null }, text ?? KIND_LABEL[kind] ?? kind);
}

function statusChip(status) {
  if (!status) return null;
  return h('span', { class: `chip tone-${STATUS_TONE[status] ?? 'neu'}`, title: `Extension status: ${status}` }, status);
}

function phaseBadge(code) {
  const p = core.phaseInfo(code);
  if (!p) return null;
  return h('span', { class: `phase${p.buildTime ? ' phase--build' : ''}`, title: p.long }, p.buildTime ? icon('lock') : null, p.label);
}

function phaseText(code) {
  const p = core.phaseInfo(code);
  return p ? h('span', { class: 'phase-text', title: p.long }, p.buildTime ? icon('lock') : null, p.label) : h('em', { class: 'none' }, 'unknown');
}

function dateEl(iso) {
  return iso ? h('time', { datetime: iso }, core.formatDate(iso)) : null;
}

function versionChips(x) {
  return [x.lts ? chip('lts', 'LTS', 'Long-term support stream') : null, preChip(x)];
}

const QUALIFIER_TITLE = { Alpha: 'Alpha pre-release', Beta: 'Beta pre-release', CR: 'Candidate release (CR): the last pre-release step before the final' };

/** The qualifier badge of a pre-release (`Alpha`, `Beta`, `CR`); null for a final. */
function preChip(x) {
  if (!core.isPreRelease(x)) return null;
  const q = core.preQualifier(x);
  const upcoming = store.V.visOff.upcomingStreams.has(streamOfEntry(x));
  const title = `${QUALIFIER_TITLE[q] ?? 'Pre-release'}${upcoming ? '. Its stream has no final release yet (upcoming).' : ''}`;
  return h('span', { class: 'chip tone-pre', title }, q ?? 'pre-release', q ? h('span', { class: 'sr-only' }, ' pre-release') : null);
}

/** "Beta pre-release", "CR pre-release"; null for a final. */
function preLabel(x) {
  if (!core.isPreRelease(x)) return null;
  const q = core.preQualifier(x);
  return q ? `${q} pre-release` : 'pre-release';
}

function upcomingChip() {
  return chip('pre', 'upcoming', 'This stream has no final release yet: its pre-releases are always listed');
}

function verLink(v) {
  return h('a', { class: 'ver', href: link.version(v) }, short(v));
}

function valueEl(v) {
  return v == null || v === '' ? h('em', { class: 'none' }, 'none') : h('code', { class: 'val' }, v);
}

function selectText(el) {
  if (!el) return false;
  try {
    const range = document.createRange();
    range.selectNodeContents(el);
    const sel = window.getSelection();
    sel.removeAllRanges();
    sel.addRange(range);
    return true;
  } catch {
    return false;
  }
}

function copyButton(text, { label = 'Copy', target = null } = {}) {
  const btn = h('button', { type: 'button', class: 'copy', title: label, 'aria-label': `${label}: ${text}` }, icon('copy'));
  let timer;
  btn.addEventListener('click', async (e) => {
    e.preventDefault();
    e.stopPropagation();
    let copied = false;
    try {
      if (navigator.clipboard && typeof navigator.clipboard.writeText === 'function') {
        await navigator.clipboard.writeText(text);
        copied = true;
      }
    } catch {
      copied = false;
    }
    let selected = false;
    if (!copied) selected = selectText(target);
    btn.replaceChildren(icon(copied ? 'check' : 'copy'));
    btn.classList.toggle('is-done', copied);
    btn.dataset.msg = copied ? 'Copied' : selected ? 'Selected: press Ctrl+C' : 'Copy failed';
    announce(copied ? `Copied ${text}` : selected ? 'Text selected. Press Control+C or Command+C to copy it.' : 'Copying is not available here.');
    clearTimeout(timer);
    timer = setTimeout(() => {
      btn.replaceChildren(icon('copy'));
      btn.classList.remove('is-done');
      delete btn.dataset.msg;
    }, 1800);
  });
  return btn;
}

/** A code-like value with a copy button next to it. */
function copyable(text, label, cls = 'mono') {
  const el = h('code', { class: `copyable-text ${cls}` }, text);
  return h('span', { class: 'copyable' }, el, copyButton(text, { label, target: el }));
}

function sectionHead(no, title, id, ...extra) {
  return h('div', { class: 'sec-head' }, h('span', { class: 'sec-no', 'aria-hidden': 'true' }, `§${no}`), h('h2', { id }, title), extra);
}

/**
 * Makes a filled section collapsible, and collapses it: the title of its head becomes a button that shows or hides
 * everything after the head. Find in page still opens a collapsed section where browsers support
 * `hidden="until-found"`. The section fires `sectiontoggle` when it opens or closes.
 */
function collapsible(sec) {
  const head = sec.querySelector(':scope > .sec-head');
  const heading = head?.querySelector('h2');
  if (!heading) return sec;
  const body = h('div', { class: 'sec-body', id: `${sec.id}-body` });
  while (head.nextSibling) body.append(head.nextSibling);
  const toggle = h('button', { type: 'button', class: 'sec-toggle', 'aria-controls': body.id },
    [...heading.childNodes], icon('chevron', 'sec-chev'));
  heading.replaceChildren(toggle);
  sec.append(body);
  toggle.addEventListener('click', () => setSectionOpen(sec, !isSectionOpen(sec)));
  body.addEventListener('beforematch', () => setSectionOpen(sec, true));
  setSectionOpen(sec, false);
  return sec;
}

/** The sections left open in each comparison during this visit, by pair of release indices ("ia:ib"). */
const openSections = new Map();

function isSectionOpen(sec) {
  return sec.querySelector(':scope > .sec-head .sec-toggle')?.getAttribute('aria-expanded') === 'true';
}

function setSectionOpen(sec, open) {
  const toggle = sec.querySelector(':scope > .sec-head .sec-toggle');
  const body = sec.querySelector(':scope > .sec-body');
  if (!toggle || !body) return;
  toggle.setAttribute('aria-expanded', String(open));
  if (open) body.removeAttribute('hidden');
  else body.setAttribute('hidden', 'until-found');
  sec.classList.toggle('is-collapsed', !open);
  sec.dispatchEvent(new CustomEvent('sectiontoggle'));
}

function moreButton(text, onClick) {
  const b = h('button', { type: 'button', class: 'more' }, text);
  b.addEventListener('click', onClick);
  return b;
}

function emptyNote(text, warn = false) {
  return h('p', { class: `empty${warn ? ' empty--warn' : ''}` }, text);
}

function missingData(file, error) {
  return emptyNote(`${file} could not be loaded${error ? ` (${error})` : ''}, so this section is unavailable.`, true);
}

function filterChips(defs, counts, active, onChange, label) {
  const wrap = h('div', { class: 'fchips', role: 'group', 'aria-label': label });
  for (const [kind, text] of defs) {
    const n = counts[kind] ?? 0;
    const b = h('button', { type: 'button', class: `fchip tone-${KIND_TONE[kind] ?? 'neu'}`, 'aria-pressed': String(active.has(kind)), disabled: n === 0 },
      h('span', { class: 'fchip-dot', 'aria-hidden': 'true' }), text, h('span', { class: 'fchip-n' }, fmt(n)));
    b.addEventListener('click', () => {
      if (active.has(kind)) active.delete(kind);
      else active.add(kind);
      b.setAttribute('aria-pressed', String(active.has(kind)));
      onChange();
    });
    wrap.append(b);
  }
  return wrap;
}

function textFilter(placeholder, onChange) {
  const input = h('input', { type: 'search', class: 'tfilter', placeholder, 'aria-label': placeholder, autocomplete: 'off', spellcheck: 'false' });
  input.addEventListener('input', debounce(() => onChange(input.value), FILTER_DEBOUNCE));
  return h('div', { class: 'tfilter-wrap' }, icon('search'), input);
}

function countMark(tone, sign, n, label) {
  if (!n) return null;
  return h('span', { class: `cnt tone-${tone}` }, h('span', { 'aria-hidden': 'true' }, `${sign}${fmt(n)}`), h('span', { class: 'sr-only' }, `${fmt(n)} ${label}`));
}

function jumpTo(id) {
  const el = document.getElementById(id);
  if (!el) return;
  const toggle = el.querySelector(':scope > .sec-head .sec-toggle');
  if (toggle && !isSectionOpen(el)) setSectionOpen(el, true);
  el.scrollIntoView({ behavior: reducedMotion() ? 'auto' : 'smooth', block: 'start' });
  if (toggle) {
    toggle.focus({ preventScroll: true });
    return;
  }
  const heading = el.querySelector('h2');
  if (heading) {
    heading.setAttribute('tabindex', '-1');
    heading.focus({ preventScroll: true });
  }
}

let uid = 0;
const nextId = (prefix) => `${prefix}-${++uid}`;

/* ---------- "Show pre-releases" and "Show old releases" switches ---------- */

const SWITCHES = {
  pre: {
    label: 'Show pre-releases', on: () => prefs.showPre, set: setShowPre,
    why: 'Alpha, Beta and CR releases of streams that already have a final release. Pre-releases of upcoming streams are always shown.',
  },
  old: {
    label: 'Show old releases', on: () => prefs.showOld, set: setShowOld,
    why: `Releases of the streams before ${OLDEST_SHOWN_STREAM}. They stay in the data; the switch only lists them.`,
  },
};

/**
 * A "Show pre-releases" (`kind` pre) or "Show old releases" (old) switch: a labelled checkbox with role="switch"
 * (Space or Enter toggles it). `count()` = the releases (or snapshots: `noun`) it shows or hides now, given the
 * other switch; `blocked()` = those of its kind that only the other switch can show; `max` = the most it controls
 * here: with none there is nothing to switch, and it is not rendered. `slot` names its place so a re-render gives
 * focus back to it.
 */
function prefSwitch(kind, slot, { count, blocked = null, max, noun }) {
  if (!max) return null;
  const sw = SWITCHES[kind];
  const id = nextId(kind);
  const input = h('input', {
    type: 'checkbox', id, class: 'switch-input', role: 'switch', 'data-pref-slot': slot, 'aria-describedby': `${id}-n ${id}-d`,
  });
  const note = h('span', { class: 'pre-toggle-n', id: `${id}-n` });
  const sync = () => {
    input.checked = sw.on();
    const n = count();
    const waiting = blocked ? blocked() : 0;
    // nothing to change here while the other switch hides them all: say so instead of "0 shown"
    note.textContent = !n && waiting
      ? `${plural(waiting, noun)} here, all ${kind === 'pre' ? `before ${OLDEST_SHOWN_STREAM}` : 'pre-releases'}`
      : `${plural(n, noun)} ${sw.on() ? 'shown' : 'hidden'}`;
  };
  input.addEventListener('change', () => sw.set(input.checked));
  input.addEventListener('keydown', (e) => {
    if (e.key !== 'Enter') return;
    e.preventDefault(); // also keeps Enter from submitting the picker form
    input.checked = !input.checked;
    sw.set(input.checked);
  });
  sync();
  onPrefChange(input, sync);
  return h('div', { class: 'pre-toggle' },
    h('span', { class: 'switch' }, input, h('span', { class: 'switch-track', 'aria-hidden': 'true' }, h('span', { class: 'switch-knob' }))),
    h('label', { for: id, class: 'switch-label', title: sw.why }, sw.label),
    note,
    h('span', { class: 'sr-only', id: `${id}-d` }, sw.why));
}

/**
 * Both switches over the releases (those they control: none, one or two). Each counts what turning it alone on or
 * off changes, given the other; the old pre-releases need both, and the page's hidden total says so.
 */
function releaseSwitches(slot) {
  const V = store.V;
  return [
    prefSwitch('pre', slot, {
      count: () => currentVis().preHideable, blocked: () => V.visOff.hideable - currentVis().preHideable, max: V.visOff.hideable, noun: 'pre-release',
    }),
    prefSwitch('old', `${slot}-old`, {
      count: () => currentVis().oldHideable, blocked: () => V.visOn.oldHideable - currentVis().oldHideable, max: V.visOn.oldHideable, noun: 'old release',
    }),
  ].filter(Boolean);
}

/** Both switches over the snapshots `snaps` (release indices of config or extension snapshots), counted alike. */
function snapshotSwitches(slot, snaps) {
  const V = store.V;
  const pre = (vis) => snaps.filter((c) => vis.pre[c] && !vis.upcoming[c] && (!vis.old[c] || vis.showOld)).length;
  const old = (vis) => snaps.filter((c) => vis.old[c] && (!vis.pre[c] || vis.upcoming[c] || vis.showPre)).length;
  return [
    prefSwitch('pre', slot, {
      count: () => pre(currentVis()), blocked: () => pre(V.visOn) - pre(currentVis()), max: pre(V.visOn), noun: 'pre-release snapshot',
    }),
    prefSwitch('old', `${slot}-old`, {
      count: () => old(currentVis()), blocked: () => old(V.visOn) - old(currentVis()), max: old(V.visOn), noun: 'old snapshot',
    }),
  ].filter(Boolean);
}

/** The switches in a row, re-rendering the page when they change (`rerender`); null without any. */
function switchRow(cls, switches, { rerender = false } = {}) {
  if (!switches.length) return null;
  const row = h('div', { class: cls }, switches);
  if (rerender) onPrefChange(row, rerenderInPlace);
  return row;
}

/* ---------- version picker ---------- */

const optgroupLabel = (g) => `${g.stream}${g.lts ? ' · LTS' : ''}${g.upcoming ? ' · upcoming' : ''}`;

/**
 * A release select grouped by stream. The releases the switches hide are left out except the selected one, so a
 * route that names a hidden release keeps it selected; the options follow the "Show pre-releases" and "Show old
 * releases" switches.
 */
function versionSelect(selected) {
  const V = store.V;
  const sel = h('select', { id: nextId('vsel'), class: 'vselect' });
  const fill = (keep) => {
    const vis = currentVis();
    const f = document.createDocumentFragment();
    for (const g of V.streams) {
      const items = g.items.filter((i) => vis.visible[i] || i === keep);
      if (!items.length) continue;
      const og = h('optgroup', { label: optgroupLabel(g) });
      for (let j = items.length - 1; j >= 0; j--) {
        const x = V.list[items[j]];
        og.append(h('option', { value: String(items[j]) }, `${short(x.v)}${core.isPreRelease(x) ? ' (pre-release)' : ''}`));
      }
      f.append(og);
    }
    sel.replaceChildren(f);
    sel.value = String(keep);
  };
  fill(selected);
  onPrefChange(sel, () => fill(Number(sel.value)));
  /** Selects release `i`, adding it to the options when the switches hide it. */
  sel.selectRelease = (i) => {
    if ([...sel.options].some((o) => o.value === String(i))) sel.value = String(i);
    else fill(i);
  };
  return sel;
}

/** Label row ("From" + release date of the current choice) above a version select. */
function pickerField(label, sel) {
  const meta = h('span', { class: 'picker-date', 'aria-hidden': 'true' });
  const sync = () => {
    const x = store.V.list[Number(sel.value)];
    meta.textContent = x ? [x.date ? core.formatDate(x.date) : null, x.lts ? 'LTS' : null, preLabel(x)].filter(Boolean).join(' · ') : '';
  };
  sel.addEventListener('change', sync);
  sync();
  return h('div', { class: 'picker-field' },
    h('div', { class: 'picker-label' }, h('label', { for: sel.id }, label), meta),
    h('div', { class: 'select-wrap' }, sel));
}

function pickerForm({ from, to }) {
  const fromSel = versionSelect(from);
  const toSel = versionSelect(to);
  const swap = h('button', { type: 'button', class: 'btn btn-icon picker-swap', title: 'Swap From and To', 'aria-label': 'Swap From and To' }, icon('swap'));
  swap.addEventListener('click', () => {
    const a = Number(fromSel.value);
    fromSel.selectRelease(Number(toSel.value));
    toSel.selectRelease(a);
    fromSel.dispatchEvent(new Event('change'));
    toSel.dispatchEvent(new Event('change'));
  });
  const switches = releaseSwitches('picker');
  const form = h('form', { class: 'picker', 'aria-label': 'Choose two releases to compare' },
    pickerField('From', fromSel),
    swap,
    pickerField('To', toSel),
    h('button', { type: 'submit', class: 'btn btn-primary picker-go' }, 'Compare', icon('arrow')),
    switches.length ? h('div', { class: 'picker-opts' }, switches) : null);
  form.addEventListener('submit', (e) => {
    e.preventDefault();
    const a = store.V.list[Number(fromSel.value)];
    const b = store.V.list[Number(toSel.value)];
    if (a && b) navigate({ page: 'compare', from: short(a.v), to: short(b.v) });
  });
  return form;
}

/* ---------- search box (combobox + listbox, keyboard navigable) ---------- */

function routeForResult(it) {
  if (it.type === 'property') return { page: 'property', key: it.id };
  if (it.type === 'extension') return { page: 'artifact', id: `io.quarkus:${it.id}` };
  if (it.type === 'class') return { page: 'class', name: it.id };
  return { page: 'artifact', id: it.id };
}

function resultSub(it) {
  const V = store.V;
  if (it.type === 'property') {
    const parts = [it.ext];
    if (!it.current && it.lastSeen != null) parts.push(`not in the latest snapshot; last seen in ${short(V.list[it.lastSeen].v)}`);
    return parts.filter(Boolean).join(' · ');
  }
  if (it.type === 'artifact') {
    if (!it.current) return 'no longer managed by quarkus-bom';
    return it.follows ? `${short(it.value)} (versioned with Quarkus)` : `managed at ${it.value}`;
  }
  if (it.type === 'class') {
    const jars = it.jars.join(', ');
    if (it.current) return `in ${jars}`;
    return it.lastSeen != null ? `not in the latest classes snapshot; last in ${jars} (${short(V.list[it.lastSeen].v)})` : jars;
  }
  const parts = [it.name, it.status];
  if (it.relocatedTo) parts.push(`relocated to ${it.relocatedTo}`);
  if (!it.current) parts.push('removed');
  return parts.filter(Boolean).join(' · ');
}

function searchBox({ id, compact = false }) {
  const listId = `${id}-list`;
  const input = h('input', {
    type: 'search',
    id: `${id}-input`,
    class: 'search-input',
    role: 'combobox',
    'aria-autocomplete': 'list',
    'aria-expanded': 'false',
    'aria-controls': listId,
    'aria-label': 'Search config properties, managed artifacts, extensions and classes',
    autocomplete: 'off',
    spellcheck: 'false',
    placeholder: compact ? 'Search properties, artifacts, classes…' : 'quarkus.http.port, vertx-core, QuarkusUnitTest, QUARKUS_HTTP_PORT',
  });
  const list = h('ul', { id: listId, class: 'search-list', role: 'listbox', 'aria-label': 'Search results', hidden: true });
  const status = h('p', { class: 'sr-only', 'aria-live': 'polite' });
  const hint = compact ? null : h('p', { class: 'search-hint', id: `${id}-hint` }, 'Arrow keys move through the results, Enter opens one, Escape closes the list.');
  if (hint) input.setAttribute('aria-describedby', hint.id);
  let items = [];
  let active = -1;
  let seq = 0;

  const options = () => list.querySelectorAll('[role="option"]');
  const open = () => {
    list.hidden = false;
    input.setAttribute('aria-expanded', 'true');
  };
  const close = () => {
    list.hidden = true;
    input.setAttribute('aria-expanded', 'false');
    input.removeAttribute('aria-activedescendant');
  };
  const setActive = (i) => {
    active = i;
    options().forEach((li, j) => li.setAttribute('aria-selected', String(j === i)));
    if (i >= 0) {
      input.setAttribute('aria-activedescendant', `${id}-opt-${i}`);
      const el = document.getElementById(`${id}-opt-${i}`);
      if (el && typeof el.scrollIntoView === 'function') el.scrollIntoView({ block: 'nearest' });
    } else {
      input.removeAttribute('aria-activedescendant');
    }
  };
  const choose = (i) => {
    const it = items[i];
    if (!it) return;
    close();
    input.blur();
    navigate(routeForResult(it));
  };
  const paint = (q, res) => {
    items = res.items;
    const f = document.createDocumentFragment();
    items.forEach((it, i) => {
      const li = h('li', { id: `${id}-opt-${i}`, role: 'option', class: 'search-opt', 'aria-selected': 'false' },
        h('span', { class: `search-type st-${it.type}` }, TYPE_LABEL[it.type]),
        h('span', { class: 'search-main' },
          h('span', { class: 'search-label' }, highlight(it.label, q)),
          h('span', { class: 'search-sub' }, resultSub(it))));
      li.addEventListener('mousedown', (e) => e.preventDefault()); // keep focus in the input
      li.addEventListener('click', () => choose(i));
      f.append(li);
    });
    if (!items.length) f.append(h('li', { class: 'search-empty', role: 'presentation' }, `Nothing matches “${q}”.`));
    else if (res.total > items.length) f.append(h('li', { class: 'search-more', role: 'presentation' }, `${fmt(res.total - items.length)} more matches. Type more to narrow them down.`));
    list.replaceChildren(f);
    open();
    setActive(items.length ? 0 : -1);
    status.textContent = items.length
      ? `${plural(res.total, 'result')}${res.total > items.length ? `, showing the first ${items.length}` : ''}.`
      : 'No results.';
  };
  const run = debounce(async () => {
    const q = input.value.trim();
    const mine = ++seq;
    if (!q) {
      items = [];
      close();
      status.textContent = '';
      return;
    }
    let index;
    try {
      index = await getSearchIndex();
    } catch {
      status.textContent = 'Search is unavailable because the data could not be loaded.';
      return;
    }
    if (mine !== seq || input.value.trim() !== q) return;
    paint(q, core.search(index, q, { limit: 12 }));
  }, SEARCH_DEBOUNCE);

  input.addEventListener('input', run);
  input.addEventListener('focus', () => {
    getSearchIndex().catch(() => {});
    if (input.value.trim() && items.length) open();
  });
  input.addEventListener('blur', () => setTimeout(close, 150));
  input.addEventListener('keydown', (e) => {
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      if (!items.length) return;
      open();
      setActive(active < 0 ? 0 : (active + 1) % items.length);
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      if (!items.length) return;
      open();
      setActive(active <= 0 ? items.length - 1 : active - 1);
    } else if (e.key === 'Enter') {
      if (items.length && !list.hidden) {
        e.preventDefault();
        choose(active >= 0 ? active : 0);
      }
    } else if (e.key === 'Escape') {
      if (!list.hidden) {
        e.preventDefault();
        close();
      }
    }
  });
  return h('div', { class: `search${compact ? ' search--compact' : ''}` },
    h('div', { class: 'search-field' }, icon('search'), input, list),
    hint,
    status);
}

/* ========================================================================== */
/* Generic views                                                               */
/* ========================================================================== */

function loadingView() {
  return h('div', { class: 'loading', role: 'status' }, h('span', { class: 'loading-bar', 'aria-hidden': 'true' }), 'Loading the almanac data…');
}

function errorView(err) {
  const attempts = err instanceof DataError ? err.attempts : [];
  const retry = h('button', { type: 'button', class: 'btn' }, 'Try again');
  retry.addEventListener('click', () => {
    store.versionsPromise = null;
    store.files.clear();
    render();
  });
  return frag(
    h('header', { class: 'page-head band' },
      h('p', { class: 'kicker' }, 'Error'),
      h('h1', { class: 'page-title', tabindex: '-1' }, 'The almanac data could not be loaded'),
      h('p', { class: 'lede' }, err && err.message ? err.message : String(err))),
    attempts.length ? h('ul', { class: 'attempts' }, attempts.map((a) => h('li', null, h('code', null, a)))) : null,
    h('p', null, 'If you opened index.html straight from disk, serve the site folder over HTTP instead, for example ', h('code', null, 'jwebserver -d site'), '.'),
    retry);
}

/** A not-found page: `extra` goes in the title band, `below` after it (a search box: the band would clip its results). */
function notFoundView(title, message, extra, below) {
  return frag(
    h('header', { class: 'page-head band' },
      h('p', { class: 'kicker' }, 'Not found'),
      h('h1', { class: 'page-title', tabindex: '-1' }, title),
      message ? h('p', { class: 'lede' }, message) : null,
      extra ?? null),
    below ?? null,
    h('p', null, h('a', { href: '#/' }, 'Compare two releases'), ' · ', h('a', { href: '#/versions' }, 'All releases')));
}

function renderNotFound(r) {
  return notFoundView('This page does not exist', `There is no page at #/${r.path ?? ''}.`);
}

/* ========================================================================== */
/* Home                                                                        */
/* ========================================================================== */

async function renderHome() {
  const V = store.V;
  const pair = core.defaultPair(V.json) ?? { from: 0, to: V.list.length - 1 };
  const first = V.list[0];
  const last = V.list[V.list.length - 1];

  const hero = h('section', { class: 'hero band', 'aria-labelledby': 'home-title' },
    h('p', { class: 'kicker' }, 'Release almanac · Quarkus ', h('span', { class: 'ver' }, short(first.v)), ' to ', h('span', { class: 'ver' }, short(last.v))),
    h('h1', { class: 'display', id: 'home-title', tabindex: '-1' }, 'What changed between Quarkus releases?'),
    h('p', { class: 'lede' },
      'Pick two releases to see which ', h('code', null, 'quarkus.*'), ' config properties were added, removed or changed, ',
      'which dependency versions managed by ', h('code', null, 'quarkus-bom'), ' moved, which extensions appeared, disappeared or were relocated, ',
      'and which classes of the Quarkus jars were added, removed or moved to another jar.'),
    pickerForm({ from: pair.from, to: pair.to }),
    h('p', { class: 'picker-note' }, 'Preselected: the newest patch of the previous LTS stream and the newest final release.'));

  const search = h('section', { class: 'home-search', 'aria-labelledby': 'home-search-h' },
    h('h2', { class: 'label-h', id: 'home-search-h' }, 'Look something up'),
    h('p', { class: 'home-search-note' }, 'Find a config property by name, alias or environment variable, a managed artifact, an extension or a class, and see its history across releases.'),
    searchBox({ id: 'qs-home' }));

  const picks = core.quickPicks(V.json);
  const quickBody = h('div');
  const fillQuick = () => {
    const vis = currentVis();
    const shown = picks.filter((p) => vis.visible[p.from] && vis.visible[p.to]);
    quickBody.replaceChildren(shown.length ? h('ol', { class: 'quick-list' }, shown.map(quickPickItem))
      : emptyNote(picks.length ? 'No quick comparison among the releases shown.' : 'Not enough releases for quick comparisons.'));
  };
  fillQuick();
  onPrefChange(quickBody, fillQuick);
  const quick = h('section', { class: 'quick', 'aria-labelledby': 'quick-h' },
    h('h2', { class: 'label-h', id: 'quick-h' }, 'Quick comparisons'),
    quickBody);

  return frag(
    hero,
    h('div', { class: 'home-grid' }, h('div', { class: 'home-main' }, search), h('aside', { class: 'home-side', 'aria-label': 'Quick comparisons' }, quick)),
    coverageFacts());
}

/**
 * The duration shown next to a quick comparison: the time between the two streams (their first finals),
 * not between the newest patches, which often ship the same day. A stream without a final release yet
 * (an upcoming pre-release) has no start, so the pre-release date is shown instead.
 */
function quickSpan(p) {
  const V = store.V;
  if (p.span) {
    const a = V.list[p.span.from];
    const b = V.list[p.span.to];
    return h('span', { class: 'quick-span', title: `Time between the streams: ${short(a.v)} (${core.formatDate(a.date)}) to ${short(b.v)} (${core.formatDate(b.date)})` },
      p.span.label, h('span', { class: 'sr-only' }, ' between the two streams'));
  }
  const B = V.list[p.to];
  if (!B.date) return null;
  return h('span', { class: 'quick-span', title: `${p.toStream} has no final release yet; ${short(B.v)} was released ${core.formatDate(B.date)}` },
    h('span', { class: 'sr-only' }, 'released '), core.formatDate(B.date));
}

function quickPickItem(p) {
  const V = store.V;
  const A = V.list[p.from];
  const B = V.list[p.to];
  const title = p.kind === 'lts' ? `${p.fromStream} LTS → ${p.toStream} LTS` : p.kind === 'latest' ? 'Latest LTS → newest release' : 'Newest release → pre-release';
  return h('li', null,
    h('a', { class: 'quick-link', href: link.compare(A.v, B.v) },
      h('span', { class: 'quick-title' }, title),
      h('span', { class: 'quick-vers' }, short(A.v), h('span', { class: 'arrow', 'aria-hidden': 'true' }, ' → '), h('span', { class: 'sr-only' }, ' to '), short(B.v)),
      quickSpan(p)));
}

function coverageFacts() {
  const V = store.V;
  const first = V.list[0];
  const last = V.list[V.list.length - 1];
  const fact = (value, label, sub, cls) => h('div', { class: cls ? `fact ${cls}` : 'fact' }, h('dt', null, label), h('dd', { class: 'fact-n' }, value), sub == null ? null : h('dd', { class: 'fact-sub' }, sub));
  const propsN = h('span', null, '…');
  const propsSub = h('span', null, 'loading config.json');
  const artsN = h('span', null, '…');
  const artsSub = h('span', null, 'loading bom.json');
  const lastCv = V.configVis.length ? V.list[V.configVis[V.configVis.length - 1]] : null;
  loadFile('config').then((c) => {
    const cvs = c.raw.versions;
    const latest = cvs[cvs.length - 1];
    const current = c.raw.props.filter((p) => core.stateAt(p.h, latest) != null).length;
    propsN.textContent = fmt(c.raw.props.length);
    propsSub.textContent = `${fmt(current)} present in ${short(V.list[latest].v)}`;
  }).catch(() => {
    propsN.textContent = '—';
    propsSub.textContent = 'config.json unavailable';
  });
  loadFile('bom').then((b) => {
    const lastVi = V.list.length - 1;
    let current = 0;
    let modules = 0;
    for (const a of b.raw.artifacts) {
      const raw = core.stateAt(a.h, lastVi);
      if (raw != null) current++;
      if (raw === '=') modules++;
    }
    artsN.textContent = fmt(b.raw.artifacts.length);
    artsSub.textContent = `${fmt(current)} managed in ${short(last.v)}, ${fmt(modules)} of them Quarkus modules`;
  }).catch(() => {
    artsN.textContent = '—';
    artsSub.textContent = 'bom.json unavailable';
  });
  const preCount = V.visOff.pre.filter(Boolean).length;
  const preSnapshots = V.configVis.filter((c) => V.visOff.pre[c]).length;
  return h('section', { class: 'facts', 'aria-labelledby': 'facts-h' },
    h('h2', { class: 'label-h', id: 'facts-h' }, 'Coverage'),
    h('dl', { class: 'facts-grid' },
      fact(fmt(V.list.length), 'releases', `${short(first.v)} to ${short(last.v)}${preCount ? `, ${plural(preCount, 'pre-release')} among them` : ''}`),
      fact(fmt(V.configVis.length), 'config snapshots', lastCv
        ? `${andList([...configCoverage(V), ...(preSnapshots ? [plural(preSnapshots, 'pre-release')] : [])])}, up to ${short(lastCv.v)}`
        : 'none'),
      fact(propsN, 'config properties tracked', propsSub),
      fact(artsN, 'managed artifacts tracked', artsSub),
      fact(V.generatedAt ? core.formatDate(V.generatedAt) : '—', 'data generated', null, 'fact--date')));
}

/** Where the config snapshots are: at the newest patch of each stream, or in how many streams while some lack one. */
function configCoverage(V) {
  if (V.configAtEveryFinal) return ['every final release'];
  const parts = [V.configAtNewestFinals ? 'the newest patch of each stream' : `${fmt(V.configStreamCount)} of ${plural(V.finalStreamCount, 'stream')}`];
  const every = V.configEveryPatch;
  if (every.length) {
    // the LTS streams by that name when all of them are covered, the others by name while few, else by number
    const allLts = V.ltsStreams.length > 0 && V.ltsStreams.every((s) => every.includes(s));
    const others = allLts ? every.filter((s) => !V.ltsStreams.includes(s)) : every;
    const named = others.length <= 3 ? others.slice().reverse() : [plural(others.length, allLts ? 'other stream' : 'stream')];
    parts.push(`every patch of ${andList(allLts ? [...named, 'the LTS streams'] : named, false)}`);
  }
  return parts;
}

/** "a", "a and b", "a, b, and c" (`serial` false: "a, b and c", for a list inside another list item). */
function andList(items, serial = true) {
  if (items.length < 3) return items.join(' and ');
  return `${items.slice(0, -1).join(', ')}${serial ? ',' : ''} and ${items[items.length - 1]}`;
}

/* ========================================================================== */
/* Compare                                                                     */
/* ========================================================================== */

function comparison(ia, ib, data) {
  const key = `${ia}:${ib}`;
  const hit = store.diffs.get(key);
  if (hit) return hit;
  const V = store.V;
  const out = { ia, ib, A: V.list[ia], B: V.list[ib], errors: data.errors, bom: null, libs: [], config: null, configRows: [], configTags: {}, ext: null };
  if (data.bom) {
    out.bom = core.diffBom(data.bom.raw, V.list, ia, ib);
    out.libs = core.headlineLibraries(data.bom.raw, V.list, ia, ib);
  }
  const cvs = data.config ? data.config.raw.versions : V.configVis;
  out.ca = core.configVersionFor(ia, V.list, cvs);
  out.cb = core.configVersionFor(ib, V.list, cvs);
  if (data.config && out.ca != null && out.cb != null) {
    out.config = core.diffConfig(data.config.raw, out.ca, out.cb);
    out.configRows = core.configRows(out.config, data.config.raw.desc);
    out.configTags = core.tagCounts(out.configRows);
  }
  // extensions have snapshots of their own: at every config snapshot, and at releases without config
  out.ea = core.extensionVersionFor(ia, V.list, V.extVis);
  out.eb = core.extensionVersionFor(ib, V.list, V.extVis);
  if (data.extensions && out.ea != null && out.eb != null) out.ext = core.diffExtensions(data.extensions.raw, out.ea, out.eb);
  // classes have snapshots of their own too; their diff waits for the classes files (see classDiff)
  out.la = core.classesVersionFor(ia, V.list, V.clsVis);
  out.lb = core.classesVersionFor(ib, V.list, V.clsVis);
  if (!Object.keys(data.errors).length) {
    if (store.diffs.size >= 12) store.diffs.delete(store.diffs.keys().next().value);
    store.diffs.set(key, out);
  }
  return out;
}

async function renderCompare(r) {
  const V = store.V;
  const ia = core.findVersionIndex(V.list, r.from);
  const ib = core.findVersionIndex(V.list, r.to);
  if (ia < 0 || ib < 0) {
    const missing = [ia < 0 ? r.from : null, ib < 0 ? r.to : null].filter((x) => x !== null).map((x) => x || '(empty)');
    const pair = core.defaultPair(V.json);
    return notFoundView('Unknown release', `This dataset has no release ${missing.join(' or ')}. Pick two releases instead:`,
      pickerForm({ from: ia >= 0 ? ia : pair.from, to: ib >= 0 ? ib : pair.to }));
  }
  const canonical = { page: 'compare', from: short(V.list[ia].v), to: short(V.list[ib].v) };
  if (hrefFor(canonical) !== hrefFor(r)) canonicalize(canonical);
  const data = await loadSettled(['bom', 'config', 'extensions']);
  const cmp = comparison(ia, ib, data);
  const head = compareHeader(cmp);
  if (ia === ib) return frag(head, emptyNote('From and To are the same release, so there is nothing to compare. Change one of them above.'));
  // the sections start collapsed (the summary above says what each holds), except those the reader left open in
  // this pair earlier in the visit, so that Back from a property or an artifact finds them as they were
  const sections = [libsSection(cmp, data), configSection(cmp, data), depsSection(cmp, data), extSection(cmp, data), classesSection(cmp)]
    .map(collapsible);
  const pair = `${ia}:${ib}`;
  const all = h('button', { type: 'button', class: 'btn jump-all', 'aria-controls': sections.map((s) => `${s.id}-body`).join(' ') });
  const sync = () => {
    all.textContent = sections.every(isSectionOpen) ? 'Collapse all' : 'Expand all';
  };
  all.addEventListener('click', () => {
    const open = !sections.every(isSectionOpen);
    for (const s of sections) setSectionOpen(s, open);
  });
  for (const s of sections) {
    if (openSections.get(pair)?.has(s.id)) setSectionOpen(s, true);
    s.addEventListener('sectiontoggle', () => {
      openSections.set(pair, new Set(sections.filter(isSectionOpen).map((x) => x.id)));
      sync();
    });
  }
  sync();
  return frag(
    head,
    summaryLedger(cmp, data),
    h('nav', { class: 'jump', 'aria-label': 'Sections of this comparison' },
      h('span', { class: 'jump-k' }, 'On this page'),
      [['sec-libs', 'Key libraries'], ['sec-config', 'Configuration'], ['sec-deps', 'Dependencies'], ['sec-ext', 'Extensions'],
        ['sec-classes', 'Classes']].map(([id, label]) =>
        h('button', { type: 'button', class: 'jump-link', onclick: () => jumpTo(id) }, label)),
      all),
    sections);
}

function compareHeader(cmp) {
  const { A, B, ia, ib } = cmp;
  const panel = h('div', { class: 'cmp-change', hidden: true }, pickerForm({ from: ia, to: ib }));
  const toggle = h('button', { type: 'button', class: 'btn', 'aria-expanded': 'false' }, 'Change releases');
  toggle.addEventListener('click', () => {
    panel.hidden = !panel.hidden;
    toggle.setAttribute('aria-expanded', String(!panel.hidden));
    if (!panel.hidden) panel.querySelector('select')?.focus();
  });
  const dir = core.compareVersions(A.v, B.v);
  const side = (label, x) => h('div', { class: 'cmp-side' },
    h('span', { class: 'cmp-side-k' }, label), verLink(x.v), dateEl(x.date), versionChips(x));
  return h('header', { class: 'cmp-head band' },
    h('p', { class: 'kicker' }, 'Comparison'),
    h('h1', { class: 'cmp-title', tabindex: '-1' },
      h('span', { class: 'ver' }, short(A.v)),
      h('span', { class: 'cmp-arrow', 'aria-hidden': 'true' }, '→'),
      h('span', { class: 'sr-only' }, ' to '),
      h('span', { class: 'ver' }, short(B.v))),
    h('div', { class: 'cmp-sides' },
      side('From', A),
      side('To', B),
      dir !== 0 && A.date && B.date ? h('span', { class: 'cmp-span' }, core.releasedApart(A.date, B.date)) : null),
    dir > 0 ? h('p', { class: 'note note--warn' }, 'To is older than From, so upgrades read as downgrades and additions as removals.') : null,
    h('div', { class: 'cmp-actions' },
      h('a', { class: 'btn', href: link.compare(B.v, A.v) }, icon('swap'), 'Swap'),
      toggle),
    panel);
}

/**
 * The snapshots a comparison uses for one kind of data: config, extensions (which also exist for releases without
 * config) or classes. `a` and `b` are the snapshot indices for From and To, null when a release has none.
 */
function snapshotsOf(cmp, kind) {
  if (kind === 'classes') return { a: cmp.la, b: cmp.lb, all: store.V.clsVis, noun: 'classes', title: 'Classes' };
  return kind === 'config'
    ? { a: cmp.ca, b: cmp.cb, all: store.V.configVis, noun: 'config', title: 'Config' }
    : { a: cmp.ea, b: cmp.eb, all: store.V.extVis, noun: 'extension', title: 'Extensions' };
}

/**
 * Why a comparison has no diff of one kind ('config', 'extension' or 'classes'), or null when both releases have such
 * data. A pre-release only uses its own snapshot, so one without a snapshot says so plainly:
 * "No config snapshot for 3.33.0.CR1: dependency changes only." The end names what the comparison still shows (for
 * config and extensions, which the rest of the page depends on).
 */
function gapText(cmp, kind) {
  const V = store.V;
  const s = snapshotsOf(cmp, kind);
  const missing = [];
  if (s.a == null) missing.push(cmp.ia);
  if (s.b == null && cmp.ib !== cmp.ia) missing.push(cmp.ib);
  if (!missing.length) return null;
  const names = (list) => list.map((i) => short(V.list[i].v)).join(' and ');
  const pre = missing.filter((i) => core.isPreRelease(V.list[i]));
  const fin = missing.filter((i) => !core.isPreRelease(V.list[i]));
  const parts = [];
  if (pre.length) parts.push(`No ${s.noun} snapshot for ${names(pre)}`);
  if (fin.length) {
    const first = s.all.length ? short(V.list[s.all[0]].v) : null;
    parts.push(`${pre.length ? 'no' : 'No'} ${s.noun} data for ${names(fin)}${first ? ` (${s.noun} snapshots start at ${first})` : ''}`);
  }
  if (kind === 'classes') return `${parts.join(', and ')}.`;
  // config needs extensions, so only a config gap can leave an extension diff to show
  const shown = kind === 'config' && cmp.ext ? 'dependency and extension changes only' : 'dependency changes only';
  return `${parts.join(', and ')}: ${shown}.`;
}

/** Both releases use one snapshot of a kind ('config', 'extension' or 'classes'), so there is no diff of it to show. */
function sameSnapshotText(cmp, kind) {
  const V = store.V;
  const { A, B } = cmp;
  const s = snapshotsOf(cmp, kind);
  const finalsOfOneStream = !core.isPreRelease(A) && !core.isPreRelease(B) && streamOfEntry(A) === streamOfEntry(B);
  return `Both releases map to the same ${s.noun} snapshot (${short(V.list[s.a].v)}). ${finalsOfOneStream
    ? `${s.title} ${kind === 'config' ? 'is' : 'are'} captured once per stream, so changes between patches of one stream are not tracked.`
    : 'Changes between them are not tracked.'}`;
}

function summaryLedger(cmp, data) {
  const cell = (title, target, nums, note) => h('div', { class: 'ledger-cell' },
    h('h2', { class: 'ledger-title' }, h('button', { type: 'button', class: 'ledger-jump', onclick: () => jumpTo(target) }, title)),
    nums.length
      ? h('div', { class: 'ledger-nums' }, nums.map(([n, label, tone]) =>
        h('span', { class: `ledger-num tone-${tone}${n ? '' : ' is-zero'}` }, h('span', { class: 'ledger-n' }, fmt(n)), h('span', { class: 'ledger-l' }, label))))
      : null,
    note ? h('p', { class: 'ledger-note' }, note) : null);
  const cells = [];
  // one snapshot for both releases: say so here, since its section starts collapsed and zeros would mislead
  if (cmp.config && cmp.ca === cmp.cb) {
    cells.push(cell('Configuration', 'sec-config', [], sameSnapshotText(cmp, 'config')));
  } else if (cmp.config) {
    const c = cmp.config.counts;
    const kinds = ['default', 'type', 'phase', 'deprecated', 'undeprecated', 'enum', 'extension'].filter((k) => c[k]).map((k) => `${fmt(c[k])} ${KIND_LABEL[k]}`);
    cells.push(cell('Configuration', 'sec-config', [[c.added, 'added', 'add'], [c.removed, 'removed', 'rem'], [c.changed, 'changed', 'chg']],
      kinds.length ? `Changed: ${kinds.join(' · ')}` : null));
  } else {
    cells.push(cell('Configuration', 'sec-config', [], data.config ? gapText(cmp, 'config') ?? 'No config data for this pair of releases.' : 'config.json is unavailable.'));
  }
  if (cmp.bom) {
    const b = cmp.bom.counts;
    const kinds = ['downgrade', 'major', 'minor', 'patch', 'other'].filter((k) => b[k]).map((k) => `${fmt(b[k])} ${KIND_LABEL[k]}`);
    cells.push(cell('Dependencies', 'sec-deps', [[b.changed, 'changed', 'chg'], [b.added, 'added', 'add'], [b.removed, 'removed', 'rem']],
      [kinds.length ? kinds.join(' · ') : null, cmp.bom.modules ? `plus ${fmt(cmp.bom.modules)} Quarkus modules` : null].filter(Boolean).join('; ')));
  } else {
    cells.push(cell('Dependencies', 'sec-deps', [], 'bom.json is unavailable.'));
  }
  if (cmp.ext && cmp.ea === cmp.eb) {
    cells.push(cell('Extensions', 'sec-ext', [], sameSnapshotText(cmp, 'extension')));
  } else if (cmp.ext) {
    const e = cmp.ext.counts;
    const kinds = ['relocated', 'status', 'renamed'].filter((k) => e[k]).map((k) => `${fmt(e[k])} ${KIND_LABEL[k]}`);
    cells.push(cell('Extensions', 'sec-ext', [[e.added, 'added', 'add'], [e.removed, 'removed', 'rem'], [cmp.ext.changed.length, 'changed', 'chg']],
      kinds.length ? kinds.join(' · ') : null));
  } else {
    cells.push(cell('Extensions', 'sec-ext', [], data.extensions ? gapText(cmp, 'extension') ?? 'No extension data for this pair of releases.' : 'extensions.json is unavailable.'));
  }
  // classes: the page does not wait for the classes files, so the cell fills in when they arrive
  const gap = gapText(cmp, 'classes');
  if (gap) {
    cells.push(cell('Classes', 'sec-classes', [], gap));
  } else if (cmp.la === cmp.lb) {
    cells.push(cell('Classes', 'sec-classes', [], sameSnapshotText(cmp, 'classes')));
  } else {
    const pending = cell('Classes', 'sec-classes', [], 'Loading the classes…');
    cells.push(pending);
    loadClasses(core.classLinesOf(store.V.list, [cmp.la, cmp.lb])).then((classes) => {
      const d = classDiff(cmp, classes);
      const t = d.top;
      const c = d.counts;
      const nested = c.added + c.removed + c.moved - t.added - t.removed - t.moved;
      pending.replaceWith(cell('Classes', 'sec-classes', [[t.added, 'added', 'add'], [t.removed, 'removed', 'rem'], [t.moved, 'moved', 'chg']],
        nested ? `Top-level classes. With nested ones: ${fmt(c.added)} added, ${fmt(c.removed)} removed, ${fmt(c.moved)} moved.` : 'No nested class changed.'));
    }).catch(() => pending.replaceWith(cell('Classes', 'sec-classes', [], 'The classes data is unavailable.')));
  }
  return h('section', { class: 'ledger', 'aria-label': 'Summary of changes' }, cells);
}

/* ---------- §1 key libraries ---------- */

function libsSection(cmp, data) {
  const sec = h('section', { class: 'sec', id: 'sec-libs', 'aria-labelledby': 'sec-libs-h' },
    sectionHead(1, 'Key libraries', 'sec-libs-h', h('p', { class: 'sec-note' }, 'Headline dependencies managed by quarkus-bom, listed when either release manages them.')));
  if (!cmp.bom) return add(sec, [missingData('bom.json', data.errors.bom)]);
  if (!cmp.libs.length) return add(sec, [emptyNote('Neither release manages any of the headline libraries.')]);
  const tbody = h('tbody');
  for (const r of cmp.libs) {
    const ids = r.fromId && r.toId && r.fromId !== r.toId
      ? frag(h('a', { href: link.artifact(r.fromId) }, r.fromId), h('span', { 'aria-hidden': 'true' }, ' → '), h('span', { class: 'sr-only' }, ' then '), r.toId)
      : r.id;
    tbody.append(h('tr', { class: r.kind === 'same' ? 'is-same' : null },
      h('th', { scope: 'row' }, h('a', { href: link.artifact(r.id) }, r.label), h('span', { class: 'cell-sub' }, ids)),
      h('td', { class: 'mono num' }, r.from ?? h('span', { class: 'none' }, 'not managed')),
      h('td', { class: 'mono num' }, r.to ?? h('span', { class: 'none' }, 'not managed')),
      h('td', null, r.kind === 'same' ? h('span', { class: 'muted' }, 'unchanged') : chip(r.kind))));
  }
  sec.append(h('div', { class: 'table-scroll' },
    h('table', { class: 'tbl tbl-libs' },
      h('thead', null, h('tr', null,
        h('th', { scope: 'col' }, 'Library'),
        h('th', { scope: 'col' }, h('span', { class: 'ver' }, short(cmp.A.v))),
        h('th', { scope: 'col' }, h('span', { class: 'ver' }, short(cmp.B.v))),
        h('th', { scope: 'col' }, 'Change'))),
      tbody)));
  return sec;
}

/* ---------- §2 configuration ---------- */

const CONFIG_CHIPS = [
  ['added', 'added'], ['removed', 'removed'], ['default', 'default'], ['type', 'type'], ['phase', 'phase'],
  ['deprecated', 'deprecated'], ['enum', 'enum'], ['extension', 'extension'],
];

/**
 * Why the snapshots of a kind ('config' or 'extension') differ from the releases, for the note under the
 * Configuration or Extensions heading. Finals without a snapshot of their own use their stream's; pre-releases
 * always use their own.
 */
function snapshotReason(cmp, kind) {
  const V = store.V;
  const s = snapshotsOf(cmp, kind);
  const borrowed = [[cmp.ia, s.a], [cmp.ib, s.b]].filter(([i, c]) => i !== c);
  if (!borrowed.length) return null;
  const inStream = borrowed.every(([i, c]) => !core.isPreRelease(V.list[c]) && streamOfEntry(V.list[c]) === streamOfEntry(V.list[i]));
  if (inStream) return ' (one snapshot per stream, at its newest patch)';
  const names = [...new Set(borrowed.map(([i]) => short(V.list[i].v)))];
  return ` (${names.join(' and ')} ${names.length === 1 ? 'has no snapshot of its own' : 'have no snapshots of their own'}; the nearest one is used)`;
}

function configSection(cmp, data) {
  const V = store.V;
  const { ca, cb } = cmp;
  let note = null;
  if (ca != null && cb != null) {
    const xa = V.list[ca];
    const xb = V.list[cb];
    const mixed = xa.configSource && xb.configSource && xa.configSource !== xb.configSource;
    note = h('p', { class: 'sec-note' },
      'Config compared at ', h('span', { class: 'ver' }, short(xa.v)), ' → ', h('span', { class: 'ver' }, short(xb.v)),
      snapshotReason(cmp, 'config'),
      xa.configSource && xb.configSource
        ? frag('; extracted from ', h('abbr', { title: SOURCE_TITLE[xa.configSource] ?? '' }, xa.configSource), ' → ', h('abbr', { title: SOURCE_TITLE[xb.configSource] ?? '' }, xb.configSource),
          mixed ? ', so a few differences may come from the extraction method rather than Quarkus' : '')
        : null,
      '.');
  }
  const sec = h('section', { class: 'sec', id: 'sec-config', 'aria-labelledby': 'sec-config-h' }, sectionHead(2, 'Configuration', 'sec-config-h', note));
  if (!data.config) return add(sec, [missingData('config.json', data.errors.config)]);
  if (ca == null || cb == null) return add(sec, [emptyNote(gapText(cmp, 'config'))]);
  if (ca === cb) return add(sec, [emptyNote(sameSnapshotText(cmp, 'config'))]);
  const rows = cmp.configRows;
  if (!rows.length) return add(sec, [emptyNote('No config property was added, removed or changed between these snapshots.')]);

  const state = { tags: new Set(), text: '', expanded: new Set() };
  const status = h('p', { class: 'filter-status', 'aria-live': 'polite' });
  const groupsEl = h('div', { class: 'cgroups' });
  const extCount = new Set(rows.map((r) => r.ext)).size;
  const update = () => {
    const filtered = core.filterRows(rows, state);
    renderConfigGroups(groupsEl, filtered, state);
    const groups = new Set(filtered.map((r) => r.ext)).size;
    status.textContent = filtered.length === rows.length
      ? `${plural(rows.length, 'change')} in ${plural(extCount, 'extension')}.`
      : `Showing ${fmt(filtered.length)} of ${plural(rows.length, 'change')}, in ${plural(groups, 'extension')}.`;
  };
  sec.append(
    h('div', { class: 'toolbar' },
      filterChips(CONFIG_CHIPS, cmp.configTags, state.tags, update, 'Show only these kinds of config change'),
      textFilter('Filter by property, extension or description', (v) => {
        state.text = v;
        update();
      })),
    status,
    groupsEl);
  update();
  return sec;
}

function renderConfigGroups(container, rows, state) {
  const filtering = state.text.trim() !== '';
  renderGroupPages(container, [...core.groupBy(rows, (r) => r.ext)], (ext, list) => configGroup(ext, list, state, filtering),
    'No change matches these filters.', 'more extension');
}

/**
 * Renders `groups` ([key, rows] pairs) GROUP_PAGE at a time, with a button for the next page; `empty` when there is
 * none. `noun` names what the button adds ("more extension").
 */
function renderGroupPages(container, groups, renderGroup, empty, noun) {
  container.replaceChildren();
  if (!groups.length) {
    container.append(emptyNote(empty));
    return;
  }
  let shown = 0;
  const more = moreButton('', () => {
    const firstNew = appendPage();
    firstNew?.querySelector('a, button')?.focus();
  });
  const appendPage = () => {
    const f = document.createDocumentFragment();
    let firstNew = null;
    const end = Math.min(groups.length, shown + GROUP_PAGE);
    for (; shown < end; shown++) {
      const el = renderGroup(groups[shown][0], groups[shown][1]);
      firstNew = firstNew ?? el;
      f.append(el);
    }
    more.remove();
    container.append(f);
    const rest = groups.length - shown;
    if (rest > 0) {
      more.textContent = `Show ${plural(rest, noun)}`;
      container.append(more);
    }
    return firstNew;
  };
  appendPage();
}

function configGroup(ext, list, state, filtering) {
  const counts = { added: 0, removed: 0, changed: 0 };
  for (const r of list) counts[r.change]++;
  const cap = filtering ? ROW_CAP_FILTERED : ROW_CAP;
  const initial = state.expanded.has(ext) ? list.length : Math.min(list.length, cap);
  const ul = h('ul', { class: 'crows' });
  const fill = (from, to) => {
    const f = document.createDocumentFragment();
    for (let i = from; i < to; i++) f.append(configRow(list[i]));
    return f;
  };
  ul.append(fill(0, initial));
  const sec = h('section', { class: 'cgroup', 'aria-label': `${ext || 'Unattributed'}: ${plural(list.length, 'change')}` },
    h('div', { class: 'cgroup-head' },
      h('h3', { class: 'cgroup-title' }, ext ? h('a', { href: link.extension(ext) }, ext) : 'Unattributed'),
      h('span', { class: 'cgroup-counts' },
        countMark('add', '+', counts.added, 'added'),
        countMark('rem', '−', counts.removed, 'removed'),
        countMark('chg', '~', counts.changed, 'changed'))),
    ul);
  if (list.length > initial) {
    const btn = moreButton(`Show all ${plural(list.length, 'change')} in ${ext || 'this group'}`, () => {
      state.expanded.add(ext);
      ul.append(fill(initial, list.length));
      btn.remove();
      ul.children[initial]?.querySelector('a')?.focus();
    });
    sec.append(btn);
  }
  return sec;
}

function configRow(r) {
  const s = r.b ?? r.a;
  const name = h('a', { class: 'prop', href: link.property(r.k) }, r.name);
  return h('li', { class: `crow crow--${r.change}` },
    h('div', { class: 'crow-name' }, name, copyButton(r.name, { label: 'Copy property name', target: name })),
    h('div', { class: 'crow-tags' }, r.kinds.map((k) => chip(k)), phaseBadge(s.p)),
    h('div', { class: 'crow-delta' }, rowDetails(r)),
    r.desc ? h('p', { class: 'crow-desc' }, richText(r.desc)) : null);
}

/** A label cell for a delta line: plain text becomes the small-caps key, a node (e.g. a chip) is used as is. */
const deltaKey = (label) => (label == null ? null : label instanceof Node ? h('span', { class: 'delta-k delta-k--chip' }, label) : h('span', { class: 'delta-k' }, label));

function delta(label, from, to) {
  return h('div', { class: 'delta' },
    deltaKey(label),
    h('span', { class: 'v-old' }, from),
    h('span', { class: 'arrow', 'aria-hidden': 'true' }, '→'),
    h('span', { class: 'sr-only' }, ' to '),
    h('span', { class: 'v-new' }, to));
}

/** Detail line for one change kind. `label` defaults to the kind's name; pass a chip to use it instead. */
function deltaLine(kind, a, b, label = KIND_LABEL[kind]) {
  switch (kind) {
    case 'default':
      return delta(label, valueEl(a.d), valueEl(b.d));
    case 'type':
      return delta(label, h('code', { class: 'val' }, core.typeLabel(a)), h('code', { class: 'val' }, core.typeLabel(b)));
    case 'phase':
      return delta(label, phaseText(a.p), phaseText(b.p));
    case 'deprecated':
      return h('div', { class: 'delta' }, deltaKey(label), b.x ? `since ${b.x}` : 'no version given');
    case 'undeprecated':
      return h('div', { class: 'delta' }, deltaKey(label), 'no longer marked as deprecated');
    case 'enum': {
      const d = core.enumDelta(a, b);
      return h('div', { class: 'delta' }, deltaKey(label === KIND_LABEL.enum ? 'values' : label),
        d.added.map((v) => h('code', { class: 'val v-add', title: 'newly accepted' }, `+${v}`)),
        d.removed.map((v) => h('code', { class: 'val v-rem', title: 'no longer accepted' }, `−${v}`)));
    }
    case 'extension':
      return delta(label, h('code', { class: 'val' }, a.e ?? '?'), h('code', { class: 'val' }, b.e ?? '?'));
    default:
      return null;
  }
}

/** One line describing a state: type, default, accepted values. */
function stateLine(label, s) {
  const en = Array.isArray(s.en) && s.en.length ? s.en : null;
  return h('div', { class: 'delta' },
    deltaKey(label),
    h('code', { class: 'val' }, core.typeLabel(s)),
    h('span', { class: 'delta-sep' }, 'default'), valueEl(s.d),
    en ? frag(h('span', { class: 'delta-sep' }, 'values'), h('span', { class: 'enum-list' }, en.slice(0, 8).join(' | '), en.length > 8 ? ` | … ${en.length - 8} more` : '')) : null,
    s.x != null ? chip('deprecated', s.x ? `deprecated since ${s.x}` : 'deprecated') : null);
}

function rowDetails(r) {
  if (r.change === 'added') return stateLine('new', r.b);
  if (r.change === 'removed') {
    return frag(
      stateLine('was', r.a),
      r.replacement
        ? h('div', { class: 'delta delta--hint' },
          h('span', { class: 'delta-k' }, 'replaced by?'),
          h('a', { class: 'mono', href: link.property(r.replacement) }, r.replacement),
          h('span', { class: 'heuristic' }, 'possible replacement (heuristic: same extension, type and last segment)'))
        : null);
  }
  return frag(
    r.kinds.map((k) => deltaLine(k, r.a, r.b)),
    r.via
      ? h('div', { class: 'delta delta--hint' }, h('span', { class: 'delta-k' }, 'listed as'), h('code', { class: 'val' }, r.via),
        h('span', { class: 'heuristic' }, 'in the older snapshot; matched to this key through aliases'))
      : null);
}

/* ---------- §3 dependencies ---------- */

const BOM_CHIPS = [['downgrade', 'downgrade'], ['major', 'major'], ['minor', 'minor'], ['patch', 'patch'], ['other', 'qualifier'], ['added', 'added'], ['removed', 'removed']];

function depsSection(cmp, data) {
  const sec = h('section', { class: 'sec', id: 'sec-deps', 'aria-labelledby': 'sec-deps-h' },
    sectionHead(3, 'Dependencies', 'sec-deps-h',
      h('p', { class: 'sec-note' }, 'Versions managed by ', h('code', null, 'io.quarkus:quarkus-bom'), '. Artifacts that share a groupId and the same version change are listed together.')));
  if (!cmp.bom) return add(sec, [missingData('bom.json', data.errors.bom)]);
  const d = cmp.bom;
  if (d.modules) {
    sec.append(h('p', { class: 'modules-line' },
      h('span', { class: 'modules-n' }, fmt(d.modules)),
      h('span', null, ' Quarkus modules '), h('span', { class: 'ver' }, short(cmp.A.v)), h('span', { class: 'arrow', 'aria-hidden': 'true' }, ' → '), h('span', { class: 'sr-only' }, ' to '),
      h('span', { class: 'ver' }, short(cmp.B.v)), '.',
      h('span', { class: 'muted' }, ' These io.quarkus artifacts follow the platform version in both releases and are not listed below.')));
  }
  if (!d.groups.length) return add(sec, [emptyNote('No other managed dependency changed version.')]);
  const state = { tags: new Set(), text: '' };
  const status = h('p', { class: 'filter-status', 'aria-live': 'polite' });
  const list = h('div', { class: 'dgroups' });
  const total = d.rows.length;
  const update = () => {
    const words = state.text.toLowerCase().split(/\s+/).filter(Boolean);
    const shown = [];
    let n = 0;
    for (const g of d.groups) {
      if (state.tags.size && !state.tags.has(g.kind)) continue;
      let items = g.items;
      if (words.length) {
        const base = `${g.group}\n${g.from ?? ''}\n${g.to ?? ''}\n`.toLowerCase();
        items = g.items.filter((it) => words.every((w) => (base + it.id.toLowerCase()).includes(w)));
        if (!items.length) continue;
      }
      shown.push([g, items]);
      n += items.length;
    }
    renderDepGroups(list, shown);
    status.textContent = n === total ? `${plural(total, 'artifact')} in ${plural(d.groups.length, 'group')}.` : `Showing ${fmt(n)} of ${plural(total, 'artifact')}.`;
  };
  sec.append(
    h('div', { class: 'toolbar' },
      filterChips(BOM_CHIPS, d.counts, state.tags, update, 'Show only these kinds of dependency change'),
      textFilter('Filter by groupId, artifactId or version', (v) => {
        state.text = v;
        update();
      })),
    status,
    list);
  update();
  return sec;
}

function renderDepGroups(container, groups) {
  container.replaceChildren();
  if (!groups.length) {
    container.append(emptyNote('No dependency change matches these filters.'));
    return;
  }
  let shown = 0;
  const more = moreButton('', () => {
    const first = appendPage();
    first?.querySelector('a, button')?.focus();
  });
  const appendPage = () => {
    const f = document.createDocumentFragment();
    let first = null;
    const end = Math.min(groups.length, shown + DEP_GROUP_PAGE);
    for (; shown < end; shown++) {
      const el = depGroup(groups[shown][0], groups[shown][1]);
      first = first ?? el;
      f.append(el);
    }
    more.remove();
    container.append(f);
    const rest = groups.length - shown;
    if (rest > 0) {
      more.textContent = `Show ${plural(rest, 'more group')}`;
      container.append(more);
    }
    return first;
  };
  appendPage();
}

function depVersions(g) {
  if (g.kind === 'added') return h('span', { class: 'dgroup-vers' }, h('span', { class: 'delta-sep' }, 'added at'), h('span', { class: 'v-new' }, g.to));
  if (g.kind === 'removed') return h('span', { class: 'dgroup-vers' }, h('span', { class: 'delta-sep' }, 'was'), h('span', { class: 'v-old' }, g.from));
  return h('span', { class: 'dgroup-vers' },
    h('span', { class: 'v-old' }, g.from), h('span', { class: 'arrow', 'aria-hidden': 'true' }, ' → '), h('span', { class: 'sr-only' }, ' to '), h('span', { class: 'v-new' }, g.to));
}

function depGroup(g, items) {
  const single = g.items.length === 1;
  const artLabel = (it) => (it.extra ? `${it.artifact}:${it.extra}` : it.artifact);
  const head = h('div', { class: 'dgroup-head' },
    chip(g.kind),
    single ? h('a', { class: 'dgroup-id', href: link.artifact(items[0].id) }, items[0].id) : h('span', { class: 'dgroup-id' }, g.group),
    depVersions(g),
    single ? null : h('span', { class: 'dgroup-n' }, items.length === g.items.length ? plural(g.items.length, 'artifact') : `${fmt(items.length)} of ${plural(g.items.length, 'artifact')}`));
  const el = h('div', { class: `dgroup dgroup--${g.kind}` }, head);
  if (!single) {
    const ul = h('ul', { class: 'dgroup-items' });
    const fill = (from, to) => {
      const f = document.createDocumentFragment();
      for (let i = from; i < to; i++) f.append(h('li', null, h('a', { href: link.artifact(items[i].id) }, artLabel(items[i]))));
      return f;
    };
    const initial = Math.min(items.length, DEP_ITEMS_CAP);
    ul.append(fill(0, initial));
    el.append(ul);
    if (items.length > initial) {
      const btn = moreButton(`Show all ${fmt(items.length)}`, () => {
        ul.append(fill(initial, items.length));
        btn.remove();
        ul.children[initial]?.querySelector('a')?.focus();
      });
      btn.classList.add('more--inline');
      el.append(btn);
    }
  }
  return el;
}

/* ---------- §4 extensions ---------- */

function cappedList(cls, items, renderItem) {
  const ul = h('ul', { class: cls });
  const initial = Math.min(items.length, LIST_CAP);
  for (let i = 0; i < initial; i++) ul.append(renderItem(items[i]));
  const out = frag(ul);
  if (items.length > initial) {
    const btn = moreButton(`Show all ${fmt(items.length)}`, () => {
      const f = document.createDocumentFragment();
      for (let i = initial; i < items.length; i++) f.append(renderItem(items[i]));
      ul.append(f);
      btn.remove();
      ul.children[initial]?.querySelector('a')?.focus();
    });
    out.append(btn);
  }
  return out;
}

function extSection(cmp, data) {
  const V = store.V;
  const { ea, eb } = cmp;
  const note = h('p', { class: 'sec-note' }, 'Metadata from each extension\'s quarkus-extension.yaml',
    ea != null && eb != null
      ? frag(', compared at ', h('span', { class: 'ver' }, short(V.list[ea].v)), ' → ', h('span', { class: 'ver' }, short(V.list[eb].v)),
        snapshotReason(cmp, 'extension'))
      : null,
    '.');
  const sec = h('section', { class: 'sec', id: 'sec-ext', 'aria-labelledby': 'sec-ext-h' }, sectionHead(4, 'Extensions', 'sec-ext-h', note));
  if (!data.extensions) return add(sec, [missingData('extensions.json', data.errors.extensions)]);
  const gap = gapText(cmp, 'extension');
  if (gap) return add(sec, [emptyNote(gap)]);
  if (!cmp.ext) return add(sec, [emptyNote('No extension data for this pair of releases.')]);
  if (ea === eb) return add(sec, [emptyNote(sameSnapshotText(cmp, 'extension'))]);
  const e = cmp.ext;
  if (!e.added.length && !e.removed.length && !e.changed.length) return add(sec, [emptyNote('No extension was added, removed, relocated, renamed or changed status.')]);
  const extLink = (id) => h('a', { class: 'mono', href: link.extension(id) }, id);
  const block = (title, items, renderItem) => (items.length
    ? h('div', { class: 'eblock' }, h('h3', { class: 'eblock-title' }, title, h('span', { class: 'eblock-n' }, fmt(items.length))), cappedList('erows', items, renderItem))
    : null);
  const relocated = e.changed.filter((c) => c.kinds.includes('relocated'));
  const statusChanged = e.changed.filter((c) => c.kinds.includes('status'));
  const renamed = e.changed.filter((c) => c.kinds.includes('renamed'));
  const unrelocated = e.changed.filter((c) => c.kinds.includes('unrelocated'));
  sec.append(h('div', { class: 'eblocks' },
    block('Relocated', relocated, (c) => h('li', { class: 'erow' }, extLink(c.id), h('span', { class: 'arrow', 'aria-hidden': 'true' }, '→'), h('span', { class: 'sr-only' }, ' relocated to '),
      h('a', { class: 'mono', href: link.artifact(c.b.r) }, c.b.r), h('span', { class: 'erow-note' }, 'change your dependency to the new artifact'))),
    block('Removed', e.removed, (c) => h('li', { class: 'erow' }, extLink(c.id), c.a.n ? h('span', { class: 'erow-name' }, c.a.n) : null, c.a.r ? h('span', { class: 'erow-note' }, `was a relocation to ${c.a.r}`) : statusChip(c.a.s))),
    block('Status changed', statusChanged, (c) => h('li', { class: 'erow' }, extLink(c.id), h('span', { class: 'erow-name' }, c.b.n ?? ''),
      statusChip(c.a.s) ?? h('em', { class: 'none' }, 'none'), h('span', { class: 'arrow', 'aria-hidden': 'true' }, '→'), h('span', { class: 'sr-only' }, ' to '), statusChip(c.b.s) ?? h('em', { class: 'none' }, 'none'))),
    block('Renamed', renamed, (c) => h('li', { class: 'erow' }, extLink(c.id), h('span', { class: 'v-old' }, c.a.n), h('span', { class: 'arrow', 'aria-hidden': 'true' }, '→'), h('span', { class: 'sr-only' }, ' to '), h('span', { class: 'v-new' }, c.b.n))),
    block('No longer relocated', unrelocated, (c) => h('li', { class: 'erow' }, extLink(c.id), h('span', { class: 'erow-note' }, `was relocated to ${c.a.r}`))),
    block('Added', e.added, (c) => h('li', { class: 'erow' }, extLink(c.id), c.b.n ? h('span', { class: 'erow-name' }, c.b.n) : null,
      c.b.r ? h('span', { class: 'erow-note' }, `relocation to ${c.b.r}`) : statusChip(c.b.s)))));
  return sec;
}

/* ---------- §5 classes ---------- */

const CLASS_CHIPS = [['added', 'added'], ['removed', 'removed'], ['moved', 'moved']];

/** The class diff of a comparison and its rows per jar, computed once (the comparison keeps them). */
function classDiff(cmp, classes) {
  if (!cmp.classes) {
    cmp.classes = core.diffClasses(classes.raw, cmp.la, cmp.lb);
    cmp.classRows = core.classRows(cmp.classes, classes.raw);
  }
  return cmp.classes;
}

/** Links to the artifact pages of jars (artifactIds), separated by commas. */
function jarLinks(artifactIds) {
  return frag(artifactIds.map((a, i) => frag(i ? ', ' : null, h('a', { class: 'mono', href: link.extension(a) }, a))));
}

/** A binary class name: its package in a lighter colour, then its name in the package. */
function classNameEl(name) {
  const pkg = core.classPackage(name);
  return frag(pkg ? h('span', { class: 'cls-pkg' }, `${pkg}.`) : null, core.simpleClassName(name));
}

/**
 * §5: the classes added, removed and moved, grouped by jar. The section is in the page at once and fills in when the
 * classes files of the two releases' major lines arrive: the comparison does not wait for them.
 */
function classesSection(cmp) {
  const V = store.V;
  const { la, lb } = cmp;
  const note = h('p', { class: 'sec-note' }, 'The classes of the ', h('code', null, 'io.quarkus'), ' jars that ', h('code', null, 'quarkus-bom'),
    ' manages, nested ones included',
    la != null && lb != null
      ? frag(', compared at ', h('span', { class: 'ver' }, short(V.list[la].v)), ' → ', h('span', { class: 'ver' }, short(V.list[lb].v)), snapshotReason(cmp, 'classes'))
      : null,
    '. A class that moved is listed in the jar it left and in the jar it reached.');
  const sec = h('section', { class: 'sec', id: 'sec-classes', 'aria-labelledby': 'sec-classes-h' }, sectionHead(5, 'Classes', 'sec-classes-h', note));
  const gap = gapText(cmp, 'classes');
  if (gap) return add(sec, [emptyNote(gap)]);
  if (la === lb) return add(sec, [emptyNote(sameSnapshotText(cmp, 'classes'))]);
  const lines = core.classLinesOf(V.list, [la, lb]);
  const slot = h('div', { class: 'cls-slot' }, h('p', { class: 'empty', role: 'status' }, 'Loading the classes…'));
  sec.append(slot);
  loadClasses(lines).then((classes) => {
    classDiff(cmp, classes);
    slot.replaceChildren(classChanges(cmp));
  }).catch((e) => slot.replaceChildren(missingData(lines.map((major) => `classes-${major}.json`).join(' or '), e?.message)));
  return sec;
}

/** The filters and the groups of the Classes section. Nested classes are left out until their switch is on. */
function classChanges(cmp) {
  const d = cmp.classes;
  const rows = cmp.classRows;
  if (!rows.length) return emptyNote('No class was added, removed or moved between these snapshots.');
  const state = { tags: new Set(), text: '', nested: false, expanded: new Set() };
  const status = h('p', { class: 'filter-status', 'aria-live': 'polite' });
  const groupsEl = h('div', { class: 'cgroups' });
  const nestedCount = d.counts.added + d.counts.removed + d.counts.moved - d.top.added - d.top.removed - d.top.moved;
  const chips = h('div', { class: 'cls-chips' });
  const update = () => {
    const pool = state.nested ? rows : rows.filter((r) => !r.nested);
    const filtered = core.filterRows(pool, state);
    const filtering = state.text.trim() !== '';
    renderGroupPages(groupsEl, [...core.groupBy(filtered, (r) => r.artifact)], (jar, list) => classGroup(jar, list, state, filtering),
      'No class change matches these filters.', 'more jar');
    const jars = new Set(filtered.map((r) => r.artifact)).size;
    status.textContent = filtered.length === pool.length
      ? `${plural(pool.length, 'change')} in ${plural(jars, 'jar')}${state.nested || !nestedCount ? '' : ', nested classes left out'}.`
      : `Showing ${fmt(filtered.length)} of ${plural(pool.length, 'change')}, in ${plural(jars, 'jar')}.`;
  };
  const fillChips = () => chips.replaceChildren(filterChips(CLASS_CHIPS, state.nested ? d.counts : d.top, state.tags, update,
    'Show only these kinds of class change'));
  const nested = h('button', {
    type: 'button', class: 'fchip tone-neu', 'aria-pressed': 'false', disabled: nestedCount === 0,
    title: 'Also list the nested classes: member, local and anonymous classes, whose names have a $',
  }, h('span', { class: 'fchip-dot', 'aria-hidden': 'true' }), 'nested classes', h('span', { class: 'fchip-n' }, fmt(nestedCount)));
  nested.addEventListener('click', () => {
    state.nested = !state.nested;
    nested.setAttribute('aria-pressed', String(state.nested));
    fillChips();
    update();
  });
  fillChips();
  update();
  return frag(
    h('div', { class: 'toolbar' },
      h('div', { class: 'cls-filters' }, chips, nested),
      textFilter('Filter by class, package or jar', (v) => {
        state.text = v;
        update();
      })),
    status,
    groupsEl);
}

function classGroup(jar, list, state, filtering) {
  let added = 0;
  let removed = 0;
  let moved = 0;
  for (const r of list) {
    if (r.change === 'added') added++;
    else if (r.change === 'removed') removed++;
    else moved++;
  }
  const cap = filtering ? ROW_CAP_FILTERED : ROW_CAP;
  const initial = state.expanded.has(jar) ? list.length : Math.min(list.length, cap);
  const ul = h('ul', { class: 'crows' });
  const fill = (from, to) => {
    const f = document.createDocumentFragment();
    for (let i = from; i < to; i++) f.append(classRow(list[i]));
    return f;
  };
  ul.append(fill(0, initial));
  const sec = h('section', { class: 'cgroup', 'aria-label': `${jar}: ${plural(list.length, 'change')}` },
    h('div', { class: 'cgroup-head' },
      h('h3', { class: 'cgroup-title' }, h('a', { href: link.extension(jar) }, jar)),
      h('span', { class: 'cgroup-counts' },
        countMark('add', '+', added, 'added'),
        countMark('rem', '−', removed, 'removed'),
        countMark('chg', '~', moved, 'moved in or out'))),
    ul);
  if (list.length > initial) {
    const btn = moreButton(`Show all ${plural(list.length, 'change')} in ${jar}`, () => {
      state.expanded.add(jar);
      ul.append(fill(initial, list.length));
      btn.remove();
      ul.children[initial]?.querySelector('a')?.focus();
    });
    sec.append(btn);
  }
  return sec;
}

function classRow(r) {
  const name = h('a', { class: 'prop', href: link.class(r.name) }, classNameEl(r.name));
  const moved = r.change === 'moved-in' || r.change === 'moved-out';
  return h('li', { class: `crow crow--${moved ? 'changed' : r.change}` },
    h('div', { class: 'crow-name' }, name, copyButton(r.name, { label: 'Copy class name', target: name })),
    h('div', { class: 'crow-tags' }, chip(r.change), r.nested ? chip('neutral', 'nested', 'A member, local or anonymous class') : null),
    r.other.length
      ? h('div', { class: 'crow-delta' }, h('div', { class: 'delta' }, deltaKey(r.change === 'moved-in' ? 'was in' : 'now in'), h('span', null, jarLinks(r.other))))
      : null);
}

/* ========================================================================== */
/* Property                                                                    */
/* ========================================================================== */

function findProperty(cfg, key) {
  const direct = cfg.byKey.get(key) ?? cfg.byKey.get(core.canonicalKey(key));
  if (direct) return direct;
  const ck = core.canonicalKey(key);
  for (const p of cfg.raw.props) {
    for (const [, si] of p.h) {
      if (si == null) continue;
      const s = cfg.raw.states[si];
      if (s.a && s.a.some((a) => a === key || core.canonicalKey(a) === ck)) return p;
    }
  }
  return null;
}

function presentInLabel(hist) {
  const V = store.V;
  const streamOf = (vi) => V.list[vi].stream || core.stream(V.list[vi].v);
  return hist.ranges.map((r) => (r.from === r.to ? streamOf(r.from) : `${streamOf(r.from)} – ${streamOf(r.to)}`)).join(', ');
}

async function renderProperty(r) {
  const V = store.V;
  const cfg = await loadFile('config');
  const prop = findProperty(cfg, r.key);
  if (!prop) {
    return notFoundView('Unknown config property', `No property named ${r.key} appears in any config snapshot of this dataset.`,
      null, h('div', { class: 'nf-search' }, searchBox({ id: nextId('qs-nf') })));
  }
  if (prop.k !== r.key) canonicalize({ page: 'property', key: prop.k });
  const hist = core.propertyHistory(cfg.raw, prop, { vis: currentVis(), widen: widenSteps() });
  const li = core.latestValue(prop.h);
  const s = hist.latest ?? (li == null ? {} : cfg.raw.states[li]);
  const cvs = hist.cvs;
  const lastCv = cvs[cvs.length - 1];
  const lastRange = hist.ranges[hist.ranges.length - 1];
  const desc = cfg.raw.desc[prop.k];
  const name = s.n ?? prop.k;
  const env = core.envVar(name);
  const deprecated = s.x != null;

  const statusLine = hist.present
    ? h('p', { class: 'status-line' }, chip('added', 'current'), ` Present in the latest config snapshot (${short(V.list[lastCv].v)}).`)
    : h('p', { class: 'status-line' }, chip('removed', 'removed'), lastRange
      ? ` Not in the latest config snapshot; last seen in ${short(V.list[lastRange.to].v)}.`
      : ' Not in any config snapshot shown.');
  const widenedNote = hist.widened
    ? h('p', { class: 'note' }, `This property only appears in snapshots of ${widenedKinds(hist.widenedBy)}, so they are shown here although they are hidden elsewhere.`)
    : null;
  const tools = switchRow('sec-tools', snapshotSwitches('timeline', V.configVis), { rerender: true });

  const kv = h('dl', { class: 'kv' });
  const row = (k, ...v) => kv.append(h('dt', null, k), h('dd', null, v));
  row('Type', h('code', { class: 'val' }, core.typeLabel(s)), s.o ? h('span', { class: 'muted' }, ' optional') : null);
  row('Default', valueEl(s.d));
  row('Phase', phaseBadge(s.p) ?? h('em', { class: 'none' }, 'unknown'), core.phaseInfo(s.p) ? h('span', { class: 'kv-sub' }, core.phaseInfo(s.p).long) : null);
  if (Array.isArray(s.en) && s.en.length) row('Accepted values', h('span', { class: 'enum-chips' }, s.en.map((v) => h('code', { class: 'val' }, v))));
  if (deprecated) row('Deprecated', s.x ? `since ${s.x}` : 'yes (no version given)');
  row('Environment variable', copyable(env, 'Copy environment variable'),
    /"/.test(name) ? h('span', { class: 'kv-sub' }, 'Quoted segments are placeholders: replace them with your own name before converting.') : null);
  if (s.a && s.a.length) row(s.a.length === 1 ? 'Also accepted as' : 'Also accepted as', h('ul', { class: 'alias-list' }, s.a.map((a) => h('li', null, copyable(a, 'Copy alias')))));
  if (prop.k !== name) row('Key', h('code', { class: 'val' }, prop.k), h('span', { class: 'kv-sub' }, 'Canonical key used to track this property: quoted segments become *.'));
  row('Extension', s.e ? h('a', { class: 'mono', href: link.extension(s.e) }, s.e) : h('em', { class: 'none' }, 'unknown'));
  row('Present in', h('span', { class: 'mono' }, presentInLabel(hist)), hist.present ? h('span', { class: 'muted' }, ' (current)') : null,
    h('span', { class: 'kv-sub' }, `Config snapshots: ${hist.ranges.map((x) => (x.from === x.to ? short(V.list[x.from].v) : `${short(V.list[x.from].v)} … ${short(V.list[x.to].v)}`)).join(', ')}`));

  const nameEl = h('span', { class: 'prop-name' }, name);
  return frag(
    h('header', { class: 'page-head band' },
      h('p', { class: 'kicker' }, 'Config property', s.e ? frag(' · ', h('a', { href: link.extension(s.e) }, s.e)) : null),
      h('h1', { class: 'page-title page-title--mono', tabindex: '-1' }, nameEl, copyButton(name, { label: 'Copy property name', target: nameEl })),
      h('div', { class: 'badges' },
        phaseBadge(s.p),
        h('code', { class: 'val' }, core.typeLabel(s)),
        deprecated ? chip('deprecated', s.x ? `deprecated since ${s.x}` : 'deprecated') : null,
        hist.present ? null : chip('removed')),
      desc ? h('p', { class: 'lede' }, richText(desc)) : null),
    statusLine,
    kv,
    h('section', { class: 'sec', 'aria-labelledby': 'presence-h' },
      sectionHead(1, 'Presence', 'presence-h', h('p', { class: 'sec-note' }, 'One cell per config snapshot, oldest on the left.', hiddenSnapshotsNote(hist, V.configVis)),
        tools),
      widenedNote,
      presenceStrip(prop, hist, cvs, cfg.raw.states)),
    h('section', { class: 'sec', 'aria-labelledby': 'history-h' },
      sectionHead(2, 'History', 'history-h', h('p', { class: 'sec-note' }, 'Every change between consecutive config snapshots shown.')),
      timeline(hist.events, (ev) => propertyEventBody(ev, hist.cvs[0] === V.configVis[0]))));
}

/** " 2 pre-release snapshots are hidden." when the switch is off and the history skipped some. */
function hiddenSnapshotsNote(hist, snaps) {
  if (!hist.hiddenSnapshots) return null;
  const shown = new Set(hist.cvs);
  const hidden = snaps.filter((c) => !shown.has(c));
  return ` ${hiddenCount(hidden, 'snapshot')} ${hidden.length === 1 ? 'is' : 'are'} hidden.`;
}

function presenceStrip(prop, hist, cvs, states) {
  const V = store.V;
  const events = new Map(hist.events.map((e) => [e.vi, e]));
  // past about 40 snapshots the cells get thin: dense cells keep the strip inside a phone's width
  const dense = cvs.length > 40;
  const strip = h('ol', { class: dense ? 'strip strip--dense' : 'strip', 'aria-label': 'Presence in each config snapshot' });
  let anyPre = false;
  for (const vi of cvs) {
    const si = core.stateAt(prop.h, vi);
    const s = si == null ? null : states[si];
    const ev = events.get(vi);
    const pre = preLabel(V.list[vi]);
    const cls = ['strip-cell', s ? 'is-on' : 'is-off'];
    if (s && s.x != null) cls.push('is-dep');
    if (ev) cls.push(`ev-${ev.type}`);
    if (pre) {
      cls.push('is-pre');
      anyPre = true;
    }
    const what = s ? (s.x != null ? 'present, deprecated' : 'present') : 'absent';
    const evText = ev ? (ev.type === 'changed' ? `changed: ${ev.kinds.map((k) => KIND_LABEL[k]).join(', ')}` : ev.type) : '';
    const label = `${short(V.list[vi].v)}${pre ? ` (${pre})` : ''}: ${what}${evText ? ` (${evText})` : ''}`;
    strip.append(h('li', { class: cls.join(' '), title: label }, h('span', { class: 'sr-only' }, label)));
  }
  const first = V.list[cvs[0]];
  const last = V.list[cvs[cvs.length - 1]];
  return frag(
    h('div', { class: 'strip-wrap', style: `max-width:${Math.max(240, cvs.length * 46)}px` },
      strip,
      h('div', { class: 'strip-axis', 'aria-hidden': 'true' }, h('span', null, short(first.v)), h('span', null, short(last.v)))),
    h('p', { class: dense ? 'legend legend--dense' : 'legend', 'aria-hidden': 'true' },
      h('span', null, h('i', { class: 'lg lg-on' }), 'present'),
      h('span', null, h('i', { class: 'lg lg-dep' }), 'deprecated'),
      h('span', null, h('i', { class: 'lg lg-off' }), 'absent'),
      h('span', null, h('i', { class: 'lg lg-ev' }), 'changed in this snapshot'),
      anyPre ? h('span', null, h('i', { class: 'lg lg-pre' }), 'pre-release snapshot') : null));
}

/** `first`: the history starts at the dataset's first config snapshot, not at the first one the switches show. */
function propertyEventBody(ev, first = true) {
  switch (ev.type) {
    case 'present':
      return frag(h('p', { class: 'tl-text' }, chip('neutral', 'present'), first
        ? ' In the first config snapshot of this dataset; it may be older.'
        : ' In the first config snapshot shown; earlier ones are hidden, so it may be older.'), stateLine(null, ev.state));
    case 'introduced':
      return frag(h('p', { class: 'tl-text' }, chip('added', 'introduced')), stateLine(null, ev.state));
    case 'reintroduced':
      return frag(h('p', { class: 'tl-text' }, chip('added', 'reintroduced')), stateLine(null, ev.state));
    case 'removed':
      return h('p', { class: 'tl-text' }, chip('removed'), ' No longer present in this snapshot.');
    default:
      return frag(ev.kinds.map((k) => deltaLine(k, ev.from, ev.to, chip(k))));
  }
}

function timeline(events, body) {
  const V = store.V;
  return h('ol', { class: 'tl' }, events.map((ev) => {
    const x = V.list[ev.vi];
    return h('li', { class: `tl-item tl-${ev.type}${core.isPreRelease(x) ? ' tl-pre' : ''}` },
      h('div', { class: 'tl-when' }, h('span', { class: 'tl-ver' }, verLink(x.v), preChip(x)), dateEl(x.date)),
      h('div', { class: 'tl-what' }, body(ev)));
  }));
}

/* ========================================================================== */
/* Artifact (and extension details for io.quarkus artifacts)                   */
/* ========================================================================== */

async function renderArtifact(r) {
  const V = store.V;
  const bomData = await loadSettled(['bom']);
  const bom = bomData.bom;
  let id = r.id;
  if (bom && !bom.byId.has(id) && !id.includes(':') && bom.byId.has(`io.quarkus:${id}`)) {
    id = `io.quarkus:${id}`;
    canonicalize({ page: 'artifact', id });
  }
  const { group, artifact } = core.splitId(id);
  const isQuarkus = group === 'io.quarkus';
  const more = isQuarkus ? await loadSettled(['extensions', 'config']) : { errors: {} };
  const extEntry = more.extensions?.byId.get(artifact) ?? null;
  const art = bom?.byId.get(id) ?? null;
  if (!art && !extEntry) {
    if (!bom) return errorView(new Error(bomData.errors.bom));
    return notFoundView('Unknown artifact', `${id} is not managed by quarkus-bom in any release of this dataset.`,
      null, h('div', { class: 'nf-search' }, searchBox({ id: nextId('qs-nf') })));
  }
  const vis = currentVis();
  const hist = art ? core.artifactHistory(bom.raw, id, V.list, { vis, widen: widenSteps() }) : null;
  const last = V.list[hist ? hist.last : vis.indices[vis.indices.length - 1]];
  const idEl = h('span', { class: 'prop-name' }, id);
  // With a BOM history the switches filter releases; on an extension-only page they filter extension snapshots.
  const tools = switchRow('sec-tools', hist ? releaseSwitches('timeline') : snapshotSwitches('timeline', V.extVis), { rerender: true });
  let status = null;
  if (hist) {
    if (hist.current.managed) {
      status = h('p', { class: 'status-line' }, chip('added', 'managed'), ' In ', verLink(last.v), hist.current.follows
        ? frag(' at ', h('code', { class: 'val' }, hist.current.value), ', the Quarkus release version.')
        : frag(' at ', h('code', { class: 'val' }, hist.current.value), '.'));
    } else {
      status = h('p', { class: 'status-line' }, chip('removed', 'not managed'), ` Not managed in ${short(last.v)}`,
        hist.lastManaged != null ? frag('; last managed in ', verLink(V.list[hist.lastManaged].v), '.') : '.');
    }
  }
  return frag(
    h('header', { class: 'page-head band' },
      h('p', { class: 'kicker' }, extEntry ? 'Quarkus extension · managed dependency' : 'Managed dependency', ' · ', group),
      h('h1', { class: 'page-title page-title--mono', tabindex: '-1' }, idEl, copyButton(id, { label: 'Copy coordinates', target: idEl }))),
    status,
    hist ? artifactRanges(hist, tools) : emptyNote(`${id} never appears in quarkus-bom in this dataset.`),
    extEntry ? extensionPanel(extEntry, more, hist ? null : tools) : null);
}

function rangeLabel(from, to) {
  const V = store.V;
  return from === to ? short(V.list[from].v) : `${short(V.list[from].v)} – ${short(V.list[to].v)}`;
}

/** "5 releases", "5 releases, 1 pre-release", "1 pre-release". */
function rangeCount(r) {
  if (r.pre && r.pre === r.count) return plural(r.count, 'pre-release');
  return `${plural(r.count, 'release')}${r.pre ? `, ${plural(r.pre, 'pre-release')}` : ''}`;
}

function artifactRanges(hist, tools) {
  const V = store.V;
  const total = hist.indices.length;
  const first = V.list[hist.indices[0]];
  const last = V.list[hist.indices[total - 1]];
  const lane = h('div', { class: 'lane', role: 'img', 'aria-label': `Managed version across ${total} releases, oldest on the left. The table below lists the same ranges.` });
  hist.ranges.forEach((r, i) => {
    const label = !r.managed ? '' : r.follows ? 'Quarkus' : r.value;
    const title = `${rangeLabel(r.from, r.to)}: ${!r.managed ? 'not managed' : r.follows ? 'same as the Quarkus release' : r.value}`;
    lane.append(h('div', { class: `lane-seg${r.managed ? '' : ' is-off'}${r.follows ? ' is-follow' : ''}${i % 2 ? ' is-alt' : ''}`, style: `flex-grow:${r.count}`, title },
      label ? h('span', { class: 'lane-label' }, label) : null));
  });
  const tbody = h('tbody');
  for (const r of hist.ranges) {
    const value = !r.managed
      ? h('em', { class: 'none' }, r.before ? 'not yet managed' : 'not managed')
      : r.follows
        ? frag(h('span', { class: 'follows' }, 'same as the Quarkus release'), h('span', { class: 'cell-sub' }, `${short(V.list[r.from].v)} … ${short(V.list[r.to].v)}`))
        : h('code', { class: 'val' }, r.value);
    tbody.append(h('tr', { class: r.managed ? null : 'is-same' },
      h('th', { scope: 'row', class: 'mono' }, rangeLabel(r.from, r.to), h('span', { class: 'cell-sub' }, rangeCount(r))),
      h('td', null, value),
      h('td', null, r.kind ? chip(r.kind) : null)));
  }
  const hidden = hist.hiddenReleases;
  const shown = new Set(hist.indices);
  const hiddenText = hidden ? hiddenCount(V.list.map((_, i) => i).filter((i) => !shown.has(i))) : '';
  return h('section', { class: 'sec', 'aria-labelledby': 'ranges-h' },
    sectionHead(1, 'Managed versions', 'ranges-h',
      h('p', { class: 'sec-note' }, `Across ${hidden ? '' : 'all '}${plural(total, 'release')}${hidden ? ` (${hiddenText} hidden)` : ''}, oldest first. Each row is a run of releases that manage the same value.`),
      tools),
    hist.widened ? h('p', { class: 'note' }, `Only ${widenedKinds(hist.widenedBy)} manage this artifact, so they are shown here although they are hidden elsewhere.`) : null,
    lane,
    h('div', { class: 'strip-axis', 'aria-hidden': 'true' }, h('span', null, short(first.v)), h('span', null, short(last.v))),
    h('div', { class: 'table-scroll' },
      h('table', { class: 'tbl tbl-ranges' },
        h('thead', null, h('tr', null, h('th', { scope: 'col' }, 'Quarkus releases'), h('th', { scope: 'col' }, 'Managed version'), h('th', { scope: 'col' }, 'Change'))),
        tbody)));
}

/** `first` as for propertyEventBody, over the extension snapshots. */
function extensionEventBody(ev, first = true) {
  switch (ev.type) {
    case 'present':
      return h('p', { class: 'tl-text' }, chip('neutral', 'present'),
        first ? ' In the first extension snapshot of this dataset.' : ' In the first extension snapshot shown; earlier ones are hidden.',
        ev.state.r ? ` Relocated to ${ev.state.r}.` : '');
    case 'introduced':
    case 'reintroduced':
      return h('p', { class: 'tl-text' }, chip('added', ev.type), ev.state.r ? ` As a relocation to ${ev.state.r}.` : statusChip(ev.state.s));
    case 'removed':
      return h('p', { class: 'tl-text' }, chip('removed'), ' No longer in this snapshot.');
    default:
      return frag(ev.kinds.map((k) => {
        if (k === 'relocated') return h('div', { class: 'delta' }, deltaKey(chip(k)), 'to ', h('a', { class: 'mono', href: link.artifact(ev.to.r) }, ev.to.r));
        if (k === 'status') return delta(chip(k), statusChip(ev.from.s) ?? h('em', { class: 'none' }, 'none'), statusChip(ev.to.s) ?? h('em', { class: 'none' }, 'none'));
        if (k === 'renamed') return delta(chip(k), ev.from.n, ev.to.n);
        if (k === 'unrelocated') return h('div', { class: 'delta' }, deltaKey(chip(k)), `was relocated to ${ev.from.r}`);
        return null;
      }));
  }
}

function extensionPanel(x, data, tools) {
  const V = store.V;
  const exts = data.extensions.raw;
  const vis = currentVis();
  const hist = core.extensionHistory(exts, x.id, V.extVis, { vis, widen: widenSteps() });
  const s = hist.latest ?? {};
  const kv = h('dl', { class: 'kv' });
  const row = (k, ...v) => kv.append(h('dt', null, k), h('dd', null, v));
  if (s.n) row('Name', s.n);
  if (s.s) row('Status', statusChip(s.s));
  if (s.d) row('Description', s.d);
  if (s.c && s.c.length) row('Categories', s.c.join(', '));
  if (s.g) row('Guide', h('a', { href: s.g, target: '_blank', rel: 'noopener noreferrer', class: 'ext-link' }, s.g.replace(/^https?:\/\//, ''), icon('external')));
  if (s.r) {
    row('Relocated to', h('a', { class: 'mono', href: link.artifact(s.r) }, s.r), h('span', { class: 'kv-sub' }, 'Depend on the new artifact; the old one only redirects to it.'));
    // A relocation stub carries no metadata: show what the extension was before.
    const before = [...hist.events].reverse().map((ev) => ev.state ?? ev.from).find((st) => st && !st.r && st.n);
    if (before) row('Last listed as', before.n, before.s ? frag(' ', statusChip(before.s)) : null);
  }
  if (s.p && s.p.length) row('Config prefixes', h('span', { class: 'enum-chips' }, s.p.map((p) => h('code', { class: 'val' }, p))));
  if (s.u) row('Listed', 'unlisted (not shown in extension catalogs)');
  row('Present in', h('span', { class: 'mono' }, presentInLabel(hist)), hist.present ? h('span', { class: 'muted' }, ' (current)') : null);

  let propsBlock = null;
  // extension snapshots do not need config, so a dataset may have extensions and no config snapshot at all
  if (data.config && data.config.raw.versions.length) {
    const cfg = data.config.raw;
    const shownCvs = core.visibleOnly(cfg.versions, vis);
    const latest = shownCvs.length ? shownCvs[shownCvs.length - 1] : cfg.versions[cfg.versions.length - 1];
    const owned = [];
    for (const p of cfg.props) {
      const si = core.stateAt(p.h, latest);
      if (si != null && cfg.states[si].e === x.id) owned.push({ k: p.k, s: cfg.states[si] });
    }
    propsBlock = h('section', { class: 'sec', 'aria-labelledby': 'ext-props-h' },
      sectionHead(4, 'Config properties', 'ext-props-h', h('p', { class: 'sec-note' }, `Attributed to ${x.id} in the latest config snapshot (${short(V.list[latest].v)}).`)),
      owned.length
        ? cappedList('plist', owned, (o) => h('li', null, h('a', { class: 'prop', href: link.property(o.k) }, o.s.n ?? o.k), phaseBadge(o.s.p)))
        : emptyNote('None in the latest config snapshot.'));
  }
  return frag(
    h('section', { class: 'sec', 'aria-labelledby': 'ext-h' },
      sectionHead(2, 'Extension', 'ext-h', h('p', { class: 'sec-note' }, 'Metadata from quarkus-extension.yaml at the extension snapshots.')),
      kv),
    h('section', { class: 'sec', 'aria-labelledby': 'ext-history-h' },
      sectionHead(3, 'Extension history', 'ext-history-h',
        h('p', { class: 'sec-note' }, 'Status, name and relocation changes between the extension snapshots shown.', hiddenSnapshotsNote(hist, V.extVis)),
        tools),
      hist.widened ? h('p', { class: 'note' }, `This extension only appears in snapshots of ${widenedKinds(hist.widenedBy)}, so they are shown here although they are hidden elsewhere.`) : null,
      timeline(hist.events, (ev) => extensionEventBody(ev, hist.cvs[0] === V.extVis[0]))),
    propsBlock);
}

/* ========================================================================== */
/* Class                                                                       */
/* ========================================================================== */

/**
 * The classes row a route names: a binary name, or what a reader may write instead: `<artifactId>/<class>`, the
 * internal form (`io/quarkus/runtime/Application`, with or without `.class`), or a name in its package that one class
 * alone has (`QuarkusUnitTest`). Null when nothing matches.
 */
function resolveClass(classes, raw) {
  const text = String(raw ?? '').trim();
  const byName = classes.byName;
  if (byName.has(text)) return byName.get(text);
  const slash = text.indexOf('/');
  if (slash > 0 && !text.slice(0, slash).includes('.') && byName.has(text.slice(slash + 1))) return byName.get(text.slice(slash + 1));
  const internal = text.replace(/\.class$/, '').replace(/\//g, '.');
  if (byName.has(internal)) return byName.get(internal);
  if (text && !/[./]/.test(text)) {
    const matches = classes.raw.classes.filter((row) => row[1] === text);
    if (matches.length === 1) return matches[0];
  }
  return null;
}

async function renderClass(r) {
  const V = store.V;
  // the classes of the lines the switches show; a class that none of them has may be in a hidden one
  let lines = shownClassLines();
  let classes = await loadClasses(lines);
  let row = resolveClass(classes, r.name);
  if (!row && lines.length < V.clsLines.length) {
    lines = V.clsLines;
    classes = await loadClasses(lines);
    row = resolveClass(classes, r.name);
  }
  if (!row) {
    return notFoundView('Unknown class', `No class named ${r.name || '(empty)'} is in any classes snapshot of this dataset.`,
      null, h('div', { class: 'nf-search' }, searchBox({ id: nextId('qs-nf') })));
  }
  const name = core.className(classes.raw, row);
  if (name !== r.name) canonicalize({ page: 'class', name });
  // the history runs over the snapshots of the lines loaded; the others are hidden by the switches anyway
  const loadedVis = V.clsVis.filter((i) => lines.includes(core.majorOf(V.list[i])));
  const hist = core.classHistory(classes.raw, row, loadedVis, { vis: currentVis(), widen: widenSteps(), versions: V.list });
  const jarNames = (jars) => jars.map((j) => classes.raw.artifacts[j]);
  const lastCv = hist.cvs[hist.cvs.length - 1];
  const lastRange = hist.ranges[hist.ranges.length - 1];
  const lastJars = hist.present ? hist.jars : lastRange ? core.jarsOf(core.stateAt(row[2], lastRange.to)) : [];
  const pkg = core.classPackage(name);
  const nested = core.isNestedClass(name);
  const outer = nested ? core.topLevelClass(name) : null;

  // the status line is a flex row: one span per sentence keeps its links and punctuation together
  const statusLine = hist.present
    ? h('p', { class: 'status-line' }, chip('added', 'current'),
      h('span', null, 'In ', jarLinks(jarNames(hist.jars)), ` in the latest classes snapshot (${short(V.list[lastCv].v)}).`))
    : h('p', { class: 'status-line' }, chip('removed', 'removed'), lastRange
      ? h('span', null, `Not in the latest classes snapshot; last seen in ${short(V.list[lastRange.to].v)}, in `, jarLinks(jarNames(lastJars)), '.')
      : ' Not in any classes snapshot shown.');
  const tools = switchRow('sec-tools', snapshotSwitches('timeline', V.clsVis), { rerender: true });
  const widenedNote = hist.widened
    ? h('p', { class: 'note' }, `This class only appears in snapshots of ${widenedKinds(hist.widenedBy)}, so they are shown here although they are hidden elsewhere.`)
    : null;

  const kv = h('dl', { class: 'kv' });
  const kvRow = (k, ...v) => kv.append(h('dt', null, k), h('dd', null, v));
  kvRow('Package', pkg ? h('code', { class: 'val' }, pkg) : h('em', { class: 'none' }, 'the unnamed package'));
  if (outer) kvRow('Nested in', classes.byName.has(outer) ? h('a', { class: 'mono', href: link.class(outer) }, outer) : h('code', { class: 'val' }, outer));
  kvRow(lastJars.length > 1 ? 'Jars' : 'Jar', lastJars.length ? jarLinks(jarNames(lastJars)) : h('em', { class: 'none' }, 'none'),
    lastJars.length > 1 ? h('span', { class: 'kv-sub' }, 'Each of these jars has a class of this name.') : null,
    hist.present ? null : h('span', { class: 'kv-sub' }, 'The last jar that had it.'));
  kvRow('Present in', h('span', { class: 'mono' }, presentInLabel(hist)), hist.present ? h('span', { class: 'muted' }, ' (current)') : null);

  // its nested classes: the names in its package that start with its own and a $
  const prefix = `${row[1]}$`;
  const nestedRows = classes.raw.classes.filter((x) => x[0] === row[0] && x[1].startsWith(prefix));
  const nestedBlock = nestedRows.length
    ? h('section', { class: 'sec', 'aria-labelledby': 'cls-nested-h' },
      sectionHead(3, 'Nested classes', 'cls-nested-h', h('p', { class: 'sec-note' }, `${plural(nestedRows.length, 'class', 'classes')} nested in this one, in any classes snapshot.`)),
      cappedList('plist', nestedRows, (x) => {
        const nestedName = core.className(classes.raw, x);
        const now = lastCv != null && core.jarsOf(core.stateAt(x[2], lastCv)).length > 0;
        return h('li', null, h('a', { class: 'prop', href: link.class(nestedName) }, x[1]), now ? null : chip('removed', 'not in the latest'));
      }))
    : null;

  const nameEl = h('span', { class: 'prop-name' }, classNameEl(name));
  return frag(
    h('header', { class: 'page-head band' },
      h('p', { class: 'kicker' }, nested ? 'Nested class' : 'Class', lastJars.length ? frag(' · ', jarLinks(jarNames(lastJars))) : null),
      h('h1', { class: 'page-title page-title--mono', tabindex: '-1' }, nameEl, copyButton(name, { label: 'Copy class name', target: nameEl })),
      h('div', { class: 'badges' },
        hist.present ? null : chip('removed'),
        nested ? chip('neutral', core.isAnonymousClass(name) ? 'anonymous or local class' : 'nested class') : null)),
    statusLine,
    kv,
    h('section', { class: 'sec', 'aria-labelledby': 'cls-jars-h' },
      sectionHead(1, 'Jars', 'cls-jars-h',
        h('p', { class: 'sec-note' }, 'The jars that have the class in each classes snapshot, oldest first. Each row is a run of snapshots in the same jars.',
          hiddenSnapshotsNote({ cvs: hist.cvs, hiddenSnapshots: V.clsVis.length - hist.cvs.length }, V.clsVis)),
        tools),
      widenedNote,
      classRuns(hist, jarNames)),
    h('section', { class: 'sec', 'aria-labelledby': 'cls-history-h' },
      sectionHead(2, 'History', 'cls-history-h', h('p', { class: 'sec-note' }, 'When the class came, moved to another jar or went, between consecutive classes snapshots shown.')),
      timeline(hist.events, (ev) => classEventBody(ev, jarNames, hist.cvs[0] === V.clsVis[0]))),
    nestedBlock);
}

/** The runs of a class history as a lane and a table: the jars of each run of snapshots, and the change it starts with. */
function classRuns(hist, jarNames) {
  const V = store.V;
  const first = V.list[hist.cvs[0]];
  const last = V.list[hist.cvs[hist.cvs.length - 1]];
  const events = new Map(hist.events.map((ev) => [ev.vi, ev]));
  const lane = h('div', { class: 'lane', role: 'img', 'aria-label': `The jars of the class across ${plural(hist.cvs.length, 'classes snapshot')}, oldest on the left. The table below lists the same runs.` });
  const tbody = h('tbody');
  hist.runs.forEach((run, i) => {
    const jars = jarNames(run.jars).join(', ');
    lane.append(h('div', { class: `lane-seg${run.jars.length ? '' : ' is-off'}${i % 2 ? ' is-alt' : ''}`, style: `flex-grow:${run.count}`, title: `${rangeLabel(run.from, run.to)}: ${jars || 'in no jar'}` },
      jars ? h('span', { class: 'lane-label' }, jars) : null));
    const ev = events.get(run.from);
    const change = !ev || ev.type === 'present' ? null : chip(ev.type);
    tbody.append(h('tr', { class: run.jars.length ? null : 'is-same' },
      h('th', { scope: 'row', class: 'mono' }, rangeLabel(run.from, run.to),
        h('span', { class: 'cell-sub' }, `${plural(run.count, 'snapshot')}${run.pre ? `, ${plural(run.pre, 'pre-release')}` : ''}`)),
      h('td', null, run.jars.length ? jarLinks(jarNames(run.jars)) : h('em', { class: 'none' }, run.before ? 'not yet in any jar' : 'in no jar')),
      h('td', null, change)));
  });
  return frag(
    lane,
    h('div', { class: 'strip-axis', 'aria-hidden': 'true' }, h('span', null, short(first.v)), h('span', null, short(last.v))),
    h('div', { class: 'table-scroll' },
      h('table', { class: 'tbl tbl-ranges' },
        h('thead', null, h('tr', null, h('th', { scope: 'col' }, 'Classes snapshots'), h('th', { scope: 'col' }, 'Jar'), h('th', { scope: 'col' }, 'Change'))),
        tbody)));
}

/** `first` as for propertyEventBody, over the classes snapshots. A sentence with links is one span of the flex row. */
function classEventBody(ev, jarNames, first = true) {
  const jars = (list) => jarLinks(jarNames(list));
  switch (ev.type) {
    case 'present':
      return h('p', { class: 'tl-text' }, chip('neutral', 'present'), h('span', null, first
        ? 'In the first classes snapshot of this dataset, so it may be older: in '
        : 'In the first classes snapshot shown; earlier ones are hidden. In ', jars(ev.jars), '.'));
    case 'introduced':
    case 'reintroduced':
      return h('p', { class: 'tl-text' }, chip(ev.type), h('span', null, 'In ', jars(ev.jars), '.'));
    case 'removed':
      return h('p', { class: 'tl-text' }, chip('removed'), h('span', null, 'No longer in any jar; it was in ', jars(ev.from), '.'));
    default:
      return delta(chip('moved'), jars(ev.from), jars(ev.to));
  }
}

/* ========================================================================== */
/* Versions and version                                                        */
/* ========================================================================== */

async function renderVersions() {
  const V = store.V;
  const vis = currentVis();
  const shownStreams = V.streams.filter((g) => g.items.some((i) => vis.visible[i]));
  const ltsCount = shownStreams.filter((g) => g.lts).length;
  const tbody = h('tbody');
  for (const g of V.streams) {
    const items = g.items.filter((i) => vis.visible[i]);
    if (!items.length) continue;
    tbody.append(h('tr', { class: g.lts ? 'is-lts' : null },
      h('th', { scope: 'row' }, h('span', { class: 'stream' }, g.stream), g.lts ? chip('lts', 'LTS', 'Long-term support stream') : null, g.upcoming ? upcomingChip() : null),
      h('td', null, h('ul', { class: 'rel-list' }, [...items].reverse().map((i) => {
        const x = V.list[i];
        return h('li', { class: core.isPreRelease(x) ? 'is-pre' : null }, h('a', { class: 'rel', href: link.version(x.v) },
          h('span', { class: 'ver' }, short(x.v)),
          dateEl(x.date),
          preChip(x),
          x.config ? h('span', { class: 'cfg-mark', title: `Config snapshot (${x.configSource ?? 'unknown source'})` }, 'config')
            : x.ext ? h('span', { class: 'cfg-mark', title: 'Extension snapshot, no config snapshot' }, 'extensions') : null));
      })))));
  }
  const tools = switchRow('page-tools', releaseSwitches('releases'), { rerender: true });
  const hiddenNow = V.list.map((_, i) => i).filter((i) => !vis.visible[i]);
  const preSnapshots = V.configVis.some((c) => V.visOff.pre[c]);
  const extOnly = V.extVis.some((e) => !V.list[e].config);
  return frag(
    h('header', { class: 'page-head band' },
      h('p', { class: 'kicker' }, 'Releases'),
      h('h1', { class: 'page-title', tabindex: '-1' }, 'All releases'),
      h('p', { class: 'lede' }, `${plural(vis.indices.length, 'release')} in ${plural(shownStreams.length, 'stream')}, newest first`,
        hiddenNow.length ? `, ${hiddenCount(hiddenNow)} hidden` : null, `. ${plural(ltsCount, 'stream')} marked LTS. `,
        'Releases tagged ', h('span', { class: 'cfg-mark' }, 'config'), ` are the config snapshots, taken ${V.configAtNewestFinals ? 'at' : 'for'} `,
        andList([...configCoverage(V), ...(preSnapshots ? ['some pre-releases, which are compared only at their own snapshot'] : [])]), '.',
        extOnly ? frag(' Releases tagged ', h('span', { class: 'cfg-mark' }, 'extensions'), ' have extension data but no config snapshot.') : null),
      tools),
    h('div', { class: 'table-scroll' },
      h('table', { class: 'tbl tbl-streams' },
        h('thead', null, h('tr', null, h('th', { scope: 'col' }, 'Stream'), h('th', { scope: 'col' }, 'Releases'))),
        tbody)));
}

async function renderVersion(r) {
  const V = store.V;
  const i = core.findVersionIndex(V.list, r.v);
  if (i < 0) return notFoundView('Unknown release', `This dataset has no release ${r.v || '(empty)'}.`, h('p', null, h('a', { href: '#/versions' }, 'See all releases')));
  const x = V.list[i];
  if (short(x.v) !== r.v) canonicalize({ page: 'version', v: short(x.v) });
  const isPre = core.isPreRelease(x);
  // the page of an old release, opened while old releases are hidden, still lists the old releases around it
  const nb = core.versionNeighbours(V.json, i, { vis: V.visOn.old[i] ? V.visBy[prefs.showPre ? 1 : 0][1] : currentVis() });
  const cv = core.configVersionFor(i, V.list, V.configVis);
  const ev = core.extensionVersionFor(i, V.list, V.extVis);
  const newestFinal = core.newestFinalIndex(V.list);
  const compares = [];
  const addCompare = (label, j, note) => {
    if (j < 0 || j === i) return;
    const same = compares.find((c) => c[1] === j);
    if (same) {
      same[0] = `${same[0]} and ${label.charAt(0).toLowerCase()}${label.slice(1)}`;
      same[2] = `${same[2]}, also the previous LTS`;
      return;
    }
    compares.push([label, j, note]);
  };
  addCompare('Previous patch', nb.previousPatch, 'same stream');
  addCompare('Previous pre-release', nb.previousPre, isPre ? 'same stream' : 'what changed since the stream\'s last pre-release');
  addCompare('Previous minor', nb.previousMinor, 'newest release of the previous stream');
  addCompare('Previous LTS', nb.previousLts, 'newest release of the previous LTS stream');
  const quick = compares.length
    ? h('ol', { class: 'quick-list' }, compares.map(([label, j, note]) => {
      const A = V.list[j];
      return h('li', null, h('a', { class: 'quick-link', href: link.compare(A.v, x.v) },
        h('span', { class: 'quick-title' }, label, h('span', { class: 'quick-note' }, ` · ${note}`)),
        h('span', { class: 'quick-vers' }, short(A.v), h('span', { class: 'arrow', 'aria-hidden': 'true' }, ' → '), h('span', { class: 'sr-only' }, ' to '), short(x.v)),
        h('span', { class: 'quick-span' }, core.spanApart(A.date, x.date))));
    }))
    : i === 0
      ? emptyNote('This is the oldest release in the dataset, so there is nothing earlier to compare with.')
      : emptyNote(frag('The earlier releases are hidden. Show them with the switches on ', h('a', { href: '#/versions' }, 'All releases'), '.'));
  const forward = newestFinal >= 0 && newestFinal !== i && core.compareVersions(V.list[newestFinal].v, x.v) > 0
    ? h('p', { class: 'forward' }, h('a', { href: link.compare(x.v, V.list[newestFinal].v) }, `Compare ${short(x.v)} with the newest release, ${short(V.list[newestFinal].v)}`))
    : null;

  const kv = h('dl', { class: 'kv' });
  const row = (k, ...v) => kv.append(h('dt', null, k), h('dd', null, v));
  row('Released', dateEl(x.date) ?? h('em', { class: 'none' }, 'unknown'));
  row('Stream', h('span', { class: 'mono' }, x.stream || core.stream(x.v)), x.lts ? frag(' ', chip('lts', 'LTS')) : null);
  const shown = ev == null ? 'dependency changes only' : 'dependency and extension changes only';
  row('Config snapshot', cv == null
    ? isPre
      ? frag(h('em', { class: 'none' }, 'none'), h('span', { class: 'kv-sub' }, `Pre-releases are compared only at their own config snapshot, so comparisons with this release show ${shown}.`))
      : h('em', { class: 'none' }, 'none: no config data for this release')
    : frag(verLink(V.list[cv].v), h('span', { class: 'muted' }, cv === i ? ' (this release)' : ev === cv ? ' (used for config and extension comparisons)' : ' (used for config comparisons)'),
      V.list[cv].configSource ? h('span', { class: 'kv-sub' }, SOURCE_TITLE[V.list[cv].configSource] ?? V.list[cv].configSource) : null));
  // extension snapshots also exist without config (a config snapshot always has one): name the one used when it
  // is not the config snapshot
  if (ev != null && ev !== cv) {
    row('Extension snapshot', verLink(V.list[ev].v), h('span', { class: 'muted' }, ev === i ? ' (this release)' : ' (used for extension comparisons)'));
  }
  const managedN = h('span', null, '…');
  const propsN = h('span', null, '…');
  const extsN = h('span', null, '…');
  row('Managed artifacts', managedN);
  row('Config properties', propsN);
  row('Extensions', extsN);
  loadFile('bom').then((b) => {
    let n = 0;
    let modules = 0;
    for (const a of b.raw.artifacts) {
      const raw = core.stateAt(a.h, i);
      if (raw != null) n++;
      if (raw === '=') modules++;
    }
    managedN.textContent = `${fmt(n)} (${fmt(modules)} Quarkus modules)`;
  }).catch(() => {
    managedN.textContent = 'bom.json unavailable';
  });
  if (cv == null) {
    propsN.textContent = isPre ? 'no config snapshot' : 'no config data';
  } else {
    loadFile('config').then((c) => {
      propsN.textContent = `${fmt(c.raw.props.filter((p) => core.stateAt(p.h, cv) != null).length)} at ${short(V.list[cv].v)}`;
    }).catch(() => {
      propsN.textContent = 'config.json unavailable';
    });
  }
  if (ev == null) {
    extsN.textContent = isPre ? 'no extension snapshot' : 'no extension data';
  } else {
    loadFile('extensions').then((e) => {
      extsN.textContent = `${fmt(e.raw.exts.filter((xx) => core.stateAt(xx.h, ev) != null).length)} at ${short(V.list[ev].v)}`;
    }).catch(() => {
      extsN.textContent = 'extensions.json unavailable';
    });
  }

  return frag(
    h('header', { class: 'page-head band' },
      h('p', { class: 'kicker' }, isPre ? 'Pre-release' : 'Release', ' · stream ', x.stream || core.stream(x.v)),
      h('h1', { class: 'page-title page-title--ver', tabindex: '-1' }, short(x.v)),
      h('div', { class: 'badges' }, dateEl(x.date), versionChips(x), V.visOff.upcoming[i] ? upcomingChip() : null,
        x.config ? h('span', { class: 'cfg-mark' }, 'config snapshot') : x.ext ? h('span', { class: 'cfg-mark' }, 'extension snapshot') : null)),
    h('section', { class: 'sec', 'aria-labelledby': 'vq-h' },
      sectionHead(1, 'Compare with', 'vq-h', h('p', { class: 'sec-note' }, `What changed on the way to ${short(x.v)}.`)),
      quick, forward),
    h('section', { class: 'sec', 'aria-labelledby': 'vf-h' }, sectionHead(2, 'Facts', 'vf-h'), kv),
    h('nav', { class: 'pager', 'aria-label': 'Neighbouring releases' },
      nb.older >= 0 ? h('a', { class: 'pager-link', href: link.version(V.list[nb.older].v) }, h('span', { class: 'pager-k' }, 'Older'), h('span', { class: 'ver' }, short(V.list[nb.older].v))) : h('span'),
      nb.newer >= 0 ? h('a', { class: 'pager-link pager-link--next', href: link.version(V.list[nb.newer].v) }, h('span', { class: 'pager-k' }, 'Newer'), h('span', { class: 'ver' }, short(V.list[nb.newer].v))) : h('span')));
}

/* ========================================================================== */
/* Boot                                                                        */
/* ========================================================================== */

function boot() {
  buildShell();
  try {
    route = parseRoute(window.location.hash);
  } catch {
    route = { page: 'home' };
  }
  render();
}

boot();
