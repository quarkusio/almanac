// Fails when assemble dropped a release that HEAD's site data has. A run adds releases and never removes one, no test
// reads the assembled files, and profile.json holds the only copy of the dependency profiles. Run from the repository
// root, before the commit.
import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';

const names = {
  'versions.json': (data) => data.versions.map((row) => row.v),
  // a profile.json of an older format names no release, so there is nothing to compare
  'profile.json': (data) => data.releases ?? [],
};
const git = (...args) => execFileSync('git', args, { encoding: 'utf8', maxBuffer: 2 ** 28 });

let dropped = 0;
for (const [file, releases] of Object.entries(names)) {
  const path = `site/data/${file}`;
  const before = git('ls-tree', '--full-tree', 'HEAD', '--', path)
    ? releases(JSON.parse(git('show', `HEAD:${path}`)))
    : [];
  const after = new Set(existsSync(path) ? releases(JSON.parse(readFileSync(path, 'utf8'))) : []);
  const gone = before.filter((release) => !after.has(release));
  if (gone.length > 0) {
    console.error(`::error::site/data/${file} lost ${gone.length} of its ${before.length} releases: `
      + gone.slice(0, 20).join(' '));
    dropped += gone.length;
  }
}
process.exit(dropped > 0 ? 1 : 0);
