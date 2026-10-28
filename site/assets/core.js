/*
 * Quarkus Almanac: core.js
 *
 * Pure logic, no DOM: version ordering, history lookup, diffs, search, timelines and pre-release visibility.
 * Mirrors the extractor's QuarkusVersion and ConfigKeys; defines how the site reads its data, the diff semantics
 * and which releases it lists.
 * Everything here is unit tested by site/tests/core.test.mjs (`node --test site/tests`).
 */

/* ========================================================================== */
/* Quarkus versions (same semantics as the extractor's QuarkusVersion)         */
/* ========================================================================== */

const QUALIFIERS = {
  alpha: { rank: 0, name: 'Alpha' },
  beta: { rank: 1, name: 'Beta' },
  cr: { rank: 2, name: 'CR' },
  final: { rank: 3, name: 'Final' },
};
const FINAL_RANK = 3;
/** Qualifiers outside Alpha/Beta/CR/Final never occur in Quarkus releases; they sort before Alpha. */
const UNKNOWN_RANK = -1;
const versionCache = new Map();

/**
 * Parses `N(.N)*` with an optional `.Final`, `.CR<n>`, `.Beta<n>` or `.Alpha<n>` qualifier
 * (`-` tolerated as separator, case ignored).
 * @returns {{raw:string, nums:number[], qualifier:string, rank:number, qnum:number, final:boolean, stream:string}}
 */
export function parseVersion(input) {
  const raw = String(input ?? '').trim();
  const cached = versionCache.get(raw);
  if (cached) return cached;
  let nums = [];
  let qualifier = '';
  let rank = FINAL_RANK;
  let qnum = 0;
  const m = /^(\d+(?:\.\d+)*)(?:[.-]([A-Za-z]+)[.-]?(\d*))?$/.exec(raw);
  if (m) {
    nums = m[1].split('.').map(Number);
    if (m[2]) {
      const q = QUALIFIERS[m[2].toLowerCase()];
      if (q) {
        rank = q.rank;
        qualifier = q.name;
        qnum = m[3] ? Number(m[3]) : 0;
      } else {
        rank = UNKNOWN_RANK;
        qualifier = m[2] + m[3];
      }
    }
  } else {
    const lead = /^(\d+(?:\.\d+)*)(?:[.-](.*))?$/.exec(raw);
    if (lead) {
      nums = lead[1].split('.').map(Number);
      qualifier = lead[2] ?? '';
      rank = qualifier ? UNKNOWN_RANK : FINAL_RANK;
    } else {
      qualifier = raw;
      rank = UNKNOWN_RANK;
    }
  }
  const parsed = Object.freeze({
    raw,
    nums,
    qualifier,
    rank,
    qnum,
    final: rank === FINAL_RANK,
    stream: `${nums[0] ?? 0}.${nums[1] ?? 0}`,
  });
  if (versionCache.size > 20000) versionCache.clear();
  versionCache.set(raw, parsed);
  return parsed;
}

/**
 * Orders Quarkus versions: numeric parts element-wise (missing = 0), then qualifier rank
 * Alpha < Beta < CR < (Final | none), then the qualifier number.
 * @returns {-1|0|1}
 */
export function compareVersions(a, b) {
  const x = typeof a === 'object' && a !== null ? a : parseVersion(a);
  const y = typeof b === 'object' && b !== null ? b : parseVersion(b);
  const n = Math.max(x.nums.length, y.nums.length);
  for (let i = 0; i < n; i++) {
    const d = (x.nums[i] ?? 0) - (y.nums[i] ?? 0);
    if (d !== 0) return d < 0 ? -1 : 1;
  }
  if (x.rank !== y.rank) return x.rank < y.rank ? -1 : 1;
  if (x.qnum !== y.qnum) return x.qnum < y.qnum ? -1 : 1;
  if (x.rank === UNKNOWN_RANK && x.qualifier !== y.qualifier) return x.qualifier < y.qualifier ? -1 : 1;
  return 0;
}

/** Returns a new array of version strings in ascending version order. */
export function sortVersions(list) {
  return [...list].sort(compareVersions);
}

/** A version is final when it has no qualifier or `Final`. */
export function isFinal(v) {
  return parseVersion(v).final;
}

/** `major.minor`, e.g. `3.20` for `3.20.6` and `4.0` for `4.0.0.Beta1`. */
export function stream(v) {
  return parseVersion(v).stream;
}

/** Display form: drops a trailing `.Final` (`3.2.12.Final` → `3.2.12`). */
export function shortVersion(v) {
  return String(v ?? '').replace(/[.-]Final$/i, '');
}

/**
 * Finds a version in `list` (strings or `{v}` objects): exact string match first, then version equality,
 * so `3.0.0` finds `3.0.0.Final`. Returns -1 when absent.
 */
export function findVersionIndex(list, v) {
  if (v == null || v === '') return -1;
  const want = String(v).trim();
  const at = (i) => (typeof list[i] === 'string' ? list[i] : list[i].v);
  for (let i = 0; i < list.length; i++) if (at(i) === want) return i;
  if (!/^\d/.test(want)) return -1;
  for (let i = 0; i < list.length; i++) if (compareVersions(at(i), want) === 0) return i;
  return -1;
}

/* ========================================================================== */
/* Library versions (BOM entries) and change kinds                             */
/* ========================================================================== */

/** Maven-style qualifier ranks for library versions: alpha < beta < milestone < rc/cr < snapshot < release < sp < other. */
const LIB_QUALIFIER_RANKS = [
  [/^(alpha|a)$/, 0],
  [/^(beta|b)$/, 1],
  [/^(milestone|m)$/, 2],
  [/^(rc|cr)$/, 3],
  [/^snapshot$/, 4],
  [/^(|final|ga|release)$/, 5],
  [/^sp$/, 6],
];
const LIB_UNKNOWN_RANK = 7;

/**
 * Splits a library version into its leading numeric parts and a qualifier
 * (`4.1.115.Final` → nums [4,1,115], qualifier `Final`).
 */
export function parseLibVersion(input) {
  const raw = String(input ?? '').trim();
  const m = /^(\d+(?:\.\d+)*)(.*)$/.exec(raw);
  if (!m) return { raw, nums: [], qualifier: raw, qrank: LIB_UNKNOWN_RANK, qnum: 0, qrest: raw.toLowerCase() };
  const nums = m[1].split('.').map(Number);
  const qualifier = m[2].replace(/^[.\-_]+/, '');
  const q = /^([A-Za-z]*)[.\-_]?(\d*)(.*)$/.exec(qualifier);
  const word = q[1].toLowerCase();
  let qrank = LIB_UNKNOWN_RANK;
  for (const [re, rank] of LIB_QUALIFIER_RANKS) {
    if (re.test(word)) {
      qrank = rank;
      break;
    }
  }
  const known = qrank !== LIB_UNKNOWN_RANK;
  return {
    raw,
    nums,
    qualifier,
    qrank,
    qnum: known && q[2] ? Number(q[2]) : 0,
    qrest: (known ? q[3] : qualifier).toLowerCase(),
  };
}

/** Orders library versions: numeric parts, then qualifier rank and number, then the remaining text. */
export function compareLibVersions(a, b) {
  const x = parseLibVersion(a);
  const y = parseLibVersion(b);
  const n = Math.max(x.nums.length, y.nums.length);
  for (let i = 0; i < n; i++) {
    const d = (x.nums[i] ?? 0) - (y.nums[i] ?? 0);
    if (d !== 0) return d < 0 ? -1 : 1;
  }
  if (x.qrank !== y.qrank) return x.qrank < y.qrank ? -1 : 1;
  if (x.qnum !== y.qnum) return x.qnum < y.qnum ? -1 : 1;
  if (x.qrest !== y.qrest) return x.qrest < y.qrest ? -1 : 1;
  return 0;
}

/**
 * `downgrade` if B < A, else `major` / `minor` / `patch` by the first differing leading numeric
 * part (any position after the second counts as patch), `other` when only qualifiers differ, `same` when equal.
 * A version kept as written with a `${...}` reference (one that Maven does not resolve either) has no order:
 * a change to or from it is `other`.
 */
export function bomChangeKind(from, to) {
  if (from === to) return 'same';
  if (String(from).includes('${') || String(to).includes('${')) return 'other';
  if (compareLibVersions(from, to) > 0) return 'downgrade';
  const a = parseLibVersion(from).nums;
  const b = parseLibVersion(to).nums;
  const n = Math.max(a.length, b.length);
  for (let i = 0; i < n; i++) {
    if ((a[i] ?? 0) !== (b[i] ?? 0)) return i === 0 ? 'major' : i === 1 ? 'minor' : 'patch';
  }
  return 'other';
}

/** Splits `groupId:artifactId[:type][:classifier]`. */
export function splitId(id) {
  const parts = String(id).split(':');
  return { group: parts[0] ?? '', artifact: parts[1] ?? '', extra: parts.slice(2).join(':') };
}

/* ========================================================================== */
/* Config keys (mirrors the extractor's ConfigKeys)                            */
/* ========================================================================== */

/** Replaces every double-quoted segment with `*` and every `[digits]` with `[*]`. Nothing else. */
export function canonicalKey(name) {
  return String(name ?? '').replace(/"[^"]*"/g, '*').replace(/\[\d+\]/g, '[*]');
}

/** Upper-case, every character not `[A-Za-z0-9]` becomes `_`. */
export function envVar(name) {
  return String(name ?? '').toUpperCase().replace(/[^A-Za-z0-9]/g, '_');
}

/**
 * Last dot-separated segment of a canonical key. A trailing `*` (map key) segment is kept together
 * with its parent so that `a.b.env.*` yields `env.*` rather than a bare `*`.
 */
export function lastSegment(key) {
  const parts = String(key ?? '').split('.');
  let last = parts.pop() ?? '';
  if (last === '*' && parts.length) last = `${parts.pop()}.*`;
  return last;
}

/* ========================================================================== */
/* Change-point histories                                                      */
/* ========================================================================== */

/**
 * Value of the last change point with index ≤ vi. `undefined` before the first point,
 * `null` where the history says "not present".
 */
