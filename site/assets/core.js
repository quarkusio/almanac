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
 * so that diffBom compares platform BOMs as it compares core BOMs. Sorted by id like bom.json;
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
  const coreByKey = groupBy(onlyCore, (r) => platformArtifactKey(r.id));
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
  const label = (groups) => {
    if (groups.length === 1) return groups[0];
    const parts = groups.map((g) => g.split('.'));
    let n = 0;
    while (parts.every((p) => n < p.length && p[n] === parts[0][n])) n++;
    return n >= 2 ? `${parts[0].slice(0, n).join('.')}…` : `${groups[0]} and ${groups.length - 1} other group${groups.length > 2 ? 's' : ''}`;
  };
  return [...groupBy(changed, (r) => `${r.core}\u0000${r.platform}`).values()]
    .map((rows) => {
      const groups = [...new Set(rows.map((r) => splitId(r.id).group))].sort();
      return { core: rows[0].core, platform: rows[0].platform, count: rows.length, groups, label: label(groups), rows };
    })
    .sort((x, y) => y.count - x.count || (x.label < y.label ? -1 : x.label > y.label ? 1 : 0));
}

/**
 * What comparing the platform BOMs changes in a comparison: the dependency changes (diffBom rows) of the core BOMs,
 * `coreDiff`, against those of the platform BOMs of the same two releases, `platformDiff`, a change being its artifact
 * (platformArtifactKey, so that ids that only write one artifact otherwise are one) and its two versions. `count` is
 * the artifacts with a change that only one of them has; `spelling` says, when there is none, whether the rows still
 * differ in such ids.
 */
export function platformDiffEffect(coreDiff, platformDiff) {
  const changes = (d, key) => new Set(d.rows.map((r) => `${key(r.id)}\u0000${r.from ?? ''}\u0000${r.to ?? ''}`));
  const c = changes(coreDiff, platformArtifactKey);
  const p = changes(platformDiff, platformArtifactKey);
  const differing = [...c].filter((k) => !p.has(k)).concat([...p].filter((k) => !c.has(k)));
  const artifacts = new Set(differing.map((k) => k.split('\u0000')[0]));
  const rc = changes(coreDiff, (id) => id);
  const rp = changes(platformDiff, (id) => id);
  return { count: artifacts.size, spelling: !artifacts.size && (rc.size !== rp.size || [...rc].some((k) => !rp.has(k))) };
}

/**
 * The other ids of the artifact that `id` names (platformArtifactKey), sorted: `core`, those the core BOM manages at
 * some release (bom.json), and `platform`, those only the platform BOM manages (platform.json).
 */
export function artifactSpellings(bom, platform, id) {
  const key = platformArtifactKey(id);
  const inCore = new Set(bom.artifacts.map((a) => a.id));
  const same = (x) => x !== id && platformArtifactKey(x) === key;
  const byText = (x, y) => (x < y ? -1 : x > y ? 1 : 0);
  return {
    core: [...inCore].filter(same).sort(byText),
    platform: platform.artifacts.map((a) => a.id).filter((x) => !inCore.has(x) && same(x)).sort(byText),
  };
}

/**
 * The runs of releases among `indices` (ascending, e.g. those the switches show) in which the platform BOM manages the
 * artifact `id` otherwise than the core BOM: `{from, to, count, platform, core, platformId, coreId, without}`, values
 * raw as bom.json writes them ("=" for the release's own version, null when not managed). Where only one BOM manages
 * `id`, the other BOM's entries of the same artifact (artifactSpellings) that only that BOM manages stand for it, as
 * in platformDifferences: one at the same version is no difference, else the first one's value is the other BOM's and
 * its id is `platformId` or `coreId` (null when the value is the id's own). A run is consecutive releases of `indices`
 * with the same values; the releases without a platform BOM inside it are `without`, and `count` is the others.
 */
