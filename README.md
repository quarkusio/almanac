# Quarkus Almanac

Pick two Quarkus releases and see what changed between them: config properties (added, removed, new default, type,
phase, deprecation), dependency versions managed by `quarkus-bom` (the core BOM `io.quarkus:quarkus-bom`, or the platform
BOM `io.quarkus.platform:quarkus-bom` that projects from code.quarkus.io import), extensions, and the classes of the
Quarkus jars (added, removed, moved to another jar). Every config property, managed artifact and class has a page with
its history.

**Site:** https://quarkus.io/almanac/

It covers Quarkus 3.0 and later, pre-releases included, with config taken at every patch of 3.0 and of the LTS streams,
and at the newest patch of the other streams.

## How it works

- `src/`: a Java 21 command-line tool that reads published Quarkus artifacts from Maven Central: the BOM and the
  platform BOM of every release, and the config and extension metadata of the releases it covers (from the config model
  Quarkus ships from 3.25 on, reconstructed from bytecode before that), with the class list of each of their jars.
- `dataset/`: every extracted release, kept losslessly as sorted JSON Lines (`dataset/README.md` describes the
  format), so a published release never has to be downloaded again. A platform BOM is kept as its few differences from
  the core BOM of the same version. The classes have one file per major version line (`dataset/classes/3.x.jsonl`), and
  so does the site (`site/data/classes-3.json`), which loads only the lines a page shows.
- `site/`: a static site without a build step. `site/data/` is assembled from the dataset, and every comparison is
  computed in the browser.

## Build and run

Requirements: JDK 21+, Maven 3.9+, and Node.js for the site tests.

```sh
mvn package                 # builds target/quarkus-almanac.jar and runs the tests
node --test site/tests/     # site logic tests
```

To browse the site locally, serve the `site` folder with any static web server, for example
`python -m http.server -d site 8000`, and open http://localhost:8000/.

## Adding a release

```sh
java -jar target/quarkus-almanac.jar bom --versions 3.40.2
java -jar target/quarkus-almanac.jar config --versions 3.40.2
java -jar target/quarkus-almanac.jar dataset import --versions 3.40.2 --offline
java -jar target/quarkus-almanac.jar dataset refresh-releases --offline
java -jar target/quarkus-almanac.jar dataset verify --offline
java -jar target/quarkus-almanac.jar assemble --offline --pre-releases all
```

Then commit `dataset/` and `site/data/`. `all` runs the whole pipeline for the releases the dataset does not have yet,
and `--help` lists every command and option.

The platform BOM of a release often comes out days after it. `platform --missing --pre-releases all` lists the releases
of the dataset whose platform BOM is out and not in the dataset yet; `platform --pre-releases all` downloads them, and
`dataset import --versions all --kinds platform --offline` adds them.

`config` writes each release's `classes.json` from the same reads of its jars. For releases extracted before it did,
`classes --offline` writes it from what the cache holds; `--cache` adds the caches of other work folders, which it only
reads:

```sh
java -jar target/quarkus-almanac.jar classes --offline --pre-releases all --cache ../other-work/cache
java -jar target/quarkus-almanac.jar dataset import --versions all --kinds classes --offline
```

New releases come in on their own: every three hours, the workflow `.github/workflows/new-releases.yml` looks for GitHub
releases of quarkusio/quarkus published at least 6 hours earlier that Maven Central serves and the dataset lacks,
extracts them on up to four runners at once, adds them to the dataset together with the platform BOMs that came out
since the last run, rebuilds `site/data/`, commits both to `main` and starts the Pages deploy. Run it by hand from the
Actions tab to add given releases.

Downloads are polite: one request at a time per `work/` folder, with a random 0.5–1.5 s pause between requests
(`--pause`, down to 0.001), shared by every command that uses that folder, and they stop at the first HTTP 403. The
workflow gives each of its runners a folder of its own and a 1 ms pause, so up to four request streams run at once, each
stopping on its own. An uncached release costs about 800 requests, roughly 20 minutes; a release already in the dataset
costs nothing.

## Limitations

- Only core extensions: the `io.quarkus` artifacts managed by `quarkus-bom`.
- Config before 3.25 is reconstructed from bytecode with the 3.25 documentation rules, so some display details differ
  from the documentation pages of older streams.
- Descriptions are the first sentence of each property's documentation.
- Classes are the class files of the `io.quarkus` jars that `quarkus-bom` manages (groupId `io.quarkus` only, not
  subgroups such as `io.quarkus.arc`), top-level and nested, read from each jar's file list: classes a jar bundles from
  its dependencies count as its own (`quarkus-grpc-cli`, `quarkus-bootstrap-gradle-resolver`), and nothing tells
  whether a class is deprecated. That could come from the `-javadoc` jars, whose `deprecated-list.html` lists the
  deprecated classes: one more read per jar, and a value other than `"+"` in the classes files of the dataset.
- Release dates are the upload times of the BOMs on Maven Central.
- The platform BOM shown for a release is the platform release of the same version; some releases have none (3.4.0,
  for example).

Data extracted from artifacts published on Maven Central. Problems and ideas go to
https://github.com/quarkusio/almanac/issues.