export function stateAt(h, vi) {
  if (!Array.isArray(h) || h.length === 0) return undefined;
  let lo = 0;
  let hi = h.length - 1;
  let found = -1;
  while (lo <= hi) {
    const mid = (lo + hi) >> 1;
    if (h[mid][0] <= vi) {
      found = mid;
      lo = mid + 1;
    } else {
      hi = mid - 1;
    }
  }
  return found < 0 ? undefined : h[found][1];
}

/** The newest non-null value in a history (the latest known state), or null. */
export function latestValue(h) {
  if (!Array.isArray(h)) return null;
  for (let i = h.length - 1; i >= 0; i--) if (h[i][1] != null) return h[i][1];
  return null;
}

/** Indices of the releases that have config snapshots, derived from `versions[].config`. */
export function configVersionsOf(versions) {
  const out = [];
  versions.forEach((x, i) => {
    if (x && x.config) out.push(i);
  });
  return out;
}

/**
 * Indices of the releases that have extension snapshots, derived from `versions[].ext`. A release may have one
 * without a config snapshot, never the reverse. Data assembled before the flag existed had extension snapshots
 * exactly at the config snapshots, so without any `ext` field the `config` flags are used.
 */
export function extensionVersionsOf(versions) {
  const list = Array.isArray(versions) ? versions : [];
  if (!list.some((x) => x && typeof x.ext === 'boolean')) return configVersionsOf(list);
  const out = [];
  list.forEach((x, i) => {
    if (x && x.ext) out.push(i);
  });
  return out;
}

function streamOf(entry) {
  if (!entry) return '';
  if (typeof entry === 'string') return stream(entry);
  return entry.stream || stream(entry.v);
}

/* ========================================================================== */
/* Pre-releases and visibility                                                 */
/* ========================================================================== */

/** True for a pre-release: the `pre` flag of a versions.json entry, else "not final" by its version. */
export function isPreRelease(entry) {
  if (entry == null) return false;
  if (typeof entry === 'string') return !isFinal(entry);
  if (typeof entry.pre === 'boolean') return entry.pre;
  return !isFinal(entry.v);
}

/** Qualifier of a pre-release for display: `Alpha`, `Beta`, `CR` (unknown ones as written); null for a final. */
export function preQualifier(entry) {
  if (!isPreRelease(entry)) return null;
  const q = parseVersion(typeof entry === 'string' ? entry : entry.v).qualifier;
  return q || null;
}

/**
 * Which releases the site lists. Finals always; a pre-release when `showPre` is on, or when it is
 * *upcoming* (its stream has no final release yet). With `oldBefore` (a stream, e.g. `3.8`), the releases of the
 * older streams are *old*, and listed only when `showOld` is on.
 * @param {Array<{v:string, stream?:string, pre?:boolean}>} versions ascending, as in versions.json
 * @returns {{showPre:boolean, showOld:boolean, oldBefore:string|null, pre:boolean[], old:boolean[],
 *   upcoming:boolean[], visible:boolean[], indices:number[], hideable:number, hidden:number, preHideable:number,
 *   oldHideable:number, upcomingStreams:Set<string>}} `indices` = the visible indices, ascending; `hideable` =
 *   pre-releases the pre-release toggle controls (not upcoming); `hidden` = how many releases are hidden now, for
 *   either reason; `preHideable` / `oldHideable` = the releases each toggle shows or hides, given the other's setting
 */
export function releaseVisibility(versions, { showPre = false, showOld = true, oldBefore = null } = {}) {
  const list = Array.isArray(versions) ? versions : [];
  const pre = list.map(isPreRelease);
  const withFinal = new Set();
  list.forEach((x, i) => {
    if (!pre[i]) withFinal.add(streamOf(x));
  });
  const upcoming = list.map((x, i) => pre[i] && !withFinal.has(streamOf(x)));
  const old = list.map((x) => !!oldBefore && compareVersions(streamOf(x), oldBefore) < 0);
  const preShown = (i) => !pre[i] || upcoming[i] || !!showPre;
  const oldShown = (i) => !old[i] || !!showOld;
  const visible = list.map((x, i) => preShown(i) && oldShown(i));
  const indices = [];
  let hideable = 0;
  let hidden = 0;
  let preHideable = 0;
  let oldHideable = 0;
  const upcomingStreams = new Set();
  list.forEach((x, i) => {
    if (visible[i]) indices.push(i);
    else hidden++;
    if (pre[i] && !upcoming[i]) {
      hideable++;
      if (oldShown(i)) preHideable++;
    }
    if (old[i] && preShown(i)) oldHideable++;
    if (upcoming[i]) upcomingStreams.add(streamOf(x));
  });
  return {
    showPre: !!showPre, showOld: !!showOld, oldBefore: oldBefore ?? null, pre, old, upcoming, visible, indices, hideable,
    hidden, preHideable, oldHideable, upcomingStreams,
  };
}

/** The entries of `indices` (release or config version indices) that `vis` shows, in order; all of them without `vis`. */
export function visibleOnly(indices, vis) {
  const list = Array.isArray(indices) ? indices : [];
  return vis ? list.filter((i) => vis.visible[i]) : [...list];
}

/**
 * The config snapshot used for release `vi`, or null ("no config data"); see snapshotVersionFor.
 * @param {number} vi index into versions
 * @param {Array<{v:string, stream?:string, pre?:boolean, config?:boolean}>} versions
 * @param {number[]} [configVis] config version indices (defaults to the `config` flags)
 */
export function configVersionFor(vi, versions, configVis) {
  if (!Array.isArray(versions)) return null;
  return snapshotVersionFor(vi, versions, configVis ?? configVersionsOf(versions));
}

/**
 * The extension snapshot used for release `vi`, or null ("no extension data"): the config rule over the
 * extension snapshots, which may exist where config does not (see snapshotVersionFor).
 * @param {number} vi index into versions
 * @param {Array<{v:string, stream?:string, pre?:boolean, config?:boolean, ext?:boolean}>} versions
 * @param {number[]} [extVis] extension version indices (defaults to extensionVersionsOf)
 */
export function extensionVersionFor(vi, versions, extVis) {
  if (!Array.isArray(versions)) return null;
  return snapshotVersionFor(vi, versions, extVis ?? extensionVersionsOf(versions));
}

/**
 * The snapshot used for release `vi` among the releases `snapshots` that have one (config or extension snapshots),
 * or null.
 * - A pre-release uses only its own snapshot: `vi` itself when it has one, else null. It never borrows another
 *   release's snapshot, not even one of its stream.
 * - A final uses the stream rule: a final snapshot of its stream (the greatest one ≤ vi, else the nearest one
 *   after it); else a pre-release snapshot of its stream (same order); otherwise the latest snapshot ≤ vi.
 * @param {number} vi index into versions
 * @param {Array<{v:string, stream?:string, pre?:boolean}>} versions
 * @param {number[]} snapshots indices of the releases that have a snapshot, ascending
 */
export function snapshotVersionFor(vi, versions, snapshots) {
  if (!Array.isArray(versions) || vi == null || vi < 0 || vi >= versions.length) return null;
  const cvs = Array.isArray(snapshots) ? snapshots : [];
  if (isPreRelease(versions[vi])) return cvs.includes(vi) ? vi : null;
  const s = streamOf(versions[vi]);
  const best = { final: [-1, -1], pre: [-1, -1] }; // [greatest ≤ vi, nearest after vi]
  let below = -1;
  for (const c of cvs) {
    if (c < 0 || c >= versions.length) continue;
    if (streamOf(versions[c]) === s) {
      const b = isPreRelease(versions[c]) ? best.pre : best.final;
      if (c <= vi) b[0] = Math.max(b[0], c);
      else if (b[1] < 0 || c < b[1]) b[1] = c;
    }
    if (c <= vi) below = Math.max(below, c);
  }
  for (const c of [...best.final, ...best.pre]) if (c >= 0) return c;
  return below >= 0 ? below : null;
}

/* ========================================================================== */
/* Config diff                                                                 */
/* ========================================================================== */

const PHASES = {
  B: { name: 'BUILD_TIME', label: 'build time', buildTime: true, long: 'BUILD_TIME: fixed at build time, not visible at runtime' },
  F: { name: 'BUILD_AND_RUN_TIME_FIXED', label: 'build time, fixed', buildTime: true, long: 'BUILD_AND_RUN_TIME_FIXED: fixed at build time, readable but not overridable at runtime' },
  R: { name: 'RUN_TIME', label: 'runtime', buildTime: false, long: 'RUN_TIME: can be set or overridden at runtime' },
  S: { name: 'BOOTSTRAP', label: 'bootstrap', buildTime: false, long: 'BOOTSTRAP: runtime, read before the application starts' },
};

/** Phase metadata for a phase code (B, F, R, S); null when unknown. */
export function phaseInfo(code) {
  const p = PHASES[code];
  return p ? { code, ...p } : null;
}

/**
 * Java-ish type label from a config state: `int`, `List<string>`, `Map<String, string>`. The processor's type of a
 * map property is usually the whole map type already (`Map<String,String>`), which is shown as it is.
 */
export function typeLabel(s) {
  if (!s) return '';
  const t = s.t || 'unknown';
  if (s.m && t.startsWith('Map<')) return t;
  if (s.m && s.l) return `Map<String, List<${t}>>`;
  if (s.m) return `Map<String, ${t}>`;
  if (s.l) return `List<${t}>`;
  return t;
}

function isDeprecated(s) {
  return s != null && s.x !== undefined && s.x !== null && s.x !== false;
}

function sameSet(a, b) {
  const x = new Set(a);
  const y = new Set(b);
  if (x.size !== y.size) return false;
  for (const v of x) if (!y.has(v)) return false;
  return true;
}

/**
 * Change kinds between two states of the same property. Name, alias, optional and description
 * differences are ignored.
 * @returns {string[]} subset of default, type, phase, deprecated, undeprecated, enum, extension
 */
export function configChangeKinds(a, b) {
  const kinds = [];
  if ((a.d ?? null) !== (b.d ?? null)) kinds.push('default');
  if ((a.t ?? null) !== (b.t ?? null) || !!a.l !== !!b.l || !!a.m !== !!b.m) kinds.push('type');
  if ((a.p ?? null) !== (b.p ?? null)) kinds.push('phase');
  const da = isDeprecated(a);
  const db = isDeprecated(b);
  if (!da && db) kinds.push('deprecated');
  else if (da && !db) kinds.push('undeprecated');
  if (Array.isArray(a.en) && Array.isArray(b.en) && !sameSet(a.en, b.en)) kinds.push('enum');
  if ((a.e ?? null) !== (b.e ?? null)) kinds.push('extension');
  return kinds;
}

