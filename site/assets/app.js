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
  removal: 'for removal', unremoval: 'no longer for removal', 'return type': 'return type', signature: 'signature', modifiers: 'modifiers',
  supertype: 'supertype', inherited: 'inherited', declared: 'declared',
};
const KIND_TONE = {
  added: 'add', removed: 'rem', changed: 'chg', default: 'chg', type: 'chg', phase: 'chg', enum: 'chg', extension: 'neu',
  deprecated: 'dep', undeprecated: 'dep-o', major: 'maj', minor: 'chg', patch: 'neu', other: 'neu', downgrade: 'rem',
  same: 'neu', relocated: 'chg', unrelocated: 'neu', status: 'chg', renamed: 'neu',
  moved: 'chg', 'moved-in': 'chg', 'moved-out': 'chg', introduced: 'add', reintroduced: 'add',
  removal: 'rem', unremoval: 'dep-o', 'return type': 'chg', signature: 'chg', modifiers: 'chg', supertype: 'chg', inherited: 'neu', declared: 'neu',
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
  removal: 'Marked for removal: @Deprecated(forRemoval = true)', unremoval: 'No longer marked for removal, still deprecated',
  'return type': 'The same name and parameters, another return type',
  signature: 'A method went and another of the same name came: possibly the same method with other parameters',
  modifiers: 'Its modifiers changed', supertype: 'Its superclass or interfaces changed',
  inherited: 'No longer declared by the class, but a supertype declares it: still there, inherited',
  declared: 'Declared by the class now, and inherited from a supertype before: it was there already',
};
/** An extension's status takes quarkus.io's tag colour for it, as on quarkus.io/extensions. */
const STATUS_TONE = { stable: 'stable', preview: 'preview', experimental: 'experimental', deprecated: 'deprecated' };
const TYPE_LABEL = { property: 'prop', artifact: 'dep', extension: 'ext', class: 'class', member: 'member' };
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
  files: new Map(), // 'bom' | 'config' | 'extensions' | 'classes-<major>' | 'classes:<majors>' | 'api-<major>-<k>' → Promise
  diffs: new Map(), // `${a}:${b}` → comparison (computed once per pair)
  searchIndex: null, // Promise<index>
  searchLines: '', // the major lines whose classes the search index has, e.g. '3,4'
  keeping: false, // the render going on keeps the reader's place (a switch toggled)
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
  // releases with API data (in the classes files, and in member shards keyed by version strings)
  const apiVis = core.apiVersionsOf(list);
  return {
    json,
    list,
    streams,
    configVis,
    extVis,
    clsVis,
    clsLines: core.classLinesOf(list, clsVis),
    apiVis,
    versionIndex: new Map(list.map((x, i) => [x.v, i])),
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
  platform(json) {
    if (!json || !Array.isArray(json.versions) || !Array.isArray(json.artifacts)) throw new Error('platform.json: missing "versions" or "artifacts"');
    return { raw: json, byId: new Map(json.artifacts.map((a) => [a.id, a])) };
  },
  classes(json) {
    if (!json || !Array.isArray(json.classes) || !Array.isArray(json.artifacts) || !Array.isArray(json.packages) || !Array.isArray(json.versions)) {
      throw new Error('classes-<major>.json: missing "versions", "artifacts", "packages" or "classes"');
    }
    return { raw: json, byName: new Map(json.classes.map((row) => [core.className(json, row), row])), index: new Map(json.classes.map((row, i) => [row, i])) };
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

/**
 * A data file the site works without, platform.json: null when it is missing or cannot be read, and the pages then
 * show nothing of it. Asked for once per visit.
 */
function loadOptional(name) {
  const key = `optional:${name}`;
  if (!store.files.has(key)) store.files.set(key, loadFile(name).catch(() => null));
  return store.files.get(key);
}

/** The classes file of a major line, `classes-<major>.json`, fetched once; its `major` is set for core.mergeClasses. */
function loadClassesFile(major) {
  const name = `classes-${major}`;
  let p = store.files.get(name);
  if (!p) {
    p = ensureVersions().then(() => fetchJson(`${store.dir}/${name}.json`)).then((json) => Object.assign(json, { major }));
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

/* ---------- API member shards ---------- */

const API_FETCHES = 6; // shard files loading at once, at most
const API_AUTO_BYTES = 150 * 1024; // a comparison loads the member files of its changed classes unasked up to about this (gzip)
const API_BYTES_PER_CLASS = 100; // a member file's size, gzipped, per class of its line with API in it (70 to 105 measured)
const shardQueue = [];
let shardsInFlight = 0;

/**
 * The member shard `api-<major>-<k>.json` (core.apiShard), fetched once, at most API_FETCHES at a time. A shard that
 * cannot be fetched or read rejects, and is forgotten so that a later page fetches it again.
 */
function loadApiShard(major, k) {
  const name = `api-${major}-${k}`;
  let p = store.files.get(name);
  if (!p) {
    p = new Promise((resolve, reject) => {
      const start = () => {
        shardsInFlight++;
        const url = `${store.dir}/${name}.json`;
        fetchJson(url)
          .then((json) => {
            try {
              return core.apiShard(json, store.V.versionIndex);
            } catch {
              throw new Error(`${url}: not in the expected shape`);
            }
          })
          .then(resolve, reject)
          .finally(() => {
            shardsInFlight--;
            shardQueue.shift()?.();
          });
      };
      if (shardsInFlight < API_FETCHES) start();
      else shardQueue.push(start);
    });
    store.files.set(name, p);
    p.catch(() => store.files.delete(name));
  }
  return p;
}

/** The lines of a classes object whose members are in shard files (phase 2 data). */
function memberLines(classes) {
  return (classes.api?.lines ?? []).filter((l) => l.shards > 0 && l.major != null);
}

/**
 * The shard files that the classes `names` need, `[major, k]` each once: in each member line (of `lines`), the
 * shard of every class that is API in some release of the line. `classes` is a loaded classes object (loadClasses).
 */
function shardFilesOf(classes, names, lines = memberLines(classes.raw)) {
  const files = new Map();
  for (const l of lines) {
    for (const name of names) {
      if (!core.apiInLine(classes.byName.get(name), l.versions)) continue;
      const k = core.memberShardOf(name, l.shards);
      files.set(`${l.major}-${k}`, [l.major, k]);
    }
  }
  return [...files.values()];
}

const shardSizes = new WeakMap(); // classes object → Map(major → estimated bytes of one of its line's shard files)

/** About how many bytes (gzip) the shard files `files` (`[major, k]`, shardFilesOf) weigh: by the classes of each line with API. */
function shardFilesBytes(classes, files) {
  let sizes = shardSizes.get(classes.raw);
  if (!sizes) {
    sizes = new Map();
    for (const l of memberLines(classes.raw)) {
      let n = 0;
      for (const row of classes.raw.classes) if (core.apiInLine(row, l.versions)) n++;
      sizes.set(l.major, (n / l.shards) * API_BYTES_PER_CLASS);
    }
    shardSizes.set(classes.raw, sizes);
  }
  return files.reduce((sum, [major]) => sum + (sizes.get(major) ?? 0), 0);
}

/** "about 340 KB", "about 1.2 MB": an estimated size. */
function aboutSize(bytes) {
  return bytes < 1024 * 1024 ? `about ${fmt(Math.max(1, Math.round(bytes / 1024)))} KB` : `about ${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

/**
 * The API rows of the classes `names` (core.mergeApiRows) over the member lines `lines` (all by default): binary name →
 * rows. A line where a class is never API adds nothing but the end of its earlier histories, so its shard is not
 * fetched. Empty without member data.
 */
async function classesApiRows(classes, names, lines = memberLines(classes.raw)) {
  const out = new Map();
  if (!lines.length) return out;
  await Promise.all(shardFilesOf(classes, names, lines).map(([major, k]) => loadApiShard(major, k)));
  for (const name of names) {
    const row = classes.byName.get(name);
    const parts = await Promise.all(lines.map(async (l) => {
      if (!core.apiInLine(row, l.versions)) return { first: l.versions[0], rows: [] };
      const shard = await loadApiShard(l.major, core.memberShardOf(name, l.shards));
      return { first: l.versions[0], rows: shard.byName.get(name) ?? [] };
    }));
    out.set(name, core.mergeApiRows(parts));
  }
  return out;
}

/**
 * For the nested classes among `names`: the API rows of the class each is declared in (core.mergeApiRows), from the
 * shard files of its own lines, which have them too (one shard per top-level class). Nested class name → rows; for
 * what is implicit in its value (core.diffApi, core.classApiHistory).
 */
async function enclosingApiRows(classes, names, lines = memberLines(classes.raw)) {
  const out = new Map();
  for (const name of names) {
    const outer = core.enclosingClassName(name);
    if (!outer) continue;
    const row = classes.byName.get(name);
    const parts = await Promise.all(lines.map(async (l) => {
      if (!core.apiInLine(row, l.versions)) return { first: l.versions[0], rows: [] };
      const shard = await loadApiShard(l.major, core.memberShardOf(name, l.shards));
      return { first: l.versions[0], rows: shard.byName.get(outer) ?? [] };
    }));
    out.set(name, core.mergeApiRows(parts));
  }
  return out;
}

/**
 * The API rows of the supertypes `seeds` (binary names), and of theirs at the releases `vis`, as far as the classes
 * files know them (a class outside the io.quarkus jars has none): binary name → rows (core.mergeApiRows), for
 * core.inheritedFrom. `known` holds rows loaded already, which are used and not returned. A member file that cannot be
 * loaded rejects, as the class's own do: an inherited member must not read as removed.
 */
async function supertypeApiRows(classes, seeds, vis, known = new Map()) {
  const out = new Map();
  const seen = new Set();
  let level = seeds;
  for (let depth = 0; level.length && depth < 16; depth++) {
    const names = [...new Set(level)].filter((n) => !seen.has(n) && classes.byName.has(n));
    for (const n of names) seen.add(n);
    const loaded = await classesApiRows(classes, names.filter((n) => !known.has(n)));
    level = [];
    for (const n of names) {
      const rows = known.get(n) ?? loaded.get(n);
      if (!rows) continue;
      if (!known.has(n)) out.set(n, rows);
      for (const vi of vis) level.push(...core.supertypesOf(core.apiAt(rows, vi)?.value));
    }
  }
  return out;
}

/**
 * The supertypes that core.inheritedFrom needs for the classes `rowsByName` (binary name → rows) between A and B: at B
 * those of a class that loses a member (it may inherit it now), at A those of a class that gains one (it may have
 * inherited it then); both for a class in several jars, where core.diffApi may compare others.
 */
function inheritanceSupertypes(rowsByName, a, b) {
  const out = [];
  const gains = (x, y) => [...y.members.keys()].some((k) => !x.members.has(k) && core.memberKind(k) !== 'constructor');
  for (const rows of rowsByName.values()) {
    const A = core.apiUnionAt(rows, a);
    const B = core.apiUnionAt(rows, b);
    if (!A || !B) continue;
    if (rows.length > 1 || gains(B, A)) out.push(...core.supertypesOf(B.value));
    if (rows.length > 1 || gains(A, B)) out.push(...core.supertypesOf(A.value));
  }
  return out;
}

/** Whether the reader asked to save data: then member files load only on request. */
function saveData() {
  try {
    return !!navigator.connection?.saveData;
  } catch {
    return false;
  }
}

/**
 * The classes files that a page about release `vi` loads for its API data: its own line's, which dates every
 * deprecation of the release (core.deprecatedFrom), within the lines the switches show when they have it, so that
 * the page shares them with search and the class pages.
 */
function apiLinesFor(vi) {
  const shown = shownClassLines();
  const own = core.classLinesOf(store.V.list, [vi]);
  return own.every((l) => shown.includes(l)) ? shown : own;
}

/**
 * The major lines with classes data from the first to the last of `lines` (ascending): what a comparison loads, so
 * that a line between its two releases' lines is seen too (where a class was last, and how it was deprecated then).
 */
function linesSpanning(lines) {
  return lines.length ? store.V.clsLines.filter((l) => l >= lines[0] && l <= lines[lines.length - 1]) : lines;
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
/** Which BOM a comparison shows: "platform" (io.quarkus.platform:quarkus-bom) or "core" (io.quarkus:quarkus-bom). */
const BOM_KEY = 'quarkus-almanac-bom';
const prefs = { showPre: readPref(PRE_KEY), showOld: readPref(OLD_KEY), platformBom: readPref(BOM_KEY, 'platform') };
let prefHooks = []; // {anchor, fn}: run while `anchor` is in the document

/** Whether the stored preference is on: its value is `onValue`. */
function readPref(key, onValue = '1') {
  try {
    return window.localStorage.getItem(key) === onValue;
  } catch {
    return false; // storage may be blocked: off by default
  }
}

/** Sets a preference, stored as `values` ([off, on]), and runs the hooks of the page. */
function setPref(name, key, on, said, values = ['0', '1']) {
  const next = !!on;
  if (prefs[name] === next) return;
  prefs[name] = next;
  try {
    window.localStorage.setItem(key, values[next ? 1 : 0]);
  } catch {
    /* storage may be blocked: the choice lasts until the page is reloaded */
  }
  announce(said(next));
  for (const hook of [...prefHooks]) if (hook.anchor.isConnected && (!hook.names || hook.names.includes(name))) hook.fn();
}

function setShowPre(on) {
  setPref('showPre', PRE_KEY, on, (next) => (next ? 'Pre-releases shown.' : 'Pre-releases hidden, except those of upcoming streams.'));
}

function setShowOld(on) {
  setPref('showOld', OLD_KEY, on, (next) => (next ? 'Old releases shown.' : `Old releases hidden: the streams from ${OLDEST_SHOWN_STREAM} on are listed.`));
}

function setPlatformBom(on) {
  setPref('platformBom', BOM_KEY, on, (next) => (next
    ? 'Comparing the platform BOM, io.quarkus.platform:quarkus-bom.'
    : 'Comparing the core BOM, io.quarkus:quarkus-bom.'), ['core', 'platform']);
}

/**
 * Runs `fn` when a preference changes (one of `names`, the `prefs` keys, when given), as long as `anchor` (an element
 * of the page) is on screen.
 */
function onPrefChange(anchor, fn, names = null) {
  prefHooks.push({ anchor, fn, names });
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

/**
 * "3.40.1.1, the newest release": the newest release that has data of one kind (`vi`, e.g. the last of a history's),
 * named; while releases the switches show after it lack that data (`what`: "config data"), "3.33.4, the newest
 * release with config data".
 */
function newestRelease(vi, what) {
  const shown = currentVis().indices;
  return `${short(store.V.list[vi].v)}, the newest release${vi === shown[shown.length - 1] ? '' : ` with ${what}`}`;
}

/** "the first release of this dataset", or, while releases before the first that has data of one kind (`snaps`) lack it, "the first release with config data" (`what`). */
function firstRelease(snaps, what) {
  return snaps[0] === 0 ? 'the first release of this dataset' : `the first release with ${what}`;
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
    case 'class': {
      // a member after the class: #/class/<binary name>/<member key>, its descriptor's slashes kept
      const all = rest.join('/');
      const m = /^([^/(:]+)\/([^/(:]+[(:].*)$/.exec(all);
      return m ? { page: 'class', name: m[1], member: m[2] } : { page: 'class', name: all };
    }
    case 'deprecated':
      return { page: 'deprecated', v: rest[0] ?? '' };
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
      return `#/class/${enc(r.name)}${r.member ? `/${enc(r.member).replace(/%2F/gi, '/').replace(/%3B/gi, ';')}` : ''}`;
    case 'deprecated':
      return `#/deprecated/${enc(r.v)}`;
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
  member: (name, key) => hrefFor({ page: 'class', name, member: key }),
  deprecated: (v) => hrefFor({ page: 'deprecated', v: short(v) }),
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
  deprecated: renderDeprecated,
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
      return `${core.simpleClassName(r.name)}${r.member ? `#${r.member.slice(0, r.member.search(/[(:]/))}` : ''} · ${base}`;
    case 'deprecated':
      return `Deprecated API ${r.v} · ${base}`;
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
    store.keeping = !!keep; // pages read it as they start
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
    // a pressed chip stays enabled, so that it can be released when another filter leaves it nothing
    const b = h('button', { type: 'button', class: `fchip tone-${KIND_TONE[kind] ?? 'neu'}`, 'aria-pressed': String(active.has(kind)), disabled: n === 0 && !active.has(kind) },
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

/* ---------- "Show pre-releases", "Show old releases" and "Platform BOM" switches ---------- */

const SWITCHES = {
  pre: {
    label: 'Show pre-releases', on: () => prefs.showPre, set: setShowPre,
    why: 'Alpha, Beta and CR releases of streams that already have a final release. Pre-releases of upcoming streams are always shown.',
  },
  old: {
    label: 'Show old releases', on: () => prefs.showOld, set: setShowOld,
    why: `Releases of the streams before ${OLDEST_SHOWN_STREAM}. They stay in the data; the switch only lists them.`,
  },
  platform: {
    label: 'Platform BOM', on: () => prefs.platformBom, set: setPlatformBom,
    why: 'Compare io.quarkus.platform:quarkus-bom, the BOM that applications generated by code.quarkus.io import, instead of '
      + 'io.quarkus:quarkus-bom. The platform aligns some versions with its other members.',
  },
};

/**
 * A "Show pre-releases" (`kind` pre), "Show old releases" (old) or "Platform BOM" (platform) switch: a labelled
 * checkbox with role="switch" (Space or Enter toggles it). `count()` = the releases (or snapshots: `noun`) it shows or
 * hides now, given the other switch; `blocked()` = those of its kind that only the other switch can show; `max` = the
 * most it controls here: with none there is nothing to switch, and it is not rendered. `text`, when given, is the note
 * instead of a count. `slot` names its place so a re-render gives focus back to it.
 */
function prefSwitch(kind, slot, { count, blocked = null, max, noun, text = null }) {
  if (!max && text == null) return null;
  const sw = SWITCHES[kind];
  const id = nextId(kind);
  const input = h('input', {
    type: 'checkbox', id, class: 'switch-input', role: 'switch', 'data-pref-slot': slot, 'aria-describedby': `${id}-n ${id}-d`,
  });
  const note = h('span', { class: 'pre-toggle-n', id: `${id}-n` });
  const sync = () => {
    input.checked = sw.on();
    if (text != null) {
      note.textContent = text;
      return;
    }
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

/** Both switches over the releases `snaps` that have data of one kind (config, extensions or classes), counted alike. */
function snapshotSwitches(slot, snaps) {
  const V = store.V;
  const pre = (vis) => snaps.filter((c) => vis.pre[c] && !vis.upcoming[c] && (!vis.old[c] || vis.showOld)).length;
  const old = (vis) => snaps.filter((c) => vis.old[c] && (!vis.pre[c] || vis.upcoming[c] || vis.showPre)).length;
  return [
    prefSwitch('pre', slot, {
      count: () => pre(currentVis()), blocked: () => pre(V.visOn) - pre(currentVis()), max: pre(V.visOn), noun: 'pre-release',
    }),
    prefSwitch('old', `${slot}-old`, {
      count: () => old(currentVis()), blocked: () => old(V.visOn) - old(currentVis()), max: old(V.visOn), noun: 'old release',
    }),
  ].filter(Boolean);
}

/**
 * The switches in a row, re-rendering the page when a preference changes (`rerender`; only when one of `names`
 * changes, when given); null without any.
 */
function switchRow(cls, switches, { rerender = false, names = null } = {}) {
  if (!switches.length) return null;
  const row = h('div', { class: cls }, switches);
  if (rerender) onPrefChange(row, rerenderInPlace, names);
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
  if (it.type === 'member') return { page: 'class', name: it.cls, member: it.id };
  return { page: 'artifact', id: it.id };
}

function resultSub(it) {
  const V = store.V;
  if (it.type === 'member') return it.sub;
  if (it.type === 'property') {
    const parts = [it.ext];
    if (!it.current && it.lastSeen != null) parts.push(`no longer present; last seen in ${short(V.list[it.lastSeen].v)}`);
    return parts.filter(Boolean).join(' · ');
  }
  if (it.type === 'artifact') {
    if (!it.current) return 'no longer managed by quarkus-bom';
    return it.follows ? `${short(it.value)} (versioned with Quarkus)` : `managed at ${it.value}`;
  }
  if (it.type === 'class') {
    const jars = it.jars.join(', ');
    if (it.current) return `in ${jars}`;
    return it.lastSeen != null ? `no longer present; last in ${jars} (${short(V.list[it.lastSeen].v)})` : jars;
  }
  const parts = [it.name, it.status];
  if (it.relocatedTo) parts.push(`relocated to ${it.relocatedTo}`);
  if (!it.current) parts.push('removed');
  return parts.filter(Boolean).join(' · ');
}

/**
 * `Class#member`: the members of the best class match for the part before `#` whose names start with (or contain)
 * the part after it, in the newest release shown with API data; none without member data.
 */
async function memberSearch(index, q) {
  const hash = q.indexOf('#');
  const found = core.search(index, q.slice(0, hash).trim(), { limit: 1, types: ['class'] }).items;
  if (!found.length) return { items: [], total: 0 };
  const name = found[0].id;
  const classes = await loadClasses(shownClassLines());
  const avs = core.visibleOnly(classes.raw.api?.versions ?? [], currentVis());
  const at = avs[avs.length - 1];
  // only the line of that release
  const lines = memberLines(classes.raw).filter((l) => l.versions.includes(at));
  if (!avs.length || !lines.length) return { items: [], total: 0 };
  const rows = (await classesApiRows(classes, [name], lines)).get(name) ?? [];
  // every jar's members, as the class page lists them
  const now = core.apiUnionAt(rows, at);
  const want = q.slice(hash + 1).trim().toLowerCase();
  const items = [];
  for (const [key, value] of now ? now.members : []) {
    const p = core.prettyMember(key, { className: name, varargs: !!core.parseApiValue(value, true)?.varargs });
    if (!p) continue;
    const memberName = p.kind === 'constructor' ? p.simple.slice(0, p.simple.indexOf('(')) : p.name;
    const lowered = memberName.toLowerCase();
    if (want && !lowered.includes(want)) continue;
    items.push({ type: 'member', id: key, cls: name, label: `${core.simpleClassName(name)}#${memberName}`, sub: p.simple, deprecation: core.deprecationOf(value), rank: want && lowered.startsWith(want) ? 0 : 1 });
  }
  items.sort((x, y) => x.rank - y.rank || (x.label < y.label ? -1 : x.label > y.label ? 1 : 0) || (x.sub < y.sub ? -1 : 1));
  return { items: items.slice(0, 12), total: items.length };
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
          h('span', { class: 'search-label' }, highlight(it.label, q), it.deprecation ? frag(' ', deprecationChip(it.deprecation)) : null),
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
    if (q.includes('#')) {
      // Class#member: the members of the class, from its shard
      let res;
      try {
        res = await memberSearch(index, q);
      } catch {
        res = { items: [], total: 0 };
      }
      if (mine !== seq || input.value.trim() !== q) return;
      paint(q, res);
      return;
    }
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
      'and which classes, methods and fields of the Quarkus jars were added, removed, deprecated or moved to another jar.'),
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
    coverageFacts(),
    h('div', { class: 'home-grid' }, h('div', { class: 'home-main' }, search), h('aside', { class: 'home-side', 'aria-label': 'Quick comparisons' }, quick)));
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
  return h('section', { class: 'facts', 'aria-labelledby': 'facts-h' },
    h('h2', { class: 'label-h', id: 'facts-h' }, 'Coverage'),
    h('dl', { class: 'facts-grid' },
      fact(fmt(V.list.length), 'releases', `${short(first.v)} to ${short(last.v)}${preCount ? `, ${plural(preCount, 'pre-release')} among them` : ''}`),
      fact(propsN, 'config properties tracked', propsSub),
      fact(artsN, 'managed artifacts tracked', artsSub),
      fact(V.generatedAt ? core.formatDate(V.generatedAt) : '—', 'data generated', null, 'fact--date')));
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
  const platform = platformSides(ia, ib, data);
  const key = `${ia}:${ib}${platform?.used ? ':platform' : ''}`;
  const hit = store.diffs.get(key);
  if (hit) return hit;
  const V = store.V;
  const out = { ia, ib, A: V.list[ia], B: V.list[ib], errors: data.errors, bom: null, config: null, configRows: [], configTags: {}, ext: null, platform };
  if (data.bom) {
    // the platform BOMs when the reader chose them and both releases have one, else the core BOMs; when both have one,
    // the other BOMs are compared too, for what the "Platform BOM" switch changes (core.platformDiffEffect)
    const platformBom = platform?.a && platform.b ? core.platformBomAt(data.bom.raw, data.platform.raw, [ia, ib]) : null;
    const bom = platform?.used ? platformBom : data.bom.raw;
    out.bom = core.diffBom(bom, V.list, ia, ib);
    if (platformBom) {
      const other = core.diffBom(platform.used ? data.bom.raw : platformBom, V.list, ia, ib);
      platform.effect = platform.used ? core.platformDiffEffect(other, out.bom) : core.platformDiffEffect(out.bom, other);
    }
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
  // the API data: from the classes files too (core.apiVersionFor)
  out.aa = core.apiVersionFor(ia, V.list, V.apiVis);
  out.ab = core.apiVersionFor(ib, V.list, V.apiVis);
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
  // the classes files of the API section start loading with the rest, though the page does not wait for them
  const classLines = linesSpanning(core.classLinesOf(V.list, [ia, ib]));
  if (classLines.length && ia !== ib && V.apiVis.length) loadClasses(classLines).catch(() => {});
  const [data, platform] = await Promise.all([loadSettled(['bom', 'config', 'extensions']), loadOptional('platform')]);
  data.platform = platform;
  const cmp = comparison(ia, ib, data);
  const head = compareHeader(cmp);
  if (ia === ib) return frag(head, emptyNote('From and To are the same release, so there is nothing to compare. Change one of them above.'));
  // the sections start collapsed (the summary above says what each holds), except those the reader left open in
  // this pair earlier in the visit, so that Back from a property or an artifact finds them as they were
  const sections = [configSection(cmp, data), depsSection(cmp, data), extSection(cmp, data), V.apiVis.length ? apiSection(cmp) : null]
    .filter(Boolean).map(collapsible);
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
    platformRow(cmp),
    h('nav', { class: 'jump', 'aria-label': 'Sections of this comparison' },
      h('span', { class: 'jump-k' }, 'On this page'),
      [['sec-config', 'Configuration'], ['sec-deps', 'Dependencies'], ['sec-ext', 'Extensions'],
        V.apiVis.length ? ['sec-api', 'API'] : null].filter(Boolean).map(([id, label]) =>
        h('button', { type: 'button', class: 'jump-link', onclick: () => jumpTo(id) }, label)),
      all),
    sections);
}

/**
 * The platform BOM side of a comparison, null without platform.json: `a` and `b`, how the platform BOM of each release
 * differs from its core BOM (core.platformDifferences; null for a release without one), and `used`, whether the
 * comparison is of the platform BOMs (the reader chose them and both releases have one). comparison() adds `effect`
 * (core.platformDiffEffect) when both releases have one.
 */
function platformSides(ia, ib, data) {
  if (!data.bom || !data.platform) return null;
  const V = store.V;
  const p = data.platform.raw;
  const side = (i) => (core.hasPlatformBom(p, i) ? core.platformDifferences(data.bom.raw, p, V.list, i) : null);
  const a = side(ia);
  const b = ib === ia ? a : side(ib);
  return { a, b, used: prefs.platformBom && a != null && b != null };
}

/** The releases of a comparison without a platform BOM: "3.0.0.Alpha1", "3.0.0 and 3.1.0". */
function withoutPlatformBom(cmp) {
  const V = store.V;
  return [cmp.platform.a ? null : cmp.ia, cmp.platform.b || cmp.ib === cmp.ia ? null : cmp.ib]
    .filter((i) => i != null).map((i) => short(V.list[i].v)).join(' and ');
}

/**
 * Under the summary of a comparison: the "Platform BOM" switch when both releases have a platform BOM, saying how many
 * dependency changes of this comparison differ with the platform BOMs (core.platformDiffEffect); when the reader chose
 * the platform BOM and a release has none, a note that the core BOM is compared; nothing without platform.json.
 */
function platformRow(cmp) {
  const p = cmp.platform;
  if (!p || cmp.ia === cmp.ib) return null;
  if (p.a && p.b) {
    const n = p.effect?.count ?? 0;
    const text = n ? `${plural(n, 'dependency change')} differ${n === 1 ? 's' : ''} with the platform BOM`
      : `no dependency change differs with the platform BOM${p.effect?.spelling ? ', which writes some artifacts otherwise' : ''}`;
    // only this switch re-renders the page: the release switches of "Change releases" refill its lists alone
    return switchRow('page-tools cmp-tools', [prefSwitch('platform', 'platform-bom', { text })], { rerender: true, names: ['platformBom'] });
  }
  if (!prefs.platformBom) return null;
  return h('p', { class: 'page-tools cmp-tools muted' },
    `No platform BOM of ${withoutPlatformBom(cmp)}, so the core BOM, io.quarkus:quarkus-bom, is compared.`);
}

/** "com.fasterxml.jackson…: 2.21.7 instead of 2.21.6" for the version changes of a platform BOM, three pairs at most. */
function platformChangesText(d) {
  const pairs = core.platformChangeSummary(d.changed);
  const shown = pairs.slice(0, 3).map((s, k) => frag(k ? '; ' : '', h('span', { class: 'mono' }, s.label), `: ${s.platform} instead of ${s.core}`,
    pairs.length > 1 ? ` (${plural(s.count, 'artifact')})` : ''));
  return frag(shown, pairs.length > 3 ? `; and ${plural(pairs.length - 3, 'other pair')} of versions` : '');
}

/**
 * In Dependencies, when the platform BOM of either release manages other versions than its core BOM: which, and, when
 * both releases have a platform BOM, a button that compares the other BOM.
 */
function platformNote(cmp) {
  const p = cmp.platform;
  if (!p || cmp.ia === cmp.ib) return null;
  const V = store.V;
  const sides = [[cmp.ia, p.a], [cmp.ib, p.b]].filter(([, d]) => d && d.changed.length);
  if (!sides.length) return null;
  const sentence = (names, d) => h('p', null, `At ${names}, the platform BOM manages ${plural(d.changed.length, 'artifact')} at other versions than the core BOM: `,
    platformChangesText(d), '.');
  const key = (d) => JSON.stringify(core.platformChangeSummary(d.changed).map((s) => [s.label, s.core, s.platform, s.count]));
  const text = sides.length === 2 && key(sides[0][1]) === key(sides[1][1])
    ? [sentence(`${short(V.list[sides[0][0]].v)} and ${short(V.list[sides[1][0]].v)}`, sides[0][1])]
    : sides.map(([i, d]) => sentence(short(V.list[i].v), d));
  const toggle = p.a && p.b
    ? h('button', { type: 'button', class: 'btn', 'data-pref-slot': 'platform-bom-note', onclick: () => setPlatformBom(!p.used) },
      p.used ? 'Compare the core BOMs' : 'Compare the platform BOMs')
    : null;
  return h('div', { class: 'note platform-note' }, h('div', { class: 'platform-note-text' }, text), toggle);
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
 * The releases whose data of one kind a comparison uses: config, extensions (which also exist for releases without
 * config) or the API. `a` and `b` are those of From and To: themselves, or, in a data set where a release has no data
 * of that kind, the release whose data stands in for it (core.snapshotVersionFor); null when there is none. `what`
 * names the data.
 */
function snapshotsOf(cmp, kind) {
  if (kind === 'api') return { a: cmp.aa, b: cmp.ab, all: store.V.apiVis, what: 'API data', title: 'The API' };
  return kind === 'config'
    ? { a: cmp.ca, b: cmp.cb, all: store.V.configVis, what: 'config data', title: 'Config' }
    : { a: cmp.ea, b: cmp.eb, all: store.V.extVis, what: 'extension data', title: 'Extensions' };
}

/**
 * Why a comparison has no diff of one kind ('config', 'extension' or 'api'), or null when both releases have such
 * data. A pre-release only uses its own data, so one without says so plainly: "No config data for 3.33.0.CR1:
 * dependency changes only." The end names what the comparison still shows (for config and extensions, which the rest
 * of the page depends on).
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
  if (pre.length) parts.push(`No ${s.what} for ${names(pre)}`);
  if (fin.length) {
    const first = s.all.length ? short(V.list[s.all[0]].v) : null;
    parts.push(`${pre.length ? 'no' : 'No'} ${s.what} for ${names(fin)}${first ? ` (the ${s.what} starts at ${first})` : ''}`);
  }
  if (kind === 'api') return `${parts.join(', and ')}.`;
  // config needs extensions, so only a config gap can leave an extension diff to show
  const shown = kind === 'config' && cmp.ext ? 'dependency and extension changes only' : 'dependency changes only';
  return `${parts.join(', and ')}: ${shown}.`;
}

/** Both releases use the data of one release for a kind ('config', 'extension' or 'api'), so there is no diff of it to show. */
function sameSnapshotText(cmp, kind) {
  const V = store.V;
  const { A, B } = cmp;
  const s = snapshotsOf(cmp, kind);
  const finalsOfOneStream = !core.isPreRelease(A) && !core.isPreRelease(B) && streamOfEntry(A) === streamOfEntry(B);
  return `Both releases use the ${s.what} of ${short(V.list[s.a].v)}. ${finalsOfOneStream
    ? `${s.title} ${kind === 'config' || kind === 'api' ? 'is' : 'are'} captured once per stream, so changes between patches of one stream are not tracked.`
    : 'Changes between them are not tracked.'}`;
}

/**
 * A cell of a comparison's summary: its title, a jump to its section (`target`), its numbers (`nums`: one row of
 * `[n, label, tone]`, or several rows) and its note (a line, or several).
 */
function ledgerCell(title, target, nums, note) {
  const row = (list) => h('div', { class: 'ledger-nums' }, list.map(([n, label, tone]) =>
    h('span', { class: `ledger-num tone-${tone}${n ? '' : ' is-zero'}` }, h('span', { class: 'ledger-n' }, fmt(n)), h('span', { class: 'ledger-l' }, label))));
  return h('div', { class: 'ledger-cell' },
    h('h2', { class: 'ledger-title' }, h('button', { type: 'button', class: 'ledger-jump', onclick: () => jumpTo(target) }, title)),
    (Array.isArray(nums[0]?.[0]) ? nums : nums.length ? [nums] : []).map(row),
    [note].flat().filter(Boolean).map((text) => h('p', { class: 'ledger-note' }, text)));
}

function summaryLedger(cmp, data) {
  const cell = ledgerCell;
  const cells = [];
  // the data of one release for both: say so here, since its section starts collapsed and zeros would mislead
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
      [cmp.platform?.used ? 'Platform BOM' : null, kinds.length ? kinds.join(' · ') : null, cmp.bom.modules ? `plus ${fmt(cmp.bom.modules)} Quarkus modules` : null].filter(Boolean).join('; ')));
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
  // the API, with API data only; the page waits neither for the classes files nor for the member files, so the cell
  // fills in when they arrive, and again when the API section has the members (cmp.onApiRows)
  if (store.V.apiVis.length) {
    const gapApi = gapText(cmp, 'api');
    if (gapApi) {
      cells.push(cell('API', 'sec-api', [], gapApi));
    } else if (cmp.aa === cmp.ab) {
      cells.push(cell('API', 'sec-api', [], sameSnapshotText(cmp, 'api')));
    } else {
      let apiEl = cell('API', 'sec-api', [], 'Loading the API data…');
      cells.push(apiEl);
      const show = (el) => {
        apiEl.replaceWith(el);
        apiEl = el;
      };
      loadClasses(linesSpanning(core.classLinesOf(store.V.list, [cmp.aa, cmp.ab]))).then((classes) => {
        cmp.onApiRows = () => show(apiCell(cmp, classes));
        cmp.onApiRows();
      }).catch(() => show(cell('API', 'sec-api', [], 'The API data is unavailable.')));
    }
  }
  return h('section', { class: 'ledger', 'aria-label': 'Summary of changes' }, cells);
}

/* ---------- §1 configuration ---------- */

const CONFIG_CHIPS = [
  ['added', 'added'], ['removed', 'removed'], ['default', 'default'], ['type', 'type'], ['phase', 'phase'],
  ['deprecated', 'deprecated'], ['enum', 'enum'], ['extension', 'extension'],
];

/**
 * Why a comparison uses the data of other releases for a kind ('config', 'extension' or 'classes'), for the note
 * under its heading, in a data set where a release has none of its own: a final without uses its stream's newest
 * patch, a pre-release only its own. Null when both releases have their own.
 */
function snapshotReason(cmp, kind) {
  const V = store.V;
  const s = snapshotsOf(cmp, kind);
  const borrowed = [[cmp.ia, s.a], [cmp.ib, s.b]].filter(([i, c]) => i !== c);
  if (!borrowed.length) return null;
  const inStream = borrowed.every(([i, c]) => !core.isPreRelease(V.list[c]) && streamOfEntry(V.list[c]) === streamOfEntry(V.list[i]));
  const names = [...new Set(borrowed.map(([i]) => short(V.list[i].v)))];
  const own = `${names.join(' and ')} ${names.length === 1 ? 'has' : 'have'} no ${s.what} of ${names.length === 1 ? 'its' : 'their'} own`;
  return inStream ? ` (${own}: the newest patch of the stream stands in)` : ` (${own}; the nearest release with it is used)`;
}

/**
 * " compared at 3.20.6 → 3.27.4 (…)" when a release of the comparison has no data of a kind of its own and another
 * release's stands in (snapshotReason); null when both have their own, since the releases compared are then the
 * page's, and when both use the same release's, which the section itself says (sameSnapshotText).
 */
function comparedAt(cmp, kind) {
  const V = store.V;
  const s = snapshotsOf(cmp, kind);
  if (s.a == null || s.b == null || s.a === s.b || (s.a === cmp.ia && s.b === cmp.ib)) return null;
  return frag(' compared at ', h('span', { class: 'ver' }, short(V.list[s.a].v)), ' → ', h('span', { class: 'ver' }, short(V.list[s.b].v)), snapshotReason(cmp, kind));
}

function configSection(cmp, data) {
  const V = store.V;
  const { ca, cb } = cmp;
  let note = null;
  if (ca != null && cb != null && ca !== cb) {
    const xa = V.list[ca];
    const xb = V.list[cb];
    const mixed = xa.configSource && xb.configSource && xa.configSource !== xb.configSource;
    const at = comparedAt(cmp, 'config');
    const sources = xa.configSource && xb.configSource
      ? frag(at ? ';' : '', ' extracted from ', h('abbr', { title: SOURCE_TITLE[xa.configSource] ?? '' }, xa.configSource), ' → ', h('abbr', { title: SOURCE_TITLE[xb.configSource] ?? '' }, xb.configSource),
        mixed ? ', so a few differences may come from the extraction method rather than Quarkus' : '')
      : null;
    note = at || sources ? h('p', { class: 'sec-note' }, 'Config', at, sources, '.') : null;
  }
  const sec = h('section', { class: 'sec', id: 'sec-config', 'aria-labelledby': 'sec-config-h' }, sectionHead(1, 'Configuration', 'sec-config-h', note));
  if (!data.config) return add(sec, [missingData('config.json', data.errors.config)]);
  if (ca == null || cb == null) return add(sec, [emptyNote(gapText(cmp, 'config'))]);
  if (ca === cb) return add(sec, [emptyNote(sameSnapshotText(cmp, 'config'))]);
  const rows = cmp.configRows;
  if (!rows.length) return add(sec, [emptyNote('No config property was added, removed or changed between these releases.')]);

  const state = { tags: new Set(), text: '', expanded: new Set(), groups: moduleGroups({ memory: `${cmp.ia}:${cmp.ib}:config`, noun: 'extensions' }) };
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
    listBar(status, state.groups),
    groupsEl);
  update();
  return sec;
}

function renderConfigGroups(container, rows, state) {
  const filtering = state.text.trim() !== '';
  const groups = [...core.groupBy(rows, (r) => r.ext)];
  state.groups.listed(groups.map(([ext]) => ext), state.text.trim());
  renderGroupPages(container, groups, (ext, list) => configGroup(ext, list, state, filtering), 'No change matches these filters.', 'more extension');
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

/** The module groups that readers opened or closed in each list during this visit (moduleGroups' `memory`), so that Back finds them as they were. */
const openGroups = new Map();

/**
 * The open state of the module groups of a list (moduleGroup): collapsed, or open while a text filter is active, unless
 * the reader opened or closed them; with a button that opens or closes every group the list holds, those on screen at
 * once and the others when they render. Another filter text forgets those choices, so that its matches show. `memory`
 * keys the choices kept for the visit (openGroups), and `noun` names the groups for the button ("jars").
 */
function moduleGroups({ memory = null, noun = 'groups' } = {}) {
  let choice = memory ? openGroups.get(memory) : null;
  if (!choice) {
    choice = new Map();
    if (memory) openGroups.set(memory, choice);
  }
  const shown = new Map(); // key → set(open) of each group on screen
  let keys = [];
  let text = '';
  const isOpen = (key) => choice.get(key) ?? text !== '';
  const button = h('button', { type: 'button', class: 'more more--inline groups-all' });
  const sync = () => {
    button.hidden = keys.length < 2;
    const open = keys.length > 0 && keys.every(isOpen);
    button.textContent = open ? 'Collapse all' : 'Expand all';
    button.setAttribute('aria-label', `${open ? 'Collapse' : 'Expand'} all ${noun}`);
  };
  button.addEventListener('click', () => {
    const open = !keys.every(isOpen);
    for (const key of keys) choice.set(key, open);
    for (const set of shown.values()) set(open);
    sync();
  });
  return {
    isOpen,
    button,
    /** The keys of the groups the list holds now, and its text filter ('' for none); the list renders them anew. */
    listed(list, filter) {
      if (filter !== text) choice.clear();
      keys = list;
      text = filter;
      shown.clear();
      sync();
    },
    shown(key, set) {
      shown.set(key, set);
    },
    choose(key, open) {
      choice.set(key, open);
      sync();
    },
  };
}

let moduleGroupSeq = 0;

/**
 * A module's group of rows (an extension's or a jar's): its heading, with the toggle, the module (`title`, a link)
 * and `counts`, shows or hides the rows `body`; it starts as `groups` says (moduleGroups), which hears of the reader's
 * choices. `label` names the group for assistive technologies. Find in page opens a collapsed group where browsers
 * support hidden="until-found".
 */
function moduleGroup(groups, key, title, counts, label, ...body) {
  const id = `cgroup-${++moduleGroupSeq}`;
  const bodyEl = h('div', { class: 'cgroup-body', id }, ...body);
  const toggle = h('button', { type: 'button', class: 'cgroup-toggle', 'aria-controls': id, 'aria-label': label }, icon('chevron', 'cgroup-chev'));
  const head = h('div', { class: 'cgroup-head' }, h('h3', { class: 'cgroup-title cgroup-name' }, toggle, title), counts);
  const sec = h('section', { class: 'cgroup', 'aria-label': label }, head, bodyEl);
  const set = (open) => {
    toggle.setAttribute('aria-expanded', String(open));
    if (open) bodyEl.removeAttribute('hidden');
    else bodyEl.setAttribute('hidden', 'until-found');
    sec.classList.toggle('is-collapsed', !open);
  };
  const choose = (open) => {
    set(open);
    groups.choose(key, open);
  };
  set(groups.isOpen(key));
  groups.shown(key, set);
  toggle.addEventListener('click', () => choose(toggle.getAttribute('aria-expanded') !== 'true'));
  // the whole head opens and closes the group, but for its links
  head.addEventListener('click', (e) => {
    if (!e.target.closest('a, button')) choose(toggle.getAttribute('aria-expanded') !== 'true');
  });
  bodyEl.addEventListener('beforematch', () => choose(true));
  return sec;
}

/** A list's status line with the button that opens or closes all its module groups (moduleGroups). */
function listBar(status, groups) {
  return h('div', { class: 'list-bar' }, status, groups.button);
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
  const more = list.length > initial ? moreButton(`Show all ${plural(list.length, 'change')} in ${ext || 'this group'}`, () => {
    state.expanded.add(ext);
    ul.append(fill(initial, list.length));
    more.remove();
    ul.children[initial]?.querySelector('a')?.focus();
  }) : null;
  return moduleGroup(state.groups, ext, ext ? h('a', { href: link.extension(ext) }, ext) : 'Unattributed',
    h('span', { class: 'cgroup-counts' },
      countMark('add', '+', counts.added, 'added'),
      countMark('rem', '−', counts.removed, 'removed'),
      countMark('chg', '~', counts.changed, 'changed')),
    `${ext || 'Unattributed'}: ${plural(list.length, 'change')}`, ul, more);
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
        h('span', { class: 'heuristic' }, 'in the older release; matched to this key through aliases'))
      : null);
}

/* ---------- §2 dependencies ---------- */

const BOM_CHIPS = [['downgrade', 'downgrade'], ['major', 'major'], ['minor', 'minor'], ['patch', 'patch'], ['other', 'qualifier'], ['added', 'added'], ['removed', 'removed']];

function depsSection(cmp, data) {
  const sec = h('section', { class: 'sec', id: 'sec-deps', 'aria-labelledby': 'sec-deps-h' },
    sectionHead(2, 'Dependencies', 'sec-deps-h',
      h('p', { class: 'sec-note' }, 'Versions managed by ',
        cmp.platform?.used ? frag('the platform BOM, ', h('code', null, 'io.quarkus.platform:quarkus-bom')) : h('code', null, 'io.quarkus:quarkus-bom'),
        '. Artifacts that share a groupId and the same version change are listed together.')));
  if (!cmp.bom) return add(sec, [missingData('bom.json', data.errors.bom)]);
  const pnote = platformNote(cmp);
  if (pnote) sec.append(pnote);
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

/* ---------- §3 extensions ---------- */

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
  const at = comparedAt(cmp, 'extension');
  const note = h('p', { class: 'sec-note' }, 'Metadata from each extension\'s quarkus-extension.yaml', at ? frag(',', at) : null, '.');
  const sec = h('section', { class: 'sec', id: 'sec-ext', 'aria-labelledby': 'sec-ext-h' }, sectionHead(3, 'Extensions', 'sec-ext-h', note));
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

/** Links to the artifact pages of jars (artifactIds), separated by commas. */
function jarLinks(artifactIds) {
  return frag(artifactIds.map((a, i) => frag(i ? ', ' : null, h('a', { class: 'mono', href: link.extension(a) }, a))));
}

/** A binary class name: its package in a lighter colour, then its name in the package. */
function classNameEl(name) {
  const pkg = core.classPackage(name);
  return frag(pkg ? h('span', { class: 'cls-pkg' }, `${pkg}.`) : null, core.simpleClassName(name));
}

/* ---------- §4 API ---------- */

/** The deprecation changes of a comparison (core.diffDeprecations), once per comparison. */
function apiDeprecations(cmp, classes) {
  if (!cmp.apiDeps) cmp.apiDeps = core.diffDeprecations(classes.raw, cmp.aa, cmp.ab);
  return cmp.apiDeps;
}

/** The classes whose API revision differs between the releases of a comparison, or null without revision data. */
function apiChangedOf(cmp, classes) {
  if (cmp.apiChanged === undefined) {
    cmp.apiChanged = classes.raw.classes.some((row) => Array.isArray(row[3])) ? core.apiChangedClasses(classes.raw, cmp.aa, cmp.ab) : null;
  }
  return cmp.apiChanged;
}

/** The classes of a comparison that are API at both releases and in other jars at B (core.movedApiClasses), once per comparison. */
function apiMovedOf(cmp, classes) {
  if (!cmp.apiMoved) cmp.apiMoved = core.movedApiClasses(classes.raw, cmp.aa, cmp.ab);
  return cmp.apiMoved;
}

/** Whether the API section loads the member files of the classes `changed` unasked: they are small (API_AUTO_BYTES) and the reader does not save data. */
function apiAutoLoads(classes, changed) {
  return !saveData() && shardFilesBytes(classes, shardFilesOf(classes, changed)) <= API_AUTO_BYTES;
}

const API_KINDS = [['class', 'classes'], ['method', 'methods'], ['field', 'fields'], ['constructor', 'constructors']];

/**
 * The API cell of a comparison's summary: the classes and members added, removed and changed, as the API section lists
 * them by default (core.apiRowCounts, nested classes left out), with a count per kind of element, and the new
 * deprecations (core.diffDeprecations, counted as the section counts them), the other deprecation changes in its note.
 * Until the section has the members, the classes alone (core.apiClassCounts), or "Loading" while it loads them unasked.
 */
function apiCell(cmp, classes) {
  // as the API section counts them: changes of what both releases have (a member new at B already deprecated is
  // added), nested classes left out
  const depCount = {};
  for (const r of apiDeprecations(cmp, classes).rows) {
    if (!r.added && !core.isNestedClass(r.name)) depCount[r.change] = (depCount[r.change] ?? 0) + 1;
  }
  const dep = (k) => depCount[k] ?? 0;
  const deprecations = [[dep('deprecated'), 'deprecated', 'dep'], [dep('removal'), 'for removal', 'rem']];
  const changed = apiChangedOf(cmp, classes);
  const movedTop = changed ? apiMovedOf(cmp, classes).filter((r) => !r.nested).length : 0;
  const more = [
    dep('undeprecated') ? `${fmt(dep('undeprecated'))} undeprecated` : null,
    dep('unremoval') ? `${fmt(dep('unremoval'))} no longer for removal` : null,
    dep('removed') ? `${fmt(dep('removed'))} removed after deprecation` : null,
    movedTop ? `${plural(movedTop, 'class', 'classes')} moved to another jar` : null,
  ].filter(Boolean).join(' · ');
  // without revisions: the deprecations alone
  if (!changed) return ledgerCell('API', 'sec-api', deprecations, more || null);
  const rows = cmp.apiRows;
  if (!rows && !cmp.apiFailed && changed.length && memberLines(classes.raw).length && apiAutoLoads(classes, changed)) {
    return ledgerCell('API', 'sec-api', [], 'Loading the API data…');
  }
  const n = rows ? core.apiRowCounts(rows) : core.apiClassCounts(classes.raw, cmp.aa, cmp.ab);
  const byKind = rows ? API_KINDS.map(([kind, label]) => {
    const x = Object.hasOwn(n.byKind, kind) ? n.byKind[kind] : null;
    const parts = x ? [x.added ? `+${fmt(x.added)}` : null, x.removed ? `−${fmt(x.removed)}` : null, x.changed ? `~${fmt(x.changed)}` : null].filter(Boolean) : [];
    return parts.length ? `${label} ${parts.join(' ')}` : null;
  }).filter(Boolean) : [];
  const what = rows
    ? `${byKind.length ? byKind.join(' · ') : 'no class or member added, removed or changed'}; nested classes left out`
    : 'Classes, nested ones left out; their members count once the API section loads them';
  return ledgerCell('API', 'sec-api', [[[n.added, 'added', 'add'], [n.removed, 'removed', 'rem'], [n.changed, 'changed', 'chg']], deprecations],
    [`${what[0].toUpperCase()}${what.slice(1)}.`, more || null]);
}

/** For core.diffApi: the jars of a class at A and at B (artifactIds), to pick among several. */
function jarsAtBoth(cmp, classes) {
  return (name) => {
    const row = classes.byName.get(name);
    if (!row) return [[], []];
    const at = (vi) => core.jarsOf(core.stateAt(row[2], vi)).map((j) => classes.raw.artifacts[j]);
    return [at(cmp.aa), at(cmp.ab)];
  };
}

/**
 * §4: the API of the io.quarkus jars between the two releases. From the classes files: the deprecation changes and the
 * classes that moved to another jar. With member data: every change of the classes whose API revision differs, whose
 * shard files load after the page when they are small (API_AUTO_BYTES, estimated), else when the section opens or on
 * request (always on request under Save-Data). Classes are compared by name across jars.
 */
function apiSection(cmp) {
  const V = store.V;
  const at = comparedAt(cmp, 'api');
  const note = h('p', { class: 'sec-note' }, 'The public and protected classes and members of the ', h('code', null, 'io.quarkus'), ' jars',
    at ? frag(',', at) : null, '. A class is compared by name, whichever jar has it, and one that moved to another jar says so.');
  const sec = h('section', { class: 'sec', id: 'sec-api', 'aria-labelledby': 'sec-api-h' }, sectionHead(4, 'API', 'sec-api-h', note));
  const gap = gapText(cmp, 'api');
  if (gap) return add(sec, [emptyNote(gap)]);
  if (cmp.aa === cmp.ab) return add(sec, [emptyNote(sameSnapshotText(cmp, 'api'))]);
  const slot = h('div', { class: 'api-slot' }, h('p', { class: 'empty', role: 'status' }, 'Loading the API data…'));
  sec.append(slot);
  loadClasses(linesSpanning(core.classLinesOf(V.list, [cmp.aa, cmp.ab]))).then((classes) => {
    const deps = apiDeprecations(cmp, classes);
    const changed = apiChangedOf(cmp, classes);
    const moved = apiMovedOf(cmp, classes);
    const memory = `${cmp.ia}:${cmp.ib}:api-classes`;
    if (!changed || !memberLines(classes.raw).length) {
      slot.replaceChildren(deprecationChanges(deps, moved, memory));
      return;
    }
    if (cmp.apiRows) {
      slot.replaceChildren(apiChanges(cmp));
      return;
    }
    // the summary counts the members once they are here (summaryLedger)
    const settled = (rows) => {
      cmp.apiRows = rows;
      cmp.onApiRows?.();
    };
    if (!changed.length) {
      // the same API at both, but maybe in other jars
      settled(core.sortApiRows([...moved]));
      slot.replaceChildren(moved.length ? apiChanges(cmp) : emptyNote('No class changed its API between these releases.'));
      return;
    }
    const files = shardFilesOf(classes, changed);
    const bytes = shardFilesBytes(classes, files);
    let loading = false;
    let note = null;
    const load = () => {
      if (loading) return;
      loading = true;
      // the deprecations stay in view while the files load
      const status = h('p', { class: 'empty', role: 'status' }, `Loading the members of ${plural(changed.length, 'class', 'classes')}…`);
      if (note?.isConnected) note.replaceWith(status);
      else slot.replaceChildren(status);
      classesApiRows(classes, changed).then(async (rowsByName) => {
        const enclosing = await enclosingApiRows(classes, changed);
        // a member a class no longer declares may be inherited, one it declares now may have been: from the supertypes
        const supers = await supertypeApiRows(classes, inheritanceSupertypes(rowsByName, cmp.aa, cmp.ab), [cmp.aa, cmp.ab], rowsByName);
        const rows = core.diffApi(rowsByName, cmp.aa, cmp.ab, jarsAtBoth(cmp, classes), (name) => enclosing.get(name),
          (name) => rowsByName.get(name) ?? supers.get(name) ?? null);
        cmp.apiFailed = false;
        settled(core.sortApiRows([...rows, ...moved]));
        slot.replaceChildren(apiChanges(cmp));
      }).catch((e) => {
        // the files that failed are forgotten (loadApiShard): another try fetches them again
        loading = false;
        cmp.apiFailed = true;
        cmp.onApiRows?.();
        slot.replaceChildren(missingData('the API member files', e?.message),
          h('p', null, h('button', { type: 'button', class: 'btn', onclick: load }, 'Try again')),
          deprecationChanges(deps, moved, memory));
      });
    };
    if (apiAutoLoads(classes, changed)) {
      load();
      return;
    }
    // larger member files: the deprecations and the moves now, the members when the section opens (unless saving
    // data) or on request
    const btn = h('button', { type: 'button', class: 'btn', onclick: load },
      `Load the API changes of ${plural(changed.length, 'class', 'classes')} (at least ${plural(files.length, 'file')}, ${aboutSize(bytes)})`);
    note = h('div', { class: 'note api-load' },
      h('p', null, `${plural(changed.length, 'class', 'classes')} changed their API. Their members are in ${plural(files.length, 'file')}, and their supertypes' in a few more, which load when you ask: below, the deprecations ${moved.length ? 'and the moves to another jar ' : ''}alone.`), btn);
    slot.replaceChildren(note, deprecationChanges(deps, moved, memory));
    if (!saveData()) {
      sec.addEventListener('sectiontoggle', () => {
        if (isSectionOpen(sec)) load();
      });
      if (isSectionOpen(sec)) load();
    }
  }).catch((e) => slot.replaceChildren(missingData('the API data', e?.message)));
  return sec;
}

const DEPRECATION_CHIPS = [
  ['removal', 'for removal'], ['deprecated', 'deprecated'], ['unremoval', 'no longer for removal'], ['undeprecated', 'undeprecated'], ['removed', 'removed'],
  ['moved', 'moved'],
];

/**
 * What the classes files tell of the API without the member files: the deprecation changes (core.diffDeprecations)
 * and the classes that moved to another jar (`moved`, core.movedApiClasses), by jar, then class. `memory` keeps the
 * jars the reader opened (moduleGroups).
 */
function deprecationChanges(deps, moved = [], memory = null) {
  const all = core.sortApiRows([...deps.rows, ...moved]);
  if (!all.length) return emptyNote('No class or member was deprecated, marked for removal or undeprecated between these releases.');
  const counts = {};
  for (const r of all) counts[r.change] = (counts[r.change] ?? 0) + 1;
  const state = { tags: new Set(), text: '' };
  const groups = moduleGroups({ memory, noun: 'jars' });
  const groupsEl = h('div', { class: 'cgroups' });
  const status = h('p', { class: 'filter-status', 'aria-live': 'polite' });
  const update = () => {
    const words = state.text.toLowerCase().split(/\s+/).filter(Boolean);
    const rows = all.filter((r) => (!state.tags.size || state.tags.has(r.change))
      && words.every((w) => `${r.name}\n${r.member ?? ''}\n${r.artifact}\n${(r.fromJars ?? []).join('\n')}`.toLowerCase().includes(w)));
    const byJar = [...core.groupBy(rows, (r) => r.artifact)];
    groups.listed(byJar.map(([jar]) => jar), words.join(' '));
    renderGroupPages(groupsEl, byJar, (jar, list) => apiJarGroup(jar, list, deprecationRow, groups),
      'No change matches these filters.', 'more jar');
    status.textContent = rows.length === all.length ? `${plural(rows.length, 'change')}.` : `Showing ${fmt(rows.length)} of ${plural(all.length, 'change')}.`;
  };
  update();
  return frag(
    h('div', { class: 'toolbar' },
      filterChips(DEPRECATION_CHIPS, counts, state.tags, update, 'Show only these kinds of deprecation change'),
      textFilter('Filter by class, member or jar', (v) => {
        state.text = v;
        update();
      })),
    listBar(status, groups),
    groupsEl);
}

/** What a deprecation change says: "for removal since 3.35 (declared); marked from …", "removed after deprecation"; or a move. */
function deprecationRow(r) {
  if (r.change === 'moved') return apiRow(r);
  const what = r.member ? memberSignature(r.name, r.member, r.value) : h('span', { class: 'muted' }, 'the class');
  const since = r.since || r.from != null ? h('span', { class: 'msince' }, deprecationSince(r)) : null;
  const change = r.change === 'removal' ? deprecationChip('removal', 'Newly marked for removal')
    : r.change === 'deprecated' ? deprecationChip('deprecated', 'Newly deprecated')
      : r.change === 'removed' ? chip('removed', 'removed after deprecation', 'Gone, and deprecated the last time it was there')
        : chip(r.change);
  return h('li', { class: 'mrow' }, change, what, since);
}

/**
 * A jar's group of API rows (moduleGroup, as `groups` says), by class: the class as a heading, its rows below
 * (`renderRow`); `noun` names a row for assistive technologies.
 */
function apiJarGroup(jar, list, renderRow, groups, noun = 'change') {
  const byClass = [...core.groupBy(list, (r) => r.name)];
  return moduleGroup(groups, jar, h('a', { href: link.extension(jar) }, jar), h('span', { class: 'cgroup-counts' }, plural(byClass.length, 'class', 'classes')),
    `${jar}: ${plural(list.length, noun)}`,
    byClass.map(([name, rows]) => h('div', { class: 'api-class' },
      h('a', { class: 'prop api-class-name', href: link.class(name) }, classNameEl(name)),
      h('ul', { class: 'mlist' }, rows.map(renderRow)))));
}

const API_CHIPS = [['removed', 'removed'], ['inherited', 'inherited'], ['moved', 'moved'], ['removal', 'for removal'], ['deprecated', 'deprecated'],
  ['unremoval', 'no longer for removal'], ['undeprecated', 'undeprecated'], ['return type', 'return type'], ['signature', 'signature'],
  ['modifiers', 'modifiers'], ['supertype', 'supertype'], ['declared', 'declared'], ['added', 'added']];

/** The API changes (core.diffApi) by jar, then class, with filters for the kinds, nested classes, protected members and constructors. */
function apiChanges(cmp) {
  const rows = cmp.apiRows;
  if (!rows.length) return emptyNote('No class or member was added, removed or changed between these releases.');
  const state = { tags: new Set(), text: '', nested: false, protected: true, constructors: true };
  const groups = moduleGroups({ memory: `${cmp.ia}:${cmp.ib}:api`, noun: 'jars' });
  const groupsEl = h('div', { class: 'cgroups' });
  const status = h('p', { class: 'filter-status', 'aria-live': 'polite' });
  const chips = h('div', { class: 'cls-chips' });
  const pool = () => rows.filter((r) => (state.nested || !r.nested) && (state.protected || !r.protected) && (state.constructors || !r.constructor));
  const update = () => {
    const words = state.text.toLowerCase().split(/\s+/).filter(Boolean);
    const all = pool();
    const shown = all.filter((r) => (!state.tags.size || state.tags.has(r.change)) && words.every((w) => r.text.includes(w)));
    const byJar = [...core.groupBy(shown, (r) => r.artifact)];
    groups.listed(byJar.map(([jar]) => jar), words.join(' '));
    renderGroupPages(groupsEl, byJar, (jar, list) => apiJarGroup(jar, list, apiRow, groups), 'No API change matches these filters.', 'more jar');
    // what the toggles leave out (nested classes are left out at first)
    const out = andList([
      !state.nested && rows.some((r) => r.nested) ? 'nested classes' : null,
      !state.protected && rows.some((r) => r.protected) ? 'protected classes and members' : null,
      !state.constructors && rows.some((r) => r.constructor) ? 'constructors' : null,
    ].filter(Boolean));
    if (!all.length) {
      status.textContent = `Every change is in ${out}, which are left out.`;
      return;
    }
    status.textContent = shown.length === all.length
      ? `${plural(all.length, 'change')} in ${plural(new Set(all.map((r) => r.name)).size, 'class', 'classes')}${out ? `, ${out} left out` : ''}.`
      : `Showing ${fmt(shown.length)} of ${plural(all.length, 'change')}${out ? `, ${out} left out` : ''}.`;
  };
  const fillChips = () => {
    const counts = {};
    for (const r of pool()) counts[r.change] = (counts[r.change] ?? 0) + 1;
    chips.replaceChildren(filterChips(API_CHIPS, counts, state.tags, update, 'Show only these kinds of API change'));
  };
  const toggle = (key, label, title) => {
    const n = rows.filter((r) => (key === 'nested' ? r.nested : key === 'protected' ? r.protected : r.constructor)).length;
    const b = h('button', { type: 'button', class: 'fchip tone-neu', 'aria-pressed': String(state[key]), disabled: n === 0, title },
      h('span', { class: 'fchip-dot', 'aria-hidden': 'true' }), label, h('span', { class: 'fchip-n' }, fmt(n)));
    b.addEventListener('click', () => {
      state[key] = !state[key];
      b.setAttribute('aria-pressed', String(state[key]));
      fillChips();
      update();
    });
    return b;
  };
  fillChips();
  update();
  return frag(
    h('div', { class: 'toolbar' },
      h('div', { class: 'cls-filters' }, chips,
        toggle('nested', 'nested classes', 'Also list the changes of nested classes, whose names have a $'),
        toggle('protected', 'protected', 'List the changes of protected classes and members, which only subclasses see'),
        toggle('constructors', 'constructors', 'List the changes of constructors')),
      textFilter('Filter by class, member or jar', (v) => {
        state.text = v;
        update();
      })),
    listBar(status, groups),
    groupsEl);
}

/** A class by its simple name (full name on hover), linked to its page, or to one of its members there; a JDK class has no page. */
function classRef(name, member = null) {
  const simple = core.simpleClassName(name).replace(/\$/g, '.');
  if (name.startsWith('java.')) return h('code', { class: 'mono', title: name }, simple);
  return h('a', { class: 'mono', href: member ? link.member(name, member) : link.class(name), title: name }, simple);
}

/** ", deprecated for removal" when the member a class inherits is deprecated there (its value in the supertype), else null. */
function inheritedDeprecation(value) {
  const d = core.deprecationOf(value);
  return d ? frag(' ', deprecationChip(d, d === 'removal' ? 'Deprecated for removal where it is declared now' : 'Deprecated where it is declared now')) : null;
}

/** One API change: its kind, the member (or "the class"), and what changed. */
function apiRow(r) {
  const label = r.change === 'removal' ? deprecationChip('removal', 'Newly marked for removal')
    : r.change === 'deprecated' ? deprecationChip('deprecated', 'Newly deprecated') : chip(r.change);
  // a removed member links to the class page too, which lists it among the removed ones
  const what = r.member ? memberSignature(r.name, r.member, r.value)
    : h('span', { class: 'muted' }, r.change === 'added' ? 'the class, new' : r.change === 'removed' ? 'the class, gone' : 'the class');
  const was = r.change === 'removed'
    ? h('span', { class: 'msince' }, r.was === 'removal' ? 'was deprecated for removal' : r.was ? 'was deprecated' : 'removed without deprecation')
    : r.change === 'inherited' ? h('span', { class: 'msince' }, r.moved ? 'moved to ' : 'inherited from ', classRef(r.from, r.member), inheritedDeprecation(r.inheritedValue))
      : r.change === 'moved' ? h('span', { class: 'msince' }, 'was in ', jarLinks(r.fromJars), r.toJars.length > 1 ? frag('; now in ', jarLinks(r.toJars)) : null)
        : r.change === 'declared' ? h('span', { class: 'msince' }, 'inherited from ', classRef(r.from, r.member), ' before') : null;
  const previous = r.previous ? h('span', { class: 'msince' }, 'was ', memberSignature(r.name, r.previous, r.previousValue, { linked: false })) : null;
  const details = r.details?.length ? h('span', { class: 'msince' }, r.details.join(', ')) : null;
  return h('li', { class: 'mrow' }, label, what, previous, details, was,
    r.protected ? chip('neutral', 'protected', 'Protected: for subclasses') : null);
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
    return notFoundView('Unknown config property', `No property named ${r.key} appears in any release of this dataset.`,
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
    ? h('p', { class: 'status-line' }, chip('added', 'current'), ` Present in ${newestRelease(lastCv, 'config data')}.`)
    : h('p', { class: 'status-line' }, chip('removed', 'removed'), lastRange
      ? ` Not in ${newestRelease(lastCv, 'config data')}; last seen in ${short(V.list[lastRange.to].v)}.`
      : ' Not in any release shown.');
  const widenedNote = hist.widened
    ? h('p', { class: 'note' }, `This property only appears in ${widenedKinds(hist.widenedBy)}, so they are shown here although they are hidden elsewhere.`)
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
    h('span', { class: 'kv-sub' }, `Releases: ${hist.ranges.map((x) => (x.from === x.to ? short(V.list[x.from].v) : `${short(V.list[x.from].v)} … ${short(V.list[x.to].v)}`)).join(', ')}`));

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
      sectionHead(1, 'Presence', 'presence-h', h('p', { class: 'sec-note' }, 'One cell per release, oldest on the left.', hiddenReleasesNote(hist, V.configVis)),
        tools),
      widenedNote,
      presenceStrip(prop, hist, cvs, cfg.raw.states)),
    h('section', { class: 'sec', 'aria-labelledby': 'history-h' },
      sectionHead(2, 'History', 'history-h', h('p', { class: 'sec-note' }, 'Every change between consecutive releases shown.')),
      timeline(hist.events, (ev) => propertyEventBody(ev, hist.cvs[0] === V.configVis[0]))));
}

/** " 2 pre-releases are hidden." when the switches hide some of the releases `snaps` that a history runs over. */
function hiddenReleasesNote(hist, snaps) {
  if (!hist.hiddenSnapshots) return null;
  const shown = new Set(hist.cvs);
  const hidden = snaps.filter((c) => !shown.has(c));
  return ` ${hiddenCount(hidden)} ${hidden.length === 1 ? 'is' : 'are'} hidden.`;
}

function presenceStrip(prop, hist, cvs, states) {
  const V = store.V;
  const events = new Map(hist.events.map((e) => [e.vi, e]));
  // past about 40 releases the cells get thin: dense cells keep the strip inside a phone's width
  const dense = cvs.length > 40;
  const strip = h('ol', { class: dense ? 'strip strip--dense' : 'strip', 'aria-label': 'Presence in each release' });
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
      h('span', null, h('i', { class: 'lg lg-ev' }), 'changed in this release'),
      anyPre ? h('span', null, h('i', { class: 'lg lg-pre' }), 'pre-release') : null));
}

