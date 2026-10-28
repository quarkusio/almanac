// Unit tests for site/assets/core.js. Run with: node --test site/tests
import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import * as core from '../assets/core.js';

const {
  parseVersion, compareVersions, sortVersions, isFinal, stream, shortVersion, findVersionIndex,
  parseLibVersion, compareLibVersions, bomChangeKind, splitId, canonicalKey, envVar, lastSegment,
  stateAt, latestValue, configVersionsOf, configVersionFor, phaseInfo, typeLabel, configChangeKinds, enumDelta,
  diffConfig, configRows, tagCounts, filterRows, groupBy, resolveBomValue, diffBom, groupBomRows,
  HEADLINE_LIBRARIES, headlineLibraries, extensionChangeKinds, diffExtensions, matchTier, buildSearchIndex, search,
  propertyHistory, extensionHistory, artifactHistory, newestFinalIndex, newestInStream, ltsStreamsPresent,
  defaultPair, quickPicks, versionNeighbours, formatDate, spanLabel, CONFIG_FILTER_TAGS, BOM_KIND_ORDER,
  isPreRelease, preQualifier, releaseVisibility, visibleOnly, streamStartIndex, streamSpan, releasedApart, spanApart,
  extensionVersionsOf, extensionVersionFor, snapshotVersionFor,
  classVersionsOf, classesVersionFor, className, findClass, jarsOf, simpleClassName, classPackage, isNestedClass,
  isAnonymousClass, topLevelClass, diffClasses, classRows, CLASS_FILTER_TAGS, classHistory, majorOf, classLinesOf,
  mergeClasses,
} = core;

const SAMPLE = join(dirname(fileURLToPath(import.meta.url)), '..', 'dev', 'sample');
const load = (name) => JSON.parse(readFileSync(join(SAMPLE, `${name}.json`), 'utf8'));

/** Asserts that every version in the list is strictly smaller than the next one. */
function assertAscending(list) {
  for (let i = 1; i < list.length; i++) {
    assert.equal(compareVersions(list[i - 1], list[i]), -1, `${list[i - 1]} < ${list[i]}`);
    assert.equal(compareVersions(list[i], list[i - 1]), 1, `${list[i]} > ${list[i - 1]}`);
  }
}

const V = (...vs) => vs.map((v) => ({ v, stream: stream(v), pre: !isFinal(v), lts: false, config: false }));

/* -------------------------------------------------------------------------- */
describe('Quarkus versions', () => {
  test('parseVersion splits numeric parts, qualifier and stream', () => {
    const p = parseVersion('3.0.0.CR1');
    assert.deepEqual(p.nums, [3, 0, 0]);
    assert.equal(p.qualifier, 'CR');
    assert.equal(p.qnum, 1);
    assert.equal(p.final, false);
    assert.equal(p.stream, '3.0');
    assert.deepEqual(parseVersion('3.8.6.1').nums, [3, 8, 6, 1]);
    assert.equal(parseVersion('3.8.6.1').final, true);
    assert.equal(parseVersion('3.2.12.Final').qualifier, 'Final');
    assert.equal(parseVersion('3.2.12.Final').final, true);
    assert.equal(parseVersion('4.0.0.Beta1').stream, '4.0');
    assert.equal(parseVersion('3.0.0-alpha2').qualifier, 'Alpha');
    assert.equal(parseVersion('3.0.0-alpha2').qnum, 2);
    assert.equal(parseVersion(' 3.20.6 ').raw, '3.20.6');
  });

  test('required orderings', () => {
    assertAscending(['3.0.0.Alpha1', '3.0.0.CR1', '3.0.0.Final']);
    assert.equal(compareVersions('3.0.0.Final', '3.0.0'), 0);
    assert.equal(compareVersions('3.0.0', '3.0.0.Final'), 0);
    assertAscending(['3.8.6', '3.8.6.1', '3.9.0.CR1', '3.9.0']);
    assertAscending(['3.40.1', '3.40.1.1', '4.0.0.Beta1', '4.0.0']);
  });

  test('qualifier rank and number, separators, missing parts', () => {
    assertAscending(['3.0.0.Alpha1', '3.0.0.Alpha2', '3.0.0.Beta1', '3.0.0.Beta2', '3.0.0.CR1', '3.0.0.CR2', '3.0.0']);
    assert.equal(compareVersions('3.0.0-CR1', '3.0.0.CR1'), 0);
    assert.equal(compareVersions('3.8', '3.8.0'), 0);
    assertAscending(['3.2.12.Final', '3.8.1', '3.9.0', '3.10.0', '3.15.1', '3.20.0', '3.100.0']);
    assert.equal(compareVersions('3.0.0.CR', '3.0.0.CR0'), 0);
  });

  test('unknown qualifiers sort before Alpha, then by text', () => {
    assertAscending(['3.0.0.SNAPSHOT', '3.0.0.Alpha1']);
    assert.equal(parseVersion('999-SNAPSHOT').final, false);
    assert.equal(compareVersions('1.0.0.Xyz', '1.0.0.Abc'), 1);
    assert.equal(parseVersion('not-a-version').nums.length, 0);
  });

  test('compareVersions accepts parsed objects', () => {
    assert.equal(compareVersions(parseVersion('3.1.0'), parseVersion('3.0.9')), 1);
  });

  test('sortVersions, isFinal, stream, shortVersion', () => {
    assert.deepEqual(sortVersions(['4.0.0.Beta1', '3.8.6.1', '3.8.6', '3.40.1']), ['3.8.6', '3.8.6.1', '3.40.1', '4.0.0.Beta1']);
    assert.equal(isFinal('3.0.0'), true);
    assert.equal(isFinal('3.0.0.Final'), true);
    assert.equal(isFinal('4.0.0.Beta1'), false);
    assert.equal(stream('3.20.6'), '3.20');
    assert.equal(stream('3'), '3.0');
    assert.equal(shortVersion('3.2.12.Final'), '3.2.12');
    assert.equal(shortVersion('4.0.0.Beta1'), '4.0.0.Beta1');
    assert.equal(shortVersion('3.40.1.1'), '3.40.1.1');
  });

  test('findVersionIndex: exact, then version equality', () => {
    const list = V('3.0.0.Final', '3.8.6', '3.8.6.1', '4.0.0.Beta1');
    assert.equal(findVersionIndex(list, '3.0.0.Final'), 0);
    assert.equal(findVersionIndex(list, '3.0.0'), 0);
    assert.equal(findVersionIndex(list, '3.8.6'), 1);
    assert.equal(findVersionIndex(list, '3.8.6.1'), 2);
    assert.equal(findVersionIndex(['3.1.0', '3.2.0'], '3.2.0'), 1);
    assert.equal(findVersionIndex(list, '3.9.0'), -1);
    assert.equal(findVersionIndex(list, ''), -1);
    assert.equal(findVersionIndex(list, 'latest'), -1);
  });
});

/* -------------------------------------------------------------------------- */
describe('library versions and BOM change kinds', () => {
  test('parseLibVersion: leading numeric parts and qualifier', () => {
    assert.deepEqual(parseLibVersion('4.1.115.Final').nums, [4, 1, 115]);
    assert.equal(parseLibVersion('4.1.115.Final').qualifier, 'Final');
    assert.deepEqual(parseLibVersion('2.20').nums, [2, 20]);
    assert.equal(parseLibVersion('5.0.0-RC1').qnum, 1);
    assert.deepEqual(parseLibVersion('v1').nums, []);
  });

  test('compareLibVersions uses Maven-like qualifier order', () => {
    const asc = ['1.0.0-alpha1', '1.0.0-beta2', '1.0.0-M1', '1.0.0-RC1', '1.0.0-SNAPSHOT', '1.0.0', '1.0.0.SP1', '1.0.0-redhat-00001'];
    for (let i = 1; i < asc.length; i++) assert.equal(compareLibVersions(asc[i - 1], asc[i]), -1, `${asc[i - 1]} < ${asc[i]}`);
    assert.equal(compareLibVersions('1.0.0.Final', '1.0.0'), 0);
    assert.equal(compareLibVersions('2.20', '2.20.0'), 0);
    assert.equal(compareLibVersions('4.1.9.Final', '4.1.10.Final'), -1);
    assert.equal(compareLibVersions('32.1.2-android', '32.1.2-jre'), -1);
  });

  test('bomChangeKind', () => {
    assert.equal(bomChangeKind('4.5.22', '5.0.4'), 'major');
    assert.equal(bomChangeKind('4.1.127.Final', '4.2.6.Final'), 'minor');
    assert.equal(bomChangeKind('4.4.1', '4.4.4'), 'patch');
    assert.equal(bomChangeKind('3.8.6', '3.8.6.1'), 'patch');
    assert.equal(bomChangeKind('2.1', '2.5'), 'minor');
    assert.equal(bomChangeKind('2.0.0.Alpha5', '2.0.0.Beta1'), 'other');
    assert.equal(bomChangeKind('2.0.0-RC1', '2.0.0'), 'other');
    assert.equal(bomChangeKind('2.0.0', '2.0.0-RC1'), 'downgrade');
    assert.equal(bomChangeKind('5.0.1', '4.33.0'), 'downgrade');
    assert.equal(bomChangeKind('4.1.112.Final', '4.1.108.Final'), 'downgrade');
    assert.equal(bomChangeKind('15.2.5.Final', '16.0.1'), 'major');
    assert.equal(bomChangeKind('2.20.0', '2.20'), 'other');
    assert.equal(bomChangeKind('1.0', '1.0'), 'same');
  });

  test('splitId', () => {
    assert.deepEqual(splitId('io.vertx:vertx-core'), { group: 'io.vertx', artifact: 'vertx-core', extra: '' });
    assert.deepEqual(splitId('io.quarkus:quarkus-bom-quarkus-platform-descriptor:json:${quarkus.version}'),
      { group: 'io.quarkus', artifact: 'quarkus-bom-quarkus-platform-descriptor', extra: 'json:${quarkus.version}' });
  });
});

/* -------------------------------------------------------------------------- */
describe('config keys', () => {
  test('canonicalKey replaces quoted segments and [digits] only', () => {
    assert.equal(canonicalKey('quarkus.log.category."categories".level'), 'quarkus.log.category.*.level');
    assert.equal(canonicalKey('quarkus.log.category."io.quarkus.arc".level'), 'quarkus.log.category.*.level');
    assert.equal(canonicalKey('quarkus.a."x".b."y".c'), 'quarkus.a.*.b.*.c');
    assert.equal(canonicalKey('quarkus.foo.bar[0].baz[12]'), 'quarkus.foo.bar[*].baz[*]');
    assert.equal(canonicalKey('quarkus.foo.bar[*].baz'), 'quarkus.foo.bar[*].baz');
    assert.equal(canonicalKey('quarkus.http.port'), 'quarkus.http.port');
  });

  test('envVar upper-cases and replaces non-alphanumerics', () => {
    assert.equal(envVar('quarkus.hibernate-orm."pu".active'), 'QUARKUS_HIBERNATE_ORM__PU__ACTIVE');
    assert.equal(envVar('quarkus.http.port'), 'QUARKUS_HTTP_PORT');
    assert.equal(envVar('quarkus.foo[0].bar'), 'QUARKUS_FOO_0__BAR');
  });

  test('lastSegment keeps a trailing map key with its parent', () => {
    assert.equal(lastSegment('quarkus.package.jar.add-runner-suffix'), 'add-runner-suffix');
    assert.equal(lastSegment('quarkus.datasource.devservices.container-env.*'), 'container-env.*');
    assert.equal(lastSegment('quarkus.foo.bar[*]'), 'bar[*]');
    assert.equal(lastSegment('single'), 'single');
  });
});

/* -------------------------------------------------------------------------- */
describe('histories', () => {
  const h = [[3, 0], [12, 5], [40, null]];
  test('stateAt returns the last point ≤ vi', () => {
    assert.equal(stateAt(h, 0), undefined);
    assert.equal(stateAt(h, 3), 0);
    assert.equal(stateAt(h, 11), 0);
    assert.equal(stateAt(h, 12), 5);
    assert.equal(stateAt(h, 39), 5);
    assert.equal(stateAt(h, 40), null);
    assert.equal(stateAt(h, 1000), null);
    assert.equal(stateAt([], 3), undefined);
    assert.equal(stateAt(undefined, 3), undefined);
    assert.equal(stateAt([[0, '=']], 7), '=');
  });

  test('latestValue skips trailing nulls', () => {
    assert.equal(latestValue(h), 5);
    assert.equal(latestValue([[1, null]]), null);
    assert.equal(latestValue([[0, 'a'], [3, 'b']]), 'b');
  });
});

/* -------------------------------------------------------------------------- */
describe('config version for a release', () => {
  const versions = [
    { v: '3.15.1', stream: '3.15' }, // 0
    { v: '3.15.7', stream: '3.15', config: true }, // 1
    { v: '3.16.0', stream: '3.16' }, // 2
    { v: '3.20.0', stream: '3.20' }, // 3
    { v: '3.20.6', stream: '3.20', config: true }, // 4
    { v: '3.21.0', stream: '3.21' }, // 5
  ];
  test('uses the snapshot of the same stream, even when it is a later patch', () => {
    assert.equal(configVersionFor(0, versions), 1);
    assert.equal(configVersionFor(1, versions), 1);
    assert.equal(configVersionFor(3, versions), 4);
  });
  test('otherwise the latest config version ≤ vi', () => {
    assert.equal(configVersionFor(2, versions), 1);
    assert.equal(configVersionFor(5, versions), 4);
  });
  test('otherwise none', () => {
    const early = [{ v: '3.0.0', stream: '3.0' }, { v: '3.1.0', stream: '3.1', config: true }];
    assert.equal(configVersionFor(0, early), null);
    assert.equal(configVersionFor(9, early), null);
    assert.equal(configVersionFor(0, []), null);
  });
  test('several snapshots in one stream: greatest ≤ vi, else the nearest after', () => {
    const vs = [{ v: '3.15.1' }, { v: '3.15.2' }, { v: '3.15.5' }, { v: '3.15.7' }];
    assert.equal(configVersionFor(2, vs, [1, 3]), 1);
    assert.equal(configVersionFor(0, vs, [1, 3]), 1);
    assert.equal(configVersionFor(3, vs, [1, 3]), 3);
  });
  test('configVersionsOf reads the flags', () => {
    assert.deepEqual(configVersionsOf(versions), [1, 4]);
  });
});

/* -------------------------------------------------------------------------- */
describe('config change kinds', () => {
  const base = { n: 'quarkus.x.y', e: 'quarkus-x', t: 'boolean', d: 'false', p: 'R' };
  test('each kind', () => {
    assert.deepEqual(configChangeKinds(base, { ...base, d: 'true' }), ['default']);
    assert.deepEqual(configChangeKinds(base, { ...base, d: undefined }), ['default']);
    assert.deepEqual(configChangeKinds(base, { ...base, t: 'string' }), ['type']);
    assert.deepEqual(configChangeKinds(base, { ...base, l: 1 }), ['type']);
    assert.deepEqual(configChangeKinds(base, { ...base, m: 1 }), ['type']);
    assert.deepEqual(configChangeKinds(base, { ...base, p: 'B' }), ['phase']);
    assert.deepEqual(configChangeKinds(base, { ...base, x: '3.22' }), ['deprecated']);
    assert.deepEqual(configChangeKinds(base, { ...base, x: '' }), ['deprecated'], 'x "" means deprecated without since');
    assert.deepEqual(configChangeKinds({ ...base, x: '' }, base), ['undeprecated']);
    assert.deepEqual(configChangeKinds({ ...base, x: '3.1' }, { ...base, x: '3.2' }), []);
    assert.deepEqual(configChangeKinds({ ...base, en: ['a', 'b'] }, { ...base, en: ['b', 'a'] }), []);
    assert.deepEqual(configChangeKinds({ ...base, en: ['a', 'b'] }, { ...base, en: ['a', 'c'] }), ['enum']);
    assert.deepEqual(configChangeKinds(base, { ...base, en: ['a'] }), [], 'enum only when both sides are known');
    assert.deepEqual(configChangeKinds(base, { ...base, e: 'quarkus-z' }), ['extension']);
    assert.deepEqual(configChangeKinds(base, { ...base, d: 'true', p: 'B', x: '3.3' }), ['default', 'phase', 'deprecated']);
  });
  test('name, alias, optional and description differences are ignored', () => {
    assert.deepEqual(configChangeKinds(base, { ...base, n: 'quarkus.x."other".y', a: ['z'], o: 1 }), []);
  });
  test('enumDelta', () => {
    assert.deepEqual(enumDelta({ en: ['a', 'b', 'c'] }, { en: ['a', 'c', 'd'] }), { added: ['d'], removed: ['b'] });
  });
  test('phaseInfo and typeLabel', () => {
    assert.equal(phaseInfo('B').buildTime, true);
    assert.equal(phaseInfo('F').name, 'BUILD_AND_RUN_TIME_FIXED');
    assert.equal(phaseInfo('F').buildTime, true);
    assert.equal(phaseInfo('R').buildTime, false);
    assert.equal(phaseInfo('S').name, 'BOOTSTRAP');
    assert.equal(phaseInfo('?'), null);
    assert.equal(typeLabel({ t: 'int' }), 'int');
    assert.equal(typeLabel({ t: 'string', l: 1 }), 'List<string>');
    assert.equal(typeLabel({ t: 'string', m: 1 }), 'Map<String, string>');
    assert.equal(typeLabel({ t: 'string', m: 1, l: 1 }), 'Map<String, List<string>>');
    // the processor already describes most map properties with the whole map type
    assert.equal(typeLabel({ t: 'Map<String,String>', m: 1 }), 'Map<String,String>');
    assert.equal(typeLabel({ t: 'Map<String,String>' }), 'Map<String,String>');
    assert.equal(typeLabel(null), '');
  });
});

