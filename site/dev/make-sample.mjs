// Regenerates site/dev/sample/*.json: a SYNTHETIC dataset in the exact site data format, used for UI
// development, as the site's fallback when data/versions.json is missing, and by site/tests/core.test.mjs.
// Release numbers and library versions look realistic but the histories are made up.
//
// Usage: node site/dev/make-sample.mjs

import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { canonicalKey, compareVersions, isFinal, stream } from '../assets/core.js';

const OUT = join(dirname(fileURLToPath(import.meta.url)), 'sample');
const LTS = ['3.2', '3.8', '3.15', '3.20', '3.27', '3.33'];

/* ---------- releases (ascending version order; dates are not monotonic, like real LTS patches) ----------*/

// The releases the BOM token lines below are written for (one token each).
// 3.27.4 and 3.33.3 share a date on purpose: the newest patches of two LTS streams often ship the same day.
const BASE_RELEASES = [
  ['3.0.0.Final', '2023-04-26'],
  ['3.0.4.Final', '2023-05-24'],
  ['3.2.0.Final', '2023-07-05'],
  ['3.2.6.Final', '2023-09-27'],
  ['3.2.12.Final', '2024-04-17'],
  ['3.8.1', '2024-02-28'],
  ['3.8.4', '2024-04-24'],
  ['3.8.6', '2024-08-21'],
  ['3.8.6.1', '2024-09-18'],
  ['3.9.0', '2024-03-27'],
  ['3.9.5', '2024-04-30'],
  ['3.15.1', '2024-09-25'],
  ['3.15.3', '2025-01-08'],
  ['3.15.7', '2025-09-10'],
  ['3.20.0', '2025-03-26'],
  ['3.20.3', '2025-09-17'],
  ['3.20.6', '2026-03-18'],
  ['3.27.0', '2025-09-24'],
  ['3.27.4', '2026-10-01'],
  ['3.33.0', '2026-03-25'],
  ['3.33.3', '2026-10-01'],
  ['3.40.0', '2026-09-23'],
  ['3.40.1', '2026-10-07'],
  ['3.40.1.1', '2026-10-14'],
  ['4.0.0.Beta1', '2026-10-19'],
];

const ORM_IDS = ['org.hibernate.orm:hibernate-core', 'org.hibernate.orm:hibernate-envers', 'org.hibernate.orm:hibernate-graalvm'];
const NETTY_IDS = ['io.netty:netty-common', 'io.netty:netty-buffer', 'io.netty:netty-handler', 'io.netty:netty-codec-http'];
const each = (ids, value) => Object.fromEntries(ids.map((id) => [id, value]));

// More pre-releases: Alpha, Beta and CR, in streams that have finals (3.0, 3.27, 3.33) and in the upcoming
// stream 4.0 (no final yet). Each takes the BOM of `template` (a BASE release) with `bom` overrides ('-' = not
// managed). Which of them have a config snapshot is decided by CONFIG_PRE below.
const PRE_RELEASES = [
  { v: '3.0.0.Alpha1', date: '2022-11-09', template: '3.0.0.Final',
    bom: { 'io.vertx:vertx-core': '4.3.6', ...each(ORM_IDS, '6.1.5.Final'), ...each(NETTY_IDS, '4.1.85.Final'), 'com.fasterxml.jackson.core:jackson-databind': '2.14.0' } },
  { v: '3.0.0.Beta1', date: '2023-03-01', template: '3.0.0.Final',
    bom: { 'io.vertx:vertx-core': '4.3.8', ...each(ORM_IDS, '6.2.0.CR2'), ...each(NETTY_IDS, '4.1.87.Final'), 'com.fasterxml.jackson.core:jackson-databind': '2.14.1' } },
  { v: '3.27.0.CR1', date: '2025-09-10', template: '3.27.0',
    bom: { ...each(ORM_IDS, '7.1.0.CR2'), 'io.smallrye.reactive:mutiny-zero': '1.1.1', 'org.junit.jupiter:junit-jupiter-api': '5.13.3' } },
  { v: '3.33.0.CR1', date: '2026-03-11', template: '3.33.0',
    bom: { ...each(ORM_IDS, '7.2.0.CR1'), 'org.jetbrains.kotlin:kotlin-stdlib': '2.2.20-RC2' } },
  { v: '4.0.0.Alpha1', date: '2026-07-29', template: '4.0.0.Beta1',
    bom: { 'io.vertx:vertx-core': '5.0.0', 'io.quarkus.gizmo:gizmo2': '2.0.0.Alpha6', 'org.junit.jupiter:junit-jupiter-api': '6.0.0-M2' } },
];
// Pre-releases with a config snapshot: one in a stream that has finals, one in the upcoming stream.
const CONFIG_PRE = ['3.27.0.CR1', '4.0.0.Beta1'];

const RELEASES = [...BASE_RELEASES, ...PRE_RELEASES.map((p) => [p.v, p.date])].sort((x, y) => compareVersions(x[0], y[0]));
for (let i = 1; i < RELEASES.length; i++) {
  if (compareVersions(RELEASES[i - 1][0], RELEASES[i][0]) >= 0) throw new Error(`releases out of order at ${RELEASES[i][0]}`);
}

const VI = new Map(RELEASES.map(([v], i) => [v, i]));
const vi = (v) => {
  if (!VI.has(v)) throw new Error(`unknown release ${v}`);
  return VI.get(v);
};
const BASE_INDEX = new Map(BASE_RELEASES.map(([v], i) => [v, i]));
const PRE_BY_V = new Map(PRE_RELEASES.map((p) => [p.v, p]));
for (const p of PRE_RELEASES) {
  if (isFinal(p.v)) throw new Error(`${p.v} is not a pre-release`);
  if (!BASE_INDEX.has(p.template)) throw new Error(`${p.v}: unknown template ${p.template}`);
}

// Config snapshots: the newest final of each stream (the "streams" selection) plus CONFIG_PRE.
const newestFinalOfStream = new Map();
RELEASES.forEach(([v], i) => {
  if (isFinal(v)) newestFinalOfStream.set(stream(v), i);
});
const CONFIG_VIS = [...newestFinalOfStream.values(), ...CONFIG_PRE.map(vi)].sort((a, b) => a - b);
const CONFIG_VERSIONS = CONFIG_VIS.map((i) => RELEASES[i][0]);

const versionsJson = {
  generatedAt: '2026-10-27T12:00:00Z',
  lts: LTS,
  versions: RELEASES.map(([v, date], i) => {
    const s = stream(v);
    const entry = { v, date, stream: s, pre: !/^\d+(\.\d+)*(\.Final)?$/.test(v), lts: LTS.includes(s), config: CONFIG_VIS.includes(i) };
    if (entry.config) entry.configSource = compareVersions(v, '3.25') >= 0 ? 'model' : 'bytecode';
    // extension snapshots: here exactly the config snapshots (the site tests cover releases with extensions only)
    entry.ext = entry.config;
    // classes snapshots: here too
    entry.classes = entry.config;
    return entry;
  }),
};

/* ---------- BOM: one token per BASE release, '|' separates streams for readability ---------- */
// token: a version, '=' (same as the Quarkus release), '-' (not managed). PRE_RELEASES derive theirs.

const STREAM_SIZES = '3.0:2 3.2:3 3.8:4 3.9:2 3.15:3 3.20:3 3.27:2 3.33:2 3.40:3 4.0:1';
const bomLines = [];
const lib = (id, line) => bomLines.push([id, line]);
const ALL = (token) => Array(BASE_RELEASES.length).fill(token).join(' ');