/** `first`: the history starts at the first release with config data, not at the first one the switches show. */
function propertyEventBody(ev, first = true) {
  switch (ev.type) {
    case 'present':
      return frag(h('p', { class: 'tl-text' }, chip('neutral', 'present'), first
        ? ` In ${firstRelease(store.V.configVis, 'config data')}; it may be older.`
        : ' In the first release shown; earlier ones are hidden, so it may be older.'), stateLine(null, ev.state));
    case 'introduced':
      return frag(h('p', { class: 'tl-text' }, chip('added', 'introduced')), stateLine(null, ev.state));
    case 'reintroduced':
      return frag(h('p', { class: 'tl-text' }, chip('added', 'reintroduced')), stateLine(null, ev.state));
    case 'removed':
      return h('p', { class: 'tl-text' }, chip('removed'), ' No longer present in this release.');
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
  const [bomData, platform] = await Promise.all([loadSettled(['bom']), loadOptional('platform')]);
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
    if (platform?.byId.has(id)) return platformOnlyArtifact(id, bom, platform);
    return notFoundView('Unknown artifact', `${id} is not managed by quarkus-bom in any release of this dataset.`,
      null, h('div', { class: 'nf-search' }, searchBox({ id: nextId('qs-nf') })));
  }
  const vis = currentVis();
  const hist = art ? core.artifactHistory(bom.raw, id, V.list, { vis, widen: widenSteps() }) : null;
  const last = V.list[hist ? hist.last : vis.indices[vis.indices.length - 1]];
  const idEl = h('span', { class: 'prop-name' }, id);
  // With a BOM history the switches filter releases; on an extension-only page, the releases with extension data.
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
    hist ? withPlatformRuns(artifactRanges(hist, tools), bom, platform, id, hist.indices) : emptyNote(`${id} never appears in quarkus-bom in this dataset.`),
    extEntry ? extensionPanel(extEntry, more, hist ? null : tools) : null);
}

