# Quarkus Almanac dataset, format 1

Release data of Quarkus, extracted from Maven Central by `quarkus-almanac` and committed so that no
published (immutable) release is ever downloaded again. Written by the `quarkus-almanac dataset`
commands (`import`, `remove`, `refresh-releases`) and by `all`; do not edit by hand.
`quarkus-almanac dataset verify` checks it.

## Files

UTF-8 [JSON Lines](https://jsonlines.org/): one compact JSON object per line, lines end with `\n`
(split on `\n` only), the last line too; fields in a fixed order, null fields left out; lines sorted.

| File | One line per | Fields |
|---|---|---|
| `releases.jsonl` | known release (all versions of `io.quarkus:quarkus-bom`), in version order | `version`; `date` (UTC day of the BOM's `Last-Modified`, when known); `bom: true`, `config: {source, extractor, roots}`, `extensions: true` and `classes: true` for the kinds this dataset holds |
| `bom.jsonl` | managed artifact (`groupId:artifactId[:type][:classifier]`), by id | `id`, `h` |
| `config.jsonl` | canonical config key, by key | `key`, `h` (values are config state ids) |
| `config-states.jsonl` | distinct config property, by id | `id`, `state` (every `ConfigProperty` field, as in `config.json`) |
| `config-meta.jsonl` | release with config, in version order | `version`, `source`, `extractor`, `roots`, `warnings` |
| `extensions.jsonl` | extension artifactId, by id | `id`, `h` (values are extension state ids) |
| `extension-states.jsonl` | distinct extension, by id | `id`, `state` (every `ExtensionInfo` field, as in `extensions.json`) |
| `classes/<major>.x.jsonl` | class of an `io.quarkus` jar that `quarkus-bom` manages, by id; one file per major version line (`classes/3.x.jsonl` for the 3.x releases) | `id` (`<artifactId> <class>`, the class by its binary name, e.g. `quarkus-junit-internal io.quarkus.test.QuarkusUnitTest$Builder`), `h` |

- **Change points** `h = [["<version>", value], ...]`, ascending, over the releases that hold that kind in
  `releases.jsonl` (for a classes file, those of its major line only): a value holds from its release until the
  next point, `null` means absent, nothing is recorded before the first point and only changes are. A line
  therefore changes only when its history does, and a release changes no file of another major line.
- **BOM values**: the managed version, or `"="` for "the release's own version".
- **Class values**: `"+"` while the jar has the class. A class moved to another jar ends on one line and
  starts on another.
- **State ids**: the first 16 hex digits of the SHA-256 of the state's compact JSON, exactly as the line
  holds it.
- **Order**: versions by numeric parts, then Alpha < Beta < CR < Final; ids and keys by
  UTF-16 code units (Java `String` order); state ids as text.
- **`write-in-progress`** exists only while a command rewrites the files. A folder that still has it holds
  files of an interrupted write, which may not belong together: readers refuse it.

## Restoring release R (lossless)

- `bom.json` = `{version: R, date: <date of R>, deps}`: every id whose value at R is not null, sorted by id,
  `"="` read as R.
- `config.json` = the `config-meta.jsonl` line of R plus `properties`: the state at R of every key, sorted by
  key.
- `extensions.json` = `{version: R, extensions}`: the state at R of every id, sorted by id.
- `classes.json` = `{version: R, artifacts}`: from the classes file of R's major line, per artifactId,
  `{id, classes}` with the class of every line of that artifactId whose value at R is not null, sorted; only
  artifactIds with a class, sorted.