// Quarkus modules, versioned with the platform.
for (const a of ['quarkus-core', 'quarkus-core-deployment', 'quarkus-arc', 'quarkus-vertx-http', 'quarkus-datasource', 'quarkus-agroal',
  'quarkus-hibernate-orm', 'quarkus-kafka-client', 'quarkus-oidc', 'quarkus-opentelemetry', 'quarkus-micrometer', 'quarkus-smallrye-health',
  'quarkus-logging-json', 'quarkus-junit5']) {
  lib(`io.quarkus:${a}`, ALL('='));
}
lib('io.quarkus:quarkus-bom-quarkus-platform-descriptor:json:${quarkus.version}', ALL('='));
for (const a of ['quarkus-resteasy-reactive', 'quarkus-resteasy-reactive-jackson', 'quarkus-smallrye-reactive-messaging-kafka']) {
  lib(`io.quarkus:${a}`, '= = | = = = | = = = = | = = | = = = | = = = | = = | = = | = = = | -');
}
for (const a of ['quarkus-rest', 'quarkus-rest-jackson', 'quarkus-messaging-kafka', 'quarkus-websockets-next']) {
  lib(`io.quarkus:${a}`, '- - | - - - | - - - - | = = | = = = | = = = | = = | = = | = = = | =');
}
lib('io.quarkus:quarkus-smallrye-opentracing', '= = | = = = | - - - - | - - | - - - | - - - | - - | - - | - - - | -');

