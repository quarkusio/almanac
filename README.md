# Quarkus Almanac

Pick two Quarkus releases and see what changed between them: config properties (added, removed, new default, type,
phase, deprecation), dependency versions managed by `quarkus-bom`, and extensions.

**Site:** https://quarkus.io/almanac/

It covers Quarkus 3.0 and later, pre-releases included, with config taken at every patch of 3.0 and of the LTS streams,
and at the newest patch of the other streams.

## How it works

- `src/`: a Java 21 command-line tool that reads published Quarkus artifacts from Maven Central: the BOM of every
  release, and the config and extension metadata of the releases it covers (from the config model Quarkus ships from
  3.25 on, reconstructed from bytecode before that).
- `dataset/`: every extracted release, kept losslessly as sorted JSON Lines (`dataset/README.md` describes the
  format), so a published release never has to be downloaded again.
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

Downloads are polite: one request at a time, with a random 0.5–1.5 s pause between requests (`--pause`), shared by every
command that uses the same `work/` folder, and they stop at the first HTTP 403. An uncached release costs about
800 requests, roughly 20 minutes; a release already in the dataset costs nothing.

## Limitations

- Only core extensions: the `io.quarkus` artifacts managed by `quarkus-bom`.
- Config before 3.25 is reconstructed from bytecode with the 3.25 documentation rules, so some display details differ
  from the documentation pages of older streams.
- Descriptions are the first sentence of each property's documentation.
- Release dates are the upload times of the BOMs on Maven Central.

Data extracted from artifacts published on Maven Central. Problems and ideas go to
https://github.com/quarkusio/almanac/issues.