/** How the runs of core.platformArtifactRuns are read, after "The releases whose platform BOM …". */
const PLATFORM_RUNS_READ = 'Each row is a run of releases with the same two values; a release inside a run that has no platform BOM is named under it.';

/**
 * The managed-versions section of an artifact, followed, when the platform BOM manages it otherwise at some of the
 * releases `indices`, by those runs.
 */
function withPlatformRuns(sec, bom, platform, id, indices) {
  if (!platform) return sec;
  const runs = core.platformArtifactRuns(bom.raw, platform.raw, store.V.list, id, indices);
  if (runs.length) {
    sec.append(h('h3', { class: 'pbom-h' }, 'In the platform BOM'),
      h('p', { class: 'sec-note' }, 'The releases whose platform BOM, ', h('code', null, 'io.quarkus.platform:quarkus-bom'),
        ', which applications generated by code.quarkus.io import, manages this artifact otherwise than ', h('code', null, 'io.quarkus:quarkus-bom'),
        ', under this id or another one of the same artifact. ', PLATFORM_RUNS_READ),
      platformRunsTable(runs));
  }
  return sec;
}

/**
 * The runs of core.platformArtifactRuns: the releases (and those inside the run without a platform BOM), the platform
 * BOM's value and the core BOM's, each with the other id it is managed under, if any.
 */