/** Values added to / removed from an enum between two states. */
export function enumDelta(a, b) {
  const x = new Set(a?.en ?? []);
  const y = new Set(b?.en ?? []);
  return { added: [...y].filter((v) => !x.has(v)), removed: [...x].filter((v) => !y.has(v)) };
}

function statesAt(config, vi) {
  const out = new Map();
  for (const p of config.props) {
    const si = stateAt(p.h, vi);
    if (si != null) out.set(p.k, config.states[si]);
  }
  return out;
}

/** canonical alias key → owning key, for the properties present in one snapshot. */
function aliasIndex(present) {
  const out = new Map();
  for (const [k, s] of present) {
    for (const alias of s.a ?? []) {
      const ak = canonicalKey(alias);
      if (ak !== k && !out.has(ak)) out.set(ak, k);
    }
  }
  return out;
}

const byKey = (x, y) => (x.k < y.k ? -1 : x.k > y.k ? 1 : 0);

/**
 * Config diff between config versions `a` and `b`.
 * - added: key in B, absent from A's keys and A's canonical alias keys;
 * - removed: key in A, absent from B's keys and B's canonical alias keys;
 * - changed: same key in both with at least one change kind. Additionally, a pair of keys that only
 *   match through each other's aliases (A's key is an alias in B and B's key is an alias in A) is compared
 *   and reported with `via` = A's key, since it is the same property under a re-keyed name;
 * - removed entries carry `replacement` when exactly one added key has the same `e`, `t` and last segment
 *   (a heuristic, label it as such).
 */
export function diffConfig(config, a, b) {
  const A = statesAt(config, a);
  const B = statesAt(config, b);
  const aliasA = aliasIndex(A);
  const aliasB = aliasIndex(B);
  const added = [];
  const removed = [];
  const changed = [];
  for (const [k, sb] of B) {
    const sa = A.get(k);
    if (sa) {
      const kinds = configChangeKinds(sa, sb);
      if (kinds.length) changed.push({ k, a: sa, b: sb, kinds });
    } else if (!aliasA.has(k)) {
      added.push({ k, b: sb });
    }
  }
  for (const [k, sa] of A) {
    if (B.has(k)) continue;
    const owner = aliasB.get(k);
    if (owner === undefined) {
      removed.push({ k, a: sa });
      continue;
    }
    if (!A.has(owner) && aliasA.get(owner) === k) {
      const sb = B.get(owner);
      const kinds = configChangeKinds(sa, sb);
      if (kinds.length) changed.push({ k: owner, a: sa, b: sb, kinds, via: k });
    }
  }
  const shapes = new Map();
  for (const x of added) {
    const sig = `${x.b.e ?? ''}\u0000${x.b.t ?? ''}\u0000${lastSegment(x.k)}`;
    if (!shapes.has(sig)) shapes.set(sig, []);
    shapes.get(sig).push(x.k);
  }
  for (const r of removed) {
    const candidates = shapes.get(`${r.a.e ?? ''}\u0000${r.a.t ?? ''}\u0000${lastSegment(r.k)}`);
    if (candidates && candidates.length === 1) r.replacement = candidates[0];
  }
  added.sort(byKey);
  removed.sort(byKey);
  changed.sort(byKey);
  const counts = { added: added.length, removed: removed.length, changed: changed.length };
  for (const kind of ['default', 'type', 'phase', 'deprecated', 'undeprecated', 'enum', 'extension']) counts[kind] = 0;
  for (const c of changed) for (const kind of c.kinds) counts[kind]++;
  return { a, b, added, removed, changed, counts };
}

/** Filter tags shown as chips on the compare page; undeprecated rows are found under `deprecated`. */
export const CONFIG_FILTER_TAGS = ['added', 'removed', 'default', 'type', 'phase', 'deprecated', 'enum', 'extension'];

const CHANGE_ORDER = { removed: 0, changed: 1, added: 2 };

/**
 * Flattens a config diff into display rows sorted by extension, then removed → changed → added, then name.
 * Each row carries `tags` for the filter chips and a lower-case `text` for the text filter.
 */
export function configRows(diff, desc = {}) {
  const rows = [];
  const push = (change, x) => {
    const s = x.b ?? x.a;
    const kinds = change === 'changed' ? x.kinds : [change];
    const tags = [...new Set(kinds.map((kd) => (kd === 'undeprecated' ? 'deprecated' : kd)))];
    const d = (desc && desc[x.k]) || null;
    const aliases = s.a ?? [];
    rows.push({
      change,
      k: x.k,
      name: s.n ?? x.k,
      ext: s.e ?? '',
      a: x.a ?? null,
      b: x.b ?? null,
      kinds,
      tags,
      desc: d,
      replacement: x.replacement ?? null,
      via: x.via ?? null,
      text: [s.n ?? '', x.k, ...aliases, s.e ?? '', d ?? '', x.via ?? ''].join('\n').toLowerCase(),
    });
  };
  for (const x of diff.removed) push('removed', x);
  for (const x of diff.changed) push('changed', x);
  for (const x of diff.added) push('added', x);
  rows.sort(
    (x, y) =>
      (x.ext < y.ext ? -1 : x.ext > y.ext ? 1 : 0) ||
      CHANGE_ORDER[x.change] - CHANGE_ORDER[y.change] ||
      (x.name < y.name ? -1 : x.name > y.name ? 1 : 0),
  );
  return rows;
}

/** Number of rows per filter tag. */
export function tagCounts(rows) {
  const out = {};
  for (const r of rows) for (const t of r.tags) out[t] = (out[t] ?? 0) + 1;
  return out;
}

/** Rows matching every condition: any of `tags` (empty = all) and every whitespace-separated word of `text`. */
export function filterRows(rows, { tags, text } = {}) {
  const wanted = tags && tags.size ? tags : null;
  const words = String(text ?? '').toLowerCase().split(/\s+/).filter(Boolean);
  if (!wanted && !words.length) return rows;
  return rows.filter((r) => {
    if (wanted && !r.tags.some((t) => wanted.has(t))) return false;
    for (const w of words) if (!r.text.includes(w)) return false;
    return true;
  });
}

/** Groups consecutive-or-not rows by a key function, preserving first-seen order. */
export function groupBy(rows, keyFn) {
  const out = new Map();
  for (const r of rows) {
    const k = keyFn(r);
    if (!out.has(k)) out.set(k, []);
    out.get(k).push(r);
  }
  return out;
}

/* ========================================================================== */
/* BOM diff                                                                    */
/* ========================================================================== */

/** Resolves `"="` to the Quarkus release version string; passes other values through. */
export function resolveBomValue(raw, releaseVersion) {
  return raw === '=' ? releaseVersion : raw;
}

export const BOM_KIND_ORDER = ['downgrade', 'major', 'minor', 'patch', 'other', 'added', 'removed'];

/**
 * BOM diff between releases `a` and `b` (indices into versions). `"="` resolves to the release version;
 * artifacts that are `"="` in both releases are only counted (`modules`), not listed.
 */
export function diffBom(bom, versions, a, b) {
  const va = versions[a].v;
  const vb = versions[b].v;
  let modules = 0;
  let unchanged = 0;
  const rows = [];
  for (const art of bom.artifacts) {
    const ra = stateAt(art.h, a);
    const rb = stateAt(art.h, b);
    const inA = ra != null;
    const inB = rb != null;
    if (!inA && !inB) continue;
    if (ra === '=' && rb === '=') {
      modules++;
      continue;
    }
    const from = inA ? resolveBomValue(ra, va) : null;
    const to = inB ? resolveBomValue(rb, vb) : null;
    let kind;
    if (!inA) kind = 'added';
    else if (!inB) kind = 'removed';
    else if (from === to) {
      unchanged++;
      continue;
    } else kind = bomChangeKind(from, to);
    const { group, artifact, extra } = splitId(art.id);
    rows.push({ id: art.id, group, artifact, extra, from, to, kind, follows: ra === '=' || rb === '=' });
  }
  rows.sort((x, y) => (x.id < y.id ? -1 : x.id > y.id ? 1 : 0));
  const counts = { changed: 0 };
  for (const kind of BOM_KIND_ORDER) counts[kind] = 0;
  for (const r of rows) {
    counts[r.kind]++;
    if (r.kind !== 'added' && r.kind !== 'removed') counts.changed++;
  }
  return { a, b, from: va, to: vb, modules, unchanged, rows, groups: groupBomRows(rows), counts };
}

/**
 * Groups BOM rows sharing (groupId, from, to); ordered by severity
 * (downgrade, major, minor, patch, other, added, removed), then groupId, then versions.
 */
export function groupBomRows(rows) {
  const map = new Map();
  for (const r of rows) {
    const key = `${r.group}\u0000${r.from ?? ''}\u0000${r.to ?? ''}`;
    let g = map.get(key);
    if (!g) {
      g = { key, group: r.group, from: r.from, to: r.to, kind: r.kind, items: [], text: '' };
      map.set(key, g);
    }
    g.items.push(r);
  }
  const groups = [...map.values()];
  for (const g of groups) {
    g.text = [g.group, g.from ?? '', g.to ?? '', ...g.items.map((r) => r.id)].join('\n').toLowerCase();
  }
  const order = (k) => BOM_KIND_ORDER.indexOf(k);
  groups.sort(
    (x, y) =>
      order(x.kind) - order(y.kind) ||
      (x.group < y.group ? -1 : x.group > y.group ? 1 : 0) ||
      String(x.from ?? '').localeCompare(String(y.from ?? '')) ||
      String(x.to ?? '').localeCompare(String(y.to ?? '')),
  );
  return groups;
}

/* ========================================================================== */
/* Headline libraries                                                          */
/* ========================================================================== */

/**
 * Shown first on the compare page when present in either release; matched by artifactId (and groupId where
 * given). Each label names one library, so a comparison never shows a label twice: `alternatives` marks one
 * library published under several coordinates (preferred first: artifacts order, then groups order).
 */
