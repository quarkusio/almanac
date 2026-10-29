# Quarkus Almanac dataset, format 1

Release data of Quarkus, extracted from Maven Central by `quarkus-almanac` and committed so that no
published (immutable) release is ever downloaded again. The pre-launch releases of December 2018 to
March 2019, never published there, have their BOM alone, read from the poms at their git tag. Written
by the `quarkus-almanac dataset` commands (`import`, `remove`, `refresh-releases`) and by `all`; do not
edit by hand. `quarkus-almanac dataset verify` checks it.

## Files

UTF-8 [JSON Lines](https://jsonlines.org/): one compact JSON object per line, lines end with `\n`
(split on `\n` only), the last line too; fields in a fixed order, null fields left out; lines sorted.

| File | One line per | Fields |
|---|---|---|
| `releases.jsonl` | known release (all versions of `io.quarkus:quarkus-bom` on Maven Central, and the releases never published there whose BOM comes from their git tag), in version order | `version`; `date` (UTC day of the BOM's `Last-Modified`, when known; for a release from a git tag, of the tag's commit); `bom: true`, `git: {tag, commit, bom}` (for a release never published on Maven Central: its tag in github.com/quarkusio/quarkus, the commit the tag names, and the BOM's `groupId:artifactId` there, e.g. `org.jboss.shamrock:shamrock-bom` before 0.10.0; the BOM is resolved from the poms at that commit, with the parents and imported BOMs that Maven Central has, and the release has no other kind), `config: {source, extractor, roots}`, `extensions: true`, `classes: true`, `platform: {date}` (the UTC day of the platform BOM's `Last-Modified`, when known) and `api: {reader}` (the reader of the API data, e.g. `api/1`) for the kinds this dataset holds |
| `bom.jsonl` | managed artifact (`groupId:artifactId[:type][:classifier]`), by id | `id`, `h` |
| `platform-bom.jsonl` | artifact that the platform BOM `io.quarkus.platform:quarkus-bom` of the same version manages otherwise than the release's BOM, by id | `id`, `h` |
| `config.jsonl` | canonical config key, by key | `key`, `h` (values are config state ids) |
| `config-states.jsonl` | distinct config property, by id | `id`, `state` (every `ConfigProperty` field, as in `config.json`) |
| `config-meta.jsonl` | release with config, in version order | `version`, `source`, `extractor`, `roots`, `warnings` |
| `extensions.jsonl` | extension artifactId, by id | `id`, `h` (values are extension state ids) |
| `extension-states.jsonl` | distinct extension, by id | `id`, `state` (every `ExtensionInfo` field, as in `extensions.json`) |
| `classes/<major>.x.jsonl` | class of an `io.quarkus` jar that `quarkus-bom` manages, by id; one file per major version line (`classes/3.x.jsonl` for the 3.x releases) | `id` (`<artifactId> <class>`, the class by its binary name, e.g. `quarkus-junit-internal io.quarkus.test.QuarkusUnitTest$Builder`), `h` |
| `api/<major>.x.jsonl` | API class of the classes files (see **API values**), and public or protected field, method or constructor of it, by id; one file per major version line, like the classes files | `id` (`<artifactId> <class>` for a class, as in the classes files; `<artifactId> <class> <member>` for a member), `h` |

- **Change points** `h = [["<version>", value], ...]`, ascending, over the releases that hold that kind in
  `releases.jsonl` (for a classes or API file, those of its major line only): a value holds from its
  release until the next point, `null` means absent, nothing is recorded before the first point and only
  changes are. A line therefore changes only when its history does, and a release changes no file of
  another major line.
- **BOM values**: the managed version, or `"="` for "the release's own version".
- **Platform BOM values** say how the platform BOM differs from the release's BOM, over the releases
  with `platform`: its managed version (`"="` for the release's own), `"-"` when only the release's
  BOM manages the id, `null` when both manage it alike (or neither does).
- **Class values**: `"+"` while the jar has the class. A class moved to another jar ends on one line and
  starts on another.
- **API values**, read from the class files: a class is API when it is public or protected (a nested class
  by its own `InnerClasses` entry), every enclosing class is too, and it is neither synthetic, anonymous
  nor local; its members are its public and protected fields, methods and constructors that are neither
  synthetic nor bridges, `<clinit>` left out. The classes are those of the classes files except the copies
  of other projects that some jars bundle: none of `quarkus-grpc-cli` and
  `quarkus-bootstrap-gradle-resolver`, none of `quarkus-ide-launcher` outside the package
  `io.quarkus.launcher`, and none in a package with a `shaded` segment. A class line holds
  `<access><kind><modifiers>[X][D|R][ extends <class>][ implements <class>,<class>...][ since <text>]`, a
  member line `<access><modifiers>[X][D|R][ since <text>]`:
  - access `+` public, `#` protected; kind none for a class, `i` interface, `n` annotation, `e` enum, `r`
    record;
  - modifiers in this order, implicit ones left out: of a class of no kind (the others have none), `a`
    abstract, `f` final, `s` static (a nested class, but not a member class of an interface or an
    annotation, which is static implicitly); of a member, `s` static, `a` abstract, `f` final, `d`
    default (an interface method with a body), `v` varargs, `e` enum constant (the fields of an
    interface and the enum constants are not marked `sf`, the abstract methods of an interface not `a`);
  - `X` for `@io.smallrye.common.annotation.Experimental`; `D` deprecated (the `Deprecated` attribute, which
    javac writes for `@Deprecated` and for a javadoc `@deprecated`, or a `@java.lang.Deprecated`
    annotation), `R` deprecated for removal (`forRemoval = true`), never both;
  - `extends` the superclass by binary name, left out for `java.lang.Object`, `java.lang.Enum`,
    `java.lang.Record` and interfaces; `implements` the interfaces in declaration order (of an interface,
    its super-interfaces), `java.lang.annotation.Annotation` left out, comma-separated;
  - `since` the non-blank `since` of `@Deprecated` as written, to the end of the value.

  A member is `<name><descriptor>` for a method or constructor (`<init>()V`,
  `get(I)Ljava/lang/Object;`) and `<name>:<descriptor>` for a field (`INSTANCE:Lio/quarkus/Foo;`), JVM
  descriptors; names never hold whitespace, `(` or `:`. A member line has a value only where its class
  line has one; `null` means absent or not API (the classes files tell which).
- **State ids**: the first 16 hex digits of the SHA-256 of the state's compact JSON, exactly as the line
  holds it.
- **Order**: versions by numeric parts, then Alpha < Beta < CR < Final; ids and keys by
  UTF-16 code units (Java `String` order); state ids as text.
- **`write-in-progress`** exists only while a command rewrites the files. A folder that still has it holds
  files of an interrupted write, which may not belong together: readers refuse it.

## Restoring release R (lossless)

- `bom.json` = `{version: R, date: <date of R>, git: <git of R, when its line has one>, deps}`: every id
  whose value at R is not null, sorted by id, `"="` read as R.
- `platform-bom.json` = `{version: R, date: <platform date of R>, deps}`: the deps of `bom.json` with
  every value of `platform-bom.jsonl` at R that is not null applied (a version replaces or adds the id,
  `"="` read as R, `"-"` removes it), sorted by id.
- `config.json` = the `config-meta.jsonl` line of R plus `properties`: the state at R of every key, sorted by
  key.
- `extensions.json` = `{version: R, extensions}`: the state at R of every id, sorted by id.
- `classes.json` = `{version: R, artifacts}`: from the classes file of R's major line, per artifactId,
  `{id, classes}` with the class of every line of that artifactId whose value at R is not null, sorted; only
  artifactIds with a class, sorted.
- `api.json` = `{version: R, reader: <the API reader of R in releases.jsonl>, artifacts}`: from the API file
  of R's major line, per artifactId, `{id, classes}` with `{name, v, members}` for every class line of that
  artifactId whose value `v` at R is not null, `members` the `{id, v}` of its member lines whose value at R
  is not null (`id` the member part of the line's id); classes and members sorted, only artifactIds with a
  class, sorted.