function platformRunsTable(runs) {
  const V = store.V;
  const value = (raw, id) => frag(raw == null ? h('em', { class: 'none' }, 'not managed')
    : raw === '=' ? h('span', { class: 'follows' }, 'same as the Quarkus release') : h('code', { class: 'val' }, raw),
  id ? h('span', { class: 'cell-sub' }, 'as ', h('a', { href: link.artifact(id) }, id)) : null);
  return h('div', { class: 'table-scroll' },
    h('table', { class: 'tbl tbl-ranges tbl-pbom' },
      h('thead', null, h('tr', null, h('th', { scope: 'col' }, 'Quarkus releases'), h('th', { scope: 'col' }, 'Platform BOM'), h('th', { scope: 'col' }, 'Core BOM'))),
      h('tbody', null, runs.map((r) => h('tr', null,
        h('th', { scope: 'row', class: 'mono' }, rangeLabel(r.from, r.to), h('span', { class: 'cell-sub' }, plural(r.count, 'release')),
          r.without.length ? h('span', { class: 'cell-sub pbom-without' }, `no platform BOM: ${r.without.map((i) => short(V.list[i].v)).join(', ')}`) : null),
        h('td', null, value(r.platform, r.platformId)),
        h('td', null, value(r.core, r.coreId)))))));
}