lib('io.quarkus.gizmo:gizmo', '1.6.0.Final 1.6.1.Final | 1.6.1.Final 1.6.1.Final 1.6.1.Final | 1.7.0 1.7.0 1.8.0 1.8.0 | 1.8.0 1.8.0 | 1.8.0 1.8.0 1.8.0 | 1.9.0 1.9.0 1.9.0 | 1.9.0 1.9.0 | 1.9.0 1.9.0 | 1.9.0 1.9.0 1.9.0 | 1.9.0');
lib('io.quarkus.gizmo:gizmo2', '- - | - - - | - - - - | - - | - - - | - - - | - - | - - | 2.0.0.Alpha5 2.0.0.Alpha5 2.0.0.Alpha5 | 2.0.0.Beta1');
lib('io.vertx:vertx-core', '4.4.1 4.4.2 | 4.4.4 4.4.5 4.4.9 | 4.5.3 4.5.7 4.5.9 4.5.10 | 4.5.10 4.5.10 | 4.5.10 4.5.11 4.5.14 | 4.5.14 4.5.16 4.5.21 | 4.5.21 4.5.22 | 4.5.22 4.5.22 | 4.5.22 4.5.22 4.5.22 | 5.0.4');
const NETTY = '4.1.91.Final 4.1.92.Final | 4.1.94.Final 4.1.97.Final 4.1.108.Final | 4.1.108.Final 4.1.108.Final 4.1.111.Final 4.1.112.Final | 4.1.112.Final 4.1.112.Final | 4.1.113.Final 4.1.115.Final 4.1.118.Final | 4.1.118.Final 4.1.119.Final 4.1.124.Final | 4.1.124.Final 4.1.127.Final | 4.1.127.Final 4.1.128.Final | 4.2.6.Final 4.2.6.Final 4.2.7.Final | 4.2.7.Final';
for (const a of ['netty-common', 'netty-buffer', 'netty-handler', 'netty-codec-http']) lib(`io.netty:${a}`, NETTY);
lib('io.netty:netty-tcnative-boringssl-static', '2.0.59.Final 2.0.60.Final | 2.0.61.Final 2.0.61.Final 2.0.61.Final | 2.0.65.Final 2.0.65.Final 2.0.65.Final 2.0.65.Final | 2.0.65.Final 2.0.65.Final | 2.0.66.Final 2.0.69.Final 2.0.70.Final | 2.0.70.Final 2.0.72.Final 2.0.72.Final | 2.0.73.Final 2.0.73.Final | 2.0.74.Final 2.0.74.Final | 2.0.74.Final 2.0.74.Final 2.0.74.Final | 2.0.74.Final');
const ORM = '6.2.1.Final 6.2.4.Final | 6.2.5.Final 6.2.9.Final 6.2.13.Final | 6.4.4.Final 6.4.4.Final 6.4.4.Final 6.4.4.Final | 6.4.4.Final 6.4.4.Final | 6.6.1.Final 6.6.4.Final 6.6.11.Final | 6.6.11.Final 6.6.20.Final 6.6.26.Final | 7.1.1.Final 7.1.6.Final | 7.2.0.Final 7.2.3.Final | 7.3.0.Final 7.3.1.Final 7.3.1.Final | 7.3.1.Final';
for (const a of ['hibernate-core', 'hibernate-envers', 'hibernate-graalvm']) lib(`org.hibernate.orm:${a}`, ORM);
lib('org.hibernate.reactive:hibernate-reactive-core', '2.0.0.Final 2.0.0.Final | 2.0.3.Final 2.0.6.Final 2.0.8.Final | 2.2.2.Final 2.2.2.Final 2.2.2.Final 2.2.2.Final | 2.2.2.Final 2.2.2.Final | 2.4.2.Final 2.4.2.Final 2.4.7.Final | 2.4.7.Final 2.4.8.Final 2.4.9.Final | 3.1.0.Final 3.1.2.Final | 3.2.0.Final 3.2.1.Final | 3.2.1.Final 3.2.1.Final 3.2.1.Final | 3.3.0.CR1');
lib('org.hibernate.validator:hibernate-validator', '8.0.0.Final 8.0.0.Final | 8.0.1.Final 8.0.1.Final 8.0.1.Final | 8.0.1.Final 8.0.1.Final 8.0.1.Final 8.0.1.Final | 8.0.1.Final 8.0.1.Final | 8.0.1.Final 8.0.2.Final 8.0.2.Final | 8.0.2.Final 8.0.2.Final 8.0.3.Final | 9.0.1.Final 9.0.1.Final | 9.0.1.Final 9.1.0.Final | 9.1.0.Final 9.1.0.Final 9.1.0.Final | 9.1.0.Final');
lib('org.hibernate.search:hibernate-search-mapper-orm', '6.1.7.Final 6.1.7.Final | 6.2.0.Final 6.2.1.Final 6.2.4.Final | 7.0.0.Final 7.0.0.Final 7.0.1.Final 7.0.1.Final | 7.1.0.Final 7.1.1.Final | 7.2.1.Final 7.2.2.Final 7.2.4.Final | 7.2.4.Final 7.2.4.Final 7.2.5.Final | 8.1.0.Final 8.1.2.Final | 8.2.0.Final 8.2.1.Final | 8.2.1.Final 8.2.1.Final 8.2.1.Final | 8.2.1.Final');
lib('org.jboss.resteasy:resteasy-core', '6.2.3.Final 6.2.3.Final | 6.2.4.Final 6.2.5.Final 6.2.7.Final | 6.2.7.Final 6.2.8.Final 6.2.9.Final 6.2.9.Final | 6.2.9.Final 6.2.9.Final | 6.2.10.Final 6.2.11.Final 6.2.12.Final | 6.2.12.Final 6.2.12.Final 6.2.12.Final | 6.2.12.Final 6.2.12.Final | 6.2.13.Final 6.2.13.Final | 6.2.13.Final 6.2.13.Final 6.2.13.Final | 7.0.0.Final');
lib('io.smallrye.config:smallrye-config', '3.2.1 3.2.1 | 3.3.0 3.3.4 3.3.4 | 3.5.4 3.5.4 3.5.4 3.5.4 | 3.6.1 3.7.1 | 3.9.1 3.10.2 3.10.2 | 3.12.4 3.12.4 3.12.4 | 3.13.4 3.13.4 | 3.14.0 3.14.1 | 3.14.1 3.15.0 3.15.0 | 4.0.0');
lib('io.smallrye.reactive:mutiny', '2.1.0 2.2.0 | 2.3.1 2.3.1 2.3.1 | 2.5.7 2.5.7 2.5.8 2.5.8 | 2.5.8 2.5.8 | 2.6.2 2.6.2 2.6.2 | 2.8.0 2.8.0 2.8.0 | 2.9.4 2.9.4 | 2.9.4 2.9.5 | 3.0.1 3.0.1 3.0.1 | 3.0.1');
// Managed only in 3.27.0.CR1 (see PRE_RELEASES): an artifact that exists only in a pre-release.
lib('io.smallrye.reactive:mutiny-zero', ALL('-'));
lib('io.smallrye.reactive:smallrye-reactive-messaging-api', '4.4.0 4.5.0 | 4.7.0 4.9.0 4.9.0 | 4.18.0 4.18.0 4.21.0 4.21.0 | 4.21.0 4.21.0 | 4.24.0 4.25.0 4.25.0 | 4.27.0 4.27.0 4.27.0 | 4.28.0 4.28.0 | 4.30.0 4.30.0 | 4.31.0 4.31.0 4.31.0 | 5.0.0');
const JACKSON = '2.14.2 2.15.0 | 2.15.2 2.15.2 2.15.2 | 2.16.1 2.16.1 2.17.2 2.17.2 | 2.17.2 2.17.2 | 2.17.2 2.18.2 2.18.2 | 2.18.2 2.18.3 2.18.4 | 2.19.2 2.19.2 | 2.20.0 2.20.0 | 2.20.1 2.20.1 2.20.1 | 2.20.1';
lib('com.fasterxml.jackson.core:jackson-databind', JACKSON);
lib('com.fasterxml.jackson.core:jackson-core', JACKSON);
lib('com.fasterxml.jackson.core:jackson-annotations', '2.14.2 2.15.0 | 2.15.2 2.15.2 2.15.2 | 2.16.1 2.16.1 2.17.2 2.17.2 | 2.17.2 2.17.2 | 2.17.2 2.18.2 2.18.2 | 2.18.2 2.18.3 2.18.4 | 2.19.2 2.19.2 | 2.20 2.20 | 2.20 2.20 2.20 | 2.20');
lib('org.jetbrains.kotlin:kotlin-stdlib', '1.8.20 1.8.21 | 1.8.22 1.8.22 1.8.22 | 1.9.22 1.9.22 1.9.23 1.9.23 | 1.9.23 1.9.23 | 2.0.20 2.0.21 2.0.21 | 2.1.10 2.1.10 2.1.10 | 2.2.0 2.2.10 | 2.2.20 2.2.20 | 2.2.21 2.2.21 2.2.21 | 2.3.0');
lib('org.apache.kafka:kafka-clients', '3.4.0 3.4.0 | 3.5.1 3.5.1 3.5.1 | 3.6.1 3.6.1 3.6.2 3.6.2 | 3.7.0 3.7.0 | 3.8.0 3.8.0 3.9.0 | 3.9.0 3.9.0 3.9.1 | 4.0.0 4.0.0 | 4.1.0 4.1.0 | 4.1.0 4.1.0 4.1.0 | 4.1.0');
lib('io.micrometer:micrometer-core', '1.10.6 1.11.0 | 1.11.1 1.11.4 1.11.5 | 1.12.2 1.12.4 1.12.5 1.12.5 | 1.12.5 1.12.5 | 1.13.4 1.13.6 1.13.6 | 1.14.5 1.14.5 1.14.7 | 1.15.3 1.15.3 | 1.15.4 1.15.5 | 1.16.0 1.16.0 1.16.0 | 1.16.0');
lib('io.opentelemetry:opentelemetry-api', '1.23.1 1.25.0 | 1.28.0 1.29.0 1.29.0 | 1.32.0 1.32.0 1.32.0 1.32.0 | 1.35.0 1.35.0 | 1.42.1 1.42.1 1.42.1 | 1.48.0 1.48.0 1.48.0 | 1.52.0 1.52.0 | 1.54.1 1.54.1 | 1.55.0 1.55.0 1.55.0 | 1.55.0');
lib('io.grpc:grpc-core', '1.54.1 1.54.1 | 1.56.1 1.56.1 1.56.1 | 1.61.0 1.61.0 1.61.0 1.61.0 | 1.62.2 1.62.2 | 1.65.1 1.65.1 1.65.1 | 1.69.1 1.69.1 1.69.1 | 1.73.0 1.73.0 | 1.75.0 1.75.0 | 1.76.0 1.76.0 1.76.0 | 1.76.0');
lib('org.graalvm.sdk:graal-sdk', '22.3.2 22.3.2 | 23.0.1 23.0.1 23.0.1 | 23.1.2 23.1.2 23.1.2 23.1.2 | 23.1.2 23.1.2 | 23.1.2 23.1.2 23.1.2 | 23.1.2 23.1.2 23.1.2 | - - | - - | - - - | -');
lib('org.graalvm.sdk:nativeimage', '- - | - - - | 23.1.2 23.1.2 23.1.2 23.1.2 | 23.1.2 23.1.2 | 23.1.2 23.1.2 23.1.2 | 23.1.2 23.1.2 23.1.2 | 24.2.1 24.2.1 | 24.2.1 24.2.1 | 24.2.1 24.2.1 24.2.1 | 25.0.0');
lib('org.jboss.logmanager:jboss-logmanager-embedded', '1.1.1 1.1.1 | 1.1.1 1.1.1 1.1.1 | 1.1.2 1.1.2 1.1.2 1.1.2 | - - | - - - | - - - | - - | - - | - - - | -');
lib('org.jboss.logmanager:jboss-logmanager', '- - | - - - | - - - - | 3.0.4.Final 3.0.6.Final | 3.0.6.Final 3.1.1.Final 3.1.1.Final | 3.1.1.Final 3.1.2.Final 3.1.2.Final | 3.1.2.Final 3.1.2.Final | 3.1.2.Final 3.1.2.Final | 3.2.0.Final 3.2.0.Final 3.2.0.Final | 3.2.0.Final');
lib('org.jboss.narayana.jta:narayana-jta', '6.0.0.Final 6.0.1.Final | 6.0.1.Final 6.0.1.Final 6.0.1.Final | 7.0.0.Final 7.0.0.Final 7.0.2.Final 7.0.2.Final | 7.0.2.Final 7.0.2.Final | 7.0.2.Final 7.1.0.Final 7.1.0.Final | 7.2.1.Final 7.2.2.Final 7.2.2.Final | 7.3.0.Final 7.3.0.Final | 7.3.2.Final 7.3.2.Final | 7.3.2.Final 7.3.2.Final 7.3.2.Final | 7.3.2.Final');
lib('io.agroal:agroal-api', '2.1 2.1 | 2.1 2.1 2.1 | 2.1 2.1 2.1 2.1 | 2.1 2.1 | 2.5 2.5 2.5 | 2.6 2.6 2.6 | 2.7 2.7 | 2.8 2.8 | 2.8 2.8 2.8 | 2.8');
lib('org.flywaydb:flyway-core', '9.16.3 9.16.3 | 9.20.0 9.21.1 9.21.1 | 9.22.3 9.22.3 9.22.3 9.22.3 | 10.10.0 10.10.0 | 10.17.1 10.17.3 10.17.3 | 11.3.4 11.4.1 11.4.1 | 11.8.2 11.8.2 | 11.13.1 11.13.1 | 11.14.0 11.14.0 11.14.0 | 11.14.0');
// The one deliberate downgrade: 3.40.x → 4.0.0.Beta1 goes back from Liquibase 5 to 4.33.
lib('org.liquibase:liquibase-core', '4.20.0 4.20.0 | 4.23.0 4.23.0 4.23.0 | 4.25.1 4.25.1 4.26.0 4.26.0 | 4.26.0 4.26.0 | 4.29.2 4.29.2 4.29.2 | 4.31.1 4.31.1 4.31.1 | 4.33.0 4.33.0 | 4.33.0 4.33.0 | 5.0.1 5.0.1 5.0.1 | 4.33.0');
lib('org.junit.jupiter:junit-jupiter-api', '5.9.3 5.9.3 | 5.10.0 5.10.0 5.10.0 | 5.10.1 5.10.2 5.10.2 5.10.3 | 5.10.3 5.10.3 | 5.10.5 5.10.5 5.10.5 | 5.11.4 5.11.4 5.11.4 | 5.13.4 5.13.4 | 5.14.0 5.14.0 | 5.14.0 5.14.0 5.14.0 | 6.0.0');
lib('org.testcontainers:testcontainers', '1.18.0 1.18.1 | 1.18.3 1.19.0 1.19.0 | 1.19.3 1.19.7 1.19.7 1.19.8 | 1.19.8 1.19.8 | 1.20.1 1.20.1 1.20.1 | 1.20.6 1.20.6 1.21.0 | 1.21.3 1.21.3 | 1.21.3 1.21.3 | 1.21.3 1.21.3 1.21.3 | 2.0.1');
lib('info.picocli:picocli', '4.7.3 4.7.3 | 4.7.4 4.7.5 4.7.5 | 4.7.5 4.7.5 4.7.5 4.7.5 | 4.7.5 4.7.5 | 4.7.6 4.7.6 4.7.6 | 4.7.6 4.7.6 4.7.6 | 4.7.7 4.7.7 | 4.7.7 4.7.7 | 4.7.7 4.7.7 4.7.7 | 4.7.7');
lib('org.mongodb:mongodb-driver-sync', '4.9.1 4.9.1 | 4.10.2 4.10.2 4.10.2 | 4.11.1 4.11.1 4.11.1 4.11.1 | 4.11.1 4.11.1 | 5.1.4 5.2.0 5.2.0 | 5.3.1 5.3.1 5.3.1 | 5.5.1 5.5.1 | 5.6.0 5.6.0 | 5.6.1 5.6.1 5.6.1 | 5.6.1');
lib('org.infinispan:infinispan-client-hotrod', '14.0.8.Final 14.0.9.Final | 14.0.11.Final 14.0.17.Final 14.0.24.Final | 14.0.24.Final 14.0.27.Final 14.0.31.Final 14.0.31.Final | 15.0.0.Final 15.0.1.Final | 15.0.8.Final 15.0.11.Final 15.0.13.Final | 15.0.13.Final 15.0.15.Final 15.0.18.Final | 15.2.5.Final 15.2.5.Final | 16.0.1 16.0.1 | 16.0.1 16.0.1 16.0.1 | 16.0.1');