/* -------------------------------------------------------------------------- */
describe('config diff', () => {
  // Synthetic snapshot pair: config versions 0 (A) and 1 (B).
  const states = [
    /* 0 */ { n: 'quarkus.a.kept', e: 'quarkus-a', t: 'int', d: '1', p: 'R' },
    /* 1 */ { n: 'quarkus.a.kept', e: 'quarkus-a', t: 'int', d: '2', p: 'R' },
    /* 2 */ { n: 'quarkus.a.gone', e: 'quarkus-a', t: 'boolean', p: 'B' },
    /* 3 */ { n: 'quarkus.a.new.gone', e: 'quarkus-a', t: 'boolean', p: 'B' },
    /* 4 */ { n: 'quarkus.ds.url', a: ['quarkus.ds."name".url'], e: 'quarkus-ds', t: 'string', p: 'R' },
    /* 5 */ { n: 'quarkus.ds."name".url', e: 'quarkus-ds', t: 'string', p: 'R' },
    /* 6 */ { n: 'quarkus.ds."name".port', e: 'quarkus-ds', t: 'int', p: 'B' },
    /* 7 */ { n: 'quarkus.ds.port', a: ['quarkus.ds."name".port'], e: 'quarkus-ds', t: 'int', p: 'B' },
    /* 8 */ { n: 'quarkus.b.flag', e: 'quarkus-b', t: 'boolean', d: 'true', p: 'R' },
    /* 9 */ { n: 'quarkus.b.flag', e: 'quarkus-b2', t: 'boolean', d: 'true', p: 'R', x: '' },
    /* 10 */ { n: 'quarkus.r.one.size', e: 'quarkus-r', t: 'int', p: 'R' },
    /* 11 */ { n: 'quarkus.r.two.size', e: 'quarkus-r', t: 'int', p: 'R' },
    /* 12 */ { n: 'quarkus.r.three.size', e: 'quarkus-r', t: 'int', p: 'R' },
    /* 13 */ { n: 'quarkus.m.x', a: ['quarkus.m."k".x'], e: 'quarkus-m', t: 'string', d: 'a', p: 'R' },
    /* 14 */ { n: 'quarkus.m."k".x', a: ['quarkus.m.x'], e: 'quarkus-m', t: 'string', d: 'b', p: 'R' },
    /* 15 */ { n: 'quarkus.c."old-name".level', e: 'quarkus-c', t: 'Level', p: 'R' },
    /* 16 */ { n: 'quarkus.c."new-name".level', e: 'quarkus-c', t: 'Level', p: 'R' },
  ];
  const config = {
    versions: [0, 1],
    states,
    props: [
      { k: 'quarkus.a.kept', h: [[0, 0], [1, 1]] },
      { k: 'quarkus.a.gone', h: [[0, 2], [1, null]] },
      { k: 'quarkus.a.new.gone', h: [[1, 3]] },
      // A has the unnamed key with a named alias; B only has the named form → not removed, not added
      { k: 'quarkus.ds.url', h: [[0, 4], [1, null]] },
      { k: 'quarkus.ds.*.url', h: [[1, 5]] },
      // A has only the named key; B adds the unnamed key with the named one as alias
      { k: 'quarkus.ds.*.port', h: [[0, 6], [1, null]] },
      { k: 'quarkus.ds.port', h: [[1, 7]] },
      { k: 'quarkus.b.flag', h: [[0, 8], [1, 9]] },
      // two removed keys with two same-shaped candidates → no suggestion
      { k: 'quarkus.r.one.size', h: [[0, 10], [1, null]] },
      { k: 'quarkus.r.two.size', h: [[1, 11]] },
      { k: 'quarkus.r.three.size', h: [[1, 12]] },
      // mutual aliases: re-keyed property, compared through the aliases
      { k: 'quarkus.m.x', h: [[0, 13], [1, null]] },
      { k: 'quarkus.m.*.x', h: [[1, 14]] },
      // name-only change
      { k: 'quarkus.c.*.level', h: [[0, 15], [1, 16]] },
    ],
    desc: { 'quarkus.a.kept': 'Kept with a `new` default.' },
  };
  const d = diffConfig(config, 0, 1);

  test('added and removed are alias-aware', () => {
    assert.deepEqual(d.added.map((x) => x.k), ['quarkus.a.new.gone', 'quarkus.ds.port', 'quarkus.r.three.size', 'quarkus.r.two.size']);
    // quarkus.ds.url: B only keeps the named form, which has no alias back to the unnamed path → really removed.
    assert.deepEqual(d.removed.map((x) => x.k), ['quarkus.a.gone', 'quarkus.ds.url', 'quarkus.r.one.size']);
    assert.ok(!d.added.some((x) => x.k === 'quarkus.ds.*.url'), 'named form was an alias in A → not added');
    assert.ok(!d.removed.some((x) => x.k === 'quarkus.ds.*.port'), 'named key is an alias in B → not removed');
  });

  test('changed: kinds per key, name-only differences ignored', () => {
    const byK = Object.fromEntries(d.changed.map((c) => [c.k, c]));
    assert.deepEqual(byK['quarkus.a.kept'].kinds, ['default']);
    assert.deepEqual(byK['quarkus.b.flag'].kinds, ['deprecated', 'extension']);
    assert.equal(byK['quarkus.c.*.level'], undefined);
    assert.equal(d.counts.default, 2);
    assert.equal(d.counts.deprecated, 1);
    assert.equal(d.counts.extension, 1);
    assert.equal(d.counts.added, 4);
    assert.equal(d.counts.removed, 3);
  });

  test('mutually aliased keys are compared and flagged with via', () => {
    const m = d.changed.find((c) => c.k === 'quarkus.m.*.x');
    assert.ok(m);
    assert.equal(m.via, 'quarkus.m.x');
    assert.deepEqual(m.kinds, ['default']);
  });

  test('possible replacement only with exactly one candidate', () => {
    const gone = d.removed.find((x) => x.k === 'quarkus.a.gone');
    assert.equal(gone.replacement, 'quarkus.a.new.gone');
    const one = d.removed.find((x) => x.k === 'quarkus.r.one.size');
    assert.equal(one.replacement, undefined);
  });

  test('identical snapshots produce no changes', () => {
    const same = diffConfig(config, 1, 1);
    assert.equal(same.added.length + same.removed.length + same.changed.length, 0);
  });

  test('configRows flattens, tags and sorts; filterRows and tagCounts', () => {
    const rows = configRows(d, config.desc);
    assert.deepEqual(rows.map((r) => r.ext), [...rows.map((r) => r.ext)].sort());
    const qa = rows.filter((r) => r.ext === 'quarkus-a').map((r) => r.change);
    assert.deepEqual(qa, ['removed', 'changed', 'added']);
    const flag = rows.find((r) => r.k === 'quarkus.b.flag');
    assert.deepEqual(flag.tags, ['deprecated', 'extension']);
    assert.ok(rows.find((r) => r.k === 'quarkus.a.kept').text.includes('`new` default'));
    const counts = tagCounts(rows);
    assert.equal(counts.added, 4);
    assert.equal(counts.removed, 3);
    assert.equal(counts.default, 2);
    assert.equal(filterRows(rows, { tags: new Set(['removed']) }).length, 3);
    assert.equal(filterRows(rows, { tags: new Set(['removed', 'added']) }).length, 7);
    assert.deepEqual(filterRows(rows, { text: 'ds port' }).map((r) => r.k), ['quarkus.ds.port']);
    assert.equal(filterRows(rows, {}).length, rows.length);
    const undep = configRows({ added: [], removed: [], changed: [{ k: 'k', a: {}, b: {}, kinds: ['undeprecated'] }] });
    assert.deepEqual(undep[0].tags, ['deprecated']);
    assert.ok(CONFIG_FILTER_TAGS.includes('enum'));
  });

  test('groupBy keeps first-seen order', () => {
    const g = groupBy([{ x: 'b' }, { x: 'a' }, { x: 'b' }], (r) => r.x);
    assert.deepEqual([...g.keys()], ['b', 'a']);
    assert.equal(g.get('b').length, 2);
  });
});

/* -------------------------------------------------------------------------- */
describe('BOM diff', () => {
  const versions = V('3.15.7', '3.20.6');
  const bom = {
    artifacts: [
      { id: 'io.quarkus:quarkus-core', h: [[0, '=']] },
      { id: 'io.quarkus:quarkus-arc', h: [[0, '=']] },
      { id: 'io.quarkus:quarkus-new', h: [[1, '=']] },
      { id: 'io.quarkus:quarkus-old', h: [[0, '='], [1, null]] },
      { id: 'io.quarkus:quarkus-pinned', h: [[0, '='], [1, '3.20.5']] },
      { id: 'io.netty:netty-common', h: [[0, '4.1.118.Final'], [1, '4.1.124.Final']] },
      { id: 'io.netty:netty-buffer', h: [[0, '4.1.118.Final'], [1, '4.1.124.Final']] },
      { id: 'io.netty:netty-tcnative', h: [[0, '2.0.70.Final'], [1, '2.0.72.Final']] },
      { id: 'org.liquibase:liquibase-core', h: [[0, '5.0.1'], [1, '4.33.0']] },
      { id: 'io.vertx:vertx-core', h: [[0, '4.5.22'], [1, '5.0.4']] },
      { id: 'io.quarkus.gizmo:gizmo2', h: [[0, '2.0.0.Alpha5'], [1, '2.0.0.Beta1']] },
      { id: 'org.same:same', h: [[0, '1.0']] },
      { id: 'org.late:late', h: [[3, '1.0']] },
    ],
  };
  const d = diffBom(bom, versions, 0, 1);

  test('"=" in both releases is only counted', () => {
    assert.equal(d.modules, 2);
    assert.ok(!d.rows.some((r) => r.id === 'io.quarkus:quarkus-core'));
  });
  test('"=" resolves to the release version', () => {
    assert.deepEqual(d.rows.find((r) => r.id === 'io.quarkus:quarkus-new'), {
      id: 'io.quarkus:quarkus-new', group: 'io.quarkus', artifact: 'quarkus-new', extra: '', from: null, to: '3.20.6', kind: 'added', follows: true,
    });
    assert.equal(d.rows.find((r) => r.id === 'io.quarkus:quarkus-old').from, '3.15.7');
    const pinned = d.rows.find((r) => r.id === 'io.quarkus:quarkus-pinned');
    assert.equal(pinned.from, '3.15.7');
    assert.equal(pinned.to, '3.20.5');
    assert.equal(pinned.kind, 'minor');
    assert.equal(resolveBomValue('=', '3.0.0.Final'), '3.0.0.Final');
    assert.equal(resolveBomValue('1.2', '3.0.0.Final'), '1.2');
  });
  test('kinds and counts', () => {
    const kind = (id) => d.rows.find((r) => r.id === id).kind;
    assert.equal(kind('org.liquibase:liquibase-core'), 'downgrade');
    assert.equal(kind('io.vertx:vertx-core'), 'major');
    assert.equal(kind('io.netty:netty-common'), 'patch');
    assert.equal(kind('io.quarkus.gizmo:gizmo2'), 'other');
    assert.equal(kind('io.quarkus:quarkus-old'), 'removed');
    assert.equal(d.unchanged, 1);
    assert.ok(!d.rows.some((r) => r.id === 'org.late:late'), 'absent in both');
    assert.deepEqual(
      { changed: d.counts.changed, added: d.counts.added, removed: d.counts.removed, downgrade: d.counts.downgrade, major: d.counts.major, minor: d.counts.minor, patch: d.counts.patch, other: d.counts.other },
      { changed: 7, added: 1, removed: 1, downgrade: 1, major: 1, minor: 1, patch: 3, other: 1 },
    );
  });
  test('groups share (groupId, from, to) and are ordered by severity', () => {
    const netty = d.groups.filter((g) => g.group === 'io.netty');
    assert.equal(netty.length, 2);
    const main = netty.find((g) => g.from === '4.1.118.Final');
    assert.deepEqual(main.items.map((r) => r.artifact), ['netty-buffer', 'netty-common']);
    assert.ok(main.text.includes('io.netty:netty-common'));
    const kinds = d.groups.map((g) => g.kind);
    const order = kinds.map((k) => BOM_KIND_ORDER.indexOf(k));
    assert.deepEqual(order, [...order].sort((a, b) => a - b));
    assert.equal(kinds[0], 'downgrade');
  });
  test('groupBomRows on its own', () => {
    const g = groupBomRows([
      { id: 'a:x', group: 'a', from: '1', to: '2', kind: 'major' },
      { id: 'a:y', group: 'a', from: '1', to: '2', kind: 'major' },
      { id: 'b:z', group: 'b', from: null, to: '1', kind: 'added' },
    ]);
    assert.equal(g.length, 2);
    assert.equal(g[0].items.length, 2);
    assert.equal(g[1].kind, 'added');
  });
  test('reverse comparison mirrors kinds', () => {
    const r = diffBom(bom, versions, 1, 0);
    assert.equal(r.rows.find((x) => x.id === 'org.liquibase:liquibase-core').kind, 'major');
    assert.equal(r.rows.find((x) => x.id === 'io.vertx:vertx-core').kind, 'downgrade');
    assert.equal(r.rows.find((x) => x.id === 'io.quarkus:quarkus-new').kind, 'removed');
  });
});

/* -------------------------------------------------------------------------- */
describe('headline libraries', () => {
  test('matched by artifactId (and groupId for hibernate-core), in contract order', () => {
    const versions = V('3.8.6', '3.9.0');
    const bom = {
      artifacts: [
        { id: 'com.fasterxml.jackson.core:jackson-databind', h: [[0, '2.16.1'], [1, '2.17.2']] },
        { id: 'org.hibernate.orm:hibernate-core', h: [[0, '6.4.4.Final']] },
        { id: 'org.example:hibernate-core', h: [[0, '1.0']] },
        { id: 'org.jboss.logmanager:jboss-logmanager-embedded', h: [[0, '1.1.2'], [1, null]] },
        { id: 'org.jboss.logmanager:jboss-logmanager', h: [[1, '3.0.4.Final']] },
        { id: 'io.vertx:vertx-core:test-jar', h: [[0, '4.5.7']] },
        { id: 'io.vertx:vertx-web', h: [[0, '4.5.7']] },
      ],
    };
    const rows = headlineLibraries(bom, versions, 0, 1);
    assert.deepEqual(rows.map((r) => [r.label, r.id, r.kind]), [
      ['Hibernate ORM', 'org.hibernate.orm:hibernate-core', 'same'],
      ['Jackson', 'com.fasterxml.jackson.core:jackson-databind', 'minor'],
      ['JBoss LogManager', 'org.jboss.logmanager:jboss-logmanager', 'added'],
      ['JBoss LogManager Embedded', 'org.jboss.logmanager:jboss-logmanager-embedded', 'removed'],
    ]);
    assert.ok(HEADLINE_LIBRARIES.length >= 27);
  });

  test('every label names one library: no two entries share a label', () => {
    const labels = HEADLINE_LIBRARIES.map((d) => d.label);
    assert.equal(new Set(labels).size, labels.length);
  });

  test('GraalVM SDK: one row, graal-sdk preferred, nativeimage stands in where graal-sdk is not managed', () => {
    const versions = V('3.2.0', '3.8.3', '3.20.0', '3.27.0');
    const bom = {
      artifacts: [
        { id: 'org.graalvm.sdk:graal-sdk', h: [[0, '23.0.1'], [1, '23.1.2'], [3, null]] },
        { id: 'org.graalvm.sdk:nativeimage', h: [[1, '23.1.2'], [3, '24.2.1']] },
        { id: 'org.example:nativeimage', h: [[0, '9.9']] },
      ],
    };
    const graal = (a, b) => headlineLibraries(bom, versions, a, b).filter((r) => r.label === 'GraalVM SDK');
    // both managed at the same version in 3.8.3 and 3.20.0: one row, not two
    assert.deepEqual(graal(1, 2).map((r) => [r.id, r.from, r.to, r.kind]), [['org.graalvm.sdk:graal-sdk', '23.1.2', '23.1.2', 'same']]);
    // graal-sdk only → both → nativeimage only: one row, continuous versions, ids per side
    const across = graal(0, 3);
    assert.equal(across.length, 1);
    assert.deepEqual(
      { fromId: across[0].fromId, toId: across[0].toId, id: across[0].id, from: across[0].from, to: across[0].to, kind: across[0].kind },
      { fromId: 'org.graalvm.sdk:graal-sdk', toId: 'org.graalvm.sdk:nativeimage', id: 'org.graalvm.sdk:nativeimage', from: '23.0.1', to: '24.2.1', kind: 'major' },
    );
    assert.equal(graal(3, 3)[0].id, 'org.graalvm.sdk:nativeimage', 'groupId must be org.graalvm.sdk');
  });

  test('libraries listed under two groupIds: one row per label', () => {
    const versions = V('3.20.0', '4.0.0');
    const bom = {
      artifacts: [
        { id: 'com.fasterxml.jackson.core:jackson-databind', h: [[0, '2.18.2'], [1, '2.20.1']] },
        { id: 'io.smallrye.config:smallrye-config', h: [[0, '3.12.4'], [1, '4.0.0']] },
        { id: 'io.smallrye:smallrye-config', h: [[0, '1.5.0'], [1, null]] },
        { id: 'org.hibernate.orm:hibernate-core', h: [[0, '6.6.11.Final'], [1, '7.1.1.Final']] },
        { id: 'org.hibernate:hibernate-core', h: [[0, '5.6.15.Final']] },
        { id: 'tools.jackson.core:jackson-databind', h: [[1, '3.0.1']] },
      ],
    };
    const rows = headlineLibraries(bom, versions, 0, 1);
    assert.equal(new Set(rows.map((r) => r.label)).size, rows.length, 'no label twice');
    assert.deepEqual(rows.map((r) => [r.label, r.id, r.from, r.to, r.kind]), [
      ['Hibernate ORM', 'org.hibernate.orm:hibernate-core', '6.6.11.Final', '7.1.1.Final', 'major'],
      ['SmallRye Config', 'io.smallrye.config:smallrye-config', '3.12.4', '4.0.0', 'major'],
      ['Jackson', 'com.fasterxml.jackson.core:jackson-databind', '2.18.2', '2.20.1', 'minor'],
      ['Jackson 3', 'tools.jackson.core:jackson-databind', null, '3.0.1', 'added'],
    ]);
    // a release that only has the old coordinate still gets its row
    const legacy = { artifacts: [{ id: 'io.smallrye:smallrye-config', h: [[0, '1.8.5'], [1, null]] }, { id: 'io.smallrye.config:smallrye-config', h: [[1, '2.0.0']] }] };
    assert.deepEqual(headlineLibraries(legacy, versions, 0, 1).map((r) => [r.fromId, r.toId, r.from, r.to, r.kind]),
      [['io.smallrye:smallrye-config', 'io.smallrye.config:smallrye-config', '1.8.5', '2.0.0', 'major']]);
  });

  test('the sample never shows a label twice', () => {
    const vj = load('versions');
    const bom = load('bom');
    for (let a = 0; a < vj.versions.length; a++) {
      for (let b = 0; b < vj.versions.length; b += 3) {
        const labels = headlineLibraries(bom, vj.versions, a, b).map((r) => r.label);
        assert.equal(new Set(labels).size, labels.length, `${vj.versions[a].v} → ${vj.versions[b].v}`);
      }
    }
    const graal = headlineLibraries(bom, vj.versions, findVersionIndex(vj.versions, '3.20.6'), findVersionIndex(vj.versions, '3.27.4'))
      .filter((r) => r.label === 'GraalVM SDK');
    assert.deepEqual(graal.map((r) => [r.fromId, r.toId, r.from, r.to, r.kind]),
      [['org.graalvm.sdk:graal-sdk', 'org.graalvm.sdk:nativeimage', '23.1.2', '24.2.1', 'major']]);
  });
});