/**
 * The page of an id that only platform BOMs manage: a Quarkiverse extension of the platform, say, or the platform's
 * way of writing an artifact that the core BOM writes otherwise (core.artifactSpellings), which it then names. Its runs
 * (core.platformArtifactRuns) are those of the releases the switches show, widened like an artifact history
 * (widenSteps) when only hidden releases have one.
 */
function platformOnlyArtifact(id, bom, platform) {
  const V = store.V;
  const { group } = core.splitId(id);
  const spellings = core.artifactSpellings(bom.raw, platform.raw, id).core;
  const runsOver = (indices) => core.platformArtifactRuns(bom.raw, platform.raw, V.list, id, indices);
  let runs = runsOver(currentVis().indices);
  let widenedBy = null;
  if (!runs.length) {
    for (const step of [...widenSteps(), { vis: null, kind: 'all' }]) {
      const rs = runsOver(step.vis ? step.vis.indices : V.list.map((_, k) => k));
      if (rs.length) {
        runs = rs;
        widenedBy = step.kind;
        break;
      }
    }
  }
  const idEl = h('span', { class: 'prop-name' }, id);
  // the core BOM's ids of it, linked; built afresh for each place
  const names = () => spellings.map((x, k) => frag(k ? (k === spellings.length - 1 ? ' and ' : ', ') : '', h('a', { href: link.artifact(x) }, h('code', null, x))));
  return frag(
    h('header', { class: 'page-head band' },
      h('p', { class: 'kicker' }, 'Managed dependency of the platform BOM · ', group),
      h('h1', { class: 'page-title page-title--mono', tabindex: '-1' }, idEl, copyButton(id, { label: 'Copy coordinates', target: idEl }))),
    spellings.length
      ? h('p', { class: 'status-line' }, chip('neutral', 'platform BOM spelling'),
        h('span', null, 'The platform BOM, ', h('code', null, 'io.quarkus.platform:quarkus-bom'), ', writes this artifact this way; ',
          h('code', null, 'io.quarkus:quarkus-bom'), ' manages the same artifact as ', names(), '.'))
      : h('p', { class: 'status-line' }, chip('neutral', 'platform BOM only'),
        h('span', null, 'The platform BOM, ', h('code', null, 'io.quarkus.platform:quarkus-bom'), ', manages it; ', h('code', null, 'io.quarkus:quarkus-bom'), ' never does.')),
    h('section', { class: 'sec', 'aria-labelledby': 'ranges-h' },
      sectionHead(1, spellings.length ? 'Where the BOMs differ' : 'Managed versions', 'ranges-h',
        h('p', { class: 'sec-note' }, spellings.length
          ? 'The releases whose platform BOM manages it at another version than the core BOM, or where only one of them manages it. '
          : 'The releases whose platform BOM manages it. ', PLATFORM_RUNS_READ),
        switchRow('sec-tools', releaseSwitches('timeline'), { rerender: true })),
      widenedBy ? h('p', { class: 'note' }, `Only ${widenedKinds(widenedBy)} ${spellings.length ? 'differ' : 'manage it'}, so they are shown here although they are hidden elsewhere.`) : null,
      runs.length
        ? platformRunsTable(runs)
        : emptyNote(spellings.length
          ? frag('Every release that has a platform BOM manages it at the version at which the core BOM manages ', names(), '.')
          : 'The two BOMs agree on it at every release.')));
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

/** `first` as for propertyEventBody, over the releases with extension data. */
function extensionEventBody(ev, first = true) {
  switch (ev.type) {
    case 'present':
      return h('p', { class: 'tl-text' }, chip('neutral', 'present'),
        first ? ` In ${firstRelease(store.V.extVis, 'extension data')}.` : ' In the first release shown; earlier ones are hidden.',
        ev.state.r ? ` Relocated to ${ev.state.r}.` : '');
    case 'introduced':
    case 'reintroduced':
      return h('p', { class: 'tl-text' }, chip('added', ev.type), ev.state.r ? ` As a relocation to ${ev.state.r}.` : statusChip(ev.state.s));
    case 'removed':
      return h('p', { class: 'tl-text' }, chip('removed'), ' No longer in this release.');
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
  // extension data does not need config, so a data set may have extension data and no config at all
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
      sectionHead(4, 'Config properties', 'ext-props-h', h('p', { class: 'sec-note' }, `Attributed to ${x.id} in ${newestRelease(latest, 'config data')}.`)),
      owned.length
        ? cappedList('plist', owned, (o) => h('li', null, h('a', { class: 'prop', href: link.property(o.k) }, o.s.n ?? o.k), phaseBadge(o.s.p)))
        : emptyNote(`None in ${short(V.list[latest].v)}.`));
  }
  return frag(
    h('section', { class: 'sec', 'aria-labelledby': 'ext-h' },
      sectionHead(2, 'Extension', 'ext-h', h('p', { class: 'sec-note' }, 'Metadata from quarkus-extension.yaml, in the newest release that has the extension.')),
      kv),
    h('section', { class: 'sec', 'aria-labelledby': 'ext-history-h' },
      sectionHead(3, 'Extension history', 'ext-history-h',
        h('p', { class: 'sec-note' }, 'Status, name and relocation changes between the releases shown.', hiddenReleasesNote(hist, V.extVis)),
        tools),
      hist.widened ? h('p', { class: 'note' }, `This extension only appears in ${widenedKinds(hist.widenedBy)}, so they are shown here although they are hidden elsewhere.`) : null,
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
  // a render that keeps the reader's place (a switch) does not move them to the route's member
  const moveToMember = !store.keeping;
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
    return notFoundView('Unknown class', `No class named ${r.name || '(empty)'} is in any release of this dataset.`,
      null, h('div', { class: 'nf-search' }, searchBox({ id: nextId('qs-nf') })));
  }
  const name = core.className(classes.raw, row);
  if (name !== r.name) canonicalize({ page: 'class', name, member: r.member });
  // the history runs over the releases of the lines loaded; the others are hidden by the switches anyway
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
      h('span', null, 'In ', jarLinks(jarNames(hist.jars)), ` in ${newestRelease(lastCv, 'class data')}.`))
    : h('p', { class: 'status-line' }, chip('removed', 'removed'), lastRange
      ? h('span', null, `Not in ${newestRelease(lastCv, 'class data')}; last seen in ${short(V.list[lastRange.to].v)}, in `, jarLinks(jarNames(lastJars)), '.')
      : ' Not in any release shown.');
  const tools = switchRow('sec-tools', snapshotSwitches('timeline', V.clsVis), { rerender: true });
  const widenedNote = hist.widened
    ? h('p', { class: 'note' }, `This class only appears in ${widenedKinds(hist.widenedBy)}, so they are shown here although they are hidden elsewhere.`)
    : null;
  // its API, when the data has it: deprecation from the classes files, members from the shard files
  const info = classApiInfo(classes, row, hist);
  const events = [...hist.events, ...(info ? core.deprecationEvents(info.dep, info.cvs).map((ev) => ({ ...ev, dep: true })) : [])]
    .sort((x, y) => x.vi - y.vi);

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
      sectionHead(0, 'Nested classes', 'cls-nested-h', h('p', { class: 'sec-note' }, `${plural(nestedRows.length, 'class', 'classes')} nested in this one, in any release.`)),
      cappedList('plist', nestedRows, (x) => {
        const nestedName = core.className(classes.raw, x);
        const now = lastCv != null && core.jarsOf(core.stateAt(x[2], lastCv)).length > 0;
        return h('li', null, h('a', { class: 'prop', href: link.class(nestedName) }, x[1]), now ? null : chip('removed', 'not in the latest'));
      }))
    : null;

  const nameEl = h('span', { class: 'prop-name' }, classNameEl(name));
  const kicker = h('span', null, nested ? 'Nested class' : 'Class');
  const sections = [
    h('section', { class: 'sec', 'aria-labelledby': 'cls-jars-h' },
      sectionHead(0, 'Jars', 'cls-jars-h',
        h('p', { class: 'sec-note' }, 'The jars that have the class in each release, oldest first. Each row is a run of releases in the same jars.',
          hiddenReleasesNote({ cvs: hist.cvs, hiddenSnapshots: V.clsVis.length - hist.cvs.length }, V.clsVis)),
        tools),
      widenedNote,
      classRuns(hist, jarNames)),
    h('section', { class: 'sec', 'aria-labelledby': 'cls-history-h' },
      sectionHead(0, 'History', 'cls-history-h', h('p', { class: 'sec-note' },
        info ? 'When the class came, moved to another jar, went, or was deprecated, between consecutive releases shown.'
          : 'When the class came, moved to another jar or went, between consecutive releases shown.')),
      timeline(events, (ev) => classEventBody(ev, jarNames, hist.cvs[0] === V.clsVis[0]))),
    info?.members.length ? deprecatedMembersSection(name, info) : null,
    ...(info?.hasMembers && info.at != null ? classMemberSections(name, info, classes, r.member ?? null, kicker, moveToMember) : []),
    nestedBlock,
  ].filter(Boolean);
  numberSections(sections);
  return frag(
    h('header', { class: 'page-head band' },
      h('p', { class: 'kicker' }, kicker, lastJars.length ? frag(' · ', jarLinks(jarNames(lastJars))) : null),
      h('h1', { class: 'page-title page-title--mono', tabindex: '-1' }, nameEl, copyButton(name, { label: 'Copy class name', target: nameEl })),
      h('div', { class: 'badges' },
        hist.present ? null : chip('removed'),
        info?.now ? deprecationChip(info.now.kind) : null,
        nested ? chip('neutral', core.isAnonymousClass(name) ? 'anonymous or local class' : 'nested class') : null)),
    statusLine,
    classDeprecationLine(info),
    kv,
    sections);
}