const expectedSizes = STREAM_SIZES.split(' ').map((x) => Number(x.split(':')[1]));
function parseLine(id, line) {
  const groups = line.split('|').map((g) => g.trim().split(/\s+/).filter(Boolean));
  const tokens = groups.flat();
  if (tokens.length !== BASE_RELEASES.length) throw new Error(`${id}: ${tokens.length} tokens, expected ${BASE_RELEASES.length}`);
  if (groups.length > 1) groups.forEach((g, i) => {
    if (g.length !== expectedSizes[i]) throw new Error(`${id}: stream group ${i} has ${g.length} tokens, expected ${expectedSizes[i]}`);
  });
  // One token per release: BASE releases read theirs, pre-releases take their template's unless overridden.
  const perRelease = RELEASES.map(([v]) => {
    const pre = PRE_BY_V.get(v);
    if (!pre) return tokens[BASE_INDEX.get(v)];
    return Object.hasOwn(pre.bom, id) ? pre.bom[id] : tokens[BASE_INDEX.get(pre.template)];
  });
  const h = [];
  let prev;
  perRelease.forEach((tok, i) => {
    const value = tok === '-' ? null : tok;
    if (value === prev) return;
    if (prev === undefined && value === null) return; // absent before the first point
    h.push([i, value]);
    prev = value;
  });
  return { id, h };
}

const bomIds = new Set(bomLines.map(([id]) => id));
for (const p of PRE_RELEASES) for (const id of Object.keys(p.bom)) if (!bomIds.has(id)) throw new Error(`${p.v}: no BOM line for ${id}`);
const bomJson = { artifacts: bomLines.map(([id, line]) => parseLine(id, line)).sort((a, b) => (a.id < b.id ? -1 : 1)) };

/* ---------- interned states ---------- */

const STATE_FIELDS = ['n', 'a', 'e', 't', 'l', 'o', 'm', 'd', 'p', 'x', 'en'];
function compactState(s, fields) {
  const out = {};
  for (const f of fields) {
    const v = s[f];
    if (v === undefined || v === null || v === false || v === 0) continue;
    if (v === '' && f !== 'x') continue; // `x: ""` = deprecated without a since version
    if (Array.isArray(v) && v.length === 0) continue;
    out[f] = v === true ? 1 : v;
  }
  return out;
}

function makeInterner(fields) {
  const states = [];
  const index = new Map();
  return {
    states,
    intern(s) {
      const c = compactState(s, fields);
      const key = JSON.stringify(c);
      if (!index.has(key)) {
        index.set(key, states.length);
        states.push(c);
      }
      return index.get(key);
    },
  };
}

function applyPatch(state, patch) {
  if (patch.$replace) return { ...patch.$replace };
  const next = { ...state };
  for (const [k, v] of Object.entries(patch)) {
    if (v === undefined) delete next[k];
    else next[k] = v;
  }
  return next;
}

/** Builds a change-point history over the config snapshots. changes: {configVersion: patch | null}. */
function history(intern, base, changes, since) {
  if (since && !CONFIG_VERSIONS.includes(since)) throw new Error(`unknown config version ${since}`);
  for (const v of Object.keys(changes)) if (!CONFIG_VERSIONS.includes(v)) throw new Error(`unknown config version ${v}`);
  const h = [];
  let state = null;
  let last = null;
  let started = false;
  let prevIdx;
  for (let i = 0; i < CONFIG_VERSIONS.length; i++) {
    const cv = CONFIG_VERSIONS[i];
    if (!started && (!since || cv === since)) {
      started = true;
      state = { ...base };
    }
    if (started && cv in changes) {
      const patch = changes[cv];
      if (patch === null) state = null;
      else state = applyPatch(state ?? last, patch);
    }
    if (state) last = state;
    const idx = started && state ? intern(state) : null;
    if (idx !== prevIdx && !(prevIdx === undefined && idx === null)) h.push([CONFIG_VIS[i], idx]);
    prevIdx = idx;
  }
  return h;
}

/* ---------- config properties ---------- */

const config = makeInterner(STATE_FIELDS);
const props = [];
const desc = {};
const DS_ALIAS = (name) => name.replace('quarkus.datasource.', 'quarkus.datasource."datasource-name".');
const ORM_ALIAS = (name) => name.replace('quarkus.hibernate-orm.', 'quarkus.hibernate-orm."persistence-unit-name".');
const OIDC_ALIAS = (name) => name.replace('quarkus.oidc.', 'quarkus.oidc."tenant".');

function P(name, base, text, changes = {}, { since, alias } = {}) {
  const k = canonicalKey(name);
  if (props.some((p) => p.k === k)) throw new Error(`duplicate key ${k}`);
  const a = alias ? [alias(name)] : undefined;
  props.push({ k, h: history(config.intern, { n: name, a, ...base }, changes, since) });
  if (text) desc[k] = text;
}