/* -------------------------------------------------------------------------- */
describe('platform BOM', () => {
  const {
    hasPlatformBom, platformBomAt, platformArtifactKey, platformDifferences, platformChangeSummary, platformArtifactRuns, platformDiffEffect,
    artifactSpellings,
  } = core;
  // 3.33.3.1 has no platform BOM; 3.33.4 overrides Jackson, as the real one does
  const versions = V('3.33.3', '3.33.3.1', '3.33.4', '3.33.4.1');
  const bom = {
    artifacts: [
      { id: 'com.fasterxml.jackson.core:jackson-core', h: [[0, '2.21.6']] },
      { id: 'com.fasterxml.jackson.core:jackson-databind', h: [[0, '2.21.6']] },
      { id: 'com.fasterxml.jackson.dataformat:jackson-dataformat-yaml', h: [[0, '2.21.6']] },
      { id: 'io.agroal:agroal-api', h: [[0, '2.7.1']] },
      { id: 'io.fabric8:crd-generator-maven-plugin:maven-plugin', h: [[0, '7.5.2']] },
      { id: 'io.quarkus.gizmo:gizmo:test-jar', h: [[0, '1.10.1']] },
      { id: 'io.quarkus:quarkus-bom-quarkus-platform-descriptor:json:${quarkus.version}', h: [[0, '=']] },
      { id: 'io.quarkus:quarkus-core', h: [[0, '=']] },
      { id: 'io.quarkus:quarkus-pinned', h: [[0, '3.33.2']] },
      { id: 'io.vertx:vertx-core:test-jar', h: [[0, '4.5.34']] },
      { id: 'io.vertx:vertx-core:test-jar:tests', h: [[0, '4.5.34']] },
      { id: 'org.graalvm.polyglot:js', h: [[0, '23.1.2']] },
    ],
  };
  const platform = {
    versions: [0, 2, 3],
    artifacts: [
      { id: 'com.fasterxml.jackson.core:jackson-core', h: [[2, '2.21.7'], [3, null]] },
      { id: 'com.fasterxml.jackson.core:jackson-databind', h: [[2, '2.21.7'], [3, null]] },
      { id: 'com.fasterxml.jackson.dataformat:jackson-dataformat-yaml', h: [[2, '2.21.7'], [3, null]] },
      { id: 'io.agroal:agroal-api', h: [[0, '2.7.2'], [2, null]] },
      { id: 'io.fabric8:crd-generator-maven-plugin', h: [[0, '7.5.2']] },
      { id: 'io.fabric8:crd-generator-maven-plugin:maven-plugin', h: [[0, '-']] },
      { id: 'io.quarkiverse.vault:quarkus-vault', h: [[0, '4.7.0']] },
      { id: 'io.quarkus.gizmo:gizmo:test-jar', h: [[0, '-']] },
      { id: 'io.quarkus.gizmo:gizmo:tests', h: [[0, '1.11.0']] },
      { id: 'io.quarkus.platform:quarkus-bom-quarkus-platform-descriptor:json:${quarkus.version}', h: [[0, '=']] },
      { id: 'io.quarkus:quarkus-bom-quarkus-platform-descriptor:json:${quarkus.version}', h: [[0, '-']] },
      { id: 'io.quarkus:quarkus-pinned', h: [[0, '=']] },
      { id: 'io.vertx:vertx-core:test-jar', h: [[0, '-']] },
      { id: 'io.vertx:vertx-core:test-jar:tests', h: [[0, '-']] },
      { id: 'io.vertx:vertx-core:tests', h: [[0, '4.5.34']] },
      { id: 'org.graalvm.polyglot:js', h: [[0, '-']] },
    ],
  };

  test('hasPlatformBom reads the versions list', () => {
    assert.deepEqual([0, 1, 2, 3].map((i) => hasPlatformBom(platform, i)), [true, false, true, true]);
    assert.equal(hasPlatformBom(null, 0), false);
    assert.equal(hasPlatformBom({ artifacts: [] }, 0), false);
  });

  test('platformArtifactKey: one artifact written differently', () => {
    assert.equal(platformArtifactKey('io.vertx:vertx-core:test-jar'), 'io.vertx:vertx-core:tests');
    assert.equal(platformArtifactKey('io.vertx:vertx-core:test-jar:tests'), 'io.vertx:vertx-core:tests');
    assert.equal(platformArtifactKey('io.vertx:vertx-core:tests'), 'io.vertx:vertx-core:tests');
    assert.equal(platformArtifactKey('org.infinispan:infinispan-core:test-jar:jakarta'), 'org.infinispan:infinispan-core:jakarta');
    assert.equal(platformArtifactKey('io.fabric8:crd-generator-maven-plugin:maven-plugin'), 'io.fabric8:crd-generator-maven-plugin');
    assert.equal(platformArtifactKey('io.quarkus.platform:quarkus-bom-quarkus-platform-properties:properties'),
      'io.quarkus:quarkus-bom-quarkus-platform-properties:properties');
    assert.equal(platformArtifactKey('io.quarkus.platform:quarkus-camel-bom'), 'io.quarkus.platform:quarkus-camel-bom');
    assert.equal(platformArtifactKey('org.acme:lib:pom'), 'org.acme:lib:pom');
  });

  test('platformDifferences: versions, only one BOM, and entries written differently', () => {
    const d = platformDifferences(bom, platform, versions, 2);
    assert.deepEqual(d.changed, [
      { id: 'com.fasterxml.jackson.core:jackson-core', core: '2.21.6', platform: '2.21.7' },
      { id: 'com.fasterxml.jackson.core:jackson-databind', core: '2.21.6', platform: '2.21.7' },
      { id: 'com.fasterxml.jackson.dataformat:jackson-dataformat-yaml', core: '2.21.6', platform: '2.21.7' },
      // written differently and at another version: a change, named by the core BOM's id too
      { id: 'io.quarkus.gizmo:gizmo:tests', coreId: 'io.quarkus.gizmo:gizmo:test-jar', core: '1.10.1', platform: '1.11.0' },
      // "=" reads as the release: 3.33.4 instead of 3.33.2
      { id: 'io.quarkus:quarkus-pinned', core: '3.33.2', platform: '3.33.4' },
    ]);
    assert.deepEqual(d.added, [{ id: 'io.quarkiverse.vault:quarkus-vault', platform: '4.7.0' }]);
    assert.deepEqual(d.removed, [{ id: 'org.graalvm.polyglot:js', core: '23.1.2' }]);
    // the maven-plugin type, the test-jar classifier and the platform's own descriptor, each at the same version
    assert.equal(d.structural, 3);
    // the override of agroal ends at 3.33.4; at 3.33.3 it is a change
    assert.deepEqual(platformDifferences(bom, platform, versions, 0).changed.map((r) => r.id),
      ['io.agroal:agroal-api', 'io.quarkus.gizmo:gizmo:tests', 'io.quarkus:quarkus-pinned']);
    assert.deepEqual(platformDifferences(bom, platform, versions, 3).changed.map((r) => r.id),
      ['io.quarkus.gizmo:gizmo:tests', 'io.quarkus:quarkus-pinned']);
  });

  test('platformChangeSummary groups by pair of versions and names the groupIds', () => {
    const changed = [
      { id: 'com.fasterxml.jackson.core:jackson-core', core: '2.21.6', platform: '2.21.7' },
      { id: 'com.fasterxml.jackson.core:jackson-databind', core: '2.21.6', platform: '2.21.7' },
      { id: 'com.fasterxml.jackson.dataformat:jackson-dataformat-yaml', core: '2.21.6', platform: '2.21.7' },
      { id: 'io.agroal:agroal-api', core: '2.8', platform: '2.8.1' },
      { id: 'io.agroal:agroal-pool', core: '2.8', platform: '2.8.1' },
      { id: 'org.acme:a', core: '1', platform: '2' },
      { id: 'net.acme:b', core: '1', platform: '2' },
      { id: 'net.other:c', core: '1', platform: '2' },
    ];
    assert.deepEqual(platformChangeSummary(changed).map((s) => [s.label, s.core, s.platform, s.count]), [
      ['com.fasterxml.jackson…', '2.21.6', '2.21.7', 3],
      ['net.acme and 2 other groups', '1', '2', 3],
      ['io.agroal', '2.8', '2.8.1', 2],
    ]);
    assert.deepEqual(platformChangeSummary([]), []);
  });

  test('platformBomAt: diffBom and headlineLibraries compare platform BOMs', () => {
    const pb = platformBomAt(bom, platform, [2, 0]);
    // ascending release indices, sorted ids, artifacts it never manages left out
    assert.deepEqual(pb.artifacts.find((a) => a.id === 'io.quarkus:quarkus-pinned').h, [[0, '='], [2, '=']]);
    assert.ok(!pb.artifacts.some((a) => a.id === 'org.graalvm.polyglot:js'));
    assert.ok(!pb.artifacts.some((a) => a.id === 'io.vertx:vertx-core:test-jar'));
    const ids = pb.artifacts.map((a) => a.id);
    assert.deepEqual(ids, [...ids].sort());
    // 3.33.3 to 3.33.4: the core BOM keeps Jackson, the platform BOM upgrades it
    assert.equal(diffBom(bom, versions, 0, 2).rows.length, 0);
    const d = diffBom(pb, versions, 0, 2);
    assert.deepEqual(d.rows.map((r) => [r.id, r.from, r.to, r.kind]), [
      ['com.fasterxml.jackson.core:jackson-core', '2.21.6', '2.21.7', 'patch'],
      ['com.fasterxml.jackson.core:jackson-databind', '2.21.6', '2.21.7', 'patch'],
      ['com.fasterxml.jackson.dataformat:jackson-dataformat-yaml', '2.21.6', '2.21.7', 'patch'],
      ['io.agroal:agroal-api', '2.7.2', '2.7.1', 'downgrade'],
    ]);
    // "=" in both releases: quarkus-core, the pinned module the platform aligns, and the platform's own descriptor
    assert.equal(d.modules, 3);
    const jackson = headlineLibraries(pb, versions, 0, 2).find((r) => r.label === 'Jackson');
    assert.deepEqual([jackson.from, jackson.to, jackson.kind], ['2.21.6', '2.21.7', 'patch']);
  });

  test('platformArtifactRuns: runs over the shown releases with a platform BOM', () => {
    const all = [0, 1, 2, 3];
    assert.deepEqual(platformArtifactRuns(bom, platform, versions, 'com.fasterxml.jackson.core:jackson-databind', all),
      [{ from: 2, to: 2, count: 1, platform: '2.21.7', core: '2.21.6', platformId: null, coreId: null, without: [] }]);
    // a release without a platform BOM inside a run keeps it going and is named; a hidden one is not
    assert.deepEqual(platformArtifactRuns(bom, platform, versions, 'io.quarkus:quarkus-pinned', all).map((r) => [r.from, r.to, r.count, r.platform, r.core, r.without]),
      [[0, 3, 3, '=', '3.33.2', [1]]]);
    assert.deepEqual(platformArtifactRuns(bom, platform, versions, 'io.quarkus:quarkus-pinned', [0, 2, 3]).map((r) => [r.from, r.to, r.count, r.without]),
      [[0, 3, 3, []]]);
    // a release without a platform BOM after which the difference ends belongs to no run
    assert.deepEqual(platformArtifactRuns(bom, platform, versions, 'io.agroal:agroal-api', all).map((r) => [r.from, r.to, r.count, r.without]),
      [[0, 0, 1, []]]);
    // only one of the BOMs manages it, and the other has no other id of it: the other value is null
    assert.deepEqual(platformArtifactRuns(bom, platform, versions, 'org.graalvm.polyglot:js', all).map((r) => [r.from, r.to, r.platform, r.core, r.without]),
      [[0, 3, null, '23.1.2', [1]]]);
    assert.deepEqual(platformArtifactRuns(bom, platform, versions, 'io.quarkiverse.vault:quarkus-vault', all).map((r) => [r.from, r.to, r.platform, r.core]),
      [[0, 3, '4.7.0', null]]);
    assert.deepEqual(platformArtifactRuns(bom, platform, versions, 'io.quarkus:quarkus-core', all), []);
    assert.deepEqual(platformArtifactRuns(bom, platform, versions, 'org.unknown:unknown', all), []);
  });

  test('platformArtifactRuns: another id of the same artifact stands for it, as in platformDifferences', () => {
    const all = [0, 1, 2, 3];
    // the platform BOM manages io.vertx:vertx-core:tests at the same version: no difference, from either id
    assert.deepEqual(platformArtifactRuns(bom, platform, versions, 'io.vertx:vertx-core:test-jar', all), []);
    assert.deepEqual(platformArtifactRuns(bom, platform, versions, 'io.vertx:vertx-core:test-jar:tests', all), []);
    assert.deepEqual(platformArtifactRuns(bom, platform, versions, 'io.vertx:vertx-core:tests', all), []);
    assert.deepEqual(platformArtifactRuns(bom, platform, versions, 'io.fabric8:crd-generator-maven-plugin:maven-plugin', all), []);
    assert.deepEqual(platformArtifactRuns(bom, platform, versions, 'io.quarkus.platform:quarkus-bom-quarkus-platform-descriptor:json:${quarkus.version}', all), []);
    // at another version, the other id gives the other BOM's value, as the release page's change does
    const atRelease = platformDifferences(bom, platform, versions, 2).changed.find((r) => r.coreId === 'io.quarkus.gizmo:gizmo:test-jar');
    assert.deepEqual([atRelease.id, atRelease.core, atRelease.platform], ['io.quarkus.gizmo:gizmo:tests', '1.10.1', '1.11.0']);
    assert.deepEqual(platformArtifactRuns(bom, platform, versions, 'io.quarkus.gizmo:gizmo:test-jar', all),
      [{ from: 0, to: 3, count: 3, platform: '1.11.0', core: '1.10.1', platformId: 'io.quarkus.gizmo:gizmo:tests', coreId: null, without: [1] }]);
    assert.deepEqual(platformArtifactRuns(bom, platform, versions, 'io.quarkus.gizmo:gizmo:tests', all),
      [{ from: 0, to: 3, count: 3, platform: '1.11.0', core: '1.10.1', platformId: null, coreId: 'io.quarkus.gizmo:gizmo:test-jar', without: [1] }]);
  });

  test('artifactSpellings: the other ids of one artifact, by BOM', () => {
    assert.deepEqual(artifactSpellings(bom, platform, 'io.vertx:vertx-core:tests'),
      { core: ['io.vertx:vertx-core:test-jar', 'io.vertx:vertx-core:test-jar:tests'], platform: [] });
    assert.deepEqual(artifactSpellings(bom, platform, 'io.vertx:vertx-core:test-jar'),
      { core: ['io.vertx:vertx-core:test-jar:tests'], platform: ['io.vertx:vertx-core:tests'] });
    assert.deepEqual(artifactSpellings(bom, platform, 'io.quarkiverse.vault:quarkus-vault'), { core: [], platform: [] });
  });

  test('platformDiffEffect: what comparing the platform BOMs changes', () => {
    // 3.33.3 to 3.33.4: three Jackson upgrades and the end of the agroal override exist in the platform BOMs only
    const pb = platformBomAt(bom, platform, [0, 2]);
    assert.deepEqual(platformDiffEffect(diffBom(bom, versions, 0, 2), diffBom(pb, versions, 0, 2)), { count: 4, spelling: false });
    const row = (id, from, to) => ({ id, from, to });
    // one artifact written otherwise is one change; a change to another version is one artifact, not two
    assert.deepEqual(platformDiffEffect(
      { rows: [row('g:a:test-jar', '1', '2'), row('g:a:test-jar:tests', '1', '2'), row('g:b', '1', '2')] },
      { rows: [row('g:a:tests', '1', '2'), row('g:b', '1', '3')] }), { count: 1, spelling: false });
    assert.deepEqual(platformDiffEffect({ rows: [row('g:a:test-jar', '1', '2')] }, { rows: [row('g:a:tests', '1', '2')] }),
      { count: 0, spelling: true });
    assert.deepEqual(platformDiffEffect({ rows: [row('g:b', '1', '2')] }, { rows: [row('g:b', '1', '2')] }), { count: 0, spelling: false });
    // only in one of them: an artifact added by the platform BOM, or one it does not manage
    assert.deepEqual(platformDiffEffect({ rows: [] }, { rows: [row('g:c', null, '1'), row('g:d', '1', null)] }), { count: 2, spelling: false });
  });
});