/* ---------- the API of a class: deprecation (classes files), members (shards) ---------- */

/** "for removal" or "deprecated" chips for a deprecation kind ('removal', 'deprecated'), with what they mean as title. */
function deprecationChip(kind, title) {
  return kind === 'removal'
    ? chip('removal', 'for removal', title ?? 'Deprecated, marked for removal: @Deprecated(forRemoval = true)')
    : chip('deprecated', 'deprecated', title ?? 'Deprecated: @Deprecated or a javadoc @deprecated tag');
}

/** "since 3.35 (declared); marked from 3.35.0.CR1": a deprecation's declared since and the release it is marked from. */
function deprecationSince(d) {
  const V = store.V;
  return frag(
    d.since ? frag('since ', h('code', { class: 'val' }, d.since), ' (declared)') : null,
    d.from != null ? frag(d.since ? '; ' : '', 'marked from ', verLink(V.list[d.from].v)) : null);
}

/**
 * What the API data says of a class row: null without api data; else its deprecation rows (`dep`), the releases with
 * api data that the history shows (`cvs`), the one the page describes (`at`): the newest of them, or, when the class is
 * not API there (`gone`), the last of them where it is; the class's deprecation there (`now`), the nearest enclosing
 * class deprecated there (`outer`: `{name, now}`), its members deprecated there (`members`), and whether members are
 * in shard files (`hasMembers`).
 */
function classApiInfo(classes, row, hist) {
  const api = classes.raw.api;
  if (!api || !api.versions?.length) return null;
  const V = store.V;
  const index = classes.index.get(row);
  const lines = core.apiLinesOf(classes.raw);
  const apiSet = new Set(api.versions);
  const cvs = hist.cvs.filter((vi) => apiSet.has(vi));
  // its revisions say where it is API; without them, its jars where it is there
  const isApi = (vi) => (Array.isArray(row[3]) ? core.apiRevisionAt(row, vi) != null : core.jarsOf(core.stateAt(row[2], vi)).length > 0);
  const newest = cvs.length ? cvs[cvs.length - 1] : null;
  const at = newest == null || isApi(newest) ? newest : [...cvs].reverse().find(isApi) ?? newest;
  const gone = at !== newest;
  const dep = core.depRowsOf(classes.raw, index);
  const now = at == null ? null : core.deprecationAt(dep, lines, at);
  let outer = null;
  if (at != null) {
    for (let name = hist.name; core.isNestedClass(name);) {
      name = name.slice(0, name.lastIndexOf('$'));
      const outerRow = classes.byName.get(name);
      const d = outerRow ? core.deprecationAt(core.depRowsOf(classes.raw, classes.index.get(outerRow)), lines, at) : null;
      if (d) {
        outer = { name, now: d };
        break;
      }
    }
  }
  const members = [];
  for (const d of api.dep) {
    if (d[0] !== index || d[2] == null || at == null) continue;
    const m = core.deprecationAt(core.depRowsOf(classes.raw, index, d[2]), lines, at, true);
    if (m && !members.some((x) => x.key === d[2])) members.push({ key: d[2], now: m });
  }
  members.sort((x, y) => (x.key < y.key ? -1 : 1));
  const hasMembers = Array.isArray(row[3]) && memberLines(classes.raw).length > 0;
  return { dep, cvs, at, newest, gone, now, outer, members, hasMembers, versions: V.list };
}

/** The status line of a deprecated class, or of a class nested in a deprecated one; null otherwise. */
function classDeprecationLine(info) {
  if (!info) return null;
  const V = store.V;
  if (info.now) {
    const kind = info.now.kind === 'removal' ? 'Deprecated for removal' : 'Deprecated';
    const since = info.now.since || info.now.from != null;
    // a class gone since: as it was the last time it was API
    return h('p', { class: 'status-line' }, deprecationChip(info.now.kind),
      info.gone
        ? h('span', null, `${kind} in ${short(V.list[info.at].v)}, ${apiAtWhat(info)}`, since ? ': ' : '', deprecationSince(info.now), '.')
        : h('span', null, kind, since ? ' ' : '', deprecationSince(info.now), '.'));
  }
  if (info.outer) {
    return h('p', { class: 'status-line' }, chip('deprecated', 'in a deprecated class', 'The class it is nested in is deprecated'),
      h('span', null, 'Nested in ', h('a', { class: 'mono', href: link.class(info.outer.name) }, core.simpleClassName(info.outer.name)), ', which is ',
        info.outer.now.kind === 'removal' ? 'deprecated for removal' : 'deprecated', '.'));
  }
  return null;
}

/** What release the API parts of a class page describe (classApiInfo's `at`), in words. */
function apiAtWhat(info) {
  return info.gone ? 'the last release shown where it is public API' : 'the newest release shown with API data';
}

/** A member of a class: its signature with simple names (full names on hover), as a link to it on the class page. */
function memberSignature(name, key, value, { linked = true } = {}) {
  const p = core.prettyMember(key, { className: name, varargs: !!core.parseApiValue(value, true)?.varargs });
  if (!p) return h('code', { class: 'msig' }, key);
  const sig = h('code', { class: 'msig', title: p.full }, p.simple);
  return linked ? h('a', { class: 'msig-link', href: link.member(name, key) }, sig) : sig;
}

/** The members of a class deprecated in the newest release shown (from the classes files: no shard needed). */
function deprecatedMembersSection(name, info) {
  const V = store.V;
  return h('section', { class: 'sec', 'aria-labelledby': 'cls-dep-h' },
    sectionHead(0, 'Deprecated members', 'cls-dep-h',
      h('p', { class: 'sec-note' }, `The fields, methods and constructors of the class deprecated in ${short(V.list[info.at].v)}, ${apiAtWhat(info)}.`)),
    cappedList('mlist', info.members, (m) => h('li', { class: 'mrow' },
      memberSignature(name, m.key, m.now.value),
      h('span', { class: 'mchips' }, deprecationChip(m.now.kind)),
      h('span', { class: 'msince' }, deprecationSince(m.now)))));
}

/** The chips of a member value: static, abstract, final, default, protected, experimental, deprecated, for removal. */
function memberChips(parsed) {
  if (!parsed) return null;
  const flag = (on, text, title) => (on ? chip('neutral', text, title) : null);
  return [
    flag(parsed.access === 'protected', 'protected', 'Protected: for subclasses'),
    flag(parsed.static, 'static'),
    flag(parsed.abstract, 'abstract'),
    flag(parsed.final, 'final'),
    flag(parsed.default, 'default', 'A default method of an interface'),
    flag(parsed.enumConstant, 'enum constant'),
    parsed.experimental ? chip('extension', 'experimental', 'Annotated @Experimental (io.smallrye.common.annotation)') : null,
    parsed.deprecated ? deprecationChip(parsed.forRemoval ? 'removal' : 'deprecated') : null,
  ];
}

const MEMBER_GROUPS = [['constructor', 'Constructors'], ['field', 'Fields'], ['method', 'Methods']];
const MEMBERS_PAGE = 100; // members listed before "show more"

/**
 * The declaration of a class from its value: "public abstract class Application implements Closeable", simple names
 * with full ones on hover.
 */