const HTTP = 'quarkus-vertx-http';
P('quarkus.http.port', { e: HTTP, t: 'int', d: '8080', p: 'R' }, 'The HTTP port.');
P('quarkus.http.ssl-port', { e: HTTP, t: 'int', d: '8443', p: 'R' }, 'The HTTPS port.');
P('quarkus.http.host', { e: HTTP, t: 'string', d: '0.0.0.0', p: 'R' }, 'The HTTP host. In dev and test mode it defaults to `localhost`.');
P('quarkus.http.cors', { e: HTTP, t: 'boolean', d: 'false', p: 'F' }, 'Enable the CORS filter.', { '3.15.7': { x: '3.14' }, '3.33.3': null });
P('quarkus.http.cors.enabled', { e: HTTP, t: 'boolean', d: 'false', p: 'F' }, 'Enable the CORS filter.', {}, { since: '3.15.7' });
P('quarkus.http.cors.origins', { e: HTTP, t: 'string', l: true, o: true, p: 'R' }, 'Origins allowed for CORS, as exact origins or regular expressions between slashes.');
P('quarkus.http.limits.max-body-size', { e: HTTP, t: 'MemorySize', d: '10240K', p: 'R' }, 'The maximum size of a request body.');
P('quarkus.http.limits.max-form-attribute-size', { e: HTTP, t: 'MemorySize', d: '2048', p: 'R' }, 'The maximum size of a form attribute.', {}, { since: '3.40.1.1' });
P('quarkus.http.idle-timeout', { e: HTTP, t: 'Duration', d: '30M', p: 'R' }, 'HTTP connection idle timeout.');
P('quarkus.http.access-log.enabled', { e: HTTP, t: 'boolean', d: 'false', p: 'R' }, 'If access logging is enabled.');
// Missing from the 3.27.0.CR1 snapshot only: hiding that CR must not show it as removed and reintroduced.
P('quarkus.http.so-reuse-port', { e: HTTP, t: 'boolean', d: 'false', p: 'R' }, 'Enable socket reuse port (native transports on Linux and macOS only).',
  { '3.27.0.CR1': null, '3.27.4': {} });
P('quarkus.http.auth.permission."permissions".paths', { e: HTTP, t: 'string', l: true, o: true, p: 'R' }, 'The paths that this permission check applies to.',
  { '3.15.7': { n: 'quarkus.http.auth.permission."name".paths' } });
P('quarkus.http.auth.permission."permissions".policy', { e: HTTP, t: 'string', p: 'R' }, 'The HTTP policy that these paths are linked to.',
  { '3.15.7': { n: 'quarkus.http.auth.permission."name".policy' } });
P('quarkus.http.insecure-requests', { e: HTTP, t: 'InsecureRequests', en: ['enabled', 'redirect', 'disabled'], o: true, p: 'R' },
  'If insecure (plain HTTP) requests are allowed when HTTPS is enabled.');
P('quarkus.http.compress-media-types', { e: HTTP, t: 'string', l: true, d: 'text/html,text/plain,text/xml,text/css,text/javascript,application/javascript', p: 'B' },
  'Media types for which compression is enabled when `quarkus.http.enable-compression` is set.',
  { '3.20.6': { d: 'text/html,text/plain,text/xml,text/css,text/javascript,application/javascript,application/json,application/graphql+json,application/xhtml+xml' } });

const CORE = 'quarkus-core';
P('quarkus.log.level', { e: CORE, t: 'Level', d: 'INFO', p: 'R' }, 'The log level of the root category, used as the default log level for all categories.');
P('quarkus.log.min-level', { e: CORE, t: 'Level', d: 'DEBUG', p: 'B' }, 'The default minimum log level; messages below it are removed at build time.');
P('quarkus.log.category."categories".level', { e: CORE, t: 'string', d: 'inherit', p: 'R' }, 'The log level for this category.', { '3.9.5': { t: 'InheritableLevel' } });
P('quarkus.log.file.enable', { e: CORE, t: 'boolean', d: 'false', p: 'R' }, 'If file logging should be enabled.', { '3.20.6': { x: '' }, '4.0.0.Beta1': null });
P('quarkus.log.file.enabled', { e: CORE, t: 'boolean', d: 'false', p: 'R' }, 'If file logging should be enabled.', {}, { since: '3.20.6' });
P('quarkus.application.name', { e: CORE, t: 'string', o: true, p: 'F' }, 'The name of the application; defaults to the name of the project.');
P('quarkus.shutdown.timeout', { e: CORE, t: 'Duration', o: true, p: 'R' }, 'The timeout to wait for running requests to finish during shutdown.');
P('quarkus.native.container-build', { e: CORE, t: 'boolean', o: true, p: 'B' }, 'If the native build should be done using a container runtime.');
P('quarkus.native.builder-image', { e: CORE, t: 'string', d: 'quay.io/quarkus/ubi-quarkus-mandrel-builder-image:22.3-java17', p: 'B' },
  'The container image used for the native build: `graalvm`, `mandrel` or a full image name.',
  { '3.2.12.Final': { d: 'quay.io/quarkus/ubi-quarkus-mandrel-builder-image:23.0-java17' }, '3.8.6.1': { d: 'mandrel' } });
P('quarkus.native.additional-build-args', { e: CORE, t: 'string', l: true, o: true, p: 'B' }, 'Additional arguments to pass to the `native-image` build process.');
P('quarkus.native.enabled', { e: CORE, t: 'boolean', d: 'false', p: 'B' }, 'Set to enable building a native executable with GraalVM or Mandrel.', {}, { since: '3.9.5' });
P('quarkus.package.type', { e: CORE, t: 'string', d: 'jar', p: 'B' }, 'The requested output type.', { '3.9.5': { x: '3.9' }, '3.15.7': null });
P('quarkus.package.jar.type', { e: CORE, t: 'JarType', en: ['fast-jar', 'uber-jar', 'mutable-jar', 'legacy-jar'], d: 'fast-jar', p: 'B' }, 'The JAR output type to use.',
  { '3.33.3': { en: ['fast-jar', 'uber-jar', 'mutable-jar'] } }, { since: '3.9.5' });
P('quarkus.package.add-runner-suffix', { e: CORE, t: 'boolean', d: 'true', p: 'B' }, 'Whether to add the `-runner` suffix to the JAR name.', { '3.9.5': { x: '3.9' }, '3.15.7': null });
P('quarkus.package.jar.add-runner-suffix', { e: CORE, t: 'boolean', d: 'true', p: 'B' }, 'Whether to add the `-runner` suffix to the JAR name.', {}, { since: '3.9.5' });
P('quarkus.package.manifest.add-implementation-entries', { e: CORE, t: 'boolean', d: 'true', p: 'B' }, 'Whether to add the `Implementation-*` attributes to the JAR manifest.',
  { '3.9.5': { x: '3.9' }, '3.15.7': null });
P('quarkus.package.jar.manifest.add-implementation-entries', { e: CORE, t: 'boolean', d: 'true', p: 'B' }, 'Whether to add the `Implementation-*` attributes to the JAR manifest.', {}, { since: '3.9.5' });
P('quarkus.package.output-name', { e: CORE, t: 'string', o: true, p: 'B' }, 'The name of the final artifact, without suffix or file extension.');

const ARC = 'quarkus-arc';
P('quarkus.arc.remove-unused-beans', { e: ARC, t: 'string', d: 'all', p: 'B' }, 'Which unused beans are removed at build time: `all`, `none` or `fwk`.');
P('quarkus.arc.auto-inject-fields', { e: ARC, t: 'boolean', d: 'true', p: 'B' }, 'If true, `@Inject` is added automatically to non-static fields annotated with a qualifier.',
  { '3.27.0.CR1': { x: '3.27' }, '3.33.3': { x: undefined } });
P('quarkus.arc.strict-compatibility', { e: ARC, t: 'boolean', d: 'false', p: 'B' }, 'If true, ArC follows the CDI specification more strictly.', {}, { since: '3.8.6.1' });

const DS = 'quarkus-datasource';
const ds = { alias: DS_ALIAS };
P('quarkus.datasource.db-kind', { e: DS, t: 'string', o: true, p: 'B' }, 'The kind of database to connect to, for example `postgresql` or `h2`.', {}, ds);
P('quarkus.datasource.username', { e: DS, t: 'string', o: true, p: 'R' }, 'The datasource username.', {}, ds);
P('quarkus.datasource.password', { e: DS, t: 'string', o: true, p: 'R' }, 'The datasource password.', {}, ds);
P('quarkus.datasource.active', { e: DS, t: 'boolean', o: true, p: 'R' }, 'Whether this datasource should be active at runtime.', {}, { ...ds, since: '3.15.7' });
P('quarkus.datasource.devservices.enabled', { e: DS, t: 'boolean', o: true, p: 'B' }, 'Whether this Dev Service should start with the application in dev mode or tests.', {}, ds);
P('quarkus.datasource.devservices.image-name', { e: DS, t: 'string', o: true, p: 'B' }, 'The container image name for container-based Dev Service providers.', {}, ds);
P('quarkus.datasource.devservices.init-script-path', { e: DS, t: 'string', o: true, p: 'B' }, 'Paths to SQL scripts on the classpath applied to the Dev Service database.',
  { '3.20.6': { l: true } }, ds);