export const HEADLINE_LIBRARIES = [
  { label: 'Hibernate ORM', artifacts: ['hibernate-core'], groups: ['org.hibernate.orm', 'org.hibernate'], alternatives: true },
  { label: 'Hibernate Reactive', artifacts: ['hibernate-reactive-core'] },
  { label: 'Hibernate Validator', artifacts: ['hibernate-validator'] },
  { label: 'Hibernate Search', artifacts: ['hibernate-search-mapper-orm'] },
  { label: 'Vert.x', artifacts: ['vertx-core'] },
  { label: 'Netty', artifacts: ['netty-common'] },
  { label: 'RESTEasy', artifacts: ['resteasy-core'] },
  // io.smallrye:smallrye-config is the 1.x coordinate; 3.0 to 3.23 still list it (1.5.0) next to the real one.
  { label: 'SmallRye Config', artifacts: ['smallrye-config'], groups: ['io.smallrye.config', 'io.smallrye'], alternatives: true },
  { label: 'Mutiny', artifacts: ['mutiny'] },
  { label: 'SmallRye Reactive Messaging', artifacts: ['smallrye-reactive-messaging-api'] },
  { label: 'Jackson', artifacts: ['jackson-databind'], groups: ['com.fasterxml.jackson.core'] },
  { label: 'Jackson 3', artifacts: ['jackson-databind'], groups: ['tools.jackson.core'] },
  { label: 'Kotlin', artifacts: ['kotlin-stdlib'] },
  { label: 'Kafka clients', artifacts: ['kafka-clients'] },
  { label: 'Micrometer', artifacts: ['micrometer-core'] },
  { label: 'OpenTelemetry', artifacts: ['opentelemetry-api'] },
  { label: 'gRPC', artifacts: ['grpc-core'] },
  // One row: since 3.8.3 Quarkus manages both artifacts at the same version, so a second row would only repeat
  // the first. graal-sdk stands for the SDK; nativeimage stands in for a release that does not manage graal-sdk.
  { label: 'GraalVM SDK', artifacts: ['graal-sdk', 'nativeimage'], groups: ['org.graalvm.sdk'], alternatives: true },
  // Two rows: from 3.4 to 3.30 both are managed, at different versions (3.x and 1.x).
  { label: 'JBoss LogManager', artifacts: ['jboss-logmanager'] },
  { label: 'JBoss LogManager Embedded', artifacts: ['jboss-logmanager-embedded'] },
  { label: 'Narayana JTA', artifacts: ['narayana-jta'] },
  { label: 'Agroal', artifacts: ['agroal-api'] },
  { label: 'Flyway', artifacts: ['flyway-core'] },
  { label: 'Liquibase', artifacts: ['liquibase-core'] },
  { label: 'JUnit Jupiter', artifacts: ['junit-jupiter-api'] },
  { label: 'Testcontainers', artifacts: ['testcontainers'] },
  { label: 'Picocli', artifacts: ['picocli'] },
  { label: 'MongoDB driver', artifacts: ['mongodb-driver-sync'] },
  { label: 'Infinispan client', artifacts: ['infinispan-client-hotrod'] },
];

/**
 * Headline library rows for a comparison, in HEADLINE_LIBRARIES order. An entry marked `alternatives` yields at
 * most one row: in each release, the first of its artifacts that the release manages stands for the library
 * (`fromId` / `toId` say which; `id` is the newer one).
 * @returns {Array<{label:string, id:string, fromId:string|null, toId:string|null, from:string|null, to:string|null, kind:string}>}
 */
export function headlineLibraries(bom, versions, a, b) {
  const byArtifact = new Map();
  for (const art of bom.artifacts) {
    const parts = art.id.split(':');
    if (parts.length !== 2) continue;
    if (!byArtifact.has(parts[1])) byArtifact.set(parts[1], []);
    byArtifact.get(parts[1]).push(art);
  }
  const va = versions[a].v;
  const vb = versions[b].v;
  const out = [];
  const row = (label, artA, artB, ra, rb) => {
    const from = ra == null ? null : resolveBomValue(ra, va);
    const to = rb == null ? null : resolveBomValue(rb, vb);
    const kind = from == null ? 'added' : to == null ? 'removed' : bomChangeKind(from, to);
    const fromId = artA ? artA.id : null;
    const toId = artB ? artB.id : null;
    out.push({ label, id: toId ?? fromId, fromId, toId, from, to, kind });
  };
  const groupOf = (art) => art.id.split(':')[0];
  for (const def of HEADLINE_LIBRARIES) {
    const arts = [];
    for (const artifactId of def.artifacts) {
      const found = (byArtifact.get(artifactId) ?? []).filter((art) => !def.groups || def.groups.includes(groupOf(art)));
      if (def.groups) found.sort((x, y) => def.groups.indexOf(groupOf(x)) - def.groups.indexOf(groupOf(y)));
      arts.push(...found);
    }
    if (def.alternatives) {
      const pick = (vi) => {
        for (const art of arts) {
          const raw = stateAt(art.h, vi);
          if (raw != null) return { art, raw };
        }
        return null;
      };
      const pa = pick(a);
      const pb = pick(b);
      if (pa || pb) row(def.label, pa?.art, pb?.art, pa?.raw, pb?.raw);
      continue;
    }
    for (const art of arts) {
      const ra = stateAt(art.h, a);
      const rb = stateAt(art.h, b);
      if (ra == null && rb == null) continue;
      row(def.label, ra == null ? null : art, rb == null ? null : art, ra, rb);
    }
  }
  return out;
}

/* ========================================================================== */
/* Platform BOM                                                                */
/* ========================================================================== */

/*
 * platform.json: how io.quarkus.platform:quarkus-bom, the BOM that applications generated by code.quarkus.io import,
 * differs from io.quarkus:quarkus-bom (bom.json) of the same release. `versions` lists the release indices that have
 * one; `artifacts` the ids that it manages otherwise at some of them, with change points over those releases only:
 * its version ("=" for the release's own), "-" when only the core BOM manages the id, null when both manage it alike
 * or neither does.
 */

/** The platform.json value of an artifact that the core BOM manages and the platform BOM does not. */
export const NOT_IN_PLATFORM = '-';

/** Whether release `vi` has a platform BOM in `platform` (platform.json). */
export function hasPlatformBom(platform, vi) {
  const list = platform?.versions;
  if (!Array.isArray(list)) return false;
  let lo = 0;
  let hi = list.length - 1;
  while (lo <= hi) {
    const mid = (lo + hi) >> 1;
    if (list[mid] === vi) return true;
    if (list[mid] < vi) lo = mid + 1;
    else hi = mid - 1;
  }
  return false;
}

/** The platform BOM's raw value of one artifact at a release that has one, as bom.json writes it; null when not managed. */
function platformRawAt(coreH, platformH, vi) {
  const d = platformH ? stateAt(platformH, vi) : undefined;
  if (d == null) return (coreH ? stateAt(coreH, vi) : undefined) ?? null;
  return d === NOT_IN_PLATFORM ? null : d;
}

/**
 * The platform BOM at the releases `indices` (each with a platform BOM) in the shape of bom.json, `{artifacts}`, whose
 * histories hold its raw values at those releases only ("=" for the release's own version, null when not managed),
 * so that diffBom and headlineLibraries compare platform BOMs as they compare core BOMs. Sorted by id like bom.json;
 * an artifact it manages at none of the releases is left out.
 */
export function platformBomAt(bom, platform, indices) {
  const at = [...new Set(indices)].sort((x, y) => x - y);
  const coreById = new Map(bom.artifacts.map((a) => [a.id, a.h]));
  const platformById = new Map(platform.artifacts.map((a) => [a.id, a.h]));
  const ids = [...new Set([...coreById.keys(), ...platformById.keys()])].sort((x, y) => (x < y ? -1 : x > y ? 1 : 0));
  const artifacts = [];
  for (const id of ids) {
    const h = at.map((i) => [i, platformRawAt(coreById.get(id), platformById.get(id), i)]);
    if (h.some((p) => p[1] != null)) artifacts.push({ id, h });
  }
  return { artifacts };
}

/**
 * The file an artifact id (`groupId:artifactId[:type][:classifier]`) names, to tell entries that only write one
 * artifact differently from real differences: type `test-jar` is a jar with the `tests` classifier unless it gives
 * another, type `maven-plugin` is a jar, and the platform BOM's own descriptor and properties
 * (`io.quarkus.platform:quarkus-bom-quarkus-platform-*`) stand where the core BOM has its own (`io.quarkus:…`).
 */
export function platformArtifactKey(id) {
  const [group, artifact = '', ...rest] = String(id).split(':');
  const g = group === 'io.quarkus.platform' && artifact.startsWith('quarkus-bom-quarkus-platform-') ? 'io.quarkus' : group;
  let extra = rest;
  if (rest[0] === 'test-jar') extra = [rest[1] ?? 'tests'];
  else if (rest[0] === 'maven-plugin') extra = rest.slice(1);
  return [g, artifact, ...extra].join(':');
}

/**
 * How the platform BOM of release `vi` (which has one) differs from the core BOM, in resolved versions: `changed`
 * (both manage the artifact, at other versions: `{id, core, platform}`), `added` (only the platform BOM manages it:
 * `{id, platform}`) and `removed` (only the core BOM: `{id, core}`), each sorted by id. Entries that write one
 * artifact differently (platformArtifactKey) are no difference at the same version (`structural` counts the platform
 * BOM's entries so left out), and `changed` at another one, with the core BOM's id as `coreId`.
 */