function classDeclaration(name, value) {
  const c = core.parseApiValue(value);
  if (!c) return null;
  const simple = (full) => h('abbr', { title: full, class: 'decl-type' }, core.simpleClassName(full).replace(/\$/g, '.'));
  const words = [c.access, c.static ? 'static' : null, c.abstract && c.kind !== 'interface' ? 'abstract' : null, c.final ? 'final' : null,
    c.kind === 'annotation' ? '@interface' : c.kind].filter(Boolean).join(' ');
  return h('p', { class: 'decl' },
    h('code', null, `${words} `, h('strong', null, core.simpleClassName(name).replace(/^.*\$/, '')),
      c.extends ? frag(' extends ', simple(c.extends)) : null,
      c.implements.length ? frag(c.kind === 'interface' ? ' extends ' : ' implements ', c.implements.map((x, k) => frag(k ? ', ' : null, simple(x)))) : null),
    c.experimental ? chip('extension', 'experimental', 'Annotated @Experimental (io.smallrye.common.annotation)') : null);
}

/**
 * The Members and API history sections of a class with member data: they fill in when its shard files arrive. The
 * members are those of the release the page describes (`info.at`, classApiInfo); `member` (a route's) is highlighted, and
 * scrolled to and focused unless `move` is false (a render that keeps the reader's place).
 */
function classMemberSections(name, info, classes, member, kicker, move = true) {
  const V = store.V;
  const membersBody = h('div', { class: 'members-slot' }, h('p', { class: 'empty', role: 'status' }, 'Loading the members…'));
  const historyBody = h('div', { class: 'members-slot' }, h('p', { class: 'empty', role: 'status' }, 'Loading the API history…'));
  const members = h('section', { class: 'sec', id: 'sec-members', 'aria-labelledby': 'cls-members-h' },
    sectionHead(0, 'Members', 'cls-members-h',
      h('p', { class: 'sec-note' }, `The public and protected fields, constructors and methods in ${short(V.list[info.at].v)}, ${apiAtWhat(info)}. `,
        '“Since” is the first release shown from which a member is there without a break.')),
    membersBody);
  const history = h('section', { class: 'sec', 'aria-labelledby': 'cls-api-h' },
    sectionHead(0, 'API history', 'cls-api-h', h('p', { class: 'sec-note' }, 'How the API of the class changed between consecutive releases shown with API data.')),
    historyBody);
  const seq = renderSeq;
  const load = (reveal) => Promise.all([classesApiRows(classes, [name]), enclosingApiRows(classes, [name])]).then(async ([byName, enclosing]) => {
    if (seq !== renderSeq) return;
    const rows = byName.get(name) ?? [];
    const outer = enclosing.get(name) ?? null;
    const render = (rowsOf, first) => {
      const m = core.classMembers(rows, info.cvs, info.at, name, rowsOf);
      const parsed = core.parseApiValue(m.value);
      if (parsed && kicker) kicker.textContent = `${parsed.kind === 'annotation' ? 'Annotation' : parsed.kind[0].toUpperCase() + parsed.kind.slice(1)}${core.isNestedClass(name) ? ' (nested)' : ''}`;
      membersBody.replaceChildren(membersView(name, m, parsed, member, info.at));
      historyBody.replaceChildren(apiHistoryView(rows, info, outer, rowsOf));
      if (member) revealMember(members, member, first && reveal);
    };
    // its own members at once; then, when it lost or gained a member, again with the supertypes it ever had, which
    // tell a member it inherits from a removed one and a member it declares now from an added one
    render((n) => byName.get(n) ?? null, true);
    const seeds = core.classApiHistory(rows, info.cvs, outer).some((ev) => !ev.first && (ev.removed || ev.added))
      ? rows.flatMap((r) => r.h.flatMap(([, value]) => core.supertypesOf(value))) : [];
    if (!seeds.length) return;
    let supers;
    try {
      supers = await supertypeApiRows(classes, seeds, info.cvs, byName);
    } catch (e) {
      if (seq === renderSeq) historyBody.append(emptyNote(`The member files of its supertypes could not be loaded${e?.message ? ` (${e.message})` : ''}, so a member it inherits may read as removed, or as added.`, true));
      return;
    }
    if (seq !== renderSeq || !supers.size) return;
    render((n) => byName.get(n) ?? supers.get(n) ?? null, false);
  }).catch((e) => {
    if (seq !== renderSeq) return;
    // the files that failed are forgotten (loadApiShard): another try fetches them again
    const retry = h('button', { type: 'button', class: 'btn' }, 'Try again');
    retry.addEventListener('click', () => {
      membersBody.replaceChildren(h('p', { class: 'empty', role: 'status' }, 'Loading the members…'));
      historyBody.replaceChildren(h('p', { class: 'empty', role: 'status' }, 'Loading the API history…'));
      load(true);
    });
    membersBody.replaceChildren(missingData('the member files of this class', e?.message), h('p', null, retry));
    historyBody.replaceChildren(missingData('the member files of this class', e?.message));
  });
  load(move);
  return [members, history];
}

/** The members of a class (core.classMembers) by kind, with a filter, paging, and the removed ones collapsed. */
function membersView(name, m, parsed, target, at) {
  const V = store.V;
  const kind = parsed?.kind ?? 'class';
  const since = (vi) => h('span', { class: 'msince' }, 'since ', verLink(V.list[vi].v));
  const row = (x, extra) => {
    const implicit = core.implicitMember(x.key, kind, name);
    return h('li', { class: `mrow${implicit ? ' is-implicit' : ''}`, 'data-key': x.key, title: implicit ? `Written by the compiler for every ${kind}` : null },
      memberSignature(name, x.key, x.value),
      h('span', { class: 'mchips' }, memberChips(x.parsed)),
      extra);
  };
  const removed = m.removed.length
    ? h('details', { class: 'mremoved' },
      h('summary', null, `Removed members (${fmt(m.removed.length)})`),
      // a removed member's deprecation is in its note, not in chips
      h('ul', { class: 'mlist' }, m.removed.map((x) => row({ ...x, parsed: x.parsed && { ...x.parsed, deprecated: false, forRemoval: false } },
        h('span', { class: 'msince' }, removedNote(x))))))
    : null;
  // members it declared in releases shown and inherits now: still there, from a supertype
  const inherited = m.inherited.length
    ? h('details', { class: 'mremoved' },
      h('summary', { title: 'No longer declared by this class: a supertype declares them, so the class still has them' },
        `Now inherited (${fmt(m.inherited.length)})`),
      // the member as its supertype declares it now, java.lang.Object's as the class had it
      h('ul', { class: 'mlist' }, m.inherited.map((x) => row(x.inheritedValue ? { ...x, parsed: core.parseApiValue(x.inheritedValue, true) } : x,
        h('span', { class: 'msince' }, 'from ', classRef(x.from, x.key), '; last declared here in ', verLink(V.list[x.last].v))))))
    : null;
  // a class gone since, or no longer API: what it had in the releases shown
  if (!m.value) return frag(emptyNote(`The class is not public API in ${short(V.list[at].v)}.`), removed);
  const state = { text: '' };
  const groupsEl = h('div', { class: 'mgroups' });
  const status = h('p', { class: 'filter-status', 'aria-live': 'polite' });
  const update = () => {
    const words = state.text.toLowerCase().split(/\s+/).filter(Boolean);
    const match = (x) => !words.length || words.every((w) => `${x.key}\n${x.pretty?.full ?? ''}`.toLowerCase().includes(w));
    const shown = m.members.filter(match);
    groupsEl.replaceChildren(...MEMBER_GROUPS.map(([k, title]) => {
      const list = shown.filter((x) => core.memberKind(x.key) === k);
      if (!list.length) return null;
      const ul = h('ul', { class: 'mlist' });
      const fill = (from, to) => {
        for (let i = from; i < to; i++) ul.append(row(list[i], since(list[i].since)));
      };
      const first = state.text || list.some((x) => x.key === target) ? list.length : Math.min(list.length, MEMBERS_PAGE);
      fill(0, first);
      const block = h('div', { class: 'mgroup' }, h('h3', { class: 'mgroup-h' }, title, h('span', { class: 'mgroup-n' }, ` ${fmt(list.length)}`)), ul);
      if (list.length > first) {
        const btn = moreButton(`Show ${plural(list.length - first, `more ${title.toLowerCase().replace(/s$/, '')}`, `more ${title.toLowerCase()}`)}`, () => {
          fill(first, list.length);
          btn.remove();
          ul.children[first]?.querySelector('a')?.focus();
        });
        block.append(btn);
      }
      return block;
    }).filter(Boolean));
    if (!shown.length && m.members.length) groupsEl.append(emptyNote('No member matches this filter.'));
    status.textContent = shown.length === m.members.length ? `${plural(m.members.length, 'member')}.` : `Showing ${fmt(shown.length)} of ${plural(m.members.length, 'member')}.`;
  };
  update();
  return frag(
    classDeclaration(name, m.value),
    m.members.length > 8 ? h('div', { class: 'toolbar' }, textFilter('Filter members by name or type', (v) => {
      state.text = v;
      update();
    })) : null,
    status,
    groupsEl,
    removed,
    inherited);
}

/** "last in 3.27.4" or "was deprecated since 3.15 (declared); last in 3.27.4" for a member gone since. */
function removedNote(x) {
  const V = store.V;
  const last = frag('last in ', verLink(V.list[x.last].v));
  if (!x.parsed?.deprecated) return last;
  return frag(x.parsed.forRemoval ? 'was deprecated for removal' : 'was deprecated', x.parsed.since ? frag(' since ', h('code', { class: 'val' }, x.parsed.since)) : null, '; ', last);
}

/** Marks a member of the Members section, and unless `move` is false scrolls to it (a removed one opens its list first). */
function revealMember(section, key, move = true) {
  const el = [...section.querySelectorAll('.mrow')].find((li) => li.dataset.key === key);
  if (!el) {
    section.querySelector('.members-slot')?.prepend(emptyNote('This class has no such member in the releases shown.', true));
    return;
  }
  el.classList.add('is-target');
  if (!move) return;
  const details = el.closest('details');
  if (details) details.open = true;
  el.setAttribute('tabindex', '-1');
  try {
    el.scrollIntoView({ block: 'center', behavior: reducedMotion() ? 'auto' : 'smooth' });
    el.focus({ preventScroll: true });
  } catch {
    /* ignore */
  }
}

/**
 * The API history of a class (core.classApiHistory over the releases shown with API data) as a timeline; `supertypes`
 * gives the rows of a class by binary name, or null, to tell the members it inherits from those it lost.
 */
function apiHistoryView(rows, info, enclosing = null, supertypes = () => null) {
  const events = core.classApiHistory(rows, info.cvs, enclosing, supertypes);
  if (!events.length) return emptyNote('The class is not public API in any release shown.');
  // deprecation is in the History above; here the members and the declaration
  const quiet = new Set(['deprecated', 'marked for removal', 'no longer marked for removal', 'undeprecated']);
  const shown = events.filter((ev) => ev.first || ev.added || ev.removed || ev.inherited?.length || ev.declared?.length || ev.changed || ev.changes.some((c) => !quiet.has(c)));
  return timeline(shown, (ev) => {
    if (ev.first) {
      return h('p', { class: 'tl-text' }, chip('neutral', 'API'),
        ` Public API${ev.again ? ' again' : ''} with ${plural(ev.added, 'member')}${ev.vi === info.cvs[0] ? ' in the first release shown' : ''}.`);
    }
    const parts = [
      ev.added || ev.removed ? h('span', { class: 'api-delta' }, countMark('add', '+', ev.added, ev.added === 1 ? 'member added' : 'members added'),
        ev.added && ev.removed ? ' ' : null, countMark('rem', '−', ev.removed, ev.removed === 1 ? 'member removed' : 'members removed'),
        h('span', { 'aria-hidden': 'true' }, ev.added + ev.removed === 1 ? ' member' : ' members')) : null,
      ...(ev.inherited ?? []).map((x) => h('span', null, `${plural(x.count, 'member')} ${x.moved ? 'moved to' : 'now inherited from'} `, classRef(x.from))),
      ...(ev.declared ?? []).map((x) => h('span', null, `${plural(x.count, 'member')} declared here now, inherited from `, classRef(x.from), ' before')),
      ev.changed ? `${plural(ev.changed, 'member')} changed` : null,
      ...ev.changes.filter((c) => !quiet.has(c)),
    ].filter(Boolean);
    return h('p', { class: 'tl-text' }, parts.map((p, k) => frag(k ? ' · ' : null, p)));
  });
}

/** Numbers sections (`sectionHead` with 0) in order, §1 on, leaving out hidden ones; again when one appears later. */
function numberSections(sections) {
  let n = 0;
  for (const sec of sections) {
    if (!sec || sec.hidden) continue;
    const no = sec.querySelector(':scope > .sec-head .sec-no');
    if (no) no.textContent = `§${++n}`;
  }
}

/** The runs of a class history as a lane and a table: the jars of each run of releases, and the change it starts with. */
function classRuns(hist, jarNames) {
  const V = store.V;
  const first = V.list[hist.cvs[0]];
  const last = V.list[hist.cvs[hist.cvs.length - 1]];
  const events = new Map(hist.events.map((ev) => [ev.vi, ev]));
  const lane = h('div', { class: 'lane', role: 'img', 'aria-label': `The jars of the class across ${plural(hist.cvs.length, 'release')}, oldest on the left. The table below lists the same runs.` });
  const tbody = h('tbody');
  hist.runs.forEach((run, i) => {
    const jars = jarNames(run.jars).join(', ');
    lane.append(h('div', { class: `lane-seg${run.jars.length ? '' : ' is-off'}${i % 2 ? ' is-alt' : ''}`, style: `flex-grow:${run.count}`, title: `${rangeLabel(run.from, run.to)}: ${jars || 'in no jar'}` },
      jars ? h('span', { class: 'lane-label' }, jars) : null));
    const ev = events.get(run.from);
    const change = !ev || ev.type === 'present' ? null : chip(ev.type);
    tbody.append(h('tr', { class: run.jars.length ? null : 'is-same' },
      h('th', { scope: 'row', class: 'mono' }, rangeLabel(run.from, run.to),
        h('span', { class: 'cell-sub' }, rangeCount(run))),
      h('td', null, run.jars.length ? jarLinks(jarNames(run.jars)) : h('em', { class: 'none' }, run.before ? 'not yet in any jar' : 'in no jar')),
      h('td', null, change)));
  });
  return frag(
    lane,
    h('div', { class: 'strip-axis', 'aria-hidden': 'true' }, h('span', null, short(first.v)), h('span', null, short(last.v))),
    h('div', { class: 'table-scroll' },
      h('table', { class: 'tbl tbl-ranges' },
        h('thead', null, h('tr', null, h('th', { scope: 'col' }, 'Quarkus releases'), h('th', { scope: 'col' }, 'Jar'), h('th', { scope: 'col' }, 'Change'))),
        tbody)));
}