P('quarkus.datasource.devservices.container-env."environment-variable-name"', { e: DS, t: 'string', m: true, p: 'B' }, 'Environment variables passed to the container.', {}, ds);
P('quarkus.datasource."datasource-name".devservices.port', { e: DS, t: 'int', o: true, p: 'B' }, 'Optional fixed port the Dev Service listens to.', { '3.20.6': null });
P('quarkus.datasource.devservices.port', { e: DS, t: 'int', o: true, p: 'B' }, 'Optional fixed port the Dev Service listens to.', {}, { ...ds, since: '3.20.6' });

const AGROAL = 'quarkus-agroal';
P('quarkus.datasource.jdbc.url', { e: AGROAL, t: 'string', o: true, p: 'R' }, 'The datasource JDBC URL.', {}, ds);
P('quarkus.datasource.jdbc.max-size', { e: AGROAL, t: 'int', d: '20', p: 'R' }, 'The maximum size of the connection pool.', { '3.27.0.CR1': { d: '40' }, '3.27.4': { d: '50' } }, ds);
P('quarkus.datasource.jdbc.min-size', { e: AGROAL, t: 'int', d: '0', p: 'R' }, 'The minimum size of the connection pool.', {}, ds);
P('quarkus.datasource.jdbc.enable-metrics', { e: AGROAL, t: 'boolean', o: true, p: 'B' }, 'Enable datasource metrics collection.', { '3.20.6': { p: 'F' } }, ds);
P('quarkus.datasource.jdbc.acquisition-timeout', { e: AGROAL, t: 'Duration', d: '5S', p: 'R' }, 'The timeout before cancelling the acquisition of a new connection.', {}, ds);

const ORMX = 'quarkus-hibernate-orm';
const orm = { alias: ORM_ALIAS };
P('quarkus.hibernate-orm.database.generation', { e: ORMX, t: 'string', d: 'none', p: 'R' }, 'Select whether the database schema is generated or not.',
  { '3.27.4': { x: '3.22' }, '4.0.0.Beta1': null }, orm);
P('quarkus.hibernate-orm.schema-management.strategy', { e: ORMX, t: 'string', d: 'none', p: 'R' }, 'Select whether the database schema is generated or not.', {}, { ...orm, since: '3.27.4' });
P('quarkus.hibernate-orm.database.generation.halt-on-error', { e: ORMX, t: 'boolean', d: 'false', p: 'R' }, 'Whether to stop on the first error when applying the schema.',
  { '3.27.4': { x: '3.22' }, '4.0.0.Beta1': null }, orm);
P('quarkus.hibernate-orm.schema-management.halt-on-error', { e: ORMX, t: 'boolean', d: 'false', p: 'R' }, 'Whether to stop on the first error when applying the schema.', {}, { ...orm, since: '3.27.4' });
P('quarkus.hibernate-orm.log.sql', { e: ORMX, t: 'boolean', d: 'false', p: 'R' }, 'Show SQL logs and format them nicely.', {}, orm);
P('quarkus.hibernate-orm.active', { e: ORMX, t: 'boolean', o: true, p: 'R' }, 'Whether this persistence unit should be active at runtime.', {}, { ...orm, since: '3.15.7' });
P('quarkus.hibernate-orm.packages', { e: ORMX, t: 'string', l: true, o: true, p: 'B' }, 'Packages in which to look for entities and other mapped classes.', {}, orm);
P('quarkus.hibernate-orm.second-level-caching-enabled', { e: ORMX, t: 'boolean', d: 'true', p: 'B' }, 'Whether second-level caching is enabled.', {}, orm);
P('quarkus.hibernate-orm.metrics.enabled', { e: ORMX, t: 'boolean', d: 'false', p: 'B' }, 'Whether metrics are published if a metrics extension is enabled.');

P('quarkus.resteasy-reactive.path', { e: 'quarkus-resteasy-reactive', t: 'string', o: true, p: 'F' },
  'Overrides the default path for Jakarta REST resources when no `@ApplicationPath` is declared.',
  { '3.9.5': { e: 'quarkus-rest', x: '3.9' }, '3.27.4': null });
P('quarkus.rest.path', { e: 'quarkus-rest', t: 'string', o: true, p: 'F' }, 'Overrides the default path for Jakarta REST resources when no `@ApplicationPath` is declared.', {}, { since: '3.9.5' });
P('quarkus.resteasy-reactive.input-buffer-size', { e: 'quarkus-resteasy-reactive', t: 'MemorySize', d: '10k', p: 'R' },
  'The amount of memory used to buffer input before switching to blocking IO.', { '3.9.5': { e: 'quarkus-rest', x: '3.9' }, '3.27.4': null });
P('quarkus.rest.input-buffer-size', { e: 'quarkus-rest', t: 'MemorySize', d: '10k', p: 'R' }, 'The amount of memory used to buffer input before switching to blocking IO.', {}, { since: '3.9.5' });
P('quarkus.rest.jackson.optimization.enable-reflection-free-serializers', { e: 'quarkus-rest-jackson', t: 'boolean', d: 'false', p: 'B' },
  'If true, generates reflection-free Jackson serializers for the types returned by REST endpoints.', {}, { since: '3.20.6' });

const KAFKA = 'quarkus-kafka-client';
P('quarkus.kafka.devservices.enabled', { e: KAFKA, t: 'boolean', o: true, p: 'B' }, 'If Dev Services for Kafka has been explicitly enabled or disabled.');
P('quarkus.kafka.devservices.image-name', { e: KAFKA, t: 'string', d: 'docker.io/vectorized/redpanda:v22.3.4', p: 'B' }, 'The Kafka container image to use.',
  { '3.8.6.1': { d: 'docker.io/redpandadata/redpanda:v23.3.4' }, '3.27.4': { d: 'docker.io/redpandadata/redpanda:v24.3.6' }, '3.40.1.1': { d: 'docker.io/redpandadata/redpanda:v25.1.4' } });
P('quarkus.kafka.health.enabled', { e: KAFKA, t: 'boolean', d: 'false', p: 'B' }, 'Whether a health check is published when the SmallRye Health extension is present.');
P('quarkus.reactive-messaging.kafka.serializer-autodetection.enabled', { e: 'quarkus-smallrye-reactive-messaging-kafka', t: 'boolean', d: 'true', p: 'B' },
  'Whether Kafka serializer and deserializer auto-detection is enabled.', { '3.9.5': null });
P('quarkus.messaging.kafka.serializer-autodetection.enabled', { e: 'quarkus-messaging-kafka', t: 'boolean', d: 'true', p: 'B' },
  'Whether Kafka serializer and deserializer auto-detection is enabled.', {}, { since: '3.9.5' });

const OIDC = 'quarkus-oidc';
const oidc = { alias: OIDC_ALIAS };
P('quarkus.oidc.auth-server-url', { e: OIDC, t: 'string', o: true, p: 'R' }, 'The base URL of the OpenID Connect server, for example `https://host:port/auth`.', {}, oidc);
P('quarkus.oidc.client-id', { e: OIDC, t: 'string', o: true, p: 'R' }, 'The client id of the application.', {}, oidc);
P('quarkus.oidc.application-type', { e: OIDC, t: 'ApplicationType', en: ['web-app', 'service', 'hybrid'], o: true, p: 'R' }, 'The application type.', {}, oidc);
P('quarkus.oidc.authentication.cookie-same-site', { e: OIDC, t: 'CookieSameSite', en: ['strict', 'lax', 'none'], d: 'strict', p: 'R' }, 'SameSite attribute for the session cookie.',
  { '3.15.7': { d: 'lax' } }, oidc);