/* -------------------------------------------------------------------------- */
describe('extension diff', () => {
  const extensions = {
    states: [
      /* 0 */ { n: 'RESTEasy Reactive', s: 'stable' },
      /* 1 */ { r: 'io.quarkus:quarkus-rest' },
      /* 2 */ { n: 'OpenTelemetry', s: 'preview' },
      /* 3 */ { n: 'OpenTelemetry', s: 'stable' },
      /* 4 */ { n: 'Agroal - Database connection pool', s: 'stable' },
      /* 5 */ { n: 'Agroal - Database Connection Pool', s: 'stable' },
      /* 6 */ { n: 'REST', s: 'stable' },
      /* 7 */ { n: 'Old', s: 'deprecated' },
      /* 8 */ { n: 'OpenTelemetry', s: 'stable', d: 'new description' },
    ],
    exts: [
      { id: 'quarkus-resteasy-reactive', h: [[0, 0], [1, 1]] },
      { id: 'quarkus-opentelemetry', h: [[0, 2], [1, 3]] },
      { id: 'quarkus-agroal', h: [[0, 4], [1, 5]] },
      { id: 'quarkus-rest', h: [[1, 6]] },
      { id: 'quarkus-old', h: [[0, 7], [1, null]] },
      { id: 'quarkus-desc', h: [[0, 3], [1, 8]] },
    ],
  };
  test('added, removed, relocated, status, renamed', () => {
    const d = diffExtensions(extensions, 0, 1);
    assert.deepEqual(d.added.map((x) => x.id), ['quarkus-rest']);
    assert.deepEqual(d.removed.map((x) => x.id), ['quarkus-old']);
    assert.deepEqual(d.changed.map((x) => [x.id, x.kinds]), [
      ['quarkus-agroal', ['renamed']],
      ['quarkus-opentelemetry', ['status']],
      ['quarkus-resteasy-reactive', ['relocated']],
    ]);
    assert.deepEqual(
      { relocated: d.counts.relocated, status: d.counts.status, renamed: d.counts.renamed, added: d.counts.added, removed: d.counts.removed },
      { relocated: 1, status: 1, renamed: 1, added: 1, removed: 1 },
    );
  });
  test('extensionChangeKinds', () => {
    assert.deepEqual(extensionChangeKinds({ n: 'A', s: 'stable' }, { r: 'g:a' }), ['relocated']);
    assert.deepEqual(extensionChangeKinds({ r: 'g:a' }, { r: 'g:b' }), ['relocated']);
    assert.deepEqual(extensionChangeKinds({ r: 'g:a' }, { n: 'A', s: 'stable' }), ['unrelocated']);
    assert.deepEqual(extensionChangeKinds({ n: 'A', s: 'preview' }, { n: 'B', s: 'stable' }), ['status', 'renamed']);
    assert.deepEqual(extensionChangeKinds({ n: 'A', s: 'stable' }, { n: 'A', s: 'stable', d: 'x' }), []);
  });
});

/* -------------------------------------------------------------------------- */
describe('classes', () => {
  // three classes snapshots (releases 0, 1, 2): QuarkusUnitTest moves at 2, one of its nested classes goes, another
  // comes at 1 and moves along; a class of the unnamed package and one in two jars come at 2
  const classes = {
    versions: [0, 1, 2],
    artifacts: ['quarkus-amazon-lambda-http', 'quarkus-amazon-lambda-rest', 'quarkus-core', 'quarkus-junit-internal', 'quarkus-junit5-internal'],
    packages: ['', 'io.quarkus.amazon.lambda.http', 'io.quarkus.runtime', 'io.quarkus.test'],
    classes: [
      [0, 'Main', [[2, 2]]],
      [1, 'Handler', [[2, [0, 1]]]],
      [2, 'Application', [[0, 2]]],
      [2, 'Gone', [[0, 2], [1, null]]],
      [2, 'Old', [[0, 2], [2, null]]],
      [3, 'QuarkusExtensionTest', [[2, 3]]],
      [3, 'QuarkusUnitTest', [[0, 4], [2, 3]]],
      [3, 'QuarkusUnitTest$1', [[0, 4], [2, null]]],
      [3, 'QuarkusUnitTest$Builder', [[1, 4], [2, 3]]],
    ],
  };
  const versions = V('3.30.0', '3.30.1', '3.31.0');
  versions.forEach((x) => {
    x.classes = true;
  });

  test('names: binary, in the package, nested, anonymous, top-level', () => {
    assert.equal(className(classes, classes.classes[6]), 'io.quarkus.test.QuarkusUnitTest');
    assert.equal(className(classes, classes.classes[0]), 'Main', 'the unnamed package');
    assert.equal(findClass(classes, 'io.quarkus.test.QuarkusUnitTest$1'), classes.classes[7]);
    assert.equal(findClass(classes, 'io.quarkus.test.Nope'), null);
    assert.equal(simpleClassName('io.quarkus.test.QuarkusUnitTest$Builder'), 'QuarkusUnitTest$Builder');
    assert.equal(classPackage('io.quarkus.test.QuarkusUnitTest$Builder'), 'io.quarkus.test');
    assert.equal(classPackage('Main'), '');
    assert.equal(isNestedClass('io.quarkus.test.QuarkusUnitTest$Builder'), true);
    assert.equal(isNestedClass('io.quarkus.test.QuarkusUnitTest'), false);
    assert.equal(isNestedClass('io.quarkus.gen.$Proxy'), false, 'a $ that starts the name does not nest it');
    assert.equal(isAnonymousClass('io.quarkus.test.QuarkusUnitTest$1'), true);
    assert.equal(isAnonymousClass('io.quarkus.test.QuarkusUnitTest$4$2'), true);
    assert.equal(isAnonymousClass('io.quarkus.test.Outer$1Local'), true, 'local classes too');
    assert.equal(isAnonymousClass('io.quarkus.test.QuarkusUnitTest$Builder'), false);
    assert.equal(topLevelClass('io.quarkus.test.QuarkusUnitTest$4$2'), 'io.quarkus.test.QuarkusUnitTest');
    assert.equal(topLevelClass('io.quarkus.test.QuarkusUnitTest'), 'io.quarkus.test.QuarkusUnitTest');
    assert.equal(topLevelClass('io.quarkus.gen.$Proxy$1'), 'io.quarkus.gen.$Proxy');
    assert.deepEqual([jarsOf(null), jarsOf(undefined), jarsOf(3), jarsOf([0, 1])], [[], [], [3], [0, 1]]);
  });

  test('classes snapshots follow the classes flags and the config rule', () => {
    const list = V('3.30.0', '3.30.1', '3.31.0.CR1', '3.31.0');
    list[1].classes = true;
    list[3].classes = true;
    assert.deepEqual(classVersionsOf(list), [1, 3]);
    assert.deepEqual(classVersionsOf(undefined), []);
    assert.equal(classesVersionFor(0, list), 1, 'a final without a snapshot uses its stream\'s');
    assert.equal(classesVersionFor(2, list), null, 'a pre-release only its own');
    assert.equal(classesVersionFor(3, list, [1, 3]), 3);
  });

  test('diffClasses: added, removed and moved, for every class and the top-level ones', () => {
    const d = diffClasses(classes, 0, 2);
    const names = (list) => list.map((x) => x.name);
    assert.deepEqual(names(d.added), ['Main', 'io.quarkus.amazon.lambda.http.Handler', 'io.quarkus.test.QuarkusExtensionTest', 'io.quarkus.test.QuarkusUnitTest$Builder']);
    assert.deepEqual(names(d.removed), ['io.quarkus.runtime.Gone', 'io.quarkus.runtime.Old', 'io.quarkus.test.QuarkusUnitTest$1']);
    assert.deepEqual(d.moved, [{ name: 'io.quarkus.test.QuarkusUnitTest', from: [4], to: [3] }]);
    assert.deepEqual(d.added[1].to, [0, 1], 'a class in two jars');
    assert.deepEqual(d.counts, { added: 4, removed: 3, moved: 1 });
    assert.deepEqual(d.top, { added: 3, removed: 2, moved: 1 });
    // from 1: the builder moves too, Gone is gone already
    const later = diffClasses(classes, 1, 2);
    assert.deepEqual(names(later.moved), ['io.quarkus.test.QuarkusUnitTest', 'io.quarkus.test.QuarkusUnitTest$Builder']);
    assert.deepEqual(names(later.removed), ['io.quarkus.runtime.Old', 'io.quarkus.test.QuarkusUnitTest$1']);
    // nothing between a snapshot and itself; the reverse mirrors
    assert.deepEqual(diffClasses(classes, 2, 2).counts, { added: 0, removed: 0, moved: 0 });
    const back = diffClasses(classes, 2, 0);
    assert.deepEqual([back.counts.added, back.counts.removed, back.counts.moved], [3, 4, 1]);
  });

  test('classRows: a row per jar, a move in the jar it left and in the jar it reached', () => {
    const rows = classRows(diffClasses(classes, 0, 2), classes);
    assert.deepEqual(rows.map((r) => [r.artifact, r.change, r.name, r.other]), [
      ['quarkus-amazon-lambda-http', 'added', 'io.quarkus.amazon.lambda.http.Handler', []],
      ['quarkus-amazon-lambda-rest', 'added', 'io.quarkus.amazon.lambda.http.Handler', []],
      ['quarkus-core', 'removed', 'io.quarkus.runtime.Gone', []],
      ['quarkus-core', 'removed', 'io.quarkus.runtime.Old', []],
      ['quarkus-core', 'added', 'Main', []],
      ['quarkus-junit-internal', 'moved-in', 'io.quarkus.test.QuarkusUnitTest', ['quarkus-junit5-internal']],
      ['quarkus-junit-internal', 'added', 'io.quarkus.test.QuarkusExtensionTest', []],
      ['quarkus-junit-internal', 'added', 'io.quarkus.test.QuarkusUnitTest$Builder', []],
      ['quarkus-junit5-internal', 'removed', 'io.quarkus.test.QuarkusUnitTest$1', []],
      ['quarkus-junit5-internal', 'moved-out', 'io.quarkus.test.QuarkusUnitTest', ['quarkus-junit-internal']],
    ]);
    assert.deepEqual(rows.filter((r) => r.nested).map((r) => r.name), ['io.quarkus.test.QuarkusUnitTest$Builder', 'io.quarkus.test.QuarkusUnitTest$1']);
    assert.deepEqual([...new Set(rows.flatMap((r) => r.tags))].sort(), [...CLASS_FILTER_TAGS].sort());
    // the chips and the text filter of the compare page
    assert.deepEqual(filterRows(rows, { tags: new Set(['moved']) }).map((r) => r.artifact), ['quarkus-junit-internal', 'quarkus-junit5-internal']);
    assert.deepEqual(filterRows(rows, { text: 'junit5 unittest' }).map((r) => r.change), ['moved-in', 'removed', 'moved-out']);
  });

  test('classHistory: runs of jars, moves, removals and the switches', () => {
    const unit = classHistory(classes, 'io.quarkus.test.QuarkusUnitTest', classes.versions, { versions });
    assert.deepEqual(unit.events.map((e) => [e.vi, e.type]), [[0, 'present'], [2, 'moved']]);
    assert.deepEqual([unit.events[1].from, unit.events[1].to], [[4], [3]]);
    assert.deepEqual(unit.runs.map((r) => [r.from, r.to, r.count, r.jars]), [[0, 1, 2, [4]], [2, 2, 1, [3]]]);
    assert.deepEqual(unit.ranges, [{ from: 0, to: 2 }]);
    assert.deepEqual([unit.present, unit.jars, unit.name, unit.widened], [true, [3], 'io.quarkus.test.QuarkusUnitTest', false]);

    const old = classHistory(classes, 'io.quarkus.runtime.Old', classes.versions);
    assert.deepEqual(old.events.map((e) => [e.vi, e.type]), [[0, 'present'], [2, 'removed']]);
    assert.deepEqual(old.events[1].from, [2]);
    assert.deepEqual(old.runs.map((r) => [r.from, r.to, r.jars, r.before]), [[0, 1, [2], false], [2, 2, [], false]]);
    assert.equal(old.present, false);

    const main = classHistory(classes, 'Main', classes.versions);
    assert.deepEqual(main.events.map((e) => [e.vi, e.type, e.jars]), [[2, 'introduced', [2]]]);
    assert.deepEqual(main.runs.map((r) => [r.from, r.to, r.jars, r.before]), [[0, 1, [], true], [2, 2, [2], false]]);

    // the switches hide snapshot 0: Gone is only there, so its history widens to every snapshot
    const vis = { visible: [false, true, true] };
    assert.deepEqual(classHistory(classes, 'io.quarkus.test.QuarkusUnitTest', classes.versions, { vis }).cvs, [1, 2]);
    assert.equal(classHistory(classes, 'io.quarkus.test.QuarkusUnitTest', classes.versions, { vis }).hiddenSnapshots, 1);
    const gone = classHistory(classes, 'io.quarkus.runtime.Gone', classes.versions, { vis, widen: [{ vis: { visible: [true, true, true] }, kind: 'old' }] });
    assert.deepEqual([gone.widened, gone.widenedBy, gone.cvs], [true, 'old', [0, 1, 2]]);
    assert.equal(classHistory(classes, 'io.quarkus.Nope', classes.versions), null);
  });

  test('one file per major line: which lines releases need, and the files merged as one', () => {
    const list = V('3.30.0', '3.31.0', '4.0.0.Alpha1', '4.0.0');
    assert.deepEqual([majorOf('3.40.1'), majorOf('4.0.0.Beta1'), majorOf(list[2])], [3, 4, 4]);
    assert.deepEqual(classLinesOf(list, [3, 0, 1]), [3, 4]);
    assert.deepEqual(classLinesOf(list, []), []);
    // each line's file starts its histories afresh, with jars and packages of its own
    const line3 = {
      versions: [0, 1], artifacts: ['quarkus-a', 'quarkus-b'], packages: ['p'],
      classes: [[0, 'Gap', [[0, 0]]], [0, 'Gone', [[0, 0], [1, null]]], [0, 'Kept', [[0, 0]]], [0, 'Late', [[1, 1]]], [0, 'Moved', [[0, 0]]]],
    };
    const line4 = {
      versions: [2, 3], artifacts: ['quarkus-a', 'quarkus-c'], packages: ['p', 'q'],
      classes: [[0, 'Gap', [[3, 0]]], [0, 'Kept', [[2, 0]]], [0, 'Moved', [[2, 1]]], [1, 'New', [[3, [0, 1]]]]],
    };
    const merged = mergeClasses([line4, line3]);
    assert.deepEqual(merged, {
      versions: [0, 1, 2, 3],
      artifacts: ['quarkus-a', 'quarkus-b', 'quarkus-c'],
      packages: ['p', 'q'],
      classes: [
        [0, 'Gap', [[0, 0], [2, null], [3, 0]]], // missing at the start of the 4.x line
        [0, 'Gone', [[0, 0], [1, null]]],
        [0, 'Kept', [[0, 0]]], // the same jar on both sides of the line change: no point
        [0, 'Late', [[1, 1], [2, null]]], // not in the 4.x line at all
        [0, 'Moved', [[0, 0], [2, 2]]],
        [1, 'New', [[3, [0, 2]]]],
      ],
    });
    const across = diffClasses(merged, 1, 2);
    assert.deepEqual([across.removed.map((x) => x.name), across.moved.map((x) => x.name)], [['p.Gap', 'p.Late'], ['p.Moved']]);
    // a single file is used as it is; none gives an empty one
    assert.equal(mergeClasses([line3]), line3);
    assert.deepEqual(mergeClasses([]), { versions: [], artifacts: [], packages: [], classes: [] });
  });

  test('search finds classes by binary name and by name in the package, not anonymous ones', () => {
    const index = buildSearchIndex({ classes, versions });
    const entries = index.entries.filter((e) => e.type === 'class');
    assert.equal(entries.length, 8, 'QuarkusUnitTest$1 is left out');
    const r = search(index, 'QuarkusUnitTest').items;
    assert.deepEqual(r.map((x) => [x.id, x.tier]), [['io.quarkus.test.QuarkusUnitTest', 0], ['io.quarkus.test.QuarkusUnitTest$Builder', 1]]);
    assert.deepEqual(r[0].jars, ['quarkus-junit-internal']);
    const old = search(index, 'io.quarkus.runtime.Old').items[0];
    assert.deepEqual([old.current, old.lastSeen, old.jars], [false, 1, ['quarkus-core']]);
    assert.deepEqual(search(index, 'lambda handler').items[0].jars, ['quarkus-amazon-lambda-http', 'quarkus-amazon-lambda-rest']);
    // at equal tiers, classes come after properties, extensions and artifacts
    const mixed = buildSearchIndex({ classes, versions, bom: { artifacts: [{ id: 'io.quarkus:application', h: [[2, '=']]}] } });
    assert.deepEqual(search(mixed, 'application').items.map((x) => x.type), ['artifact', 'class']);
  });
});