export function platformDifferences(bom, platform, versions, vi) {
  const v = versions[vi].v;
  const coreById = new Map(bom.artifacts.map((a) => [a.id, a.h]));
  const changed = [];
  const onlyPlatform = [];
  const onlyCore = [];
  for (const art of platform.artifacts) {
    const d = stateAt(art.h, vi);
    if (d == null) continue;
    const c = coreById.has(art.id) ? stateAt(coreById.get(art.id), vi) : null;
    const core = c == null ? null : resolveBomValue(c, v);
    if (d === NOT_IN_PLATFORM) {
      if (core != null) onlyCore.push({ id: art.id, core });
      continue;
    }
    const platformValue = resolveBomValue(d, v);
    if (core == null) onlyPlatform.push({ id: art.id, platform: platformValue });
    else if (core !== platformValue) changed.push({ id: art.id, core, platform: platformValue });
  }
  const coreByKey = new Map();
  for (const r of onlyCore) {
    const key = platformArtifactKey(r.id);
    if (!coreByKey.has(key)) coreByKey.set(key, []);
    coreByKey.get(key).push(r);
  }
  const paired = new Set();
  const added = [];
  let structural = 0;
  for (const r of onlyPlatform) {
    const partners = coreByKey.get(platformArtifactKey(r.id));
    if (!partners) {
      added.push(r);
      continue;
    }
    for (const p of partners) paired.add(p);
    if (partners.some((p) => p.core === r.platform)) structural++;
    else changed.push({ id: r.id, coreId: partners[0].id, core: partners[0].core, platform: r.platform });
  }
  const byId = (x, y) => (x.id < y.id ? -1 : x.id > y.id ? 1 : 0);
  return {
    changed: changed.sort(byId),
    added: added.sort(byId),
    removed: onlyCore.filter((r) => !paired.has(r)).sort(byId),
    structural,
  };
}

/**
 * The `changed` rows of platformDifferences by pair of versions, most artifacts first:
 * `{core, platform, count, groups, label, rows}`, where `label` names their groupIds: the one they share, the dotted
 * prefix of at least two parts that they share followed by "…" (`com.fasterxml.jackson…`), or the first of them and
 * how many others.
 */
export function platformChangeSummary(changed) {
  const byPair = new Map();
  for (const r of changed) {
    const key = `${r.core}\u0000${r.platform}`;
    if (!byPair.has(key)) byPair.set(key, { core: r.core, platform: r.platform, rows: [] });
    byPair.get(key).rows.push(r);
  }
  const label = (groups) => {
    if (groups.length === 1) return groups[0];
    const parts = groups.map((g) => g.split('.'));
    let n = 0;
    while (parts.every((p) => n < p.length && p[n] === parts[0][n])) n++;
    return n >= 2 ? `${parts[0].slice(0, n).join('.')}…` : `${groups[0]} and ${groups.length - 1} other group${groups.length > 2 ? 's' : ''}`;
  };
  return [...byPair.values()]
    .map((p) => {
      const groups = [...new Set(p.rows.map((r) => splitId(r.id).group))].sort();
      return { core: p.core, platform: p.platform, count: p.rows.length, groups, label: label(groups), rows: p.rows };
    })
    .sort((x, y) => y.count - x.count || (x.label < y.label ? -1 : x.label > y.label ? 1 : 0));
}

/**
 * The runs of releases among `indices` (ascending, e.g. those the switches show) in which the platform BOM manages the
 * artifact `id` otherwise than the core BOM: `{from, to, count, platform, core}` with raw values as bom.json writes
 * them ("=" for the release's own version, null when not managed). Consecutive releases of `indices` with the same
 * two values make one run; a release without a platform BOM, or where it manages the artifact as the core BOM does,
 * ends it.
 */
export function platformArtifactRuns(bom, platform, id, indices) {
  const p = platform.artifacts.find((a) => a.id === id);
  if (!p) return [];
  const c = bom.artifacts.find((a) => a.id === id);
  const runs = [];
  let cur = null;
  for (const i of indices) {
    const d = hasPlatformBom(platform, i) ? stateAt(p.h, i) : null;
    if (d == null) {
      cur = null;
      continue;
    }
    const platformValue = d === NOT_IN_PLATFORM ? null : d;
    const coreValue = (c ? stateAt(c.h, i) : undefined) ?? null;
    if (cur && cur.platform === platformValue && cur.core === coreValue) {
      cur.to = i;
      cur.count++;
      continue;
    }
    cur = { from: i, to: i, count: 1, platform: platformValue, core: coreValue };
    runs.push(cur);
  }
  return runs;
}

/* ========================================================================== */
/* Extension diff                                                              */
/* ========================================================================== */

/** Change kinds between two states of one extension: relocated (`r` newly set), status (`s`), renamed (`n`). */
export function extensionChangeKinds(a, b) {
  const kinds = [];
  if (b.r && a.r !== b.r) kinds.push('relocated');
  else if (a.r && !b.r) kinds.push('unrelocated');
  if (!a.r && !b.r) {
    if ((a.s ?? null) !== (b.s ?? null)) kinds.push('status');
    if (a.n && b.n && a.n !== b.n) kinds.push('renamed');
  }
  return kinds;
}

/** Extension diff between the extension snapshots at release indices `a` and `b` (see extensionVersionFor). */
export function diffExtensions(extensions, a, b) {
  const added = [];
  const removed = [];
  const changed = [];
  for (const x of extensions.exts) {
    const ia = stateAt(x.h, a);
    const ib = stateAt(x.h, b);
    const sa = ia == null ? null : extensions.states[ia];
    const sb = ib == null ? null : extensions.states[ib];
    if (!sa && !sb) continue;
    if (!sa) added.push({ id: x.id, b: sb });
    else if (!sb) removed.push({ id: x.id, a: sa });
    else if (ia !== ib) {
      const kinds = extensionChangeKinds(sa, sb);
      if (kinds.length) changed.push({ id: x.id, a: sa, b: sb, kinds });
    }
  }
  const sortId = (x, y) => (x.id < y.id ? -1 : x.id > y.id ? 1 : 0);
  added.sort(sortId);
  removed.sort(sortId);
  changed.sort(sortId);
  const counts = { added: added.length, removed: removed.length, relocated: 0, unrelocated: 0, status: 0, renamed: 0 };
  for (const c of changed) for (const kind of c.kinds) counts[kind]++;
  return { a, b, added, removed, changed, counts };
}

/* ========================================================================== */
/* Classes                                                                     */
/* ========================================================================== */

/**
 * Indices of the releases that have classes snapshots, derived from `versions[].classes` (none in data assembled
 * before the flag existed).
 */
export function classVersionsOf(versions) {
  const out = [];
  (Array.isArray(versions) ? versions : []).forEach((x, i) => {
    if (x && x.classes) out.push(i);
  });
  return out;
}

/**
 * The classes snapshot used for release `vi`, or null ("no class data"): the config rule over the classes snapshots
 * (see snapshotVersionFor).
 * @param {number[]} [clsVis] classes version indices (defaults to classVersionsOf)
 */
export function classesVersionFor(vi, versions, clsVis) {
  if (!Array.isArray(versions)) return null;
  return snapshotVersionFor(vi, versions, clsVis ?? classVersionsOf(versions));
}

/** The major version line of a release (a version or a versions.json entry): `3` for `3.40.1`, `4` for `4.0.0.Beta1`. */
export function majorOf(entry) {
  return parseVersion(typeof entry === 'object' && entry !== null ? entry.v : entry).nums[0] ?? 0;
}

/**
 * The major lines of the releases `indices` (into `versions`), ascending, each once: which classes files
 * (`classes-<major>.json`) cover them.
 */
export function classLinesOf(versions, indices) {
  return [...new Set((indices ?? []).map((i) => majorOf(versions[i])))].sort((a, b) => a - b);
}

/**
 * One classes object from the classes files of several major lines (`classes-3.json`, `classes-4.json`, in any order),
 * as one file over all their releases would be: their artifacts and packages, renumbered, and one history per class.
 * Each line's file starts its histories afresh, so where a class that one line ends with is missing at the start of
 * the next, the merged history gets a null point at that line's first snapshot.
 */
export function mergeClasses(parts) {
  const files = (parts ?? []).filter((f) => f && Array.isArray(f.versions) && f.versions.length)
    .sort((x, y) => x.versions[0] - y.versions[0]);
  if (files.length <= 1) return files[0] ?? { versions: [], artifacts: [], packages: [], classes: [] };
  const artifacts = [...new Set(files.flatMap((f) => f.artifacts))].sort();
  const packages = [...new Set(files.flatMap((f) => f.packages))].sort();
  const artifactIndex = new Map(artifacts.map((a, i) => [a, i]));
  const packageIndex = new Map(packages.map((p, i) => [p, i]));
  const rows = new Map(); // `${package index} ${name}` → merged row
  const same = (x, y) => JSON.stringify(x ?? null) === JSON.stringify(y ?? null);
  for (const f of files) {
    const first = f.versions[0];
    const remap = (v) => {
      if (v == null) return null;
      if (!Array.isArray(v)) return artifactIndex.get(f.artifacts[v]);
      return v.map((j) => artifactIndex.get(f.artifacts[j])).sort((a, b) => a - b);
    };
    const inLine = new Set();
    for (const [pi, name, h] of f.classes) {
      const pkg = packageIndex.get(f.packages[pi]);
      const key = `${pkg} ${name}`;
      inLine.add(key);
      if (!rows.has(key)) rows.set(key, [pkg, name, []]);
      const out = rows.get(key)[2];
      // what the earlier lines end with does not hold in this one before its first point
      if (out.length && out[out.length - 1][1] != null && h[0][0] !== first) out.push([first, null]);
      for (const [vi, v] of h) {
        const value = remap(v);
        if (!out.length || !same(out[out.length - 1][1], value)) out.push([vi, value]);
      }
    }
    for (const [key, row] of rows) {
      const out = row[2];
      if (!inLine.has(key) && out[out.length - 1][1] != null && out[out.length - 1][0] < first) out.push([first, null]);
    }
  }
  const classes = [...rows.values()].sort((x, y) => x[0] - y[0] || (x[1] < y[1] ? -1 : x[1] > y[1] ? 1 : 0));
  return { versions: files.flatMap((f) => f.versions), artifacts, packages, classes };
}

/** The binary name of a row `[package index, name in the package, h]` of a classes file (or of mergeClasses). */
export function className(classes, row) {
  const pkg = classes.packages[row[0]];
  return pkg ? `${pkg}.${row[1]}` : row[1];
}

/** The row of a class by binary name, or null. */
export function findClass(classes, name) {
  const want = String(name ?? '');
  return classes.classes.find((row) => className(classes, row) === want) ?? null;
}

/** The jars of a class history value, as artifact indices: none (null or before the first point), one or several. */
export function jarsOf(value) {
  return value == null ? [] : Array.isArray(value) ? value : [value];
}

const sameJars = (x, y) => x.length === y.length && x.every((j, i) => j === y[i]);

