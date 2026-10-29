# Quarkus Almanac

Pick two Quarkus releases and see what changed between them: config properties, managed dependencies (of the core BOM,
or of the platform BOM that code.quarkus.io projects import), extensions, and the classes of the Quarkus jars with
their API and deprecations. Every config property, managed artifact and class also has a page with its history.

**Site:** https://quarkus.io/almanac/

It covers every Quarkus release from 0.11.0 on, pre-releases included. Releases before 3.8 are listed with the "Show
old releases" switch.

## How it works

- `src/`: a Java 21 command-line tool that reads the published Quarkus artifacts from Maven Central: the BOMs, the
  config and extension metadata, and the class files of the jars.
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

New releases come in on their own: every twelve hours, the workflow `.github/workflows/new-releases.yml` adds the
releases published on GitHub at least 6 hours earlier and the platform BOMs that came out since, commits them to
`main` and deploys the site. Run it from the Actions tab to add given releases.

By hand:

```sh
java -jar target/quarkus-almanac.jar bom --versions 3.40.2
java -jar target/quarkus-almanac.jar config --versions 3.40.2
java -jar target/quarkus-almanac.jar api --versions 3.40.2
java -jar target/quarkus-almanac.jar dataset import --versions 3.40.2 --kinds bom,config,extensions,classes,api --offline
java -jar target/quarkus-almanac.jar dataset refresh-releases --offline
java -jar target/quarkus-almanac.jar dataset verify --offline
java -jar target/quarkus-almanac.jar assemble --offline --pre-releases all
```

Then commit `dataset/` and `site/data/`. `all` runs the whole pipeline for the releases the dataset does not have yet,
and `--help` lists every command and option.

## Limitations

- Only core extensions: the `io.quarkus` artifacts that `quarkus-bom` manages. The classes are those of the
  `io.quarkus` jars, not of subgroups such as `io.quarkus.arc`.
- Config before 3.25 is reconstructed from bytecode, so some details differ from the documentation of older streams.
- Descriptions are the first sentence of each property's documentation.
- Extensions start at 0.27, platform BOMs at 2.0. The platform BOM of a release is the platform release of the same
  version, and some releases have none (3.4.0, for example).
- The classes include those that some jars bundle from other libraries (`quarkus-grpc-cli`, for one); the API leaves
  them out. The API is everything public or protected in the class files, Quarkus internals included. Signatures are
  erased, so a change of generics alone does not show, and `throws` clauses and javadoc are not read.
- Deprecation comes from `@Deprecated` or a javadoc `@deprecated`. Its `since` is often missing, so the site also shows
  the first release with the mark. The members of a deprecated class are not marked one by one.
- Release dates are the upload times of the BOMs on Maven Central.

Data extracted from artifacts published on Maven Central. Problems and ideas go to
https://github.com/quarkusio/almanac/issues.