/* -------------------------------------------------------------------------- */
describe('search', () => {
  const config = {
    versions: [0, 1],
    states: [
      { n: 'quarkus.http.port', e: 'quarkus-vertx-http', t: 'int' },
      { n: 'quarkus.http.ssl-port', e: 'quarkus-vertx-http', t: 'int' },
      { n: 'quarkus.http.port-range', e: 'quarkus-vertx-http', t: 'string' },
      { n: 'quarkus.management.http-port', e: 'quarkus-vertx-http', t: 'int' },
      { n: 'quarkus.datasource.jdbc.url', a: ['quarkus.datasource."datasource-name".jdbc.url'], e: 'quarkus-agroal', t: 'string' },
      { n: 'quarkus.http.export', e: 'quarkus-vertx-http', t: 'string' },
    ],
    props: [
      { k: 'quarkus.datasource.jdbc.url', h: [[0, 4]] },
      { k: 'quarkus.http.export', h: [[0, 5]] },
      { k: 'quarkus.http.port', h: [[0, 0]] },
      { k: 'quarkus.http.port-range', h: [[0, 2], [1, null]] },
      { k: 'quarkus.http.ssl-port', h: [[0, 1]] },
      { k: 'quarkus.management.http-port', h: [[0, 3]] },
    ],
  };
  const versions = V('3.0.0', '3.1.0');
  const bom = { artifacts: [{ id: 'io.vertx:vertx-core', h: [[0, '4.4.1']] }, { id: 'io.quarkus:quarkus-vertx-http', h: [[0, '=']] }, { id: 'io.netty:netty-common', h: [[0, '4.1.1'], [1, null]] }] };
  const extensions = { states: [{ n: 'Vert.x HTTP', s: 'stable' }], exts: [{ id: 'quarkus-vertx-http', h: [[0, 0]] }] };
  const index = buildSearchIndex({ config, bom, extensions, versions });

  test('matchTier', () => {
    assert.equal(matchTier('quarkus.http.port', 'quarkus.http.port'), 0);
    assert.equal(matchTier('quarkus.http.port', 'quarkus.http'), 1);
    assert.equal(matchTier('quarkus.http.port', 'port'), 2);
    assert.equal(matchTier('quarkus.http.ssl-port', 'port'), 2);
    assert.equal(matchTier('quarkus.http.export', 'port'), 3);
    assert.equal(matchTier('quarkus.http.port', 'nope'), -1);
    assert.equal(matchTier('', 'x'), -1);
  });

  test('index covers properties, artifacts and extensions', () => {
    const types = new Set(index.entries.map((e) => e.type));
    assert.deepEqual([...types].sort(), ['artifact', 'extension', 'property']);
    const art = index.entries.find((e) => e.id === 'io.quarkus:quarkus-vertx-http');
    assert.equal(art.value, '3.1.0', '"=" resolves to the newest release');
    assert.equal(index.entries.find((e) => e.id === 'io.netty:netty-common').current, false);
    const removed = index.entries.find((e) => e.id === 'quarkus.http.port-range');
    assert.equal(removed.current, false);
    assert.equal(removed.lastSeen, 0);
  });

  test('ranking: exact > prefix > segment > substring', () => {
    const r = search(index, 'port').items.map((x) => x.id);
    // segment matches (current first, then shorter), then substring
    assert.deepEqual(r, ['quarkus.http.port', 'quarkus.http.ssl-port', 'quarkus.management.http-port', 'quarkus.http.port-range', 'quarkus.http.export']);
    assert.equal(search(index, 'quarkus.http.port').items[0].id, 'quarkus.http.port');
    assert.equal(search(index, 'quarkus.http.port').items[0].tier, 0);
    assert.equal(search(index, 'quarkus.http.p').items[0].tier, 1);
    assert.equal(search(index, 'http.port').items[0].id, 'quarkus.http.port', 'quarkus.-less name counts as exact');
  });

  test('aliases, env vars, artifacts, extensions, multiple tokens, limits', () => {
    assert.equal(search(index, 'quarkus.datasource."datasource-name".jdbc.url').items[0].id, 'quarkus.datasource.jdbc.url');
    assert.equal(search(index, 'QUARKUS_HTTP_PORT').items[0].id, 'quarkus.http.port');
    assert.equal(search(index, 'vertx-core').items[0].id, 'io.vertx:vertx-core');
    const vh = search(index, 'quarkus-vertx-http').items;
    assert.equal(vh[0].type, 'extension');
    assert.equal(vh[1].type, 'artifact');
    assert.equal(search(index, 'Vert.x HTTP').items[0].type, 'extension');
    assert.deepEqual(search(index, 'http ssl').items.map((x) => x.id), ['quarkus.http.ssl-port']);
    assert.deepEqual(search(index, 'port', { types: ['artifact'] }).items, []);
    const limited = search(index, 'quarkus', { limit: 2 });
    assert.equal(limited.items.length, 2);
    assert.ok(limited.total > 2);
    assert.equal(search(index, '   ').total, 0);
    assert.equal(search(null, 'x').total, 0);
  });

  test('index builds with missing files', () => {
    assert.equal(buildSearchIndex({ bom }).entries.length, 3);
    assert.equal(buildSearchIndex({}).entries.length, 0);
  });
});

/* -------------------------------------------------------------------------- */
describe('timelines', () => {
  const config = {
    versions: [1, 4, 8, 10],
    states: [
      { n: 'quarkus.a', e: 'x', t: 'int', d: '1', p: 'R' },
      { n: 'quarkus.a', e: 'x', t: 'int', d: '2', p: 'R' },
      { n: 'quarkus.a', e: 'x', t: 'int', d: '2', p: 'R', x: '3.9' },
      { n: 'quarkus."renamed"', e: 'x', t: 'int', d: '2', p: 'R', x: '3.9' },
    ],
    props: [
      { k: 'quarkus.a', h: [[1, 0], [4, 1], [8, null], [10, 2]] },
      { k: 'quarkus.b', h: [[4, 0], [8, 3]] },
    ],
  };
  test('propertyHistory: present, changed, removed, reintroduced, ranges', () => {
    const h = propertyHistory(config, 'quarkus.a');
    assert.deepEqual(h.events.map((e) => [e.vi, e.type, e.kinds ?? null]), [
      [1, 'present', null],
      [4, 'changed', ['default']],
      [8, 'removed', null],
      [10, 'reintroduced', null],
    ]);
    assert.deepEqual(h.ranges, [{ from: 1, to: 4 }, { from: 10, to: 10 }]);
    assert.equal(h.present, true);
    assert.equal(h.latest.x, '3.9');
    assert.equal(propertyHistory(config, 'nope'), null);
  });
  test('propertyHistory: introduced later; state changes without kinds are skipped', () => {
    const h = propertyHistory(config, config.props[1]);
    assert.deepEqual(h.events.map((e) => [e.vi, e.type]), [[4, 'introduced'], [8, 'changed']]);
    assert.deepEqual(h.events[1].kinds, ['default', 'deprecated']);
  });
  test('extensionHistory', () => {
    const extensions = {
      states: [{ n: 'RESTEasy Reactive', s: 'stable' }, { r: 'io.quarkus:quarkus-rest' }],
      exts: [{ id: 'quarkus-resteasy-reactive', h: [[1, 0], [8, 1], [10, null]] }],
    };
    const h = extensionHistory(extensions, 'quarkus-resteasy-reactive', [1, 4, 8, 10]);
    assert.deepEqual(h.events.map((e) => [e.vi, e.type, e.kinds ?? null]), [[1, 'present', null], [8, 'changed', ['relocated']], [10, 'removed', null]]);
    assert.equal(h.present, false);
    assert.equal(h.latest.r, 'io.quarkus:quarkus-rest');
    assert.equal(extensionHistory(extensions, 'none', [1]), null);
  });
  test('artifactHistory: ranges, "=" ranges and change kinds', () => {
    const versions = V('3.0.0', '3.1.0', '3.2.0', '3.3.0', '3.4.0', '3.5.0');
    const bom = {
      artifacts: [
        { id: 'io.vertx:vertx-core', h: [[1, '4.4.1'], [3, '4.5.0'], [4, null], [5, '5.0.0']] },
        { id: 'io.quarkus:quarkus-core', h: [[0, '=']] },
        { id: 'io.quarkus:quarkus-x', h: [[0, '='], [2, '3.1.5']] },
      ],
    };
    const h = artifactHistory(bom, 'io.vertx:vertx-core', versions);
    assert.deepEqual(h.ranges.map((r) => [r.from, r.to, r.raw ?? null, r.kind ?? null]), [
      [0, 0, null, null],
      [1, 2, '4.4.1', 'added'],
      [3, 3, '4.5.0', 'minor'],
      [4, 4, null, 'removed'],
      [5, 5, '5.0.0', 'added'],
    ]);
    assert.equal(h.ranges[0].before, true);
    assert.equal(h.firstManaged, 1);
    assert.equal(h.lastManaged, 5);
    assert.deepEqual(h.current, { raw: '5.0.0', managed: true, follows: false, value: '5.0.0' });
    const q = artifactHistory(bom, 'io.quarkus:quarkus-core', versions);
    assert.equal(q.ranges.length, 1);
    assert.equal(q.ranges[0].follows, true);
    assert.equal(q.current.value, '3.5.0');
    const x = artifactHistory(bom, 'io.quarkus:quarkus-x', versions);
    assert.equal(x.ranges[1].kind, 'patch', '"=" resolves to 3.1.0 at the end of its range; 3.1.0 → 3.1.5 is a patch');
  });
  test('a version kept as written (${...}) is no downgrade and no upgrade', () => {
    // netty-tcnative in the published BOMs: 2.0.40.Final, ${tcnative.version} from 2.2.4.Final to 2.4.2.Final, then
    // 2.0.46.Final; maven-artifact-transfer and apache-client in 2.6.0.CR1; el-impl in the early releases
    assert.equal(bomChangeKind('2.0.40.Final', '${tcnative.version}'), 'other');
    assert.equal(bomChangeKind('${tcnative.version}', '2.0.46.Final'), 'other');
    assert.equal(bomChangeKind('${tcnative.version}', '${tcnative.version}'), 'same');
    assert.equal(bomChangeKind('0.10.0', '${maven-artifact-transfer.version}'), 'other');
    assert.equal(bomChangeKind('2.17.78', '${awssdk.version}'), 'other');
    assert.equal(bomChangeKind('${el-impl.version}', '3.0.1-b08'), 'other');
    const versions = V('2.2.3.Final', '2.2.4.Final', '2.4.2.Final', '2.5.0.Final');
    const bom = {
      artifacts: [
        { id: 'io.netty:netty-tcnative', h: [[0, '2.0.40.Final'], [1, '${tcnative.version}'], [3, '2.0.46.Final']] },
      ],
    };
    const h = artifactHistory(bom, 'io.netty:netty-tcnative', versions);
    assert.deepEqual(h.ranges.map((r) => [r.from, r.to, r.raw, r.kind ?? null]), [
      [0, 0, '2.0.40.Final', null],
      [1, 2, '${tcnative.version}', 'other'],
      [3, 3, '2.0.46.Final', 'other'],
    ]);
    const d = diffBom(bom, versions, 0, 1);
    assert.deepEqual(d.rows.map((r) => [r.from, r.to, r.kind]), [['2.0.40.Final', '${tcnative.version}', 'other']]);
    assert.equal(d.counts.downgrade, 0);
    assert.equal(diffBom(bom, versions, 2, 3).counts.other, 1);
  });
});

/* -------------------------------------------------------------------------- */
describe('release selection helpers', () => {
  const vj = load('versions');
  const list = vj.versions;
  const idx = (v) => findVersionIndex(list, v);

  test('newestFinalIndex and newestInStream', () => {
    assert.equal(list[newestFinalIndex(list)].v, '3.40.1.1');
    assert.equal(list[newestInStream(list, '3.20')].v, '3.20.6');
    assert.equal(list[newestInStream(list, '4.0')].v, '4.0.0.Beta1', 'falls back to a pre-release when the stream has no final');
    assert.equal(newestInStream(list, '9.9'), -1);
  });
  test('ltsStreamsPresent', () => {
    assert.deepEqual(ltsStreamsPresent(vj), ['3.2', '3.8', '3.15', '3.20', '3.27', '3.33']);
    assert.deepEqual(ltsStreamsPresent({ lts: ['3.2', '9.9'], versions: [{ v: '3.2.0' }] }), ['3.2']);
  });
  test('defaultPair: latest patch of the previous LTS → newest final', () => {
    const p = defaultPair(vj);
    assert.equal(list[p.from].v, '3.33.3');
    assert.equal(list[p.to].v, '3.40.1.1');
    const noLts = { lts: [], versions: [{ v: '3.0.0' }, { v: '3.1.0' }, { v: '3.1.2' }] };
    assert.deepEqual(defaultPair(noLts), { from: 0, to: 2 });
    assert.equal(defaultPair({ versions: [] }), null);
  });
  test('quickPicks: LTS chain, latest LTS → newest final, newest final → pre-release', () => {
    const picks = quickPicks(vj).map((p) => [p.kind, list[p.from].v, list[p.to].v]);
    assert.deepEqual(picks, [
      ['lts', '3.2.12.Final', '3.8.6.1'],
      ['lts', '3.8.6.1', '3.15.7'],
      ['lts', '3.15.7', '3.20.6'],
      ['lts', '3.20.6', '3.27.4'],
      ['lts', '3.27.4', '3.33.3'],
      ['latest', '3.33.3', '3.40.1.1'],
      ['pre', '3.40.1.1', '4.0.0.Beta1'],
    ]);
  });
  test('versionNeighbours: previous patch, minor, LTS', () => {
    const n = versionNeighbours(vj, idx('3.20.6'));
    assert.equal(list[n.previousPatch].v, '3.20.3');
    assert.equal(list[n.previousMinor].v, '3.15.7');
    assert.equal(list[n.previousLts].v, '3.15.7');
    assert.equal(list[n.older].v, '3.20.3');
    assert.equal(list[n.newer].v, '3.27.0.CR1', 'without a visibility filter every release counts');
    const first = versionNeighbours(vj, 0);
    assert.deepEqual([first.previousPatch, first.previousMinor, first.previousLts, first.older], [-1, -1, -1, -1]);
    const beta = versionNeighbours(vj, idx('4.0.0.Beta1'));
    assert.equal(list[beta.previousMinor].v, '3.40.1.1');
    assert.equal(list[beta.previousLts].v, '3.33.3');
    assert.equal(beta.newer, -1);
    assert.equal(list[versionNeighbours(vj, idx('3.9.0')).previousLts].v, '3.8.6.1');
  });

  test('versionNeighbours: pre-releases are not patches; hidden ones are skipped', () => {
    const off = releaseVisibility(list, { showPre: false });
    const on = releaseVisibility(list, { showPre: true });
    // 3.27.0 is the stream's first final: no previous patch; its CR is offered only while visible
    assert.equal(versionNeighbours(vj, idx('3.27.0'), { vis: off }).previousPatch, -1);
    assert.equal(versionNeighbours(vj, idx('3.27.0'), { vis: off }).previousPre, -1);
    assert.equal(list[versionNeighbours(vj, idx('3.27.0'), { vis: on }).previousPre].v, '3.27.0.CR1');
    // a later patch compares with the previous final, never with the CR
    const p = versionNeighbours(vj, idx('3.27.4'), { vis: on });
    assert.equal(list[p.previousPatch].v, '3.27.0');
    assert.equal(p.previousPre, -1);
    // upcoming pre-releases stay visible: Beta1's previous pre-release is Alpha1 even with the toggle off
    assert.equal(list[versionNeighbours(vj, idx('4.0.0.Beta1'), { vis: off }).previousPre].v, '4.0.0.Alpha1');
    assert.equal(versionNeighbours(vj, idx('4.0.0.Beta1'), { vis: off }).previousPatch, -1);
    // older / newer skip hidden pre-releases
    assert.equal(list[versionNeighbours(vj, idx('3.20.6'), { vis: off }).newer].v, '3.27.0');
    assert.equal(list[versionNeighbours(vj, idx('3.27.0'), { vis: off }).older].v, '3.20.6');
    assert.equal(versionNeighbours(vj, idx('3.0.0.Final'), { vis: off }).older, -1);
    assert.equal(list[versionNeighbours(vj, idx('3.0.0.Final'), { vis: on }).older].v, '3.0.0.Beta1');
    // a hidden pre-release opened directly still gets visible neighbours
    const cr = versionNeighbours(vj, idx('3.33.0.CR1'), { vis: off });
    assert.equal(list[cr.older].v, '3.27.4');
    assert.equal(list[cr.newer].v, '3.33.0');
    assert.equal(list[cr.previousMinor].v, '3.27.4');
  });
});