/** `QuarkusUnitTest$Builder` for `io.quarkus.test.QuarkusUnitTest$Builder`. */
export function simpleClassName(name) {
  const s = String(name ?? '');
  return s.slice(s.lastIndexOf('.') + 1);
}

/** `io.quarkus.test` for `io.quarkus.test.QuarkusUnitTest$Builder`; '' for the unnamed package. */
export function classPackage(name) {
  const s = String(name ?? '');
  const dot = s.lastIndexOf('.');
  return dot < 0 ? '' : s.slice(0, dot);
}

/** A nested class (member, local or anonymous): a `$` after the first character of its simple name. */
export function isNestedClass(name) {
  return simpleClassName(name).indexOf('$', 1) > 0;
}

/** An anonymous or local class: a `$` followed by a digit (`Foo$1`, `Foo$1Local`). */
export function isAnonymousClass(name) {
  return /.\$\d/.test(simpleClassName(name));
}

/** The top-level class of a nested class (`io.quarkus.test.QuarkusUnitTest` for `…QuarkusUnitTest$Builder`), else the name. */
export function topLevelClass(name) {
  const s = String(name ?? '');
  const dollar = s.indexOf('$', s.lastIndexOf('.') + 2);
  return dollar < 0 ? s : s.slice(0, dollar);
}

/**
 * Class diff between the classes snapshots at release indices `a` and `b` (see classesVersionFor), one entry per
 * class: added (in no jar at A), removed (in no jar at B), moved (in jars at both, not the same ones). An entry has
 * the binary `name` and `from` / `to`, the jars at A and B as artifact indices (ascending). `counts` count every
 * class, `top` the top-level ones.
 */
export function diffClasses(classes, a, b) {
  const added = [];
  const removed = [];
  const moved = [];
  for (const row of classes.classes) {
    const from = jarsOf(stateAt(row[2], a));
    const to = jarsOf(stateAt(row[2], b));
    if (sameJars(from, to)) continue;
    const x = { name: className(classes, row), from, to };
    if (!from.length) added.push(x);
    else if (!to.length) removed.push(x);
    else moved.push(x);
  }
  const top = (list) => list.filter((x) => !isNestedClass(x.name)).length;
  return {
    a, b, added, removed, moved,
    counts: { added: added.length, removed: removed.length, moved: moved.length },
    top: { added: top(added), removed: top(removed), moved: top(moved) },
  };
}

/** Filter tags shown as chips in the Classes section of the compare page. */
export const CLASS_FILTER_TAGS = ['added', 'removed', 'moved'];

const CLASS_CHANGE_ORDER = { removed: 0, 'moved-out': 1, 'moved-in': 2, added: 3 };

/**
 * Flattens a class diff into rows per jar, for groups by artifact. A class that a jar has at B and not at A is
 * `added` there, or `moved-in` when some jar had it at A; one that a jar has at A and not at B is `removed` from it, or
 * `moved-out` when some jar has it at B. So a class that moved has a row in each jar it left and in each jar it reached.
 * A row has `artifact`, `change`, `name`, `other` (its jars at A for moved-in, at B for moved-out: artifactIds),
 * `nested`, `tags` for the filter chips (added, removed, moved) and a lower-case `text`. Sorted by artifact, then
 * removed → moved-out → moved-in → added, then name.
 */
export function classRows(diff, classes) {
  const rows = [];
  const push = (artifact, change, x, other) => rows.push({
    artifact,
    change,
    name: x.name,
    other,
    nested: isNestedClass(x.name),
    tags: [change.startsWith('moved') ? 'moved' : change],
    text: [x.name, artifact, ...other].join('\n').toLowerCase(),
  });
  for (const x of [...diff.removed, ...diff.moved, ...diff.added]) {
    const from = x.from.map((j) => classes.artifacts[j]);
    const to = x.to.map((j) => classes.artifacts[j]);
    for (const jar of from) if (!to.includes(jar)) push(jar, to.length ? 'moved-out' : 'removed', x, to);
    for (const jar of to) if (!from.includes(jar)) push(jar, from.length ? 'moved-in' : 'added', x, from);
  }
  rows.sort(
    (x, y) =>
      (x.artifact < y.artifact ? -1 : x.artifact > y.artifact ? 1 : 0) ||
      CLASS_CHANGE_ORDER[x.change] - CLASS_CHANGE_ORDER[y.change] ||
      (x.name < y.name ? -1 : x.name > y.name ? 1 : 0),
  );
  return rows;
}

/* ========================================================================== */
/* Search                                                                      */
/* ========================================================================== */

const SEGMENT_BOUNDARY = new Set(['.', '-', ':', '_', '"', '[', '/', ' ', '*', '(']);
const TYPE_ORDER = { property: 0, extension: 1, artifact: 2, class: 3 };

/**
 * Match tier of `token` in `field` (both lower-case): 0 exact, 1 prefix, 2 segment start
 * (after `.`, `-`, `:`, `_`, `"`, `[`, `/`), 3 substring, -1 no match.
 */
export function matchTier(field, token) {
  if (!field || !token) return -1;
  if (field === token) return 0;
  if (field.startsWith(token)) return 1;
  let i = field.indexOf(token, 1);
  if (i < 0) return -1;
  for (; i >= 0; i = field.indexOf(token, i + 1)) {
    if (SEGMENT_BOUNDARY.has(field[i - 1])) return 2;
  }
  return 3;
}

const lower = (s) => String(s).toLowerCase();

/**
 * Builds the search index over properties (keys, names, aliases, env vars), artifacts (ids),
 * extensions (ids, names) and classes (binary names, names in their package; anonymous and local classes are left
 * out). Any of the four data files may be missing.
 */
export function buildSearchIndex({ config, bom, extensions, classes, versions } = {}) {
  const entries = [];
  const lastRelease = versions && versions.length ? versions.length - 1 : null;
  const cvs = config?.versions ?? [];
  const lastConfig = cvs.length ? cvs[cvs.length - 1] : null;
  // extension snapshots are flagged `ext`; data without the flag has them at the config snapshots
  const flagged = Array.isArray(versions) && versions.some((x) => x && typeof x.ext === 'boolean');
  const evs = flagged ? extensionVersionsOf(versions) : cvs;
  const lastExtension = evs.length ? evs[evs.length - 1] : null;
  if (config) {
    for (const p of config.props) {
      const si = latestValue(p.h);
      const s = si == null ? null : config.states[si];
      const name = s?.n ?? p.k;
      const aliases = s?.a ?? [];
      const fields = [...new Set([p.k, name, ...aliases, ...aliases.map(canonicalKey)].map(lower))];
      const alts = fields.filter((f) => f.startsWith('quarkus.')).map((f) => f.slice(8));
      let lastSeen = null;
      for (const vi of cvs) if (stateAt(p.h, vi) != null) lastSeen = vi;
      entries.push({
        type: 'property',
        id: p.k,
        label: name,
        fields,
        alts,
        env: lower(envVar(name)),
        ext: s?.e ?? null,
        current: lastConfig != null && stateAt(p.h, lastConfig) != null,
        lastSeen,
      });
    }
  }
  if (bom) {
    for (const art of bom.artifacts) {
      const { group, artifact } = splitId(art.id);
      const raw = lastRelease == null ? latestValue(art.h) : stateAt(art.h, lastRelease);
      entries.push({
        type: 'artifact',
        id: art.id,
        label: art.id,
        fields: [lower(art.id)],
        alts: [lower(artifact), lower(group)],
        current: raw != null,
        value: raw === '=' && versions ? versions[lastRelease].v : raw ?? null,
        follows: raw === '=',
      });
    }
  }
  if (extensions) {
    for (const x of extensions.exts) {
      const si = latestValue(x.h);
      const s = si == null ? null : extensions.states[si];
      const fields = [lower(x.id)];
      if (s?.n) fields.push(lower(s.n));
      entries.push({
        type: 'extension',
        id: x.id,
        label: x.id,
        name: s?.n ?? null,
        status: s?.s ?? null,
        relocatedTo: s?.r ?? null,
        fields,
        alts: [lower(x.id.replace(/^quarkus-/, ''))],
        current: lastExtension != null && stateAt(x.h, lastExtension) != null,
      });
    }
  }
  if (classes) {
    const lvs = classes.versions ?? [];
    const lastClasses = lvs.length ? lvs[lvs.length - 1] : null;
    for (const row of classes.classes) {
      const name = className(classes, row);
      if (isAnonymousClass(name)) continue;
      const now = lastClasses == null ? [] : jarsOf(stateAt(row[2], lastClasses));
      const lastSeen = lastPresence(row[2], lvs);
      entries.push({
        type: 'class',
        id: name,
        label: name,
        fields: [lower(name)],
        alts: [lower(row[1])],
        current: now.length > 0,
        jars: (now.length ? now : jarsOf(latestValue(row[2]))).map((j) => classes.artifacts[j]),
        lastSeen,
      });
    }
  }
  return { entries };
}

/** The last snapshot of `snapshots` (ascending) that has a value in history `h`, or null. */
function lastPresence(h, snapshots) {
  if (!Array.isArray(h) || !h.length || !snapshots.length) return null;
  const end = h[h.length - 1];
  if (end[1] != null) return snapshots[snapshots.length - 1];
  // the last point is a removal: the class was last in the snapshot before it
  let found = null;
  for (const vi of snapshots) {
    if (vi >= end[0]) break;
    found = vi;
  }
  return found;
}

/**
 * Ranked search: exact > prefix > segment > substring (per whitespace-separated token; an entry must
 * match every token and is ranked by its weakest token). `quarkus.`-less names, artifactIds and
 * environment variable names (`QUARKUS_HTTP_PORT`) count as exact/prefix matches.
 * Ties: current entries first, then property < extension < artifact < class, then shorter labels.
 * @returns {{items: object[], total: number}}
 */