P('quarkus.log.console.json.log-format', { e: 'quarkus-logging-json', t: 'LogFormat', en: ['default', 'ecs'], d: 'default', p: 'R' }, 'The format of the produced JSON.',
  { '3.33.3': { en: ['default', 'ecs', 'gcp'] } }, { since: '3.15.7' });
P('quarkus.otel.enabled', { e: 'quarkus-opentelemetry', t: 'boolean', d: 'true', p: 'B' }, 'If false, disables OpenTelemetry at build time.');
P('quarkus.otel.traces.sampler', { e: 'quarkus-opentelemetry', t: 'string', d: 'parentbased_always_on', p: 'R' }, 'The sampler to use for tracing.');
P('quarkus.otel.metrics.enabled', { e: 'quarkus-opentelemetry', t: 'boolean', d: 'false', p: 'B' }, 'Enables metrics with OpenTelemetry.', {}, { since: '3.15.7' });
P('quarkus.otel.logs.enabled', { e: 'quarkus-opentelemetry', t: 'boolean', d: 'false', p: 'B' }, 'Enables logs with OpenTelemetry.', {}, { since: '3.27.0.CR1' });
P('quarkus.smallrye-health.root-path', { e: 'quarkus-smallrye-health', t: 'string', d: 'health', p: 'B' }, 'Root path for health-checking endpoints.', { '3.33.3': { p: 'F' } });
P('quarkus.websockets-next.server.auto-ping-interval', { e: 'quarkus-websockets-next', t: 'Duration', o: true, p: 'R' },
  'When set, the server sends a ping message to every connected client at this interval.', {}, { since: '3.15.7' });
// Only in the 3.27.0.CR1 snapshot: added in the CR, dropped before the final.
P('quarkus.websockets-next.server.metrics.enabled', { e: 'quarkus-websockets-next', t: 'boolean', d: 'false', p: 'B' },
  'Whether WebSocket metrics are collected when a metrics extension is present.', { '3.27.4': null }, { since: '3.27.0.CR1' });
P('quarkus.jaeger.service-name', { e: 'quarkus-smallrye-opentracing', t: 'string', o: true, p: 'R' }, 'The service name reported to Jaeger.', { '3.8.6.1': null });

props.sort((a, b) => (a.k < b.k ? -1 : a.k > b.k ? 1 : 0));
const configJson = { versions: CONFIG_VIS, states: config.states, props, desc: Object.fromEntries(Object.entries(desc).sort()) };

/* ---------- extensions ---------- */

const extIntern = makeInterner(['n', 's', 'c', 'g', 'd', 'u', 'r', 'p']);
const exts = [];
function X(id, base, changes = {}, since) {
  exts.push({ id, h: history(extIntern.intern, base, changes, since) });
}
const G = 'https://quarkus.io/guides/';
X('quarkus-core', { n: 'Quarkus - Core', s: 'stable', u: true, d: 'Quarkus core components' });
X('quarkus-arc', { n: 'ArC', s: 'stable', c: ['core'], g: `${G}cdi-reference`, d: 'Build time CDI dependency injection', p: ['quarkus.arc.'] });
X('quarkus-vertx-http', { n: 'Vert.x HTTP', s: 'stable', u: true, c: ['web'], g: `${G}http-reference`, d: 'Vert.x HTTP server and router', p: ['quarkus.http.'] });
X('quarkus-datasource', { n: 'Datasources', s: 'stable', u: true, c: ['data'], g: `${G}datasource`, d: 'Configure your datasources', p: ['quarkus.datasource.'] });
X('quarkus-agroal', { n: 'Agroal - Database connection pool', s: 'stable', c: ['data'], g: `${G}datasource`, d: 'Pool JDBC database connections (included in JDBC drivers)', p: ['quarkus.datasource.'] },
  { '3.20.6': { n: 'Agroal - Database Connection Pool' } });
X('quarkus-hibernate-orm', { n: 'Hibernate ORM', s: 'stable', c: ['data'], g: `${G}hibernate-orm`, d: 'Define your persistent model with Hibernate ORM and Jakarta Persistence', p: ['quarkus.hibernate-orm.'] });
X('quarkus-resteasy-reactive', { n: 'RESTEasy Reactive', s: 'stable', c: ['web', 'reactive'], g: `${G}resteasy-reactive`, d: 'A Jakarta REST implementation utilizing build time processing and Vert.x', p: ['quarkus.resteasy-reactive.'] },
  { '3.9.5': { $replace: { r: 'io.quarkus:quarkus-rest' } }, '4.0.0.Beta1': null });
X('quarkus-resteasy-reactive-jackson', { n: 'RESTEasy Reactive Jackson', s: 'stable', c: ['web', 'serialization', 'reactive'], g: `${G}resteasy-reactive#json-serialisation`, d: 'Jackson serialization support for RESTEasy Reactive' },
  { '3.9.5': { $replace: { r: 'io.quarkus:quarkus-rest-jackson' } }, '4.0.0.Beta1': null });
X('quarkus-rest', { n: 'REST', s: 'stable', c: ['web', 'reactive'], g: `${G}rest`, d: 'A Jakarta REST implementation utilizing build time processing and Vert.x', p: ['quarkus.rest.'] }, {}, '3.9.5');
X('quarkus-rest-jackson', { n: 'REST Jackson', s: 'stable', c: ['web', 'serialization', 'reactive'], g: `${G}rest#json-serialisation`, d: 'Jackson serialization support for Quarkus REST', p: ['quarkus.rest.jackson.'] }, {}, '3.9.5');
X('quarkus-smallrye-reactive-messaging-kafka', { n: 'SmallRye Reactive Messaging - Kafka Connector', s: 'stable', c: ['messaging'], g: `${G}kafka-reactive-getting-started`, d: 'Connect to Kafka with Reactive Messaging', p: ['quarkus.reactive-messaging.kafka.'] },
  { '3.9.5': { $replace: { r: 'io.quarkus:quarkus-messaging-kafka' } }, '4.0.0.Beta1': null });
X('quarkus-messaging-kafka', { n: 'Messaging - Kafka Connector', s: 'stable', c: ['messaging'], g: `${G}kafka-reactive-getting-started`, d: 'Connect to Kafka with Reactive Messaging', p: ['quarkus.messaging.kafka.'] }, {}, '3.9.5');
X('quarkus-kafka-client', { n: 'Apache Kafka Client', s: 'stable', c: ['messaging'], g: `${G}kafka`, d: 'Connect to Apache Kafka with its native API', p: ['quarkus.kafka.'] });
X('quarkus-oidc', { n: 'OpenID Connect', s: 'stable', c: ['security'], g: `${G}security-oidc-bearer-token-authentication`, d: 'Verify Bearer access tokens and authenticate users with Authorization Code Flow', p: ['quarkus.oidc.'] });
X('quarkus-opentelemetry', { n: 'OpenTelemetry', s: 'preview', c: ['observability'], g: `${G}opentelemetry`, d: 'Use OpenTelemetry to trace services', p: ['quarkus.otel.'] },
  { '3.15.7': { s: 'stable' }, '3.20.6': { d: 'Use OpenTelemetry to trace services, collect metrics and logs' } });
X('quarkus-micrometer', { n: 'Micrometer metrics', s: 'stable', c: ['observability'], g: `${G}telemetry-micrometer`, d: 'Instrument the runtime and your application with dimensional metrics using Micrometer', p: ['quarkus.micrometer.'] });
X('quarkus-smallrye-health', { n: 'SmallRye Health', s: 'stable', c: ['observability'], g: `${G}smallrye-health`, d: 'Monitor service health', p: ['quarkus.smallrye-health.'] });
X('quarkus-websockets-next', { n: 'WebSockets Next', s: 'experimental', c: ['web'], g: `${G}websockets-next-reference`, d: 'Implementation of the WebSocket API with enhanced efficiency and usability', p: ['quarkus.websockets-next.'] },
  { '3.15.7': { s: 'preview' }, '3.27.0.CR1': { s: 'stable' } }, '3.9.5');