/* -------------------------------------------------------------------------- */
describe('formatting', () => {
  test('formatDate', () => {
    assert.equal(formatDate('2025-03-26'), '26 Mar 2025');
    assert.equal(formatDate('2026-10-27T12:00:00Z'), '27 Oct 2026');
    assert.equal(formatDate('soon'), 'soon');
    assert.equal(formatDate(undefined), '');
  });
  test('spanLabel', () => {
    assert.equal(spanLabel('2025-03-26', '2025-03-26'), 'same day');
    assert.equal(spanLabel('2025-03-26', '2025-04-02'), '7 days');
    assert.equal(spanLabel('2025-09-10', '2026-03-18'), '6 months');
    assert.equal(spanLabel('2026-03-18', '2025-09-10'), '6 months', 'order-insensitive');
    assert.equal(spanLabel('2023-04-26', '2026-10-19'), '3 years 5 months');
    assert.equal(spanLabel('2024-01-01', '2025-01-01'), '1 year');
    assert.equal(spanLabel('x', '2025-01-01'), '');
  });
});

/* -------------------------------------------------------------------------- */
describe('sample dataset (dev/sample) follows the site data format', () => {
  const vj = load('versions');
  const bom = load('bom');
  const config = load('config');
  const extensions = load('extensions');
  const classFiles = [load('classes-3'), load('classes-4')];
  const classes = mergeClasses(classFiles);
  const list = vj.versions;

  test('versions.json', () => {
    assert.ok(list.length >= 20);
    assertAscending(list.map((x) => x.v));
    for (const x of list) {
      assert.equal(x.stream, stream(x.v));
      assert.equal(x.pre, !isFinal(x.v));
      assert.equal(x.lts, vj.lts.includes(x.stream));
      assert.equal(typeof x.config, 'boolean');
      assert.equal(typeof x.ext, 'boolean');
      assert.equal(typeof x.classes, 'boolean');
      assert.match(x.date, /^\d{4}-\d{2}-\d{2}$/);
      if (x.config) assert.ok(['model', 'bytecode'].includes(x.configSource));
      if (x.config) assert.ok(x.ext, `${x.v}: a config snapshot comes with extensions`);
    }
    assert.deepEqual(configVersionsOf(list), config.versions);
    assert.deepEqual(extensionVersionsOf(list), config.versions, 'the sample has extensions exactly at its config snapshots');
  });

  const checkHistory = (h, max, label) => {
    assert.ok(Array.isArray(h) && h.length, `${label}: non-empty h`);
    for (let i = 0; i < h.length; i++) {
      assert.ok(h[i][0] >= 0 && h[i][0] < list.length, `${label}: vi in range`);
      if (i) assert.ok(h[i][0] > h[i - 1][0], `${label}: ascending`);
      if (i) assert.notDeepEqual(h[i][1], h[i - 1][1], `${label}: change points only`);
      if (max != null && h[i][1] != null) assert.ok(h[i][1] >= 0 && h[i][1] < max, `${label}: state index`);
    }
  };

  test('bom.json', () => {
    const ids = bom.artifacts.map((a) => a.id);
    assert.deepEqual(ids, [...ids].sort());
    for (const a of bom.artifacts) checkHistory(a.h, null, a.id);
    const quarkus = bom.artifacts.filter((a) => a.h.some(([, v]) => v === '='));
    assert.ok(quarkus.length >= 10);
    const kinds = new Set();
    for (let i = 1; i < list.length; i++) for (const r of diffBom(bom, list, i - 1, i).rows) kinds.add(r.kind);
    for (const k of ['major', 'minor', 'patch', 'other', 'downgrade', 'added', 'removed']) assert.ok(kinds.has(k), `sample exercises ${k}`);
  });

  test('config.json', () => {
    const keys = config.props.map((p) => p.k);
    assert.deepEqual(keys, [...keys].sort());
    assert.equal(new Set(keys).size, keys.length);
    for (const p of config.props) {
      checkHistory(p.h, config.states.length, p.k);
      for (const [vi] of p.h) assert.ok(config.versions.includes(vi), `${p.k}: points only at config versions`);
      for (const [, si] of p.h) if (si != null) assert.equal(canonicalKey(config.states[si].n), p.k, `${p.k}: key = canonical(name)`);
    }
    const seen = new Set(config.states.map((s) => JSON.stringify(s)));
    assert.equal(seen.size, config.states.length, 'states are interned');
    for (const s of config.states) {
      for (const f of Object.keys(s)) assert.ok(['n', 'a', 'e', 't', 'l', 'o', 'm', 'd', 'p', 'x', 'en'].includes(f), `field ${f}`);
      assert.ok(['B', 'F', 'R', 'S'].includes(s.p));
    }
    const kinds = new Set();
    let replacement = false;
    let aliasHit = false;
    const cvs = config.versions;
    for (let i = 0; i < cvs.length; i++) {
      for (let j = i + 1; j < cvs.length; j++) {
        const d = diffConfig(config, cvs[i], cvs[j]);
        if (d.added.length) kinds.add('added');
        if (d.removed.length) kinds.add('removed');
        for (const c of d.changed) c.kinds.forEach((k) => kinds.add(k));
        if (d.removed.some((r) => r.replacement)) replacement = true;
        if (config.props.some((p) => stateAt(p.h, cvs[i]) != null && stateAt(p.h, cvs[j]) == null && !d.removed.some((r) => r.k === p.k))) aliasHit = true;
      }
    }
    for (const k of ['added', 'removed', 'default', 'type', 'phase', 'deprecated', 'undeprecated', 'enum', 'extension']) assert.ok(kinds.has(k), `sample exercises ${k}`);
    assert.ok(replacement, 'sample has a rename candidate');
    assert.ok(aliasHit, 'sample has a key that disappears but stays reachable through an alias');
    assert.ok(config.states.some((s) => s.x === ''), 'sample has x: "" (deprecated without since)');
    for (const k of Object.keys(config.desc)) assert.ok(keys.includes(k));
  });

  test('extensions.json', () => {
    const ids = extensions.exts.map((x) => x.id);
    assert.deepEqual(ids, [...ids].sort());
    const evs = extensionVersionsOf(list);
    for (const x of extensions.exts) {
      checkHistory(x.h, extensions.states.length, x.id);
      for (const [vi] of x.h) assert.ok(evs.includes(vi), `${x.id}: points only at extension versions`);
    }
    const all = diffExtensions(extensions, config.versions[0], config.versions[config.versions.length - 1]);
    const span = diffExtensions(extensions, config.versions[2], config.versions[3]);
    assert.ok(span.changed.some((c) => c.id === 'quarkus-resteasy-reactive' && c.kinds.includes('relocated') && c.b.r === 'io.quarkus:quarkus-rest'));
    const kinds = new Set();
    const cvs = config.versions;
    for (let i = 1; i < cvs.length; i++) for (const c of diffExtensions(extensions, cvs[i - 1], cvs[i]).changed) c.kinds.forEach((k) => kinds.add(k));
    for (const k of ['relocated', 'status', 'renamed']) assert.ok(kinds.has(k), `sample exercises ${k}`);
    assert.ok(all.added.length && all.removed.length);
  });

  test('classes-3.json and classes-4.json', () => {
    assert.deepEqual(classLinesOf(list, classVersionsOf(list)), [3, 4], 'one file per line with classes snapshots');
    for (const [k, file] of classFiles.entries()) {
      assert.ok(file.versions.length && file.versions.every((i) => majorOf(list[i]) === k + 3), 'a file holds its line only');
      for (const row of file.classes) assert.ok(row[2][0][1] != null && file.versions.includes(row[2][0][0]), `${row[1]}: starts in its line`);
    }
    assert.deepEqual(classVersionsOf(list), classes.versions);
    for (const names of [classes.artifacts, classes.packages]) {
      assert.deepEqual(names, [...names].sort());
      assert.equal(new Set(names).size, names.length);
    }
    const keys = classes.classes.map(([p, n]) => [p, n]);
    assert.deepEqual(keys, [...keys].sort((x, y) => x[0] - y[0] || (x[1] < y[1] ? -1 : x[1] > y[1] ? 1 : 0)), 'sorted by package, then name');
    for (const row of classes.classes) {
      const name = className(classes, row);
      assert.ok(row[0] >= 0 && row[0] < classes.packages.length, `${name}: package index`);
      assert.ok(row[2][0][1] != null, `${name}: nothing recorded before the first presence`);
      for (let i = 0; i < row[2].length; i++) {
        const [vi, value] = row[2][i];
        assert.ok(classes.versions.includes(vi), `${name}: points only at classes versions`);
        if (i) assert.ok(vi > row[2][i - 1][0] && JSON.stringify(value) !== JSON.stringify(row[2][i - 1][1]), `${name}: change points only`);
        for (const j of jarsOf(value)) assert.ok(j >= 0 && j < classes.artifacts.length, `${name}: artifact index`);
        if (Array.isArray(value)) assert.ok(value.length > 1 && value.every((j, k) => !k || j > value[k - 1]), `${name}: several jars, ascending`);
      }
    }
    const kinds = new Set();
    const lvs = classes.versions;
    for (let i = 1; i < lvs.length; i++) {
      const d = diffClasses(classes, lvs[i - 1], lvs[i]);
      for (const k of ['added', 'removed', 'moved']) if (d[k].length) kinds.add(k);
      if (d.moved.some((x) => isNestedClass(x.name))) kinds.add('nested moved');
    }
    for (const k of ['added', 'removed', 'moved', 'nested moved']) assert.ok(kinds.has(k), `sample exercises ${k}`);
    assert.ok(classes.classes.some((row) => row[2].some(([, v]) => Array.isArray(v))), 'sample has a class in two jars');
  });

  test('pre-releases cover Alpha, Beta and CR; with and without config; with finals and upcoming', () => {
    const vis = releaseVisibility(list);
    const pres = list.map((x, i) => ({ x, i })).filter(({ x }) => x.pre);
    assert.deepEqual([...new Set(pres.map(({ x }) => preQualifier(x)))].sort(), ['Alpha', 'Beta', 'CR']);
    const combos = new Set(pres.map(({ x, i }) => `${vis.upcoming[i] ? 'upcoming' : 'finals'}/${x.config ? 'config' : 'none'}`));
    assert.deepEqual([...combos].sort(), ['finals/config', 'finals/none', 'upcoming/config', 'upcoming/none']);
    assert.ok(list[0].pre, 'the dataset starts with a hidden pre-release');
  });

  test('the whole pipeline runs on the sample for every pair of releases', () => {
    const index = buildSearchIndex({ config, bom, extensions, classes, versions: list });
    assert.ok(search(index, 'datasource').total > 5);
    assert.equal(search(index, 'QuarkusUnitTest').items[0].id, 'io.quarkus.test.QuarkusUnitTest');
    let skipped = 0;
    for (let a = 0; a < list.length; a += 3) {
      for (let b = 0; b < list.length; b += 4) {
        diffBom(bom, list, a, b);
        headlineLibraries(bom, list, a, b);
        const ca = configVersionFor(a, list, config.versions);
        const cb = configVersionFor(b, list, config.versions);
        for (const [vi, c] of [[a, ca], [b, cb]]) {
          if (list[vi].pre) assert.ok(c === null || c === vi, `${list[vi].v} uses only its own snapshot`);
          else assert.notEqual(c, null, `${list[vi].v} has config data`);
        }
        // extensions use their own snapshots; in the sample (as in every 3.x release) they are the config snapshots
        assert.equal(extensionVersionFor(a, list), ca);
        assert.equal(extensionVersionFor(b, list), cb);
        if (ca == null || cb == null) {
          skipped++;
          continue;
        }
        configRows(diffConfig(config, ca, cb), config.desc);
        diffExtensions(extensions, ca, cb);
        // classes too have snapshots of their own, here at the config snapshots
        assert.equal(classesVersionFor(a, list), ca);
        classRows(diffClasses(classes, ca, cb), classes);
      }
    }
    assert.ok(skipped > 0, 'some pairs involve a pre-release without a snapshot');
    for (const showPre of [false, true]) {
      const vis = releaseVisibility(list, { showPre });
      for (const p of config.props) assert.ok(propertyHistory(config, p, { vis }).events.length >= 1, p.k);
      for (const art of bom.artifacts) assert.ok(artifactHistory(bom, art.id, list, { vis }).ranges.length >= 1, art.id);
      for (const x of extensions.exts) assert.ok(extensionHistory(extensions, x.id, extensionVersionsOf(list), { vis }).events.length >= 1, x.id);
      for (const row of classes.classes) assert.ok(classHistory(classes, row, classVersionsOf(list), { vis, versions: list }).events.length >= 1, row[1]);
    }
    for (const p of config.props) assert.ok(propertyHistory(config, p).events.length >= 1);
    for (const art of bom.artifacts) assert.ok(artifactHistory(bom, art.id, list).ranges.length >= 1);
  });
});