export function search(index, query, { limit = 20, types } = {}) {
  const q = lower(query ?? '').trim();
  if (!q || !index) return { items: [], total: 0 };
  const tokens = q.split(/\s+/).filter(Boolean);
  const hits = [];
  for (const e of index.entries) {
    if (types && !types.includes(e.type)) continue;
    let worst = 0;
    let ok = true;
    for (const t of tokens) {
      let best = 9;
      for (const f of e.fields) {
        const r = matchTier(f, t);
        if (r >= 0 && r < best) best = r;
        if (best === 0) break;
      }
      if (best > 1) {
        for (const f of e.alts) {
          const r = matchTier(f, t);
          if (r >= 0 && r <= 1 && r < best) best = r;
        }
      }
      if (best > 0 && e.env && t.includes('_')) {
        if (e.env === t) best = 0;
        else if (best > 1 && e.env.startsWith(t)) best = 1;
        else if (best > 2 && matchTier(e.env, t) >= 0) best = Math.min(best, 3);
      }
      if (best === 9) {
        ok = false;
        break;
      }
      if (best > worst) worst = best;
    }
    if (ok) hits.push({ e, tier: worst });
  }
  hits.sort(
    (x, y) =>
      x.tier - y.tier ||
      Number(y.e.current) - Number(x.e.current) ||
      TYPE_ORDER[x.e.type] - TYPE_ORDER[y.e.type] ||
      x.e.label.length - y.e.label.length ||
      (x.e.label < y.e.label ? -1 : x.e.label > y.e.label ? 1 : 0),
  );
  return { items: hits.slice(0, limit).map((x) => ({ ...x.e, tier: x.tier })), total: hits.length };
}

/* ========================================================================== */
/* Timelines for property / artifact / extension pages                         */
/* ========================================================================== */

/**
 * Events and presence ranges of one history over the snapshots `cvs` (ascending). Changes are computed
 * between consecutive entries of `cvs` only, so leaving a snapshot out never creates removed/reintroduced
 * events of its own. `latest` = the newest state among `cvs`.
 */
function presenceTimeline(h, cvs, states, kindsFn) {
  const events = [];
  const ranges = [];
  let prevIdx;
  let prev = null;
  let current = null;
  let seen = false;
  let latest = null;
  for (const vi of cvs) {
    const si = stateAt(h, vi);
    const s = si == null ? null : states[si];
    if (s && !prev) {
      const type = seen ? 'reintroduced' : vi === cvs[0] ? 'present' : 'introduced';
      events.push({ vi, type, state: s });
      seen = true;
      current = { from: vi, to: vi };
      ranges.push(current);
    } else if (!s && prev) {
      events.push({ vi, type: 'removed', prev });
      current = null;
    } else if (s && prev) {
      current.to = vi;
      if (si !== prevIdx) {
        const kinds = kindsFn(prev, s);
        if (kinds.length) events.push({ vi, type: 'changed', kinds, from: prev, to: s });
      }
    }
    if (s) latest = s;
    prevIdx = si;
    prev = s;
  }
  return { events, ranges, latest, present: prev != null };
}

/**
 * Runs a presence timeline over the snapshots that `vis` shows. When the item is absent from all of them but
 * present in a hidden one, widens one step at a time: each `widen` entry ({vis, kind}) in order, then every
 * snapshot, so the page is never empty and turning a "Show" switch on never hides what the page showed
 * (`widened`, and `widenedBy` = the kind of the step used, or 'all').
 */
function visibleTimeline(h, allCvs, states, kindsFn, vis, widen = []) {
  const over = (v) => {
    const cvs = visibleOnly(allCvs, v);
    return { ...presenceTimeline(h, cvs, states, kindsFn), cvs };
  };
  const done = (t, by) => ({ ...t, widened: by != null, widenedBy: by, hiddenSnapshots: allCvs.length - t.cvs.length });
  const t = over(vis);
  if (!vis || t.ranges.length || t.cvs.length === allCvs.length) return done(t, null);
  for (const step of [...widen, { vis: null, kind: 'all' }]) {
    const w = over(step.vis);
    if (w.ranges.length) return done(w, step.kind);
  }
  return done(t, null);
}

/**
 * History of one config property over the config snapshots: events (present in first snapshot,
 * introduced, changed with kinds, removed, reintroduced), presence ranges and the latest state.
 * With `vis` (see releaseVisibility), only the snapshots it shows are used; `cvs` lists the snapshots
 * used, `hiddenSnapshots` how many were left out, `widened` that the property only exists in hidden ones (then
 * the `widen` visibilities are tried in order before every snapshot; see visibleTimeline).
 * @param {object} config config.json
 * @param {string|object} keyOrProp canonical key or the `props` entry
 */
export function propertyHistory(config, keyOrProp, { vis, widen } = {}) {
  const prop = typeof keyOrProp === 'object' ? keyOrProp : config.props.find((p) => p.k === keyOrProp);
  if (!prop) return null;
  const t = visibleTimeline(prop.h, config.versions, config.states, configChangeKinds, vis, widen);
  return { key: prop.k, prop, ...t };
}

/**
 * History of one extension over the extension snapshots `extVis` (see extensionVersionsOf; same event model as
 * properties, change kinds relocated / unrelocated / status / renamed). `vis` and `widen` as for propertyHistory.
 */
export function extensionHistory(extensions, id, extVis, { vis, widen } = {}) {
  const x = extensions.exts.find((e) => e.id === id);
  if (!x) return null;
  const t = visibleTimeline(x.h, extVis, extensions.states, extensionChangeKinds, vis, widen);
  return { id, ext: x, ...t };
}

function artifactRangesOver(art, versions, indices) {
  const ranges = [];
  let cur = null;
  for (const i of indices) {
    const raw = stateAt(art.h, i);
    const pre = isPreRelease(versions[i]) ? 1 : 0;
    if (cur && cur.raw === raw) {
      cur.to = i;
      cur.count++;
      cur.pre += pre;
      continue;
    }
    cur = { from: i, to: i, count: 1, pre, raw, managed: raw != null, follows: raw === '=', value: raw === '=' || raw == null ? null : raw };
    if (raw === undefined) cur.before = true;
    ranges.push(cur);
  }
  let prevManaged = null;
  for (const r of ranges) {
    if (!r.managed) {
      if (prevManaged && !r.before) r.kind = 'removed';
      prevManaged = null;
      continue;
    }
    const startValue = r.follows ? versions[r.from].v : r.value;
    if (!prevManaged) r.kind = r.from === indices[0] ? null : 'added';
    else {
      const endValue = prevManaged.follows ? versions[prevManaged.to].v : prevManaged.value;
      r.kind = bomChangeKind(endValue, startValue);
    }
    prevManaged = r;
  }
  return ranges;
}

/**
 * Version ranges of one managed artifact across the releases. Each range is
 * `{from, to, count, pre, raw, managed, follows, value, kind}`: a run of consecutive releases with the same value
 * (`from`/`to` are release indices, `count` the releases in the run, `pre` how many of them are pre-releases);
 * `follows` marks `"="` (same as the Quarkus release), `kind` is the change from the previous managed range
 * (major/minor/patch/other/downgrade/added/removed).
 * With `vis` (see releaseVisibility), only the releases it shows are used, so a hidden pre-release never splits
 * a range; when the artifact is managed only in hidden releases, the `widen` visibilities are tried in order, then
 * every release (`widened`, `widenedBy` as for propertyHistory).
 */
export function artifactHistory(bom, id, versions, { vis, widen = [] } = {}) {
  const art = bom.artifacts.find((x) => x.id === id);
  if (!art) return null;
  const everything = versions.map((_, i) => i);
  let indices = vis ? vis.indices : everything;
  let ranges = artifactRangesOver(art, versions, indices);
  let widenedBy = null;
  if (vis && indices.length < everything.length && !ranges.some((r) => r.managed)) {
    for (const step of [...widen, { vis: null, kind: 'all' }]) {
      const idx = step.vis ? step.vis.indices : everything;
      const rs = artifactRangesOver(art, versions, idx);
      if (rs.some((r) => r.managed)) {
        indices = idx;
        ranges = rs;
        widenedBy = step.kind;
        break;
      }
    }
  }
  const widened = widenedBy != null;
  const last = indices.length ? indices[indices.length - 1] : versions.length - 1;
  const raw = stateAt(art.h, last);
  const managedRanges = ranges.filter((r) => r.managed);
  return {
    id,
    ...splitId(id),
    ranges,
    indices,
    widened,
    widenedBy,
    hiddenReleases: versions.length - indices.length,
    last,
    current: { raw, managed: raw != null, follows: raw === '=', value: raw === '=' ? versions[last].v : raw ?? null },
    firstManaged: managedRanges.length ? managedRanges[0].from : null,
    lastManaged: managedRanges.length ? managedRanges[managedRanges.length - 1].to : null,
  };
}

/** Runs, events and presence ranges of one class history over the snapshots `cvs` (see classHistory). */
function classTimeline(h, cvs, versions) {
  const events = [];
  const runs = [];
  const ranges = [];
  let prev = [];
  let run = null;
  let range = null;
  let seen = false;
  for (const vi of cvs) {
    const jars = jarsOf(stateAt(h, vi));
    if (jars.length && !prev.length) {
      events.push({ vi, type: seen ? 'reintroduced' : vi === cvs[0] ? 'present' : 'introduced', jars });
      seen = true;
      range = { from: vi, to: vi };
      ranges.push(range);
    } else if (!jars.length && prev.length) {
      events.push({ vi, type: 'removed', from: prev });
      range = null;
    } else if (jars.length && !sameJars(jars, prev)) {
      events.push({ vi, type: 'moved', from: prev, to: jars });
    }
    if (range && jars.length) range.to = vi;
    const pre = versions && isPreRelease(versions[vi]) ? 1 : 0;
    if (run && sameJars(run.jars, jars)) {
      run.to = vi;
      run.count++;
      run.pre += pre;
    } else {
      run = { from: vi, to: vi, count: 1, pre, jars, before: !seen };
      runs.push(run);
    }
    prev = jars;
  }
  return { events, runs, ranges, jars: prev, present: prev.length > 0 };
}

/**
 * History of one class over the classes snapshots `clsVis` (release indices, ascending): `runs` of consecutive
 * snapshots in the same jars (`{from, to, count, pre, jars, before}`: release indices, how many snapshots and how many
 * of them pre-releases, the jars as artifact indices, empty while absent, `before` for an absent run before the first
 * presence), `events` (present in the first snapshot used, introduced, moved `from` → `to`, removed, reintroduced),
 * presence `ranges`, and the jars of the newest snapshot used (`jars`, `present`). `vis` and `widen` as for
 * propertyHistory; `versions` (versions.json's list) lets runs count their pre-releases.
 * @param {object} classes a classes file, or several merged (mergeClasses)
 * @param {string|Array} nameOrRow binary name or the row
 */
