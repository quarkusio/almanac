# Quarkus Almanac

Pick two Quarkus releases and see what changed between them: config properties, managed dependencies (of the core BOM,
or of the platform BOM that code.quarkus.io projects import), extensions, and the classes of the Quarkus jars with
their API and deprecations. Every config property, managed artifact and class also has a page with its history.

**Site:** https://quarkus.io/almanac/

It covers every Quarkus release whose BOM (`io.quarkus:quarkus-bom`) is on Maven Central, from 0.11.0 on, pre-releases
included. Before it, the pre-launch releases 0.2.0 to 0.10.0 were never published there: the dataset has their BOM
alone, read from the poms at their git tags. 0.0.1 and 0.1.0 had no BOM, and 0.9.0 was canceled; the Releases page
lists them for reference.

## How it works

- `src/`: a Java 21 command-line tool that reads the published Quarkus artifacts from Maven Central: the BOMs, the
  config and extension metadata, and the class files of the jars. It carries the BOM poms of the pre-launch releases,
  copied byte for byte from their git tags.
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
releases published on GitHub at least 6 hours ago and any platform BOM the dataset lacks, commits them to `main` and
deploys the site. Run it from the Actions tab to add given releases.

By hand, once the release is 6 hours old (a jar not yet on Maven Central would be left out for good):

```sh
java -jar target/quarkus-almanac.jar bom --versions 3.40.2
java -jar target/quarkus-almanac.jar config --versions 3.40.2
java -jar target/quarkus-almanac.jar api --versions 3.40.2
java -jar target/quarkus-almanac.jar dataset import --versions 3.40.2 --kinds bom,config,extensions,classes,api --offline
java -jar target/quarkus-almanac.jar dataset refresh-releases --offline
java -jar target/quarkus-almanac.jar dataset verify --offline
java -jar target/quarkus-almanac.jar assemble --offline --pre-releases all
```

Then commit `dataset/` and `site/data/`; the workflow adds the release's platform BOM when it comes out. `--help` lists
the commands, and `<command> --help` their options (`dataset --help` its own commands).

The pre-launch releases are in the dataset already. Their BOMs are resolved again, without Maven Central for anything but
the `jboss-parent` poms, with `bom --versions 0.2.0,0.3.0,0.4.0,0.5.0,0.6.0,0.7.0,0.8.0,0.9.1,0.10.0 --force` and
`dataset import --kinds bom` for the same versions; `config`, `classes` and `extensions` leave them out.

## Limitations

- Only core extensions: the `io.quarkus` artifacts that `quarkus-bom` manages. The classes are those of the
  `io.quarkus` jars, not of subgroups such as `io.quarkus.arc`.
- Config before 3.25 is reconstructed from bytecode, so some details differ from the documentation of older streams.
- Descriptions are the first sentence of each property's documentation.
- Extensions start at 0.27, platform BOMs at 2.0. The platform BOM of a release is the platform release of the same
  version, and some releases have none (3.4.0, for example).
- The classes include the copies of other projects that some jars bundle. The API leaves out `quarkus-grpc-cli` and
  `quarkus-bootstrap-gradle-resolver` entirely, `quarkus-ide-launcher` outside `io.quarkus.launcher`, and the packages
  with a `shaded` segment.
- The API is everything public or protected in the class files, Quarkus internals included. Signatures are erased and
  `throws` clauses are not read, so a change of generics or `throws` alone does not show.
- Deprecation is the mark javac writes into a class file for `@Deprecated` or a javadoc `@deprecated`. As `@Deprecated`
  often has no `since`, the site also shows the first release with the mark. The members of a deprecated class are not
  marked one by one.
- Release dates are the upload times of the BOMs on Maven Central; for a pre-launch release, the day of its tagged
  commit.
- The pre-launch BOMs managed only the project's own modules: the third-party versions came into the BOM with 0.11.0.
  Before 0.10.0, Quarkus was named Shamrock, with the groupId `org.jboss.shamrock`.

Data extracted from artifacts published on Maven Central, and for the pre-launch releases, from the poms at their git
tags. Problems and ideas go to
https://github.com/quarkusio/almanac/issues.