export function platformArtifactRuns(bom, platform, versions, id, indices) {
  const coreById = new Map(bom.artifacts.map((a) => [a.id, a.h]));
  const platformById = new Map(platform.artifacts.map((a) => [a.id, a.h]));
  if (!coreById.has(id) && !platformById.has(id)) return [];
  const spellings = artifactSpellings(bom, platform, id);
  const others = [...spellings.core, ...spellings.platform].sort((x, y) => (x < y ? -1 : x > y ? 1 : 0));
  const raw = (x, i) => ({ core: stateAt(coreById.get(x), i) ?? null, platform: platformRawAt(coreById.get(x), platformById.get(x), i) });
  const differenceAt = (i) => {
    const res = (r) => (r == null ? null : resolveBomValue(r, versions[i].v));
    const own = raw(id, i);
    if (own.core != null && own.platform != null) {
      return res(own.core) === res(own.platform) ? null : { platform: own.platform, core: own.core, platformId: null, coreId: null };
    }
    if (own.core == null && own.platform == null) return null;
    const lacking = own.core == null ? 'core' : 'platform';
    const having = lacking === 'core' ? 'platform' : 'core';
    // the lacking BOM's entries of this artifact that only it manages here
    const partners = others.map((x) => ({ id: x, ...raw(x, i) })).filter((r) => r[lacking] != null && r[having] == null);
    if (partners.some((r) => res(r[lacking]) === res(own[having]))) return null;
    const partner = partners[0] ?? null;
    return lacking === 'core'
      ? { platform: own.platform, core: partner ? partner.core : null, platformId: null, coreId: partner ? partner.id : null }
      : { platform: partner ? partner.platform : null, core: own.core, platformId: partner ? partner.id : null, coreId: null };
  };
  const runs = [];
  let cur = null;
  let gap = []; // releases without a platform BOM since the end of `cur`
  for (const i of indices) {
    if (!hasPlatformBom(platform, i)) {
      if (cur) gap.push(i);
      continue;
    }
    const d = differenceAt(i);
    if (cur && d && cur.platform === d.platform && cur.core === d.core && cur.platformId === d.platformId && cur.coreId === d.coreId) {
      cur.to = i;
      cur.count++;
      cur.without.push(...gap);
      gap = [];
      continue;
    }
    gap = [];
    cur = d ? { from: i, to: i, count: 1, ...d, without: [] } : null;
    if (cur) runs.push(cur);
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
 * the next, the merged history gets a null point at that line's first snapshot. The same holds for the API data:
 * the revision history of each class row (its 4th element) and the `api.dep` rows, renumbered, start afresh at each
 * line's first release with api data, and a dep row's `from` (its 5th element) becomes `[[that first release, from]]`,
 * one pair per line that carries one. `api.lines` lists, for each line with api data, its `major` (the file's, which
 * its loader sets), `shards` and `versions`, so that pages find the shard files of a class.
 */
export function mergeClasses(parts) {
  const files = (parts ?? []).filter((f) => f && Array.isArray(f.versions) && f.versions.length)
    .sort((x, y) => x.versions[0] - y.versions[0]);
  if (!files.length) return { versions: [], artifacts: [], packages: [], classes: [] };
  if (files.length === 1) return withApiLines(files[0]);
  const artifacts = [...new Set(files.flatMap((f) => f.artifacts))].sort();
  const packages = [...new Set(files.flatMap((f) => f.packages))].sort();
  const artifactIndex = new Map(artifacts.map((a, i) => [a, i]));
  const packageIndex = new Map(packages.map((p, i) => [p, i]));
  const rows = new Map(); // `${package index} ${name}` → merged row
  const deps = new Map(); // `${row key}\u0000${artifactId}\u0000${member}` → {rowKey, artifact, member, h, from}
  const same = (x, y) => JSON.stringify(x ?? null) === JSON.stringify(y ?? null);
  const identical = (x, y) => x === y;
  const last = (h) => h[h.length - 1];
  // what the earlier lines end with does not hold in this one before its first point
  const append = (out, h, first, eq) => {
    if (out.length && last(out)[1] != null && h[0]?.[0] !== first) out.push([first, null]);
    for (const [vi, v] of h) if (!out.length || !eq(last(out)[1], v)) out.push([vi, v]);
  };
  const close = (out, first) => {
    if (out.length && last(out)[1] != null && last(out)[0] < first) out.push([first, null]);
  };
  for (const f of files) {
    const first = f.versions[0];
    const apiFirst = f.api?.versions?.[0] ?? null;
    const remap = (v) => {
      if (v == null) return null;
      if (!Array.isArray(v)) return artifactIndex.get(f.artifacts[v]);
      return v.map((j) => artifactIndex.get(f.artifacts[j])).sort((a, b) => a - b);
    };
    const inLine = new Set();
    for (const [pi, name, h, rev] of f.classes) {
      const key = `${packageIndex.get(f.packages[pi])} ${name}`;
      inLine.add(key);
      if (!rows.has(key)) rows.set(key, [packageIndex.get(f.packages[pi]), name, []]);
      const row = rows.get(key);
      append(row[2], h.map(([vi, v]) => [vi, remap(v)]), first, same);
      if (apiFirst == null) continue;
      if (Array.isArray(rev) && rev.length) append(row[3] ?? (row[3] = []), rev, apiFirst, identical);
      else if (row[3]) close(row[3], apiFirst);
    }
    for (const [key, row] of rows) {
      if (inLine.has(key)) continue;
      close(row[2], first);
      if (row[3] && apiFirst != null) close(row[3], apiFirst);
    }
    if (apiFirst == null) continue;
    const seen = new Set();
    for (const [ci, ai, member, h, from] of f.api.dep ?? []) {
      const crow = f.classes[ci];
      const rowKey = `${packageIndex.get(f.packages[crow[0]])} ${crow[1]}`;
      const key = `${rowKey}\u0000${f.artifacts[ai]}\u0000${member ?? ''}`;
      seen.add(key);
      if (!deps.has(key)) deps.set(key, { rowKey, artifact: artifactIndex.get(f.artifacts[ai]), member: member ?? null, h: [], from: [] });
      const d = deps.get(key);
      append(d.h, h, apiFirst, identical);
      if (from != null) d.from.push([apiFirst, from]);
    }
    for (const [key, d] of deps) if (!seen.has(key)) close(d.h, apiFirst);
  }
  const classes = [...rows.values()].sort((x, y) => x[0] - y[0] || (x[1] < y[1] ? -1 : x[1] > y[1] ? 1 : 0));
  const merged = { versions: files.flatMap((f) => f.versions), artifacts, packages, classes };
  const apiFiles = files.filter((f) => Array.isArray(f.api?.versions) && f.api.versions.length);
  if (apiFiles.length) {
    const rowIndex = new Map(classes.map((row, i) => [`${row[0]} ${row[1]}`, i]));
    const dep = [...deps.values()].map((d) => [rowIndex.get(d.rowKey), d.artifact, d.member, d.h, ...(d.from.length ? [d.from] : [])])
      .sort((x, y) => x[0] - y[0] || x[1] - y[1] || ((x[2] ?? '') < (y[2] ?? '') ? -1 : (x[2] ?? '') > (y[2] ?? '') ? 1 : 0));
    merged.api = { versions: apiFiles.flatMap((f) => f.api.versions), dep, lines: apiFiles.map(apiLine) };
  }
  return merged;
}

/** The api line of a classes file: its major, its number of shards and its releases with api data. */
function apiLine(f) {
  return { major: f.major ?? null, shards: f.api.shards ?? 0, versions: f.api.versions };
}

/** A classes file as mergeClasses gives one: with `api.lines` when it has api data. */
function withApiLines(f) {
  if (!Array.isArray(f.api?.versions) || !f.api.versions.length) return f;
  return { ...f, api: { ...f.api, lines: [apiLine(f)] } };
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

/** The class a nested class is declared in (`…QuarkusUnitTest` for `…QuarkusUnitTest$Builder`, `A$B` for `A$B$C`), else null. */
export function enclosingClassName(name) {
  const s = String(name ?? '');
  return isNestedClass(s) ? s.slice(0, s.lastIndexOf('$')) : null;
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

/**
 * The classes that are API at both releases `a` and `b` (release indices with api data, by the classes' API
 * revisions) but in other jars at B than at A, as rows of the compare page's API changes (diffApi): `{name, nested,
 * member: null, change: 'moved', artifact, fromJars, toJars, ...}`, `fromJars` and `toJars` the jars at A and B
 * (artifactIds) and `artifact` the first one at B. From the classes files alone: no member data is needed. A class
 * that is API at one of the releases only is added or removed there, which diffApi says.
 */
export function movedApiClasses(classes, a, b) {
  const out = [];
  for (const row of classes.classes) {
    if (!Array.isArray(row[3]) || apiRevisionAt(row, a) == null || apiRevisionAt(row, b) == null) continue;
    const from = jarsOf(stateAt(row[2], a));
    const to = jarsOf(stateAt(row[2], b));
    if (!from.length || !to.length || sameJars(from, to)) continue;
    const name = className(classes, row);
    const jars = (list) => list.map((j) => classes.artifacts[j]);
    out.push({
      name, nested: isNestedClass(name), member: null, change: 'moved', artifact: classes.artifacts[to[0]], fromJars: jars(from), toJars: jars(to),
      details: [], previous: null, protected: false, constructor: false, text: [name, '', ...jars(to), ...jars(from)].join('\n').toLowerCase(),
    });
  }
  return out;
}

/**
 * The API classes of a comparison by their API revisions, nested classes left out as the API section lists them by
 * default: `added` (API at B only), `removed` (at A only) and `changed` (at both, with another revision). From the
 * classes files alone.
 */
export function apiClassCounts(classes, a, b) {
  const out = { added: 0, removed: 0, changed: 0 };
  for (const row of classes.classes) {
    if (!Array.isArray(row[3]) || isNestedClass(className(classes, row))) continue;
    const ra = apiRevisionAt(row, a);
    const rb = apiRevisionAt(row, b);
    if (ra == null && rb == null) continue;
    if (ra == null) out.added++;
    else if (rb == null) out.removed++;
    else if (ra !== rb) out.changed++;
  }
  return out;
}

/** What each kind of API change counts as on the summary of a comparison; the deprecation changes count apart. */
const API_COUNT_KIND = {
  added: 'added', removed: 'removed', inherited: 'changed', declared: 'changed', moved: 'changed', 'return type': 'changed', signature: 'changed',
  modifiers: 'changed', supertype: 'changed',
};

/**
 * The API changes of a comparison (rows of diffApi and movedApiClasses) counted for its summary, nested classes left
 * out as the API section lists them by default: `added`, `removed` and `changed` (inherited, moved, another return
 * type, signature, modifiers or supertype), and the same by element in `byKind` (class, field, constructor, method).
 * Deprecation changes are not counted: diffDeprecations counts them.
 */
export function apiRowCounts(rows) {
  const out = { added: 0, removed: 0, changed: 0, byKind: {} };
  for (const r of rows) {
    const k = API_COUNT_KIND[r.change];
    if (!k || r.nested) continue;
    out[k]++;
    const kind = r.member == null ? 'class' : memberKind(r.member);
    // own properties only: 'constructor' is one of every object's
    if (!Object.hasOwn(out.byKind, kind)) out.byKind[kind] = { added: 0, removed: 0, changed: 0 };
    out.byKind[kind][k]++;
  }
  return out;
}

/* ========================================================================== */
/* API: the public and protected classes and members of the io.quarkus jars    */
/* ========================================================================== */

/*
 * A classes file (classes-<major>.json) may have `api`: `versions`, the release indices of its line with api data;
 * `shards`, how many api-<major>-<k>.json files hold the members of its classes; `dep`, a row
 * `[class row, artifact, member key|null, history, from?]` for every class and member of the line that is deprecated
 * in some release of any line, with its whole history over the line, and `from`, where the item's deprecation began
 * when it began in an earlier line and still holds at the line's first release with api data (the rows of an item in
 * a line carry the same). A class row may have a 4th element, the history of its API revision (equal revisions,
 * even in two lines, mean equal API; null while it is not API). Values follow the dataset's grammar: a class
 * `<access><kind><modifiers>[X][D|R][ extends N][ implements N,N][ since T]`, a member
 * `<access><modifiers>[X][D|R][ since T]`; member keys are `name(descriptor)return` or `name:descriptor`.
 */

/** Indices of the releases with api data (`versions[].api`). */
export function apiVersionsOf(versions) {
  const out = [];
  (Array.isArray(versions) ? versions : []).forEach((x, i) => {
    if (x && x.api) out.push(i);
  });
  return out;
}

/** The api data used for release `vi`, or null: the config rule over the releases with api data (snapshotVersionFor). */
export function apiVersionFor(vi, versions, apiVis) {
  if (!Array.isArray(versions)) return null;
  return snapshotVersionFor(vi, versions, apiVis ?? apiVersionsOf(versions));
}

const CLASS_KINDS = { '': 'class', i: 'interface', n: 'annotation', e: 'enum', r: 'record' };
const CLASS_HEAD = /^([+#])([iner]?)(a?)(f?)(s?)(X?)([DR]?)/;
const MEMBER_HEAD = /^([+#])(s?)(a?)(f?)(d?)(v?)(e?)(X?)([DR]?)/;

/**
 * A value of the api grammar, parsed: `access` (public or protected), its modifiers as flags, `experimental`,
 * `deprecated` (also for one marked for removal), `forRemoval` and `since` (declared, else null); a class value
 * (`member` false) also has `kind` (class, interface, annotation, enum or record), `extends` (null for none written)
 * and `implements`. Null for null or for a value outside the grammar.
 */
export function parseApiValue(value, member = false) {
  if (typeof value !== 'string') return null;
  const m = (member ? MEMBER_HEAD : CLASS_HEAD).exec(value);
  if (!m) return null;
  let rest = value.slice(m[0].length);
  const access = m[1] === '+' ? 'public' : 'protected';
  const out = member
    ? { access, static: !!m[2], abstract: !!m[3], final: !!m[4], default: !!m[5], varargs: !!m[6], enumConstant: !!m[7], experimental: !!m[8] }
    : { access, kind: CLASS_KINDS[m[2]], abstract: !!m[3], final: !!m[4], static: !!m[5], experimental: !!m[6] };
  const dep = member ? m[9] : m[7];
  out.deprecated = dep !== '';
  out.forRemoval = dep === 'R';
  if (!member) {
    out.extends = null;
    out.implements = [];
    const take = (prefix) => {
      const end = rest.indexOf(' ', prefix.length);
      const text = rest.slice(prefix.length, end < 0 ? undefined : end);
      rest = end < 0 ? '' : rest.slice(end);
      return text;
    };
    if (rest.startsWith(' extends ')) out.extends = take(' extends ');
    if (rest.startsWith(' implements ')) out.implements = take(' implements ').split(',');
  }
  out.since = null;
  if (rest.startsWith(' since ')) {
    out.since = rest.slice(7);
    rest = '';
  }
  return rest === '' ? out : null;
}

/** 'removal' for a value marked for removal, 'deprecated' for one only deprecated, else null (also for null). */
export function deprecationOf(value) {
  if (typeof value !== 'string') return null;
  const space = value.indexOf(' ');
  const head = space < 0 ? value : value.slice(0, space);
  return head.includes('R') ? 'removal' : head.includes('D') ? 'deprecated' : null;
}

const PRIMITIVES = { B: 'byte', C: 'char', D: 'double', F: 'float', I: 'int', J: 'long', S: 'short', Z: 'boolean', V: 'void' };

/** One type of a JVM descriptor from `at`: `{full, simple, dims, end}` (`java.util.Map.Entry`, `Map.Entry`, array dimensions, the index after it). */
function readType(desc, at) {
  let i = at;
  let dims = 0;
  while (desc[i] === '[') {
    dims++;
    i++;
  }
  let full;
  let simple;
  if (desc[i] === 'L') {
    const end = desc.indexOf(';', i);
    if (end < 0) return null;
    const internal = desc.slice(i + 1, end);
    full = internal.replace(/[/$]/g, '.');
    simple = internal.slice(internal.lastIndexOf('/') + 1).replace(/\$/g, '.');
    i = end + 1;
  } else if (PRIMITIVES[desc[i]]) {
    full = PRIMITIVES[desc[i]];
    simple = full;
    i++;
  } else {
    return null;
  }
  return { full: full + '[]'.repeat(dims), simple: simple + '[]'.repeat(dims), dims, end: i };
}

/** The kind of member a member key names: 'constructor' (`<init>(…)V`), 'method' (`name(…)…`) or 'field' (`name:…`). */
export function memberKind(key) {
  const s = String(key ?? '');
  const paren = s.indexOf('(');
  const colon = s.indexOf(':');
  if (paren > 0 && (colon < 0 || paren < colon)) return s.startsWith('<init>(') ? 'constructor' : 'method';
  return 'field';
}

/**
 * A member key pretty-printed: `kind` ('constructor', 'method' or 'field'), `name`, `params` and `returns` (methods,
 * constructors) or `type` (fields) as `{full, simple}` types, and two texts, `simple` (`QuarkusUnitTest
 * withApplicationRoot(Consumer)`, with simple names) and `full` (with full names). A constructor takes the simple name
 * of its class `className`; with `varargs` the last parameter shows as `T...`. Null for a key outside the grammar.
 */
export function prettyMember(key, { className: owner = '', varargs = false } = {}) {
  const s = String(key ?? '');
  const kind = memberKind(s);
  if (kind === 'field') {
    const colon = s.indexOf(':');
    if (colon <= 0) return null;
    const type = readType(s, colon + 1);
    if (!type || type.end !== s.length) return null;
    const name = s.slice(0, colon);
    return { kind, name, type, simple: `${type.simple} ${name}`, full: `${type.full} ${name}` };
  }
  const paren = s.indexOf('(');
  const close = s.indexOf(')', paren);
  if (close < 0) return null;
  const params = [];
  for (let i = paren + 1; i < close;) {
    const t = readType(s, i);
    if (!t || t.end > close) return null;
    params.push(t);
    i = t.end;
  }
  const returns = readType(s, close + 1);
  if (!returns || returns.end !== s.length) return null;
  if (varargs && params.length && params[params.length - 1].dims > 0) {
    const last = params[params.length - 1];
    params[params.length - 1] = { ...last, full: `${last.full.slice(0, -2)}...`, simple: `${last.simple.slice(0, -2)}...` };
  }
  const name = s.slice(0, paren);
  const shown = kind === 'constructor' ? simpleClassName(owner).replace(/^.*\$/, '') || name : name;
  const list = (field) => params.map((p) => p[field]).join(', ');
  return {
    kind, name, params, returns,
    simple: kind === 'constructor' ? `${shown}(${list('simple')})` : `${returns.simple} ${shown}(${list('simple')})`,
    full: kind === 'constructor' ? `${shown}(${list('full')})` : `${returns.full} ${shown}(${list('full')})`,
  };
}

/** The name and parameters of a method key (`withApplicationRoot(Ljava/util/function/Consumer;)`), without its return type. */
function memberSignature(key) {
  const close = key.indexOf(')');
  return close < 0 ? key : key.slice(0, close + 1);
}

/**
 * The member shard of a class (its file `api-<major>-<k>.json` among `n`): FNV-1a 32 over the UTF-8 bytes of its
 * binary name up to the first `$`, so that nested classes share their outer class's shard, modulo `n`.
 */
export function memberShardOf(name, n) {
  const s = String(name ?? '');
  const cut = s.indexOf('$');
  let hash = 0x811c9dc5;
  for (const byte of new TextEncoder().encode(cut < 0 ? s : s.slice(0, cut))) {
    hash = Math.imul(hash ^ byte, 0x01000193) >>> 0;
  }
  return n > 0 ? hash % n : 0;
}

/**
 * The api data of a classes object: the `dep` rows grouped by class row and member (null for the class itself), each
 * group the rows of one deprecated class or member in its jars. Built once per object.
 */
const depIndexes = new WeakMap();
function depIndex(classes) {
  let index = depIndexes.get(classes);
  if (!index) {
    index = new Map(); // class row index → Map(member key or '' → [dep rows])
    for (const row of classes.api?.dep ?? []) {
      if (!index.has(row[0])) index.set(row[0], new Map());
      const members = index.get(row[0]);
      const key = row[2] ?? '';
      if (!members.has(key)) members.set(key, []);
      members.get(key).push(row);
    }
    depIndexes.set(classes, index);
  }
  return index;
}

/** The dep rows of a class row index and member key (null: the class itself): one per jar, possibly none. */
export function depRowsOf(classes, classIndex, member = null) {
  return depIndex(classes).get(classIndex)?.get(member ?? '') ?? [];
}

/** The value of a group of dep rows (one class or member in its jars) at release `vi`: the first jar's that has one. */
export function depValueAt(rows, vi) {
  for (const row of rows) {
    const value = stateAt(row[3], vi);
    if (value != null) return value;
  }
  return null;
}

/**
 * The api lines of a classes object, ascending: `api.lines` (mergeClasses), or its own releases with api data as one
 * line for a lone classes file; none without api data.
 */
export function apiLinesOf(classes) {
  const api = classes?.api;
  if (!Array.isArray(api?.versions) || !api.versions.length) return [];
  return Array.isArray(api.lines) ? api.lines : [{ versions: api.versions }];
}

/** The api line of release `vi` among `lines` (apiLinesOf): the last that starts at or before it. */
function lineOf(lines, vi) {
  let out = null;
  for (const l of lines) if (l.versions.length && l.versions[0] <= vi) out = l;
  return out;
}

/** The `from` a dep row carries (its 5th element) for the line whose first release with api data is `first`. */
function carriedFrom(row, first) {
  const from = row[4];
  if (Array.isArray(from)) return from.find((x) => x[0] === first)?.[1] ?? null;
  return from ?? null; // a lone classes file: its own line's
}

/**
 * Where a deprecation began, from release `vi` back: over the releases with api data of its own line (`lines`,
 * apiLinesOf), the first from which one of the dep rows `rows` (a class or member, one per jar) is deprecated without a
 * break up to `vi`. When that is the line's first release, the release the rows carry for it (a deprecation that
 * began in an earlier line), else that first release. It never walks into another line, so it is the same whichever
 * lines are loaded. Null when it is not deprecated at `vi`.
 */
export function deprecatedFrom(rows, lines, vi) {
  const line = lineOf(lines, vi);
  if (!line) return null;
  const deprecatedAt = (u) => rows.some((row) => deprecationOf(stateAt(row[3], u)) != null);
  const vs = line.versions;
  let from = null;
  for (let k = vs.length - 1; k >= 0; k--) {
    if (vs[k] > vi) continue;
    if (!deprecatedAt(vs[k])) break;
    from = vs[k];
  }
  if (from == null || from !== vs[0]) return from;
  for (const row of rows) {
    const carried = carriedFrom(row, from);
    if (carried != null) return carried;
  }
  return from;
}

/**
 * The deprecation of a class or member at release `vi`: `{kind, since, from, value}` with `kind` 'deprecated' or
 * 'removal', the declared `since` (null when none), and `from`, the first release of the deprecation (deprecatedFrom
 * over `lines`, apiLinesOf); null when it is not deprecated at `vi`.
 */
export function deprecationAt(rows, lines, vi, member = false) {
  const value = depValueAt(rows, vi);
  const kind = deprecationOf(value);
  if (!kind) return null;
  return { kind, since: parseApiValue(value, member)?.since ?? null, from: deprecatedFrom(rows, lines, vi), value };
}

/**
 * The deprecation of a class or member gone at release `b` (deprecationAt, with `at` the release it describes), the
 * last time it was there seen from `a`: at the last release with api data of [a, b) that has it when `a` is older, at
 * `a` itself when it is newer. Null when it was not deprecated then, or not there.
 */
export function deprecationBeforeRemoval(rows, lines, a, b, member = false) {
  if (a == null || b == null) return null;
  let last = null;
  if (a > b) {
    last = depValueAt(rows, a) != null ? a : null;
  } else if (rows.some((row) => stateAt(row[3], a) != null || row[3].some(([u, v]) => u > a && u < b && v != null))) {
    const vs = lines.flatMap((l) => l.versions);
    for (let k = vs.length - 1; k >= 0 && last == null; k--) {
      if (vs[k] < a) break;
      if (vs[k] < b && depValueAt(rows, vs[k]) != null) last = vs[k];
    }
  }
  const d = last == null ? null : deprecationAt(rows, lines, last, member);
  return d && { ...d, at: last };
}

/**
 * The deprecation events of a class or member over the releases `cvs` (ascending): `{vi, type, since}` with type
 * 'deprecated', 'removal' (marked for removal) or 'undeprecated' (still there, no longer deprecated). A deprecation in
 * the first release is an event with `first` set; a value that goes away is no event here (the class history has it).
 */
export function deprecationEvents(rows, cvs, member = false) {
  const events = [];
  let prev;
  cvs.forEach((vi, k) => {
    const value = depValueAt(rows, vi);
    const kind = deprecationOf(value);
    const before = deprecationOf(prev);
    if (value != null && kind !== before) {
      const since = parseApiValue(value, member)?.since ?? null;
      if (kind) events.push({ vi, type: kind, since, first: k === 0 || prev == null, from: before });
      else if (before) events.push({ vi, type: 'undeprecated', since: null, first: false, from: before });
    }
    prev = value;
  });
  return events;
}

/**
 * Every class and member deprecated at release `vi` (a release with api data), one item per jar:
 * `{classIndex, name, artifact, member, kind, since, from, value}`, sorted by jar, class and member (the class first).
 * `kind`, `since` and `value` are the jar's, `from` the item's over all its jars (deprecatedFrom).
 */
export function deprecationsAt(classes, vi) {
  const lines = apiLinesOf(classes);
  const out = [];
  for (const [classIndex, members] of depIndex(classes)) {
    const name = className(classes, classes.classes[classIndex]);
    for (const [key, rows] of members) {
      let from;
      for (const row of rows) {
        const value = stateAt(row[3], vi);
        const kind = deprecationOf(value);
        if (!kind) continue;
        if (from === undefined) from = deprecatedFrom(rows, lines, vi);
        out.push({
          classIndex, name, artifact: classes.artifacts[row[1]], member: key || null, kind,
          since: parseApiValue(value, key !== '')?.since ?? null, from, value,
        });
      }
    }
  }
  return out.sort((x, y) => cmpText(x.artifact, y.artifact) || cmpText(x.name, y.name) || cmpText(x.member ?? '', y.member ?? ''));
}

const cmpText = (x, y) => (x < y ? -1 : x > y ? 1 : 0);

/**
 * The deprecation changes between releases `a` and `b` (releases with api data, or null for none), for classes and
 * members merged across jars (a class that moved keeps its deprecation): `rows` of
 * `{classIndex, name, member, artifact, change, since, from, value, added}` with change 'deprecated' (newly; `added`
 * when it is new at B), 'removal' (newly marked for removal), 'unremoval' (no longer for removal, still deprecated),
 * 'undeprecated' or 'removed' (there at A, gone at B, deprecated the last time it was there: deprecationBeforeRemoval),
 * and their `counts` for classes and members. `since`, `from` and `value` are those of the deprecation shown: at B, at
 * A for 'undeprecated', the last time it was there for 'removed'.
 */
export function diffDeprecations(classes, a, b) {
  const lines = apiLinesOf(classes);
  const rows = [];
  const kinds = () => ({ deprecated: 0, removal: 0, unremoval: 0, undeprecated: 0, removed: 0 });
  const counts = { classes: kinds(), members: kinds() };
  if (a == null || b == null || a === b) return { rows, counts };
  const jarAt = (group, vi) => group.find((row) => stateAt(row[3], vi) != null);
  for (const [classIndex, members] of depIndex(classes)) {
    for (const [key, group] of members) {
      const member = key || null;
      const va = depValueAt(group, a);
      const vb = depValueAt(group, b);
      const da = deprecationOf(va);
      const db = deprecationOf(vb);
      let change = null;
      let d = null;
      if (vb == null) {
        // there at A, as diffApi has it: an item that came after A and went before B is no change of the pair
        d = va == null ? null : deprecationBeforeRemoval(group, lines, a, b, !!member);
        if (d) change = 'removed';
      } else if (db === 'removal' && da !== 'removal') change = 'removal';
      else if (db && !da) change = 'deprecated';
      else if (da === 'removal' && db === 'deprecated') change = 'unremoval';
      else if (da && !db) change = 'undeprecated';
      if (!change) continue;
      if (change !== 'removed') d = deprecationAt(group, lines, change === 'undeprecated' ? a : b, !!member);
      // the jar at B, or the last time it was there
      const jar = jarAt(group, change === 'removed' ? d.at : b);
      rows.push({
        classIndex,
        name: className(classes, classes.classes[classIndex]),
        member,
        artifact: classes.artifacts[jar[1]],
        change,
        since: d?.since ?? null,
        from: d?.from ?? null,
        value: d?.value ?? null,
        added: change !== 'removed' && change !== 'undeprecated' && va == null,
      });
      counts[member ? 'members' : 'classes'][change]++;
    }
  }
  rows.sort((x, y) => cmpText(x.artifact, y.artifact) || cmpText(x.name, y.name) || cmpText(x.member ?? '', y.member ?? ''));
  return { rows, counts };
}

/** The API revision of a class row at release `vi` (null when it is not API there, undefined without revision data). */
export function apiRevisionAt(row, vi) {
  return Array.isArray(row[3]) ? stateAt(row[3], vi) ?? null : undefined;
}

/**
 * Whether a class row is API in some release of a line, `versions` its releases with api data (ascending): by its
 * revisions, so that its shard in a line where it never is need not be fetched.
 */
export function apiInLine(row, versions) {
  const h = row?.[3];
  if (!Array.isArray(h) || !versions?.length) return false;
  const first = versions[0];
  const last = versions[versions.length - 1];
  return stateAt(h, first) != null || h.some(([vi, rev]) => vi > first && vi <= last && rev != null);
}

/**
 * The classes whose API differs between releases `a` and `b` by their revisions (a class is one row, whatever its
 * jars): the binary names, sorted. Rows without revision data are left out.
 */
export function apiChangedClasses(classes, a, b) {
  const out = [];
  for (const row of classes.classes) {
    if (!Array.isArray(row[3])) continue;
    const ra = stateAt(row[3], a) ?? null;
    const rb = stateAt(row[3], b) ?? null;
    if (ra !== rb) out.push(className(classes, row));
  }
  return out.sort(cmpText);
}

/**
 * A shard file (`{rows}`, change points keyed by version strings) with its versions mapped to release indices through
 * `versionIndex` (version string → index; points of unknown versions are dropped): `{rows: [{artifact, name, h,
 * members: [[key, h]]}], byName}`, `byName` a map from binary name to the rows of that class (one per jar).
 */
export function apiShard(json, versionIndex) {
  const toH = (h) => {
    const out = [];
    for (const [v, value] of Array.isArray(h) ? h : []) {
      const vi = versionIndex.get(v);
      if (vi != null) out.push([vi, value]);
    }
    return out;
  };
  const rows = (Array.isArray(json?.rows) ? json.rows : []).map(([artifact, name, h, members]) => ({
    artifact, name, h: toH(h), members: (Array.isArray(members) ? members : []).map(([key, mh]) => [key, toH(mh)]),
  }));
  const byName = new Map();
  for (const r of rows) {
    if (!byName.has(r.name)) byName.set(r.name, []);
    byName.get(r.name).push(r);
  }
  return { rows, byName };
}

/**
 * The API rows of one class from the shards of several major lines, `parts` = `[{first, rows}]` (the line's first
 * release with api data and the class's rows in its shard), as one history per jar over all of them:
 * `[{artifact, name, h, members: Map(key → h)}]` sorted by jar. Each line's histories start afresh, so what one line
 * ends with gets a null point where the next line starts without it.
 */
export function mergeApiRows(parts) {
  const byJar = new Map();
  const close = (out, first) => {
    if (out.length && out[out.length - 1][1] != null && out[out.length - 1][0] < first) out.push([first, null]);
  };
  const append = (out, h, first) => {
    if (out.length && out[out.length - 1][1] != null && (!h.length || h[0][0] !== first)) out.push([first, null]);
    for (const point of h) if (!out.length || out[out.length - 1][1] !== point[1]) out.push(point);
  };
  for (const { first, rows } of [...(parts ?? [])].sort((x, y) => x.first - y.first)) {
    const seen = new Set();
    for (const r of rows) {
      let m = byJar.get(r.artifact);
      if (!m) byJar.set(r.artifact, (m = { artifact: r.artifact, name: r.name, h: [], members: new Map() }));
      seen.add(r.artifact);
      append(m.h, r.h, first);
      const keys = new Set();
      for (const [key, mh] of r.members) {
        keys.add(key);
        if (!m.members.has(key)) m.members.set(key, []);
        append(m.members.get(key), mh, first);
      }
      for (const [key, out] of m.members) if (!keys.has(key)) close(out, first);
    }
    for (const [jar, m] of byJar) {
      if (seen.has(jar)) continue;
      close(m.h, first);
      for (const out of m.members.values()) close(out, first);
    }
  }
  return [...byJar.values()].sort((x, y) => cmpText(x.artifact, y.artifact));
}

/**
 * The API of a class at release `vi` from its rows (mergeApiRows), across its jars: the first jar with the class
 * there, preferring the jars `prefer` (artifactIds); `{artifact, value, members: Map(key → value)}`, or null when it
 * is not API at `vi`.
 */
export function apiAt(rows, vi, prefer = []) {
  const order = [...rows].sort((x, y) => (prefer.includes(x.artifact) ? 0 : 1) - (prefer.includes(y.artifact) ? 0 : 1));
  for (const r of order) {
    const value = stateAt(r.h, vi);
    if (value == null) continue;
    const members = new Map();
    for (const [key, h] of r.members) {
      const v = stateAt(h, vi);
      if (v != null) members.set(key, v);
    }
    return { artifact: r.artifact, value, members };
  }
  return null;
}

/**
 * apiAt with the members of every jar that has the class at `vi` (a member several have: the first jar's value), as
 * the class page lists them (classMembers).
 */
export function apiUnionAt(rows, vi) {
  const now = apiAt(rows, vi);
  if (!now) return null;
  for (const r of rows) {
    for (const [key, h] of r.members) {
      if (now.members.has(key)) continue;
      const v = stateAt(h, vi);
      if (v != null) now.members.set(key, v);
    }
  }
  return now;
}

/**
 * The supertypes written in a class value, by binary name: its superclass, then its interfaces (for an interface, the
 * interfaces it extends). Empty for null or a value outside the grammar.
 */
export function supertypesOf(value) {
  const c = parseApiValue(value);
  return c ? [...(c.extends ? [c.extends] : []), ...c.implements] : [];
}

/** The class every class and interface gets the public methods of (JLS 4.3.2, 9.2). */
export const OBJECT_CLASS = 'java.lang.Object';

/** The methods of java.lang.Object that a class may declare too, by member key, with their access there. */
const OBJECT_METHODS = new Map([
  ['toString()Ljava/lang/String;', 'public'], ['hashCode()I', 'public'], ['equals(Ljava/lang/Object;)Z', 'public'],
  ['clone()Ljava/lang/Object;', 'protected'], ['finalize()V', 'protected'],
]);

const isInterfaceKind = (kind) => kind === 'interface' || kind === 'annotation';

/** Whether a member is static: written so, an enum constant, or a field of an interface or an annotation (implied). */
function staticMember(parsed, key, ownerKind) {
  return !!parsed && (parsed.static || parsed.enumConstant || (isInterfaceKind(ownerKind) && memberKind(key) === 'field'));
}

/** The signatures (name and parameters) of the methods among member keys, for inheritedFrom's `declared`. */
export function methodSignatures(keys) {
  const out = new Set();
  for (const key of keys) if (memberKind(key) === 'method') out.add(memberSignature(key));
  return out;
}

/**
 * Where a class gets a member at release `vi` that it no longer declares: `{from, value}`, the supertype that declares
 * it and its value there, or null when its callers lost it. The superclass chain comes first, then the interfaces met
 * on the way, breadth first, as Java resolves a method, and the first supertype that is API at `vi` and declares the
 * member decides: it is inherited when that declaration is as visible as the class's was and as static (a static
 * method of the class that a superclass declares as an instance method is gone for its callers). An interface's static
 * method is never inherited, so the search goes on past it, and a constructor never is. Past the supertypes with member
 * data, java.lang.Object gives its public methods, and to a class its protected clone and finalize too (`from`
 * OBJECT_CLASS, `value` null). `key` is the member, `was` its last value in the class, `classValue` the class's value
 * at `vi`; `rowsOf` gives a class's rows (mergeApiRows), or null for one without member data, whose own supertypes stay
 * unknown; `wasKind` is the kind of the class where it declared the member, and `declared` the signatures of the
 * methods it declares at `vi` (methodSignatures): one it still declares with another return type is overridden.
 */
export function inheritedFrom(key, was, classValue, vi, rowsOf, { wasKind = null, declared = null } = {}) {
  const kind = memberKind(key);
  if (kind === 'constructor') return null;
  if (kind === 'method' && declared?.has(memberSignature(key))) return null;
  const old = parseApiValue(was, true);
  const wasPublic = old?.access === 'public';
  const wasStatic = staticMember(old, key, wasKind);
  const seen = new Set();
  // a supertype's say: its verdict when it declares the member (undefined when not, and the search goes on), and its own supertypes
  const look = (name) => {
    if (seen.has(name)) return {};
    seen.add(name);
    const rows = rowsOf(name);
    const at = rows ? apiUnionAt(rows, vi) : null;
    if (!at) return {};
    const c = parseApiValue(at.value);
    const value = at.members.get(key);
    const m = value == null ? null : parseApiValue(value, true);
    let verdict;
    if (m && !(kind === 'method' && m.static && isInterfaceKind(c?.kind))) {
      verdict = (wasPublic && m.access !== 'public') || staticMember(m, key, c?.kind) !== wasStatic ? null : { from: name, value };
    }
    return { verdict, superclass: c?.extends ?? null, interfaces: c?.implements ?? [] };
  };
  const top = parseApiValue(classValue);
  const interfaces = [...(top?.implements ?? [])];
  for (let name = top?.extends ?? null, hops = 0; name && hops < 64; hops++) {
    const r = look(name);
    if (r.verdict !== undefined) return r.verdict;
    interfaces.push(...(r.interfaces ?? []));
    name = r.superclass;
  }
  for (let i = 0; i < interfaces.length && i < 256; i++) {
    const r = look(interfaces[i]);
    if (r.verdict !== undefined) return r.verdict;
    interfaces.push(...(r.interfaces ?? []));
  }
  const objectAccess = OBJECT_METHODS.get(key);
  if (objectAccess && !wasStatic && (objectAccess === 'public' || !isInterfaceKind(top?.kind)) && !(wasPublic && objectAccess !== 'public')) {
    return { from: OBJECT_CLASS, value: null };
  }
  return null;
}

/** Whether a class declares a member at release `vi`, from its rows (mergeApiRows; null for none); java.lang.Object its methods always. */
function declaresAt(rows, key, vi, name = null) {
  if (name === OBJECT_CLASS) return true;
  return !!rows && apiUnionAt(rows, vi)?.members.has(key) === true;
}

const MODIFIER_FLAGS = [
  ['abstract', 'abstract'], ['final', 'final'], ['static', 'static'], ['default', 'default'], ['enumConstant', 'an enum constant'],
  ['experimental', 'experimental'],
];
const KIND_MODIFIERS = new Set(['abstract', 'final', 'static']);

/**
 * The modifier changes of a class or member between two parsed values, in words: "became final", "no longer static",
 * "public → protected", "class → record", 'since = "3.1" → "3.2" in @Deprecated' (of a deprecation kept). A class whose kind changed has no
 * abstract, final or static changes: the grammar writes them for classes of no kind only, the others imply theirs.
 */
export function modifierChanges(from, to) {
  if (!from || !to) return [];
  const out = [];
  if (from.access !== to.access) out.push(`${from.access} → ${to.access}`);
  const kindChanged = !!from.kind && !!to.kind && from.kind !== to.kind;
  if (kindChanged) out.push(`${from.kind} → ${to.kind}`);
  for (const [flag, word] of MODIFIER_FLAGS) {
    if (from[flag] === undefined || to[flag] === undefined || from[flag] === to[flag]) continue;
    if (kindChanged && KIND_MODIFIERS.has(flag)) continue;
    out.push(to[flag] ? `became ${word}` : `no longer ${word}`);
  }
  if (from.varargs !== undefined && from.varargs !== to.varargs) out.push(to.varargs ? 'became varargs' : 'no longer varargs');
  if (from.deprecated && to.deprecated && from.since !== to.since) {
    out.push(from.since == null ? `since = "${to.since}" added to @Deprecated`
      : to.since == null ? `since = "${from.since}" dropped from @Deprecated` : `since = "${from.since}" → "${to.since}" in @Deprecated`);
  }
  return out;
}

/**
 * A parsed member value with the modifiers that its class's kind implies, which the grammar leaves out: the fields of
 * an interface (or annotation) are static and final, its methods without a body abstract; an enum constant is static
 * and final.
 */
function impliedMember(parsed, key, classKind) {
  if (!parsed) return parsed;
  const out = { ...parsed };
  if (parsed.enumConstant) out.static = out.final = true;
  if (classKind === 'interface' || classKind === 'annotation') {
    const kind = memberKind(key);
    if (kind === 'field') out.static = out.final = true;
    else if (kind === 'method' && !parsed.default && !parsed.static) out.abstract = true;
  }
  return out;
}

/** The modifier changes of a member (modifierChanges) between its values in a class of kind `kindA` and of kind `kindB`. */
function memberModifierChanges(key, va, vb, kindA, kindB) {
  return modifierChanges(impliedMember(parseApiValue(va, true), key, kindA), impliedMember(parseApiValue(vb, true), key, kindB));
}

/**
 * A parsed class value with the `static` that the grammar leaves out for a class declared in an interface or an
 * annotation (`enclosingKind`, its enclosing class's kind there), which is implicitly static (JLS 9.5).
 */
function impliedClass(parsed, enclosingKind) {
  if (parsed?.kind !== 'class' || (enclosingKind !== 'interface' && enclosingKind !== 'annotation')) return parsed;
  return { ...parsed, static: true };
}

/** The kind of a class at release `vi` from its rows (mergeApiRows), or null when it is not API there (or no rows). */
function kindAt(rows, vi) {
  return rows ? parseApiValue(apiAt(rows, vi)?.value)?.kind ?? null : null;
}

/**
 * The deprecation change between two values: 'deprecated', 'removal' (newly marked for removal), 'unremoval' (no
 * longer for removal, still deprecated), 'undeprecated', or null.
 */
function deprecationChange(from, to) {
  const a = deprecationOf(from);
  const b = deprecationOf(to);
  if (b === 'removal' && a !== 'removal') return 'removal';
  if (b && !a) return 'deprecated';
  if (a === 'removal' && b === 'deprecated') return 'unremoval';
  if (a && !b) return 'undeprecated';
  return null;
}

/** API change kinds in their order on the compare page. */
export const API_CHANGE_ORDER = [
  'removed', 'inherited', 'moved', 'removal', 'deprecated', 'unremoval', 'undeprecated', 'return type', 'signature', 'modifiers', 'supertype',
  'declared', 'added',
];

/** API change rows (diffApi, movedApiClasses) in their order on the compare page: by jar, class, then kind and member. */
export function sortApiRows(rows) {
  const order = (c) => API_CHANGE_ORDER.indexOf(c);
  return rows.sort((x, y) => cmpText(x.artifact, y.artifact) || cmpText(x.name, y.name) || (x.member == null ? -1 : 0) - (y.member == null ? -1 : 0)
    || order(x.change) - order(y.change) || cmpText(x.member ?? '', y.member ?? ''));
}

/**
 * The value that a class or member last had before release `b` seen from `a`, from its histories `hs` (one per jar):
 * the last one of [a, b) when `a` is older, the one at `a` when it is newer; null when it was not there.
 */
function lastValueBefore(hs, a, b) {
  const at = (u) => {
    for (const h of hs) {
      const v = stateAt(h, u);
      if (v != null) return v;
    }
    return null;
  };
  if (a > b) return at(a);
  const points = new Set([a]);
  for (const h of hs) for (const [u] of h) if (u > a && u < b) points.add(u);
  for (const u of [...points].sort((x, y) => y - x)) {
    const v = at(u);
    if (v != null) return v;
  }
  return null;
}

/**
 * The API changes of the classes `rowsByName` (binary name → mergeApiRows) between releases `a` and `b`, merged across
 * jars (a class that moved jars is compared with itself). One row per change: `{name, member, artifact, change,
 * details, nested, protected, constructor, value, text}` with change 'added' or 'removed' (a whole class is one row,
 * member null), 'deprecated', 'removal', 'unremoval', 'undeprecated', 'modifiers' (details say which), 'supertype'
 * (classes), 'return type' (a method kept its name and parameters; `member` is the new key, `previous` the old one,
 * `previousValue` its value) or 'signature' (a possible signature change: a method that went and one of the same name
 * that came, kept as one row) or 'inherited' (a member the class no longer declares but inherits at B, inheritedFrom:
 * `from` names the supertype, `inheritedValue` is the member's value there (null for java.lang.Object), `moved` says
 * whether the supertype did not declare the member at A). A member row's `value` is its
 * value at B, or the last one it had for 'removed' and 'inherited'; a removed class or member says in `was` how it was
 * deprecated the last time it was there (deprecationBeforeRemoval's rule). `prefer` gives the jars of each class at A
 * and B (artifactIds), to pick among several; `enclosing` the rows of the class a nested class is declared in, whose
 * kind says whether it is implicitly static (impliedClass); `supertypes` the rows of a class by binary name, or null,
 * where inheritedFrom looks for a member a class no longer declares.
 */
export function diffApi(rowsByName, a, b, prefer = () => [[], []], enclosing = () => null, supertypes = () => null) {
  const out = [];
  for (const [name, rows] of rowsByName) {
    const [preferA, preferB] = prefer(name);
    const A = apiAt(rows, a, preferA);
    const B = apiAt(rows, b, preferB);
    if (!A && !B) continue;
    const nested = isNestedClass(name);
    const push = (row) => out.push({
      name, nested, member: null, details: [], previous: null, constructor: false, ...row,
      text: [name, row.member ?? '', row.artifact].join('\n').toLowerCase(),
    });
    if (!A || !B) {
      const value = (B ?? A).value;
      const was = A ? deprecationOf(lastValueBefore(rows.map((r) => r.h), a, b)) : null;
      push({ artifact: (B ?? A).artifact, change: B ? 'added' : 'removed', protected: value.startsWith('#'), details: [], was });
      continue;
    }
    const artifact = B.artifact;
    const outer = enclosing(name);
    const ca = impliedClass(parseApiValue(A.value), kindAt(outer, a));
    const cb = impliedClass(parseApiValue(B.value), kindAt(outer, b));
    const classProtected = B.value.startsWith('#');
    const dep = deprecationChange(A.value, B.value);
    if (dep) push({ artifact, change: dep, protected: classProtected, since: cb?.since ?? null });
    const mods = modifierChanges(ca, cb);
    if (mods.length) push({ artifact, change: 'modifiers', protected: classProtected, details: mods });
    if (ca && cb && (ca.extends !== cb.extends || ca.implements.join(',') !== cb.implements.join(','))) {
      push({ artifact, change: 'supertype', protected: classProtected, details: supertypeChanges(ca, cb) });
    }
    const gone = [...A.members.keys()].filter((k) => !B.members.has(k));
    const came = [...B.members.keys()].filter((k) => !A.members.has(k));
    const memberRow = (key, change, value, extra = {}) => push({
      artifact, member: key, change, protected: String(value).startsWith('#'), constructor: memberKind(key) === 'constructor', value, ...extra,
    });
    const lastOf = (key) => lastValueBefore(rows.map((r) => r.members.get(key)).filter(Boolean), a, b) ?? A.members.get(key);
    // a member that a supertype declares now is still there, inherited: neither removed nor half of a signature change
    const declared = methodSignatures(B.members.keys());
    const inherited = new Map();
    for (const key of gone) {
      const got = inheritedFrom(key, A.members.get(key), B.value, b, supertypes, { wasKind: ca?.kind, declared });
      if (got) inherited.set(key, got);
    }
    // the other way round, a member the class declares at B that it inherited at A (an override added, or a member
    // that moved down from a supertype) was there already: it is declared now, not added
    const declaredAtA = methodSignatures(A.members.keys());
    const nowDeclared = new Map();
    for (const key of came) {
      const got = inheritedFrom(key, B.members.get(key), A.value, a, supertypes, { wasKind: cb?.kind, declared: declaredAtA });
      if (got) nowDeclared.set(key, got);
    }
    // a method that kept its name and parameters changed its return type; another of the same name may be a new signature
    const cameBySignature = groupBy(came.filter((k) => memberKind(k) !== 'field'), memberSignature);
    const paired = new Set([...inherited.keys(), ...nowDeclared.keys()]);
    for (const key of gone) {
      if (memberKind(key) === 'field' || paired.has(key)) continue;
      const twin = (cameBySignature.get(memberSignature(key)) ?? []).find((k) => !paired.has(k));
      if (twin) {
        paired.add(twin);
        paired.add(key);
        memberRow(twin, 'return type', B.members.get(twin), { previous: key, previousValue: A.members.get(key) });
      }
    }
    const cameByName = groupBy(came.filter((k) => !paired.has(k) && memberKind(k) === 'method'), (k) => k.slice(0, k.indexOf('(')));
    for (const key of gone) {
      if (paired.has(key) || memberKind(key) !== 'method') continue;
      const twin = (cameByName.get(key.slice(0, key.indexOf('('))) ?? []).find((k) => !paired.has(k));
      if (twin) {
        paired.add(twin);
        paired.add(key);
        memberRow(twin, 'signature', B.members.get(twin), { previous: key, previousValue: A.members.get(key) });
      }
    }
    for (const [key, got] of inherited) {
      memberRow(key, 'inherited', lastOf(key), { from: got.from, inheritedValue: got.value, moved: !declaresAt(supertypes(got.from), key, a, got.from) });
    }
    for (const key of gone) {
      if (paired.has(key)) continue;
      const last = lastOf(key);
      memberRow(key, 'removed', last, { was: deprecationOf(last) });
    }
    for (const [key, got] of nowDeclared) memberRow(key, 'declared', B.members.get(key), { from: got.from, inheritedValue: got.value });
    for (const key of came) if (!paired.has(key)) memberRow(key, 'added', B.members.get(key));
    for (const [key, va] of A.members) {
      const vb = B.members.get(key);
      if (vb == null || vb === va) continue;
      const d = deprecationChange(va, vb);
      if (d) memberRow(key, d, vb, { since: parseApiValue(vb, true)?.since ?? null });
      const m = memberModifierChanges(key, va, vb, ca?.kind, cb?.kind);
      if (m.length) memberRow(key, 'modifiers', vb, { details: m });
    }
  }
  return sortApiRows(out);
}

/** "extends Foo → Bar", "implements +Baz −Qux" for the supertype change of a class. */
function supertypeChanges(from, to) {
  const out = [];
  if (from.extends !== to.extends) out.push(`extends ${from.extends ?? 'nothing'} → ${to.extends ?? 'nothing'}`);
  const gained = to.implements.filter((x) => !from.implements.includes(x));
  const lost = from.implements.filter((x) => !to.implements.includes(x));
  if (gained.length || lost.length) out.push(`implements ${[...gained.map((x) => `+${x}`), ...lost.map((x) => `−${x}`)].join(' ')}`);
  else if (from.implements.join(',') !== to.implements.join(',')) out.push('implements the same interfaces in another order');
  return out;
}

/**
 * The API history of a class over the releases `cvs` (ascending) from its rows (mergeApiRows): one event per release
 * whose API differs from the previous one shown, `{vi, added, removed, inherited, declared, changed, changes}`: members
 * added, removed and changed in any of its jars (apiUnionAt, as classMembers lists them; each change as diffApi sees
 * it), the members it no longer declares but inherits (`inherited`: `[{from, count, moved}]` by supertype, most first,
 * `moved` when none of them was in that supertype before; not counted as removed), those it declares now but
 * inherited before (`declared`: `[{from, count}]`; not counted as added), and the class's own changes in words
 * (deprecated, marked for removal, no longer marked for removal, undeprecated, became final, supertype changed, …). The
 * first release with API is `{vi, first: true}`, with `again` when it was API before; a release where it is no longer
 * API (gone, or no longer public or protected) says 'no longer public API'. `enclosing`: the rows of the class a nested
 * class is declared in (impliedClass); `supertypes`: the rows of a class by binary name, or null (inheritedFrom). A
 * release whose values differ only in what is implicit has no event.
 */
export function classApiHistory(rows, cvs, enclosing = null, supertypes = () => null) {
  const events = [];
  let prev = null;
  let prevVi = null;
  let seen = false;
  const DEPRECATION_WORDS = { removal: 'marked for removal', unremoval: 'no longer marked for removal' };
  for (const vi of cvs) {
    const now = apiUnionAt(rows, vi);
    if (!now) {
      if (prev) events.push({ vi, first: false, added: 0, removed: prev.members.size, inherited: [], declared: [], changed: 0, changes: ['no longer public API'] });
      prev = null;
      continue;
    }
    if (!prev) {
      events.push({ vi, first: true, ...(seen ? { again: true } : {}), added: now.members.size, removed: 0, inherited: [], declared: [], changes: [] });
    } else if (prev.value !== now.value || !sameMembers(prev.members, now.members)) {
      const came = [...now.members.keys()].filter((k) => !prev.members.has(k));
      const gone = [...prev.members.keys()].filter((k) => !now.members.has(k));
      // a member declared now that the class inherited at the previous release: no new API
      const declaredBefore = methodSignatures(prev.members.keys());
      const nowKind = parseApiValue(now.value)?.kind ?? null;
      const byOrigin = new Map();
      for (const key of came) {
        const got = inheritedFrom(key, now.members.get(key), prev.value, prevVi, supertypes, { wasKind: nowKind, declared: declaredBefore });
        if (got) byOrigin.set(got.from, (byOrigin.get(got.from) ?? 0) + 1);
      }
      const declaredNow = [...byOrigin].map(([from, count]) => ({ from, count })).sort((x, y) => y.count - x.count || cmpText(x.from, y.from));
      const added = came.length - declaredNow.reduce((n, x) => n + x.count, 0);
      const bySupertype = new Map();
      const declared = methodSignatures(now.members.keys());
      const wasKind = parseApiValue(prev.value)?.kind ?? null;
      for (const key of gone) {
        const got = inheritedFrom(key, prev.members.get(key), now.value, vi, supertypes, { wasKind, declared });
        if (!got) continue;
        const x = bySupertype.get(got.from) ?? { from: got.from, count: 0, moved: true };
        x.count++;
        x.moved &&= !declaresAt(supertypes(got.from), key, prevVi, got.from);
        bySupertype.set(got.from, x);
      }
      const inherited = [...bySupertype.values()].sort((x, y) => y.count - x.count || cmpText(x.from, y.from));
      const removed = gone.length - inherited.reduce((n, x) => n + x.count, 0);
      const changes = [];
      const dep = deprecationChange(prev.value, now.value);
      if (dep) changes.push(DEPRECATION_WORDS[dep] ?? dep);
      const ca = impliedClass(parseApiValue(prev.value), kindAt(enclosing, prevVi));
      const cb = impliedClass(parseApiValue(now.value), kindAt(enclosing, vi));
      changes.push(...modifierChanges(ca, cb));
      if (ca && cb && (ca.extends !== cb.extends || ca.implements.join(',') !== cb.implements.join(','))) changes.push('supertype changed');
      let memberChanges = 0;
      for (const [key, v] of prev.members) {
        const w = now.members.get(key);
        if (w == null || w === v) continue;
        if (deprecationChange(v, w) || memberModifierChanges(key, v, w, ca?.kind, cb?.kind).length) memberChanges++;
      }
      if (added || removed || inherited.length || declaredNow.length || memberChanges || changes.length) {
        events.push({ vi, first: false, added, removed, inherited, declared: declaredNow, changed: memberChanges, changes });
      }
    }
    seen = true;
    prev = now;
    prevVi = vi;
  }
  return events;
}

const sameMembers = (x, y) => x.size === y.size && [...x].every(([k, v]) => y.get(k) === v);

/**
 * The members of a class at release `vi` and how they got there over the releases `cvs` (ascending, `vi` the last
 * one shown): `members`, `{key, value, parsed, pretty, since}` for each member at `vi` in any of its jars (the first
 * jar's value), `since` the first release of `cvs` from which it is there without a break; `removed`, `{key, value,
 * parsed, pretty, last}` for each member that some release of `cvs` had and no jar has at `vi`, `last` the last
 * release that had it (its value there); and `inherited`, the same with `from`, for those of them that the class
 * inherits at `vi` (inheritedFrom, with `supertypes` the rows of a class by binary name, or null), which `removed`
 * leaves out. `value` and `artifact` are the class's at `vi` (apiAt).
 */
export function classMembers(rows, cvs, vi, className = '', supertypes = () => null) {
  const now = apiAt(rows, vi);
  const members = [];
  const removed = [];
  const inherited = [];
  const histories = new Map(); // member key → its histories, one per jar
  for (const r of rows) {
    for (const [key, h] of r.members) {
      if (!histories.has(key)) histories.set(key, []);
      histories.get(key).push(h);
    }
  }
  const valueAt = (key, u) => {
    for (const h of histories.get(key) ?? []) {
      const v = stateAt(h, u);
      if (v != null) return v;
    }
    return null;
  };
  const shown = cvs.filter((u) => u <= vi);
  const pretty = (key, value) => prettyMember(key, { className, varargs: !!parseApiValue(value, true)?.varargs });
  for (const key of now ? histories.keys() : []) {
    const value = valueAt(key, vi);
    if (value == null) continue;
    let since = vi;
    for (let k = shown.length - 1; k >= 0; k--) {
      if (valueAt(key, shown[k]) == null) break;
      since = shown[k];
    }
    members.push({ key, value, parsed: parseApiValue(value, true), pretty: pretty(key, value), since });
  }
  const declared = now ? methodSignatures([...histories.keys()].filter((key) => valueAt(key, vi) != null)) : null;
  for (const key of histories.keys()) {
    if (valueAt(key, vi) != null) continue;
    let last = null;
    for (const u of shown) if (valueAt(key, u) != null) last = u;
    if (last == null) continue;
    const value = valueAt(key, last);
    const x = { key, value, parsed: parseApiValue(value, true), pretty: pretty(key, value), last };
    const wasKind = parseApiValue(apiAt(rows, last)?.value)?.kind ?? null;
    const got = now ? inheritedFrom(key, value, now.value, vi, supertypes, { wasKind, declared }) : null;
    if (got) inherited.push({ ...x, from: got.from, inheritedValue: got.value });
    else removed.push(x);
  }
  const byKey = (x, y) => cmpText(x.key, y.key);
  return {
    members: members.sort(byKey), removed: removed.sort(byKey), inherited: inherited.sort(byKey), value: now?.value ?? null, artifact: now?.artifact ?? null,
  };
}

/**
 * Whether a member is one the compiler writes for an enum or a record, not its author: an enum's `values()` and
 * `valueOf(String)`, a record's `toString()`, `hashCode()` and `equals(Object)`.
 */
export function implicitMember(key, classKind, name = '') {
  const self = `L${String(name).replace(/\./g, '/')};`;
  if (classKind === 'enum') return key === `values()[${self}` || key === `valueOf(Ljava/lang/String;)${self}`;
  if (classKind === 'record') return key === 'toString()Ljava/lang/String;' || key === 'hashCode()I' || key === 'equals(Ljava/lang/Object;)Z';
  return false;
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
    const avs = classes.api?.versions ?? [];
    const lastApi = avs.length ? avs[avs.length - 1] : null;
    classes.classes.forEach((row, classIndex) => {
      const name = className(classes, row);
      if (isAnonymousClass(name)) return;
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
        // deprecated or marked for removal in the newest release with api data
        deprecation: lastApi == null || !now.length ? null : deprecationOf(depValueAt(depRowsOf(classes, classIndex), lastApi)),
      });
    });
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