/* -------------------------------------------------------------------------- */
describe('pre-releases: visibility', () => {
  const vj = load('versions');
  const list = vj.versions;
  const names = (vis, pred) => list.filter((_, i) => pred(i, vis)).map((x) => x.v);

  test('isPreRelease and preQualifier', () => {
    assert.equal(isPreRelease({ v: '3.27.0.CR1', pre: true }), true);
    assert.equal(isPreRelease({ v: '3.27.0', pre: false }), false);
    assert.equal(isPreRelease({ v: '3.27.0.CR1' }), true, 'without the flag: not final by its version');
    assert.equal(isPreRelease('4.0.0.Alpha2'), true);
    assert.equal(isPreRelease('3.2.12.Final'), false);
    assert.equal(isPreRelease(null), false);
    assert.equal(preQualifier('3.0.0.Alpha1'), 'Alpha');
    assert.equal(preQualifier({ v: '4.0.0.Beta1', pre: true }), 'Beta');
    assert.equal(preQualifier('3.27.0.CR1'), 'CR');
    assert.equal(preQualifier('3.27.0-cr2'), 'CR');
    assert.equal(preQualifier('3.27.0'), null);
    assert.equal(preQualifier('3.27.0.Final'), null);
  });

  test('off: pre-releases hidden except upcoming ones (their stream has no final yet)', () => {
    const vis = releaseVisibility(list, { showPre: false });
    assert.deepEqual(names(vis, (i, x) => !x.visible[i]), ['3.0.0.Alpha1', '3.0.0.Beta1', '3.27.0.CR1', '3.33.0.CR1']);
    assert.deepEqual(names(vis, (i, x) => x.upcoming[i]), ['4.0.0.Alpha1', '4.0.0.Beta1']);
    assert.deepEqual([...vis.upcomingStreams], ['4.0']);
    assert.equal(vis.hideable, 4);
    assert.equal(vis.hidden, 4);
    assert.equal(vis.indices.length, list.length - 4);
    assert.deepEqual(vis.indices, [...vis.indices].sort((a, b) => a - b));
    assert.ok(vis.indices.every((i) => vis.visible[i]));
    assert.deepEqual(releaseVisibility(list), vis, 'off by default');
  });

  test('on: every release is visible', () => {
    const vis = releaseVisibility(list, { showPre: true });
    assert.equal(vis.indices.length, list.length);
    assert.equal(vis.hidden, 0);
    assert.equal(vis.hideable, 4);
    assert.deepEqual(names(vis, (i, x) => x.upcoming[i]), ['4.0.0.Alpha1', '4.0.0.Beta1']);
  });

  test('upcoming depends on the whole stream, not on the order of the list', () => {
    const vs = V('3.9.0.CR1', '3.9.0', '3.10.0.CR1', '4.0.0.Alpha1', '4.0.0.CR1');
    const off = releaseVisibility(vs);
    assert.deepEqual(off.visible, [false, true, true, true, true]);
    assert.deepEqual(off.upcoming, [false, false, true, true, true]);
    const withFinal = releaseVisibility([...vs, ...V('4.0.0')]);
    assert.deepEqual(withFinal.upcoming.slice(3, 5), [false, false], 'once 4.0.0 is out its pre-releases are no longer upcoming');
    assert.equal(withFinal.hidden, 3);
    assert.deepEqual(releaseVisibility([]).indices, []);
  });

  test('old releases: the streams before oldBefore are hidden unless showOld, pre-releases or not', () => {
    const vs = V('3.2.0.CR1', '3.2.0', '3.2.12', '3.7.4', '3.8.0.CR1', '3.8.0', '3.10.0', '4.0.0.Alpha1');
    const name = (vis) => vs.filter((x, i) => vis.visible[i]).map((x) => x.v);
    const both = releaseVisibility(vs, { oldBefore: '3.8', showOld: false });
    assert.deepEqual(both.old, [true, true, true, true, false, false, false, false]);
    assert.deepEqual(name(both), ['3.8.0', '3.10.0', '4.0.0.Alpha1'], '3.10 is newer than 3.8, 3.8.0.CR1 is a hidden pre-release');
    assert.equal(both.hidden, 5);
    // each switch counts what it controls given the other one
    assert.equal(both.preHideable, 1, 'only 3.8.0.CR1: 3.2.0.CR1 stays hidden as an old release');
    assert.equal(both.oldHideable, 3, 'the old finals; their pre-release stays hidden as a pre-release');
    const oldOn = releaseVisibility(vs, { oldBefore: '3.8', showOld: true });
    assert.deepEqual(name(oldOn), ['3.2.0', '3.2.12', '3.7.4', '3.8.0', '3.10.0', '4.0.0.Alpha1']);
    assert.equal(oldOn.preHideable, 2);
    const preOn = releaseVisibility(vs, { oldBefore: '3.8', showOld: false, showPre: true });
    assert.equal(preOn.oldHideable, 4);
    assert.deepEqual(name(preOn), ['3.8.0.CR1', '3.8.0', '3.10.0', '4.0.0.Alpha1']);
    // without oldBefore nothing is old, whatever showOld says
    assert.ok(releaseVisibility(vs, { showOld: false }).old.every((o) => !o));
    assert.equal(releaseVisibility(vs, { showOld: false }).oldHideable, 0);
  });

  test('visibleOnly filters config versions', () => {
    const config = load('config');
    const off = releaseVisibility(list);
    const on = releaseVisibility(list, { showPre: true });
    const cv = (vis) => visibleOnly(config.versions, vis).map((i) => list[i].v);
    assert.ok(!cv(off).includes('3.27.0.CR1'));
    assert.ok(cv(off).includes('4.0.0.Beta1'), 'upcoming snapshots stay');
    assert.deepEqual(cv(on), config.versions.map((i) => list[i].v));
    assert.deepEqual(visibleOnly(config.versions), config.versions);
  });
});

/* -------------------------------------------------------------------------- */
describe('pre-releases: config snapshot selection', () => {
  const vj = load('versions');
  const list = vj.versions;
  const config = load('config');
  const idx = (v) => findVersionIndex(list, v);
  const cfg = (v, vs = list, cvs = config.versions) => {
    const c = configVersionFor(findVersionIndex(vs, v), vs, cvs);
    return c == null ? null : vs[c].v;
  };

  test('a pre-release uses only its own snapshot', () => {
    assert.equal(cfg('3.27.0.CR1'), '3.27.0.CR1');
    assert.equal(cfg('4.0.0.Beta1'), '4.0.0.Beta1');
  });

  test('a pre-release without a snapshot has none: it never borrows one, not even from its stream', () => {
    assert.equal(cfg('3.33.0.CR1'), null, 'not the 3.33.3 snapshot of its stream');
    assert.equal(cfg('3.0.0.Beta1'), null, 'not the 3.0.4 snapshot of its stream');
    assert.equal(cfg('3.0.0.Alpha1'), null, 'not an earlier snapshot either');
    assert.equal(cfg('4.0.0.Alpha1'), null, 'not the 4.0.0.Beta1 pre-release snapshot of its stream');
    assert.equal(configVersionFor(idx('3.33.0.CR1'), list), null, 'same with the versions.json config flags');
  });

  test('finals keep the stream rule and prefer final snapshots of their stream', () => {
    assert.equal(cfg('3.27.0'), '3.27.4', 'the stream final snapshot, not the CR1 snapshot just before it');
    assert.equal(cfg('3.27.4'), '3.27.4');
    assert.equal(cfg('3.33.0'), '3.33.3');
    assert.equal(cfg('3.0.0.Final'), '3.0.4.Final');
    assert.equal(cfg('3.40.0'), '3.40.1.1');
  });

  test('a final whose stream only has a pre-release snapshot uses it; without any, the latest ≤ vi', () => {
    const vs = [
      { v: '3.40.1', pre: false, config: true },
      { v: '4.0.0.CR1', pre: true, config: true },
      { v: '4.0.0', pre: false },
      { v: '4.1.0', pre: false },
    ];
    const flags = configVersionsOf(vs);
    assert.deepEqual(flags, [0, 1]);
    assert.equal(cfg('4.0.0', vs, flags), '4.0.0.CR1');
    assert.equal(cfg('4.1.0', vs, flags), '4.0.0.CR1', 'today\'s fallback: the latest config version ≤ vi');
    assert.equal(cfg('4.0.0.CR1', vs, flags), '4.0.0.CR1');
    assert.equal(cfg('3.40.1', vs, flags), '3.40.1');
  });
});

/* -------------------------------------------------------------------------- */
describe('pre-releases: timelines over the visible snapshots', () => {
  const vj = load('versions');
  const list = vj.versions;
  const config = load('config');
  const bom = load('bom');
  const extensions = load('extensions');
  const off = releaseVisibility(list, { showPre: false });
  const on = releaseVisibility(list, { showPre: true });
  const ev = (h) => h.events.map((e) => [list[e.vi].v, e.type, ...(e.kinds ?? [])]);

  test('hiding a CR does not create removed / reintroduced events', () => {
    const k = 'quarkus.http.so-reuse-port';
    assert.deepEqual(ev(propertyHistory(config, k, { vis: on })), [
      ['3.0.4.Final', 'present'],
      ['3.27.0.CR1', 'removed'],
      ['3.27.4', 'reintroduced'],
    ]);
    const hidden = propertyHistory(config, k, { vis: off });
    assert.deepEqual(ev(hidden), [['3.0.4.Final', 'present']]);
    assert.equal(hidden.ranges.length, 1);
    assert.equal(hidden.hiddenSnapshots, 1);
    assert.equal(hidden.widened, false);
    assert.ok(!hidden.cvs.includes(findVersionIndex(list, '3.27.0.CR1')));
  });

  test('changes are computed between consecutive visible snapshots', () => {
    const k = 'quarkus.datasource.jdbc.max-size';
    const shown = propertyHistory(config, k, { vis: on }).events.filter((e) => e.type === 'changed');
    assert.deepEqual(shown.map((e) => [list[e.vi].v, e.from.d, e.to.d]), [['3.27.0.CR1', '20', '40'], ['3.27.4', '40', '50']]);
    const hidden = propertyHistory(config, k, { vis: off }).events.filter((e) => e.type === 'changed');
    assert.deepEqual(hidden.map((e) => [list[e.vi].v, e.from.d, e.to.d]), [['3.27.4', '20', '50']]);
    assert.deepEqual(ev(propertyHistory(config, 'quarkus.otel.logs.enabled', { vis: on })), [['3.27.0.CR1', 'introduced']]);
    assert.deepEqual(ev(propertyHistory(config, 'quarkus.otel.logs.enabled', { vis: off })), [['3.27.4', 'introduced']]);
    assert.deepEqual(ev(propertyHistory(config, 'quarkus.arc.auto-inject-fields', { vis: off })).slice(1), [['3.27.4', 'changed', 'deprecated'], ['3.33.3', 'changed', 'undeprecated']]);
  });

  test('without vis, every snapshot is used (as before)', () => {
    assert.deepEqual(ev(propertyHistory(config, 'quarkus.http.so-reuse-port')), ev(propertyHistory(config, 'quarkus.http.so-reuse-port', { vis: on })));
  });

  test('a property that only exists in hidden snapshots falls back to every snapshot', () => {
    const k = 'quarkus.websockets-next.server.metrics.enabled';
    const h = propertyHistory(config, k, { vis: off });
    assert.equal(h.widened, true);
    assert.deepEqual(ev(h), [['3.27.0.CR1', 'introduced'], ['3.27.4', 'removed']]);
    assert.equal(h.present, false);
    assert.equal(propertyHistory(config, k, { vis: on }).widened, false);
  });

  test('widening relaxes one switch at a time, so turning a switch on never hides what was shown', () => {
    // quarkus.a exists only in 3.2.0 (old); 3.8.0.CR1 is a pre-release snapshot of a shown stream
    const vs = V('3.2.0', '3.8.0.CR1', '3.8.0', '3.9.0');
    const cfg = { versions: [0, 1, 2, 3], states: [{ n: 'quarkus.a', t: 'int', d: '1' }], props: [{ k: 'quarkus.a', h: [[0, 0], [1, null]] }] };
    const vis = (showPre, showOld) => releaseVisibility(vs, { showPre, showOld, oldBefore: '3.8' });
    const widen = [{ vis: vis(false, true), kind: 'old' }, { vis: vis(true, false), kind: 'pre' }];
    const h = propertyHistory(cfg, 'quarkus.a', { vis: vis(false, false), widen });
    assert.equal(h.widened, true);
    assert.equal(h.widenedBy, 'old', 'old releases are relaxed first');
    assert.deepEqual(h.cvs, [0, 2, 3], 'the hidden pre-release snapshot stays hidden');
    assert.equal(h.hiddenSnapshots, 1);
    // turning "Show old releases" on shows the same snapshots: nothing disappears
    assert.deepEqual(propertyHistory(cfg, 'quarkus.a', { vis: vis(false, true) }).cvs, [0, 2, 3]);
    // without widening steps, every snapshot is used, as before
    assert.equal(propertyHistory(cfg, 'quarkus.a', { vis: vis(false, false) }).widenedBy, 'all');
  });

  test('neighbours skip hidden old releases, previous minor and LTS included', () => {
    const vs = V('3.2.12', '3.7.4', '3.8.0', '3.8.1', '3.9.0');
    const vj = { lts: ['3.2', '3.8'], versions: vs };
    const at = (v) => vs.findIndex((x) => x.v === v);
    const hidden = versionNeighbours(vj, at('3.8.0'), { vis: releaseVisibility(vs, { showOld: false, oldBefore: '3.8' }) });
    assert.deepEqual([hidden.previousMinor, hidden.previousLts, hidden.older], [-1, -1, -1]);
    const shown = versionNeighbours(vj, at('3.8.0'), { vis: releaseVisibility(vs, { showOld: true, oldBefore: '3.8' }) });
    assert.deepEqual([shown.previousMinor, shown.previousLts, shown.older], [at('3.7.4'), at('3.2.12'), at('3.7.4')]);
    assert.equal(versionNeighbours(vj, at('3.9.0'), { vis: releaseVisibility(vs, { showOld: false, oldBefore: '3.8' }) }).previousLts, at('3.8.1'));
  });

  test('the first visible snapshot is "present", even when hidden ones come before it', () => {
    const vs = V('3.0.0.CR1', '3.0.0', '3.1.0');
    const cfg = { versions: [0, 1, 2], states: [{ n: 'quarkus.a', t: 'int', d: '1' }, { n: 'quarkus.a', t: 'int', d: '2' }], props: [{ k: 'quarkus.a', h: [[0, 0], [1, 1]] }] };
    const vis = releaseVisibility(vs);
    const h = propertyHistory(cfg, 'quarkus.a', { vis });
    assert.deepEqual(h.events.map((e) => [e.vi, e.type]), [[1, 'present']]);
    assert.equal(h.latest.d, '2');
    assert.deepEqual(propertyHistory(cfg, 'quarkus.a', { vis: releaseVisibility(vs, { showPre: true }) }).events.map((e) => [e.vi, e.type]), [[0, 'present'], [1, 'changed']]);
  });

  test('upcoming snapshots stay in the timeline while the toggle is off', () => {
    const h = propertyHistory(config, 'quarkus.log.file.enable', { vis: off });
    assert.deepEqual(ev(h).slice(-2), [['3.20.6', 'changed', 'deprecated'], ['4.0.0.Beta1', 'removed']]);
  });

  test('extension history over the visible snapshots', () => {
    const status = (vis) => extensionHistory(extensions, 'quarkus-websockets-next', config.versions, { vis }).events
      .filter((e) => e.type === 'changed').map((e) => [list[e.vi].v, e.from.s, e.to.s]);
    assert.deepEqual(status(on), [['3.15.7', 'experimental', 'preview'], ['3.27.0.CR1', 'preview', 'stable']]);
    assert.deepEqual(status(off), [['3.15.7', 'experimental', 'preview'], ['3.27.4', 'preview', 'stable']]);
  });

  test('artifact history: hidden pre-releases never split a range or change a kind', () => {
    const rows = (h) => h.ranges.map((r) => [list[r.from].v, list[r.to].v, r.value, r.count, r.pre, r.kind ?? null]);
    const orm = 'org.hibernate.orm:hibernate-core';
    const hidden = artifactHistory(bom, orm, list, { vis: off });
    assert.ok(!hidden.ranges.some((r) => /CR|Alpha/.test(r.value ?? '')), 'no pre-release-only values');
    assert.deepEqual(rows(hidden).slice(10, 13), [
      ['3.20.6', '3.20.6', '6.6.26.Final', 1, 0, 'patch'],
      ['3.27.0', '3.27.0', '7.1.1.Final', 1, 0, 'major'],
      ['3.27.4', '3.27.4', '7.1.6.Final', 1, 0, 'patch'],
    ]);
    assert.equal(hidden.hiddenReleases, 4);
    assert.equal(hidden.ranges.reduce((n, r) => n + r.count, 0), list.length - 4);
    const shown = artifactHistory(bom, orm, list, { vis: on });
    assert.deepEqual(rows(shown).find((r) => r[0] === '3.27.0.CR1'), ['3.27.0.CR1', '3.27.0.CR1', '7.1.0.CR2', 1, 1, 'major']);
    assert.equal(shown.ranges.reduce((n, r) => n + r.count, 0), list.length);
    // a run interrupted only by a hidden release stays one range
    const vs = V('3.0.0', '3.1.0.CR1', '3.1.0', '3.2.0');
    const one = { artifacts: [{ id: 'g:a', h: [[0, '1.0'], [1, '2.0.CR1'], [2, '1.0'], [3, '1.1']] }] };
    const r = artifactHistory(one, 'g:a', vs, { vis: releaseVisibility(vs) });
    assert.deepEqual(r.ranges.map((x) => [x.from, x.to, x.value, x.count, x.kind ?? null]), [[0, 2, '1.0', 2, null], [3, 3, '1.1', 1, 'minor']]);
  });

  test('artifact history: managed only in hidden pre-releases falls back to every release', () => {
    const h = artifactHistory(bom, 'io.smallrye.reactive:mutiny-zero', list, { vis: off });
    assert.equal(h.widened, true);
    assert.deepEqual(h.ranges.filter((r) => r.managed).map((r) => [list[r.from].v, r.value]), [['3.27.0.CR1', '1.1.1']]);
    assert.equal(list[h.lastManaged].v, '3.27.0.CR1');
    assert.equal(artifactHistory(bom, 'io.smallrye.reactive:mutiny-zero', list, { vis: on }).widened, false);
  });

  test('artifact history: current = the newest visible release', () => {
    const h = artifactHistory(bom, 'io.vertx:vertx-core', list, { vis: off });
    assert.equal(list[h.last].v, '4.0.0.Beta1', 'upcoming, so visible');
    assert.equal(h.current.value, '5.0.4');
  });
});