export function classHistory(classes, nameOrRow, clsVis, { vis, widen = [], versions } = {}) {
  const row = Array.isArray(nameOrRow) ? nameOrRow : findClass(classes, nameOrRow);
  if (!row) return null;
  const all = Array.isArray(clsVis) ? clsVis : [];
  const over = (v) => {
    const cvs = visibleOnly(all, v);
    return { ...classTimeline(row[2], cvs, versions), cvs };
  };
  const done = (t, by) => ({ name: className(classes, row), row, ...t, widened: by != null, widenedBy: by, hiddenSnapshots: all.length - t.cvs.length });
  const t = over(vis);
  if (!vis || t.ranges.length || t.cvs.length === all.length) return done(t, null);
  for (const step of [...widen, { vis: null, kind: 'all' }]) {
    const w = over(step.vis);
    if (w.ranges.length) return done(w, step.kind);
  }
  return done(t, null);
}

/* ========================================================================== */
/* Release selection helpers (home defaults, quick picks, version page)        */
/* ========================================================================== */

/** Index of the newest final release, or -1. */
export function newestFinalIndex(versions) {
  for (let i = versions.length - 1; i >= 0; i--) if (!versions[i].pre && isFinal(versions[i].v)) return i;
  return -1;
}

/** Index of the newest release in a stream (finals preferred), or -1. */
export function newestInStream(versions, s, { finalOnly = true } = {}) {
  let any = -1;
  for (let i = versions.length - 1; i >= 0; i--) {
    if (streamOf(versions[i]) !== s) continue;
    if (any < 0) any = i;
    if (!finalOnly || (!versions[i].pre && isFinal(versions[i].v))) return i;
  }
  return any;
}

/** The LTS streams that have at least one release, ascending. */
export function ltsStreamsPresent(versionsJson) {
  const list = versionsJson.versions ?? [];
  const declared = new Set(versionsJson.lts ?? []);
  for (const x of list) if (x.lts) declared.add(streamOf(x));
  const present = new Set(list.map(streamOf));
  return [...declared].filter((s) => present.has(s)).sort(compareVersions);
}

function streamsAscending(list) {
  return [...new Set(list.map(streamOf))].sort(compareVersions);
}

/**
 * Index of a stream's first final release (x.y.0 or x.y.0.Final when the dataset has it, else its lowest final,
 * such as 3.8.1), or -1 when the stream has no final release. `versions` must be ascending.
 */
export function streamStartIndex(versions, s) {
  const list = Array.isArray(versions) ? versions : [];
  for (let i = 0; i < list.length; i++) if (streamOf(list[i]) === s && !isPreRelease(list[i])) return i;
  return -1;
}

/**
 * The time between two streams: between the dates of each stream's first final release (`3.27` → `3.33`:
 * `6 months`). Null when either stream has no final release yet or a date is missing.
 * @returns {{from:number, to:number, label:string}|null} release indices of the two first finals and the span
 */
export function streamSpan(versions, fromStream, toStream) {
  const from = streamStartIndex(versions, fromStream);
  const to = streamStartIndex(versions, toStream);
  if (from < 0 || to < 0) return null;
  const label = spanLabel(versions[from].date, versions[to].date);
  return label ? { from, to, label } : null;
}

/**
 * Home page defaults: the latest patch of the previous LTS → the newest final
 * (previous LTS = newest LTS stream older than the newest final's stream).
 */
export function defaultPair(versionsJson) {
  const list = versionsJson.versions ?? [];
  if (!list.length) return null;
  let to = newestFinalIndex(list);
  if (to < 0) to = list.length - 1;
  const toStream = streamOf(list[to]);
  const prevLts = ltsStreamsPresent(versionsJson).filter((s) => compareVersions(s, toStream) < 0).pop();
  let from = prevLts ? newestInStream(list, prevLts) : -1;
  if (from < 0) {
    const older = streamsAscending(list).filter((s) => compareVersions(s, toStream) < 0).pop();
    from = older ? newestInStream(list, older) : 0;
  }
  return { from, to };
}

/**
 * Quick comparisons: LTS → next LTS (newest patch of each), latest LTS → newest final,
 * newest final → pre-release (when a newer pre-release exists). `span` is the time between the two streams
 * (streamSpan: the dates of their first finals), null when the target stream has no final yet.
 * @returns {Array<{kind:'lts'|'latest'|'pre', from:number, to:number, fromStream:string, toStream:string,
 *   span:{from:number, to:number, label:string}|null}>}
 */
export function quickPicks(versionsJson) {
  const list = versionsJson.versions ?? [];
  const picks = [];
  const push = (kind, from, to, fromStream, toStream) =>
    picks.push({ kind, from, to, fromStream, toStream, span: streamSpan(list, fromStream, toStream) });
  const lts = ltsStreamsPresent(versionsJson);
  for (let i = 1; i < lts.length; i++) {
    const from = newestInStream(list, lts[i - 1]);
    const to = newestInStream(list, lts[i]);
    if (from >= 0 && to >= 0 && from !== to) push('lts', from, to, lts[i - 1], lts[i]);
  }
  const nf = newestFinalIndex(list);
  if (lts.length && nf >= 0) {
    const latest = newestInStream(list, lts[lts.length - 1]);
    if (latest >= 0 && latest !== nf) push('latest', latest, nf, lts[lts.length - 1], streamOf(list[nf]));
  }
  const newest = list.length - 1;
  if (nf >= 0 && newest > nf && compareVersions(list[newest].v, list[nf].v) > 0) {
    push('pre', nf, newest, streamOf(list[nf]), streamOf(list[newest]));
  }
  return picks;
}

/**
 * Quick compares for a release page (-1 when absent):
 * - previousPatch: the previous final of the same stream (pre-releases are not patches);
 * - previousPre: for a pre-release, the previous pre-release of its stream; for the first final of a stream,
 *   the stream's newest pre-release ("what changed since the CR");
 * - previousMinor: the newest final of the nearest older stream;
 * - previousLts: the newest final of the newest LTS stream older than this release's stream;
 * - older / newer: the neighbouring releases.
 * With `vis` (see releaseVisibility), the releases it hides are skipped: previousPre, previousMinor, previousLts,
 * older and newer only name releases it shows (-1 otherwise).
 */
export function versionNeighbours(versionsJson, vi, { vis } = {}) {
  const list = versionsJson.versions ?? [];
  const shown = (i) => !vis || !!vis.visible[i];
  const s = streamOf(list[vi]);
  let previousPatch = -1;
  let previousPre = -1;
  for (let i = vi - 1; i >= 0; i--) {
    if (streamOf(list[i]) !== s) continue;
    if (isPreRelease(list[i])) {
      if (previousPre < 0 && shown(i)) previousPre = i;
    } else if (previousPatch < 0) {
      previousPatch = i;
    }
  }
  if (!isPreRelease(list[vi]) && previousPatch >= 0) previousPre = -1;
  const olderStream = streamsAscending(list).filter((x) => compareVersions(x, s) < 0).pop();
  const minor = olderStream ? newestInStream(list, olderStream) : -1;
  const previousMinor = minor >= 0 && shown(minor) ? minor : -1;
  const olderLts = ltsStreamsPresent(versionsJson).filter((x) => compareVersions(x, s) < 0).pop();
  const lts = olderLts ? newestInStream(list, olderLts) : -1;
  const previousLts = lts >= 0 && shown(lts) ? lts : -1;
  let older = -1;
  for (let i = vi - 1; i >= 0; i--) {
    if (shown(i)) {
      older = i;
      break;
    }
  }
  let newer = -1;
  for (let i = vi + 1; i < list.length; i++) {
    if (shown(i)) {
      newer = i;
      break;
    }
  }
  return { previousPatch, previousPre, previousMinor, previousLts, older, newer };
}

/* ========================================================================== */
/* Formatting                                                                   */
/* ========================================================================== */

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

function parseIsoDate(iso) {
  const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(String(iso ?? ''));
  if (!m) return null;
  return { y: Number(m[1]), m: Number(m[2]), d: Number(m[3]) };
}

/** `2025-03-26` → `26 Mar 2025` (no locale or time-zone dependence); unknown input is returned as-is. */
export function formatDate(iso) {
  const p = parseIsoDate(iso);
  if (!p || p.m < 1 || p.m > 12) return String(iso ?? '');
  return `${p.d} ${MONTHS[p.m - 1]} ${p.y}`;
}

/**
 * The compare header's phrase for two release dates, order-insensitive: `released the same day`,
 * `released 1 day apart`, `released 6 months apart`. Empty when a date is missing.
 */
export function releasedApart(a, b) {
  const span = spanLabel(a, b);
  if (!span) return '';
  return span === 'same day' ? 'released the same day' : `released ${span} apart`;
}

/** A span that reads on its own next to two releases: `same day`, `12 days apart`, `6 months apart`. */
export function spanApart(a, b) {
  const span = spanLabel(a, b);
  if (!span || span === 'same day') return span;
  return `${span} apart`;
}

/** Human span between two ISO dates, order-insensitive: `same day`, `12 days`, `11 months`, `1 year 2 months`. */
export function spanLabel(a, b) {
  const x0 = parseIsoDate(a);
  const y0 = parseIsoDate(b);
  if (!x0 || !y0) return '';
  const days = (p) => Date.UTC(p.y, p.m - 1, p.d) / 86400000;
  const [x, y] = days(x0) <= days(y0) ? [x0, y0] : [y0, x0];
  const d = Math.round(days(y) - days(x));
  if (d === 0) return 'same day';
  if (d < 45) return `${d} day${d === 1 ? '' : 's'}`;
  const months = (y.y - x.y) * 12 + (y.m - x.m) - (y.d < x.d ? 1 : 0);
  if (months < 12) return `${months} month${months === 1 ? '' : 's'}`;
  const years = Math.floor(months / 12);
  const rest = months % 12;
  return `${years} year${years === 1 ? '' : 's'}${rest ? ` ${rest} month${rest === 1 ? '' : 's'}` : ''}`;
}
