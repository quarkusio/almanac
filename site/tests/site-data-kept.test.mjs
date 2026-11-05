// Tests for .github/scripts/site-data-kept.mjs, which stops a run of the new-releases workflow from committing site data
// that lost a release. Each test runs it in a repository of its own, made with git's plumbing: unlike `git commit`, that
// does not depend on the settings of whoever runs the tests.
import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const SCRIPT = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '.github', 'scripts', 'site-data-kept.mjs');
const AUTHOR = {
  GIT_AUTHOR_NAME: 'test',
  GIT_AUTHOR_EMAIL: 'test@example.com',
  GIT_COMMITTER_NAME: 'test',
  GIT_COMMITTER_EMAIL: 'test@example.com',
};

const versions = (...names) => JSON.stringify({ versions: names.map((v) => ({ v })) });
const profiles = (...names) => JSON.stringify({ releases: names });
const data = (releases, profiled = releases) => ({
  'versions.json': versions(...releases),
  'profile.json': profiles(...profiled),
});

/** Replaces site/data with the files given, by name. */
function writeData(root, files) {
  const folder = join(root, 'site', 'data');
  rmSync(folder, { recursive: true, force: true });
  mkdirSync(folder, { recursive: true });
  for (const [name, content] of Object.entries(files)) {
    writeFileSync(join(folder, name), content);
  }
}

/** Runs the check, from `folder` of the repository, with `head` as HEAD's site data and `assembled` as assemble's. */
function check(head, assembled, folder = '.') {
  const root = mkdtempSync(join(tmpdir(), 'site-data-kept-'));
  try {
    const git = (...args) => execFileSync('git', args, {
      cwd: root,
      encoding: 'utf8',
      env: { ...process.env, ...AUTHOR },
    }).trim();
    git('init', '-q');
    writeData(root, head);
    git('add', '--all');
    git('update-ref', 'HEAD', git('commit-tree', '-m', 'the site data', git('write-tree')));
    writeData(root, assembled);
    return spawnSync(process.execPath, [SCRIPT], { cwd: join(root, folder), encoding: 'utf8' });
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
}

describe('site-data-kept', () => {
  const head = data(['3.0.0', '3.1.0']);

  test('passes when the site data keeps every release, with or without new ones', () => {
    for (const assembled of [head, data(['3.0.0', '3.1.0', '3.2.0'])]) {
      const run = check(head, assembled);
      assert.equal(run.status, 0, run.stderr);
      assert.equal(run.stderr, '');
    }
  });

  test('fails on a release that versions.json lost, and names it', () => {
    const run = check(head, data(['3.0.0'], ['3.0.0', '3.1.0']));
    assert.equal(run.status, 1);
    assert.match(run.stderr, /^::error::site\/data\/versions\.json lost 1 of its 2 releases: 3\.1\.0$/m);
    assert.doesNotMatch(run.stderr, /profile\.json/);
  });

  test('fails on a release that profile.json lost, and names it', () => {
    const run = check(head, data(['3.0.0', '3.1.0'], ['3.1.0']));
    assert.equal(run.status, 1);
    assert.match(run.stderr, /^::error::site\/data\/profile\.json lost 1 of its 2 releases: 3\.0\.0$/m);
    assert.doesNotMatch(run.stderr, /versions\.json/);
  });

  test('fails on a file that is gone', () => {
    const run = check(head, { 'versions.json': versions('3.0.0', '3.1.0') });
    assert.equal(run.status, 1);
    assert.match(run.stderr, /site\/data\/profile\.json lost 2 of its 2 releases: 3\.0\.0 3\.1\.0/);
  });

  test('names at most 20 of the releases lost, and counts them all', () => {
    const releases = Array.from({ length: 25 }, (_, i) => `3.${i}.0`);
    const run = check(data(releases), data([], releases));
    assert.equal(run.status, 1);
    const line = run.stderr.split('\n').find((text) => text.includes('versions.json'));
    assert.match(line, /lost 25 of its 25 releases: /);
    assert.equal(line.split(': ')[1].split(' ').length, 20);
  });

  test('has nothing to compare when HEAD has no such file, or a profile.json of an older format', () => {
    for (const before of [{}, { 'versions.json': versions('3.0.0') }, { 'profile.json': '{"versions":[1]}' }]) {
      const run = check(before, data(['3.0.0']));
      assert.equal(run.status, 0, run.stderr);
    }
  });

  test('fails below the root of the repository, rather than finding nothing to compare', () => {
    const run = check(head, head, 'site');
    assert.equal(run.status, 1);
    assert.match(run.stderr, /^::error::/m);
  });
});