/* -------------------------------------------------------------------------- */
describe('extension snapshots without config', () => {
  // 2.x releases with extension snapshots only (read from the extension catalogs), 3.x ones with config as well,
  // and a newest release with extensions only
  const list = [
    { v: '2.15.3.Final', ext: true },
    { v: '2.16.0.CR1', ext: false },
    { v: '2.16.5.Final', ext: false },
    { v: '2.16.12.Final', ext: true },
    { v: '3.0.0.Final', ext: false },
    { v: '3.0.4.Final', config: true, configSource: 'bytecode', ext: true },
    { v: '3.2.12.Final', config: true, configSource: 'bytecode', ext: true },
    { v: '3.3.0', ext: true },
  ].map((x) => ({ stream: stream(x.v), pre: !isFinal(x.v), lts: false, config: false, ...x }));
  const idx = (v) => findVersionIndex(list, v);
  const name = (i) => (i == null ? null : list[i].v);
  const extensions = {
    states: [
      /* 0 */ { n: 'RESTEasy Reactive', s: 'preview' },
      /* 1 */ { n: 'RESTEasy Reactive', s: 'stable' },
      /* 2 */ { n: 'OpenTelemetry Exporter Jaeger', s: 'preview' },
      /* 3 */ { r: 'io.quarkiverse.opentelemetry.exporter:quarkus-opentelemetry-exporter-jaeger' },
      /* 4 */ { n: 'Info', s: 'preview' },
      /* 5 */ { n: 'Newest', s: 'experimental' },
    ],
    exts: [
      { id: 'quarkus-info', h: [[5, 4]] },
      { id: 'quarkus-newest', h: [[7, 5]] },
      { id: 'quarkus-opentelemetry-exporter-jaeger', h: [[0, 2], [3, null], [5, 3]] },
      { id: 'quarkus-resteasy-reactive', h: [[0, 0], [3, 1]] },
    ],
  };

  test('extension snapshots come from the ext flags; data without them has extensions at the config snapshots', () => {
    assert.deepEqual(extensionVersionsOf(list), [0, 3, 5, 6, 7]);
    assert.deepEqual(configVersionsOf(list), [5, 6]);
    const before = list.map(({ ext, ...rest }) => rest);
    assert.deepEqual(extensionVersionsOf(before), [5, 6]);
    assert.deepEqual(extensionVersionsOf([]), []);
    assert.deepEqual(extensionVersionsOf(null), []);
  });

  test('the extension snapshot of a release follows the config rule over the extension snapshots', () => {
    const ext = (v) => name(extensionVersionFor(idx(v), list));
    const cfg = (v) => name(configVersionFor(idx(v), list));
    assert.equal(ext('2.15.3.Final'), '2.15.3.Final');
    assert.equal(ext('2.16.5.Final'), '2.16.12.Final', 'the stream snapshot, a later patch');
    assert.equal(ext('2.16.0.CR1'), null, 'a pre-release never borrows one');
    assert.equal(ext('3.0.0.Final'), '3.0.4.Final');
    assert.equal(ext('3.3.0'), '3.3.0');
    assert.equal(cfg('2.16.5.Final'), null, 'no config snapshot in the stream or before it');
    assert.equal(cfg('2.16.12.Final'), null);
    assert.equal(cfg('3.3.0'), '3.2.12.Final', 'config falls back to the latest snapshot before it');
    assert.equal(extensionVersionFor(-1, list), null);
    assert.equal(extensionVersionFor(0, null), null);
    assert.equal(snapshotVersionFor(idx('3.0.0.Final'), list, [0, 3]), idx('2.16.12.Final'), 'any snapshot list');
  });

  test('diffs and histories use the extension snapshots', () => {
    const between = (a, b) => diffExtensions(extensions, extensionVersionFor(idx(a), list), extensionVersionFor(idx(b), list));
    const d = between('2.15.3.Final', '2.16.5.Final');
    assert.deepEqual(d.changed.map((c) => [c.id, c.kinds]), [['quarkus-resteasy-reactive', ['status']]]);
    assert.deepEqual(d.removed.map((c) => c.id), ['quarkus-opentelemetry-exporter-jaeger']);
    const across = between('2.16.12.Final', '3.2.12.Final');
    assert.deepEqual(across.added.map((c) => [c.id, c.b.r ?? null]), [
      ['quarkus-info', null],
      ['quarkus-opentelemetry-exporter-jaeger', 'io.quarkiverse.opentelemetry.exporter:quarkus-opentelemetry-exporter-jaeger'],
    ]);
    const evs = extensionVersionsOf(list);
    const h = extensionHistory(extensions, 'quarkus-resteasy-reactive', evs);
    assert.deepEqual(h.events.map((e) => [list[e.vi].v, e.type, ...(e.kinds ?? [])]), [
      ['2.15.3.Final', 'present'],
      ['2.16.12.Final', 'changed', 'status'],
    ]);
    assert.deepEqual(h.ranges, [{ from: 0, to: 7 }]);
    const jaeger = extensionHistory(extensions, 'quarkus-opentelemetry-exporter-jaeger', evs);
    assert.deepEqual(jaeger.events.map((e) => [list[e.vi].v, e.type]), [
      ['2.15.3.Final', 'present'], ['2.16.12.Final', 'removed'], ['3.0.4.Final', 'reintroduced'],
    ]);
    // over the config snapshots alone (the old rule) the 2.x history would be lost
    assert.deepEqual(extensionHistory(extensions, 'quarkus-resteasy-reactive', configVersionsOf(list)).events.map((e) => e.type), ['present']);
  });

  test('pre-releases hide their extension snapshots like their config snapshots', () => {
    const vs = [...list.slice(0, 4), { v: '3.0.0.CR1', stream: '3.0', pre: true, lts: false, config: false, ext: true }, ...list.slice(4)];
    const evs = extensionVersionsOf(vs);
    assert.deepEqual(evs, [0, 3, 4, 6, 7, 8]);
    const off = releaseVisibility(vs);
    assert.deepEqual(visibleOnly(evs, off), [0, 3, 6, 7, 8]);
    assert.equal(extensionVersionFor(4, vs), 4, 'a pre-release with an extension snapshot uses it');
    assert.equal(configVersionFor(4, vs), null, 'and still has no config snapshot');
  });

  test('search: an extension is current when the newest extension snapshot has it', () => {
    const config = { versions: [5, 6], states: [], props: [] };
    const index = buildSearchIndex({ config, extensions, versions: list });
    const current = (id) => index.entries.find((e) => e.type === 'extension' && e.id === id).current;
    assert.equal(current('quarkus-newest'), true, 'only in 3.3.0, which has no config snapshot');
    assert.equal(current('quarkus-resteasy-reactive'), true);
    // data without ext flags: the newest config snapshot decides, as before
    const old = buildSearchIndex({ config, extensions, versions: list.map(({ ext, ...rest }) => rest) });
    assert.equal(old.entries.find((e) => e.id === 'quarkus-newest').current, false);
  });

  test('3.x is unchanged: with extensions at the config snapshots both rules agree for every release', () => {
    const vj = load('versions');
    for (const flags of [vj.versions, vj.versions.map(({ ext, ...rest }) => rest)]) {
      for (let i = 0; i < flags.length; i++) {
        assert.equal(extensionVersionFor(i, flags), configVersionFor(i, flags), flags[i].v);
      }
    }
  });
});

/* -------------------------------------------------------------------------- */
describe('extension snapshots without config: edge cases', () => {
  const rows = (...xs) => xs.map(([v, flags = '']) => ({
    v, stream: stream(v), pre: !isFinal(v), lts: false, config: flags.includes('c'), ext: flags.includes('e'),
  }));
  const events = (h, list) => h.events.map((e) => [list[e.vi].v, e.type, ...(e.kinds ?? [])]);

  test('the first release of the site has extensions only', () => {
    const list = rows(['2.15.0.Final'], ['2.16.0.Final'], ['2.16.12.Final', 'e'], ['3.0.0.Final'],
      ['3.0.4.Final', 'ce']);
    assert.deepEqual(extensionVersionsOf(list), [2, 4]);
    assert.equal(extensionVersionFor(2, list), 2);
    assert.equal(configVersionFor(2, list), null, 'no config snapshot at or before it');
    assert.equal(extensionVersionFor(1, list), 2, 'an earlier patch uses its stream\'s snapshot');
    assert.equal(extensionVersionFor(0, list), null, 'a release before the first snapshot has none');
    assert.equal(extensionVersionFor(3, list), 4);
    const extensions = {
      states: [{ n: 'Old', s: 'preview' }, { n: 'Old', s: 'stable' }, { n: 'New', s: 'experimental' }],
      exts: [
        { id: 'quarkus-gone', h: [[2, 0], [4, null]] },
        { id: 'quarkus-new', h: [[4, 2]] },
        { id: 'quarkus-old', h: [[2, 0], [4, 1]] },
      ],
    };
    const evs = extensionVersionsOf(list);
    assert.deepEqual(events(extensionHistory(extensions, 'quarkus-old', evs), list),
      [['2.16.12.Final', 'present'], ['3.0.4.Final', 'changed', 'status']]);
    assert.deepEqual(events(extensionHistory(extensions, 'quarkus-gone', evs), list),
      [['2.16.12.Final', 'present'], ['3.0.4.Final', 'removed']]);
    assert.deepEqual(events(extensionHistory(extensions, 'quarkus-new', evs), list), [['3.0.4.Final', 'introduced']]);
    const d = diffExtensions(extensions, extensionVersionFor(1, list), extensionVersionFor(3, list));
    assert.deepEqual([d.added.map((x) => x.id), d.removed.map((x) => x.id), d.changed.map((x) => [x.id, x.kinds])],
      [['quarkus-new'], ['quarkus-gone'], [['quarkus-old', ['status']]]]);
    const index = buildSearchIndex({ config: { versions: [4], states: [], props: [] }, extensions, versions: list });
    const current = (id) => index.entries.find((e) => e.id === id).current;
    assert.deepEqual([current('quarkus-gone'), current('quarkus-new'), current('quarkus-old')], [false, true, true]);
  });

  test('a site without any config snapshot still has extension data', () => {
    const list = rows(['2.15.3.Final', 'e'], ['2.16.0.CR1'], ['2.16.12.Final', 'e']);
    const extensions = { states: [{ n: 'A' }], exts: [{ id: 'quarkus-a', h: [[0, 0]] }] };
    assert.deepEqual(configVersionsOf(list), []);
    assert.equal(configVersionFor(2, list), null);
    assert.equal(extensionVersionFor(2, list), 2);
    assert.equal(extensionVersionFor(1, list), null, 'a pre-release never borrows');
    assert.deepEqual(events(extensionHistory(extensions, 'quarkus-a', extensionVersionsOf(list)), list),
      [['2.15.3.Final', 'present']]);
    const index = buildSearchIndex({ config: { versions: [], states: [], props: [] }, extensions, versions: list });
    assert.equal(index.entries.find((e) => e.id === 'quarkus-a').current, true);
  });

  test('a final whose stream has only a pre-release extension snapshot uses it; config uses an older stream', () => {
    const list = rows(['3.0.4.Final', 'ce'], ['3.1.0.CR1', 'e'], ['3.1.0'], ['3.1.1']);
    assert.equal(extensionVersionFor(2, list), 1);
    assert.equal(extensionVersionFor(3, list), 1);
    assert.equal(configVersionFor(2, list), 0);
    assert.equal(configVersionFor(1, list), null, 'the pre-release has no config snapshot of its own');
  });

  test('hidden pre-releases with extension snapshots', () => {
    // 3.1.0.CR1 is hidden while pre-releases are off (its stream has finals); 3.2.0.CR1 is upcoming
    const list = rows(['3.0.4.Final', 'ce'], ['3.1.0.CR1', 'e'], ['3.1.0'], ['3.1.3', 'ce'], ['3.2.0.CR1', 'e']);
    const off = releaseVisibility(list);
    const on = releaseVisibility(list, { showPre: true });
    const evs = extensionVersionsOf(list);
    assert.deepEqual(evs, [0, 1, 3, 4]);
    assert.deepEqual(visibleOnly(evs, off), [0, 3, 4]);
    assert.equal(extensionVersionFor(1, list), 1, 'a hidden pre-release still compares at its own snapshot');
    assert.equal(extensionVersionFor(2, list), 3, 'a final prefers a final snapshot of its stream');
    assert.equal(extensionVersionFor(4, list), 4);
    const extensions = {
      states: [{ n: 'A', s: 'preview' }, { n: 'A', s: 'stable' }, { n: 'B' }, { n: 'C' }],
      exts: [
        { id: 'quarkus-a', h: [[0, 0], [1, 1]] },
        { id: 'quarkus-b', h: [[1, 2], [3, null]] },
        { id: 'quarkus-c', h: [[1, 3]] },
        { id: 'quarkus-d', h: [[0, 0], [1, null], [3, 0]] },
      ],
    };
    const hist = (id, vis) => extensionHistory(extensions, id, evs, { vis });
    // a change at the hidden pre-release shows at the next visible snapshot
    assert.deepEqual(events(hist('quarkus-a', off), list),
      [['3.0.4.Final', 'present'], ['3.1.3', 'changed', 'status']]);
    assert.deepEqual(events(hist('quarkus-a', on), list),
      [['3.0.4.Final', 'present'], ['3.1.0.CR1', 'changed', 'status']]);
    assert.equal(hist('quarkus-a', off).hiddenSnapshots, 1);
    // only in the hidden pre-release: every snapshot is used instead
    const b = hist('quarkus-b', off);
    assert.equal(b.widened, true);
    assert.deepEqual(events(b, list), [['3.1.0.CR1', 'introduced'], ['3.1.3', 'removed']]);
    assert.equal(hist('quarkus-b', on).widened, false);
    // introduced at the hidden pre-release: introduced at the first visible snapshot that has it
    assert.deepEqual(events(hist('quarkus-c', off), list), [['3.1.3', 'introduced']]);
    assert.deepEqual(events(hist('quarkus-c', on), list), [['3.1.0.CR1', 'introduced']]);
    // missing only from the hidden pre-release: no removed / reintroduced pair while it is hidden
    assert.deepEqual(events(hist('quarkus-d', off), list), [['3.0.4.Final', 'present']]);
    assert.deepEqual(events(hist('quarkus-d', on), list),
      [['3.0.4.Final', 'present'], ['3.1.0.CR1', 'removed'], ['3.1.3', 'reintroduced']]);
    // the upcoming pre-release is the newest extension snapshot, so it decides what is current
    const index = buildSearchIndex({ config: { versions: [0, 3], states: [], props: [] }, extensions, versions: list });
    assert.equal(index.entries.find((e) => e.id === 'quarkus-b').current, false);
    assert.equal(index.entries.find((e) => e.id === 'quarkus-c').current, true);
  });
});

/* -------------------------------------------------------------------------- */
describe('durations: stream starts and phrasing', () => {
  const vj = load('versions');
  const list = vj.versions;

  test('streamStartIndex: the first final of a stream', () => {
    const at = (s) => {
      const i = streamStartIndex(list, s);
      return i < 0 ? null : list[i].v;
    };
    assert.equal(at('3.27'), '3.27.0', 'not 3.27.0.CR1');
    assert.equal(at('3.0'), '3.0.0.Final', 'not 3.0.0.Alpha1');
    assert.equal(at('3.8'), '3.8.1', 'the lowest final when x.y.0 is missing');
    assert.equal(at('4.0'), null, 'no final yet');
    assert.equal(at('9.9'), null);
  });

  test('streamSpan: time between the first finals of two streams', () => {
    assert.equal(streamSpan(list, '3.27', '3.33').label, '6 months');
    assert.equal(streamSpan(list, '3.20', '3.27').label, '5 months');
    assert.equal(streamSpan(list, '3.40', '4.0'), null);
  });

  test('quick picks measure streams, not their newest patches', () => {
    const picks = quickPicks(vj);
    const lts = picks.find((p) => p.kind === 'lts' && p.fromStream === '3.27');
    assert.equal(spanLabel(list[lts.from].date, list[lts.to].date), 'same day', 'the newest patches 3.27.4 and 3.33.3 share a date');
    assert.deepEqual([list[lts.span.from].v, list[lts.span.to].v, lts.span.label], ['3.27.0', '3.33.0', '6 months']);
    assert.deepEqual(picks.map((p) => p.span?.label ?? null), ['7 months', '6 months', '6 months', '5 months', '6 months', '5 months', null]);
    assert.equal(picks[picks.length - 1].kind, 'pre');
  });

  test('releasedApart reads naturally for every span', () => {
    assert.equal(releasedApart('2026-10-01', '2026-10-01'), 'released the same day');
    assert.equal(releasedApart('2026-10-01', '2026-10-02'), 'released 1 day apart');
    assert.equal(releasedApart('2026-10-01', '2026-10-13'), 'released 12 days apart');
    assert.equal(releasedApart('2025-09-24', '2026-03-25'), 'released 6 months apart');
    assert.equal(releasedApart('2026-03-25', '2025-09-24'), 'released 6 months apart', 'order-insensitive');
    assert.equal(releasedApart('2024-01-01', '2025-01-01'), 'released 1 year apart');
    assert.equal(releasedApart('2023-04-26', '2026-10-19'), 'released 3 years 5 months apart');
    assert.equal(releasedApart(undefined, '2026-10-19'), '');
    assert.equal(spanApart('2026-10-01', '2026-10-01'), 'same day');
    assert.equal(spanApart('2025-09-10', '2026-03-18'), '6 months apart');
    assert.equal(spanApart('x', '2026-03-18'), '');
  });

  test('spanLabel: singular and plural units, month boundary at 45 days', () => {
    assert.equal(spanLabel('2025-01-01', '2025-01-02'), '1 day');
    assert.equal(spanLabel('2025-01-01', '2025-02-14'), '44 days');
    assert.equal(spanLabel('2025-01-01', '2025-02-15'), '1 month');
    assert.equal(spanLabel('2025-01-31', '2025-03-17'), '1 month');
    assert.equal(spanLabel('2024-01-15', '2025-02-15'), '1 year 1 month');
    assert.equal(spanLabel('2024-01-15', '2026-01-14'), '1 year 11 months');
  });
});