/** `first` as for propertyEventBody, over the releases with class data. A sentence with links is one span of the flex row. */
function classEventBody(ev, jarNames, first = true) {
  const jars = (list) => jarLinks(jarNames(list));
  if (ev.dep) {
    const since = ev.since ? frag(' since ', h('code', { class: 'val' }, ev.since), ' (declared)') : null;
    if (ev.type === 'removal') return h('p', { class: 'tl-text' }, deprecationChip('removal'), h('span', null, ev.first ? 'Already marked for removal' : 'Marked for removal', since, '.'));
    if (ev.type === 'deprecated') {
      return h('p', { class: 'tl-text' }, deprecationChip('deprecated'),
        h('span', null, ev.from === 'removal' ? 'No longer marked for removal, still deprecated' : ev.first ? 'Already deprecated' : 'Deprecated', since, '.'));
    }
    return h('p', { class: 'tl-text' }, chip('undeprecated'), h('span', null, 'No longer deprecated.'));
  }
  switch (ev.type) {
    case 'present':
      return h('p', { class: 'tl-text' }, chip('neutral', 'present'), h('span', null, first
        ? `In ${firstRelease(store.V.clsVis, 'class data')}, so it may be older: in `
        : 'In the first release shown; earlier ones are hidden. In ', jars(ev.jars), '.'));
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
          preChip(x)));
      })))));
  }
  const tools = switchRow('page-tools', releaseSwitches('releases'), { rerender: true });
  const hiddenNow = V.list.map((_, i) => i).filter((i) => !vis.visible[i]);
  return frag(
    h('header', { class: 'page-head band' },
      h('p', { class: 'kicker' }, 'Releases'),
      h('h1', { class: 'page-title', tabindex: '-1' }, 'All releases'),
      h('p', { class: 'lede' }, `${plural(vis.indices.length, 'release')} in ${plural(shownStreams.length, 'stream')}, newest first`,
        hiddenNow.length ? `, ${hiddenCount(hiddenNow)} hidden` : null, `. ${plural(ltsCount, 'stream')} marked LTS.`),
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
  // the release's config, as it was extracted; in a data set where it has none of its own, whose its comparisons use
  // (the counts below say "at" which release when they are another's)
  const source = cv != null && V.list[cv].configSource ? SOURCE_TITLE[V.list[cv].configSource] ?? V.list[cv].configSource : null;
  if (cv === i) {
    row('Config source', source ?? h('em', { class: 'none' }, 'unknown'));
  } else if (cv != null) {
    row('Config', h('em', { class: 'none' }, 'none of its own'),
      h('span', { class: 'kv-sub' }, 'Comparisons with this release use the config data of ', verLink(V.list[cv].v), '.'),
      source ? h('span', { class: 'kv-sub' }, source) : null);
  } else {
    const shown = ev == null ? 'dependency changes only' : 'dependency and extension changes only';
    row('Config', isPre
      ? frag(h('em', { class: 'none' }, 'none'), h('span', { class: 'kv-sub' }, `Pre-releases are compared only with their own config data, so comparisons with this release show ${shown}.`))
      : h('em', { class: 'none' }, 'none: no config data for this release'));
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
    propsN.textContent = 'no config data';
  } else {
    loadFile('config').then((c) => {
      propsN.textContent = `${fmt(c.raw.props.filter((p) => core.stateAt(p.h, cv) != null).length)}${cv === i ? '' : `, at ${short(V.list[cv].v)}`}`;
    }).catch(() => {
      propsN.textContent = 'config.json unavailable';
    });
  }
  if (ev == null) {
    extsN.textContent = 'no extension data';
  } else {
    loadFile('extensions').then((e) => {
      extsN.textContent = `${fmt(e.raw.exts.filter((xx) => core.stateAt(xx.h, ev) != null).length)}${ev === i ? '' : `, at ${short(V.list[ev].v)}`}`;
    }).catch(() => {
      extsN.textContent = 'extensions.json unavailable';
    });
  }
  // the deprecated API, when the data has API: a fact, and a section listing it by jar
  const depSec = h('section', { class: 'sec', id: 'sec-deprecated', 'aria-labelledby': 'vd-h', hidden: true });
  if (V.apiVis.length) {
    const depN = h('span', null, '…');
    kv.append(h('dt', null, 'Deprecated API'), h('dd', null, depN));
    const av = core.apiVersionFor(i, V.list, V.apiVis);
    if (av == null) {
      depN.replaceChildren(h('em', { class: 'none' }, 'no API data for this release'));
    } else {
      loadClasses(apiLinesFor(av)).then((classes) => {
        const items = core.deprecationsAt(classes.raw, av);
        depN.replaceChildren(items.length ? frag(deprecatedCounts(items), av === i ? null : h('span', { class: 'muted' }, ` (API data of ${short(V.list[av].v)})`),
          h('span', { class: 'kv-sub' }, h('a', { href: link.deprecated(x.v) }, 'The deprecated API on a page of its own'))) : 'none');
        if (!items.length) return;
        add(depSec, [sectionHead(0, 'Deprecated API', 'vd-h', h('p', { class: 'sec-note' }, `The classes and members of the io.quarkus jars deprecated in ${short(x.v)}, by jar.`)), deprecatedList(items, `deprecated:${x.v}`)]);
        depSec.hidden = false;
        numberSections([...depSec.parentNode?.children ?? []].filter((el) => el.classList.contains('sec')));
      }).catch(() => depN.replaceChildren('the API data is unavailable'));
    }
  }
  // the platform BOM, when platform.json is there: a fact, and a section on how it differs from the core BOM
  const platformN = h('span', null, '…');
  const platformFact = [h('dt', null, 'Platform BOM'), h('dd', null, platformN)];
  kv.append(...platformFact);
  const platformSec = h('section', { class: 'sec', id: 'sec-platform', 'aria-labelledby': 'vp-h', hidden: true });
  Promise.all([loadFile('bom'), loadOptional('platform')]).then(([b, p]) => {
    if (!p) {
      platformFact.forEach((el) => el.remove());
      return;
    }
    if (!core.hasPlatformBom(p.raw, i)) {
      platformN.replaceChildren(h('em', { class: 'none' }, 'none of this version'));
      return;
    }
    const d = core.platformDifferences(b.raw, p.raw, V.list, i);
    const n = d.changed.length;
    platformN.replaceChildren(h('span', { class: 'mono' }, `io.quarkus.platform:quarkus-bom ${short(x.v)}`),
      h('span', { class: 'kv-sub' }, n ? `${plural(n, 'managed version')} differ${n === 1 ? 's' : ''} from the core BOM.` : 'No managed version differs from the core BOM.'));
    add(platformSec, platformSection(x, d));
    platformSec.hidden = false;
    numberSections([...platformSec.parentNode?.children ?? []].filter((el) => el.classList.contains('sec')));
  }).catch(() => {
    platformFact.forEach((el) => el.remove());
  });

  return frag(
    h('header', { class: 'page-head band' },
      h('p', { class: 'kicker' }, isPre ? 'Pre-release' : 'Release', ' · stream ', x.stream || core.stream(x.v)),
      h('h1', { class: 'page-title page-title--ver', tabindex: '-1' }, short(x.v)),
      h('div', { class: 'badges' }, dateEl(x.date), versionChips(x), V.visOff.upcoming[i] ? upcomingChip() : null)),
    h('section', { class: 'sec', 'aria-labelledby': 'vq-h' },
      sectionHead(1, 'Compare with', 'vq-h', h('p', { class: 'sec-note' }, `What changed on the way to ${short(x.v)}.`)),
      quick, forward),
    h('section', { class: 'sec', 'aria-labelledby': 'vf-h' }, sectionHead(2, 'Facts', 'vf-h'), kv),
    platformSec,
    depSec,
    h('nav', { class: 'pager', 'aria-label': 'Neighbouring releases' },
      nb.older >= 0 ? h('a', { class: 'pager-link', href: link.version(V.list[nb.older].v) }, h('span', { class: 'pager-k' }, 'Older'), h('span', { class: 'ver' }, short(V.list[nb.older].v))) : h('span'),
      nb.newer >= 0 ? h('a', { class: 'pager-link pager-link--next', href: link.version(V.list[nb.newer].v) }, h('span', { class: 'pager-k' }, 'Newer'), h('span', { class: 'ver' }, short(V.list[nb.newer].v))) : h('span')));
}

/**
 * The "Platform BOM" section of a release page: how its platform BOM (`d`, core.platformDifferences) differs from its
 * core BOM, the version changes grouped like the dependency changes of a comparison (core BOM → platform BOM), and
 * the artifacts only one of them manages in a collapsed list.
 */
function platformSection(x, d) {
  const v = short(x.v);
  const rows = d.changed.map((r) => {
    const id = r.coreId ?? r.id; // the core BOM's id has an artifact page
    return { id, ...core.splitId(id), from: r.core, to: r.platform, kind: core.bomChangeKind(r.core, r.platform) };
  });
  const groups = core.groupBomRows(rows);
  const only = [d.added.length ? `also manages ${plural(d.added.length, 'artifact')} that the core BOM does not` : null,
    d.removed.length ? `does not manage ${plural(d.removed.length, 'artifact')} that the core BOM manages` : null].filter(Boolean);
  const list = (items, value) => h('ul', { class: 'pbom-list' }, items.map((r) => h('li', null,
    h('a', { href: link.artifact(r.id) }, r.id), ' ', h('code', { class: 'val' }, value(r)))));
  return [
    sectionHead(3, 'Platform BOM', 'vp-h',
      h('p', { class: 'sec-note' }, 'How ', h('code', null, 'io.quarkus.platform:quarkus-bom'), ` ${v}, the BOM that applications generated by code.quarkus.io import, differs from `,
        h('code', null, 'io.quarkus:quarkus-bom'), ` ${v}. Versions read core BOM → platform BOM.`)),
    groups.length
      ? [h('p', { class: 'pbom-lead' }, `It manages ${plural(rows.length, 'artifact')} at other versions:`), h('div', { class: 'dgroups' }, groups.map((g) => depGroup(g, g.items)))]
      : emptyNote('Every artifact that both BOMs manage has the same version in both.'),
    only.length
      ? h('details', { class: 'pbom-more' },
        h('summary', null, `It ${only.join(', and ')}.`),
        d.added.length ? [h('h3', { class: 'pbom-h' }, 'Only in the platform BOM'), list(d.added, (r) => r.platform)] : null,
        d.removed.length ? [h('h3', { class: 'pbom-h' }, 'Only in the core BOM'), list(d.removed, (r) => r.core)] : null)
      : null,
    d.structural
      ? h('p', { class: 'sec-note pbom-foot' }, `${plural(d.structural, 'other entry', 'other entries')} of the platform BOM ${d.structural === 1 ? 'stands' : 'stand'} for ${d.structural === 1 ? 'an entry' : 'entries'} of the core BOM at the same version, so ${d.structural === 1 ? 'it is' : 'they are'} no difference: the same artifact with its type or classifier written otherwise, or the platform's own descriptor and properties in place of the core BOM's.`)
      : null,
  ];
}

/* ---------- Deprecated API (the release page and #/deprecated/<version>) ---------- */

const DEPRECATED_CHIPS = [['removal', 'for removal'], ['deprecated', 'deprecated only']];

/** "12 classes and 340 members, 25 of them for removal" for the items of core.deprecationsAt. */
function deprecatedCounts(items) {
  const classes = items.filter((x) => !x.member).length;
  const removal = items.filter((x) => x.kind === 'removal').length;
  return `${plural(classes, 'class', 'classes')} and ${plural(items.length - classes, 'member')}${removal ? `, ${fmt(removal)} of them for removal` : ''}`;
}

/** The deprecated API of a release (core.deprecationsAt) by jar, then class, with a for-removal filter; `memory` keeps the jars the reader opened (moduleGroups). */
function deprecatedList(items, memory = null) {
  if (!items.length) return emptyNote('Nothing is deprecated in this release.');
  const counts = { removal: items.filter((x) => x.kind === 'removal').length, deprecated: items.filter((x) => x.kind === 'deprecated').length };
  const state = { tags: new Set(), text: '' };
  const groups = moduleGroups({ memory, noun: 'jars' });
  const groupsEl = h('div', { class: 'cgroups' });
  const status = h('p', { class: 'filter-status', 'aria-live': 'polite' });
  const update = () => {
    const words = state.text.toLowerCase().split(/\s+/).filter(Boolean);
    const shown = items.filter((x) => (!state.tags.size || state.tags.has(x.kind))
      && words.every((w) => `${x.name}\n${x.member ?? ''}\n${x.artifact}`.toLowerCase().includes(w)));
    const byJar = [...core.groupBy(shown, (x) => x.artifact)];
    groups.listed(byJar.map(([jar]) => jar), words.join(' '));
    renderGroupPages(groupsEl, byJar, (jar, list) => apiJarGroup(jar, list, deprecatedRow, groups, 'deprecation'), 'Nothing deprecated matches these filters.', 'more jar');
    status.textContent = shown.length === items.length ? `${deprecatedCounts(items)}.` : `Showing ${fmt(shown.length)} of ${plural(items.length, 'deprecation')}.`;
  };
  update();
  return frag(
    h('div', { class: 'toolbar' },
      filterChips(DEPRECATED_CHIPS, counts, state.tags, update, 'Show only these deprecations'),
      textFilter('Filter by class, member or jar', (v) => {
        state.text = v;
        update();
      })),
    listBar(status, groups),
    groupsEl);
}

/** One deprecated class or member: its chip, signature and since. */
function deprecatedRow(x) {
  return h('li', { class: 'mrow' },
    deprecationChip(x.kind),
    x.member ? memberSignature(x.name, x.member, x.value) : h('span', { class: 'muted' }, 'the class'),
    x.since || x.from != null ? h('span', { class: 'msince' }, deprecationSince(x)) : null);
}

/** #/deprecated/<version>: every class and member of the io.quarkus jars deprecated in a release. */
async function renderDeprecated(r) {
  const V = store.V;
  const i = core.findVersionIndex(V.list, r.v);
  if (i < 0) return notFoundView('Unknown release', `This dataset has no release ${r.v || '(empty)'}.`, h('p', null, h('a', { href: '#/versions' }, 'See all releases')));
  const x = V.list[i];
  if (short(x.v) !== r.v) canonicalize({ page: 'deprecated', v: short(x.v) });
  const head = h('header', { class: 'page-head band' },
    h('p', { class: 'kicker' }, 'Deprecated API · ', h('a', { href: link.version(x.v) }, `release ${short(x.v)}`)),
    h('h1', { class: 'page-title page-title--ver', tabindex: '-1' }, short(x.v)));
  const av = core.apiVersionFor(i, V.list, V.apiVis);
  if (av == null) return frag(head, emptyNote(V.apiVis.length ? `No API data for ${short(x.v)}.` : 'This dataset has no API data.'));
  const classes = await loadClasses(apiLinesFor(av));
  const items = core.deprecationsAt(classes.raw, av);
  return frag(
    head,
    h('p', { class: 'lede' }, `The public and protected classes and members of the io.quarkus jars deprecated in ${short(x.v)}`,
      av === i ? '' : `, from the API data of ${short(V.list[av].v)}`, `: ${deprecatedCounts(items)}.`),
    h('section', { class: 'sec', 'aria-labelledby': 'dep-h' }, sectionHead(1, 'By jar', 'dep-h',
      h('p', { class: 'sec-note' }, '“Since” is what the @Deprecated annotation declares; “marked from” is the first release, of those in a row up to this one, that marks it deprecated.')),
    deprecatedList(items, `deprecated:${x.v}`)));
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