X('quarkus-logging-json', { n: 'Logging JSON', s: 'stable', c: ['core'], g: `${G}logging#json-logging`, d: 'Add JSON formatter for console logging', p: ['quarkus.log.console.json.', 'quarkus.log.file.json.'] });
X('quarkus-smallrye-opentracing', { n: 'SmallRye OpenTracing', s: 'deprecated', c: ['observability'], g: `${G}opentracing`, d: 'Trace your services with SmallRye OpenTracing', p: ['quarkus.jaeger.'] },
  { '3.8.6.1': null });

exts.sort((a, b) => (a.id < b.id ? -1 : 1));
const extensionsJson = { states: extIntern.states, exts };

/* ---------- classes: the jars of a few classes, at the classes snapshots (the config snapshots here) ---------- */
// One file per major line (classes-3.json, classes-4.json), each over the snapshots of its line only.
// [binary name, [[release, jars], ...]]: the jars from that release on, several separated by spaces, '-' for none.
// A snapshot takes the last point at or before its release; nothing is recorded before the first point.
const CLASS_LINES = [
  ['io.quarkus.runtime.Application', [['3.0.0.Final', 'quarkus-core']]],
  ['io.quarkus.runtime.Quarkus', [['3.0.0.Final', 'quarkus-core']]],
  ['io.quarkus.runtime.Quarkus$1', [['3.0.0.Final', 'quarkus-core'], ['3.20.0', '-']]],
  ['io.quarkus.runtime.util.ClassPathUtils', [['3.0.0.Final', 'quarkus-core'], ['3.15.1', 'quarkus-classloader-commons']]],
  ['io.quarkus.arc.runtime.ArcRecorder', [['3.0.0.Final', 'quarkus-arc']]],
  ['io.quarkus.arc.runtime.ArcRecorder$1', [['3.0.0.Final', 'quarkus-arc']]],
  ['io.quarkus.virtual.threads.VirtualThreadsRecorder', [['3.8.1', 'quarkus-virtual-threads']]],
  ['io.quarkus.opentracing.runtime.TracingRecorder', [['3.0.0.Final', 'quarkus-smallrye-opentracing'], ['3.15.1', '-']]],
  ['io.quarkus.resteasy.reactive.server.runtime.ResteasyReactiveRecorder', [['3.0.0.Final', 'quarkus-resteasy-reactive'], ['3.9.0', 'quarkus-rest']]],
  ['io.quarkus.amazon.lambda.http.LambdaHttpHandler', [['3.2.0.Final', 'quarkus-amazon-lambda-http quarkus-amazon-lambda-rest']]],
  ['io.quarkus.test.junit.QuarkusTest', [['3.0.0.Final', 'quarkus-junit5'], ['3.33.0', 'quarkus-junit']]],
  ['io.quarkus.test.QuarkusUnitTest', [['3.0.0.Final', 'quarkus-junit5-internal'], ['3.33.0', 'quarkus-junit-internal']]],
  ['io.quarkus.test.QuarkusUnitTest$1', [['3.0.0.Final', 'quarkus-junit5-internal'], ['3.33.0', 'quarkus-junit-internal'], ['3.40.0', '-']]],
  ['io.quarkus.test.QuarkusUnitTest$Builder', [['3.27.0', 'quarkus-junit5-internal'], ['3.33.0', 'quarkus-junit-internal']]],
  ['io.quarkus.test.QuarkusExtensionTest', [['3.40.0', 'quarkus-junit-internal']]],
];
const CLASS_VIS = CONFIG_VIS;
const packageOf = (name) => (name.includes('.') ? name.slice(0, name.lastIndexOf('.')) : '');
const nameInPackage = (name) => name.slice(name.lastIndexOf('.') + 1);
const majorOfRelease = (i) => Number(RELEASES[i][0].split('.')[0]);

/** The classes file of the snapshots `vis` (one major line): its histories start at the first snapshot of the line. */
function classesFile(vis) {
  // the jars of each class at each snapshot, null when in none; undefined before the first point of its line
  const at = CLASS_LINES.map(([name, points]) => [name, vis.map((i) => {
    let jars;
    for (const [from, value] of points) if (compareVersions(from, RELEASES[i][0]) <= 0) jars = value;
    return jars === undefined || jars === '-' ? null : jars.split(' ');
  })]).filter(([, jarsAt]) => jarsAt.some(Boolean));
  const artifacts = [...new Set(at.flatMap(([, jarsAt]) => jarsAt.flatMap((jars) => jars ?? [])))].sort();
  const packages = [...new Set(at.map(([name]) => packageOf(name)))].sort();
  const classes = at.map(([name, jarsAt]) => {
    const h = [];
    let prev = null;
    jarsAt.forEach((jars, k) => {
      const indices = (jars ?? []).map((a) => artifacts.indexOf(a)).sort((a, b) => a - b);
      const value = !indices.length ? null : indices.length === 1 ? indices[0] : indices;
      if (JSON.stringify(value) !== JSON.stringify(prev) && (h.length || value !== null)) h.push([vis[k], value]);
      prev = value;
    });
    return [packages.indexOf(packageOf(name)), nameInPackage(name), h];
  }).sort((x, y) => x[0] - y[0] || (x[1] < y[1] ? -1 : x[1] > y[1] ? 1 : 0));
  return { versions: vis, artifacts, packages, classes };
}
const classesFiles = new Map();
for (const major of [...new Set(CLASS_VIS.map(majorOfRelease))]) {
  classesFiles.set(major, classesFile(CLASS_VIS.filter((i) => majorOfRelease(i) === major)));
}

/* ---------- write (one array element per line: compact but diffable) ---------- */

function write(name, obj) {
  const lines = ['{'];
  const entries = Object.entries(obj);
  entries.forEach(([k, v], i) => {
    const comma = i < entries.length - 1 ? ',' : '';
    if (Array.isArray(v) && v.length && typeof v[0] === 'object') {
      lines.push(`  ${JSON.stringify(k)}: [`);
      v.forEach((item, j) => lines.push(`    ${JSON.stringify(item)}${j < v.length - 1 ? ',' : ''}`));
      lines.push(`  ]${comma}`);
    } else if (v && typeof v === 'object' && !Array.isArray(v)) {
      lines.push(`  ${JSON.stringify(k)}: {`);
      const sub = Object.entries(v);
      sub.forEach(([sk, sv], j) => lines.push(`    ${JSON.stringify(sk)}: ${JSON.stringify(sv)}${j < sub.length - 1 ? ',' : ''}`));
      lines.push(`  }${comma}`);
    } else {
      lines.push(`  ${JSON.stringify(k)}: ${JSON.stringify(v)}${comma}`);
    }
  });
  lines.push('}');
  writeFileSync(join(OUT, `${name}.json`), `${lines.join('\n')}\n`, 'utf8');
}

mkdirSync(OUT, { recursive: true });
write('versions', versionsJson);
write('bom', bomJson);
write('config', configJson);
write('extensions', extensionsJson);
for (const [major, file] of classesFiles) write(`classes-${major}`, file);
console.log(`wrote ${OUT}: ${RELEASES.length} releases, ${CONFIG_VIS.length} config snapshots, ${props.length} properties ` +
  `(${config.states.length} states), ${bomJson.artifacts.length} artifacts, ${exts.length} extensions (${extIntern.states.length} states), ` +
  `classes of ${classesFiles.size} major lines (${[...classesFiles].map(([major, f]) => `${major}.x: ${f.classes.length}`).join(', ')})`);
