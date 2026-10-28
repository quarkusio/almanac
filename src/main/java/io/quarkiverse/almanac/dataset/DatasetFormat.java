package io.quarkiverse.almanac.dataset;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectReader;
import io.quarkiverse.almanac.util.Json;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/// Files and encoding rules of the committed dataset, format 1 (`dataset/README.md`, written by
/// [#readme()], describes them for readers of the data).
public final class DatasetFormat {

    /// The format version this build reads and writes.
    public static final String VERSION = "1";

    /// The default dataset folder, relative to the working directory like `--work work`.
    public static final String DEFAULT_DIR = "dataset";

    public static final String README = "README.md";
    public static final String RELEASES = "releases.jsonl";
    public static final String BOM = "bom.jsonl";
    public static final String PLATFORM = "platform-bom.jsonl";
    public static final String CONFIG = "config.jsonl";
    public static final String CONFIG_STATES = "config-states.jsonl";
    public static final String CONFIG_META = "config-meta.jsonl";
    public static final String EXTENSIONS = "extensions.jsonl";
    public static final String EXTENSION_STATES = "extension-states.jsonl";

    /// Every file of the dataset, in writing order.
    public static final List<String> FILES = List.of(RELEASES, BOM, PLATFORM, CONFIG, CONFIG_STATES, CONFIG_META,
            EXTENSIONS, EXTENSION_STATES, README);

    /// Exists in the dataset folder only while [DatasetEditor#write()] replaces files. The files change one after the
    /// other and may not belong together until the last one is written (a release added to `releases.jsonl` before
    /// its change points restores as the release before it), so a marker left behind by an interrupted write makes
    /// readers refuse the folder.
    public static final String WRITE_MARKER = "write-in-progress";

    /// BOM history value for "the managed version is the release's own version".
    public static final String SAME_AS_RELEASE = "=";

    /// Config extractors whose snapshots are not validated: `dataset import` refuses them, so they never reach the
    /// dataset or the site. `bytecode/1` is the bytecode reader of releases before 3.25 while it was being finished;
    /// the finished reader is `bytecode/2`.
    public static final Set<String> UNVALIDATED_EXTRACTORS = Set.of("bytecode/1");

    /// Start of the bytecode reader's warnings about `@ConfigDoc*` annotations it could not read from the `-sources`
    /// jars of a release before 3.14 (`doc annotations unavailable…`, `doc annotations: …`): `dataset import` refuses
    /// such a config as not validated, since map keys and documented defaults may be wrong.
    public static final String DOC_ANNOTATION_WARNING = "doc annotations";

    /// First line of `README.md`; readers check it to recognise the format.
    static final String README_TITLE = "# Quarkus Almanac dataset, format " + VERSION;

    private static final int STATE_ID_HEX_DIGITS = 16;

    /// Reads one line strictly. The shared mapper is lenient: it ignores whatever follows the first JSON value (such as
    /// a second line joined to the first by a lost newline) and keeps the last of repeated fields.
    private static final ObjectReader LINE_READER = Json.mapper().reader()
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION);

    private DatasetFormat() {
    }

    /// The canonical JSON of a record: compact, fields in record component order, null fields omitted (the
    /// snapshot serialisation of `io.quarkiverse.almanac.util.Json` without pretty-printing), no trailing newline.
    public static String canonicalJson(Object value) {
        try {
            return Json.mapper().writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    /// The content-addressed id of a state: the first 16 hex digits (lower case) of the SHA-256 of its canonical JSON
    /// in UTF-8.
    public static String stateId(String canonicalJson) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonicalJson.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, STATE_ID_HEX_DIGITS);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /// Splits file content into lines at `\n` only (never at other line separators that descriptions may contain).
    /// A `\r` before the `\n` (a checkout with CRLF line ends) is dropped, and the empty piece after the final
    /// newline is not a line.
    static List<String> lines(String content) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        while (start < content.length()) {
            int end = content.indexOf('\n', start);
            if (end < 0) {
                end = content.length();
            }
            String line = content.substring(start, end);
            lines.add(line.endsWith("\r") ? line.substring(0, line.length() - 1) : line);
            start = end + 1;
        }
        return lines;
    }

    /// Parses one line of a dataset file: exactly one JSON value with nothing after it, and no field twice.
    static JsonNode parseLine(String line) throws JsonProcessingException {
        return LINE_READER.readTree(line);
    }

    /// The content of [#WRITE_MARKER]: what it means and which files the write replaces.
    static String writeMarker(List<String> files) {
        return "A quarkus-almanac write of this dataset folder started and has not finished.\n"
                + "Unless one is running now, it was interrupted, and these files may not belong together:\n\n"
                + String.join("\n", files) + "\n\n"
                + "Restore the folder (for example: git checkout -- <folder>) and repeat the change.\n";
    }

    /// The message of a reader that finds [#WRITE_MARKER].
    static String interruptedWrite(Path marker) {
        return marker + " exists: a write of the dataset is running or was interrupted, so its files may not belong "
                + "together; restore the folder (for example with git checkout) and repeat the change";
    }

    /// The dataset's `README.md`. It is generated, so writing the same dataset twice gives the same bytes.
    public static String readme() {
        return README_TITLE + """


                Release data of Quarkus, extracted from Maven Central by `quarkus-almanac` and committed so that no
                published (immutable) release is ever downloaded again. Written by the `quarkus-almanac dataset`
                commands (`import`, `remove`, `refresh-releases`) and by `all`; do not edit by hand.
                `quarkus-almanac dataset verify` checks it.

                ## Files

                UTF-8 [JSON Lines](https://jsonlines.org/): one compact JSON object per line, lines end with `\\n`
                (split on `\\n` only), the last line too; fields in a fixed order, null fields left out; lines sorted.

                | File | One line per | Fields |
                |---|---|---|
                | `releases.jsonl` | known release (all versions of `io.quarkus:quarkus-bom`), in version order | `version`; `date` (UTC day of the BOM's `Last-Modified`, when known); `bom: true`, `config: {source, extractor, roots}`, `extensions: true` and `platform: {date}` (the UTC day of the platform BOM's `Last-Modified`, when known) for the kinds this dataset holds |
                | `bom.jsonl` | managed artifact (`groupId:artifactId[:type][:classifier]`), by id | `id`, `h` |
                | `platform-bom.jsonl` | artifact that the platform BOM `io.quarkus.platform:quarkus-bom` of the same version manages otherwise than the release's BOM, by id | `id`, `h` |
                | `config.jsonl` | canonical config key, by key | `key`, `h` (values are config state ids) |
                | `config-states.jsonl` | distinct config property, by id | `id`, `state` (every `ConfigProperty` field, as in `config.json`) |
                | `config-meta.jsonl` | release with config, in version order | `version`, `source`, `extractor`, `roots`, `warnings` |
                | `extensions.jsonl` | extension artifactId, by id | `id`, `h` (values are extension state ids) |
                | `extension-states.jsonl` | distinct extension, by id | `id`, `state` (every `ExtensionInfo` field, as in `extensions.json`) |

                - **Change points** `h = [["<version>", value], ...]`, ascending, over the releases that hold that kind in
                  `releases.jsonl`: a value holds from its release until the next point, `null` means absent, nothing is
                  recorded before the first point and only changes are. A line therefore changes only when its history does.
                - **BOM values**: the managed version, or `"="` for "the release's own version".
                - **Platform BOM values** say how the platform BOM differs from the release's BOM, over the releases
                  with `platform`: its managed version (`"="` for the release's own), `"-"` when only the release's
                  BOM manages the id, `null` when both manage it alike (or neither does).
                - **State ids**: the first 16 hex digits of the SHA-256 of the state's compact JSON, exactly as the line
                  holds it.
                - **Order**: versions by numeric parts, then Alpha < Beta < CR < Final; ids and keys by
                  UTF-16 code units (Java `String` order); state ids as text.
                - **`write-in-progress`** exists only while a command rewrites the files. A folder that still has it holds
                  files of an interrupted write, which may not belong together: readers refuse it.

                ## Restoring release R (lossless)

                - `bom.json` = `{version: R, date: <date of R>, deps}`: every id whose value at R is not null, sorted by id,
                  `"="` read as R.
                - `platform-bom.json` = `{version: R, date: <platform date of R>, deps}`: the deps of `bom.json` with
                  every value of `platform-bom.jsonl` at R that is not null applied (a version replaces or adds the id,
                  `"="` read as R, `"-"` removes it), sorted by id.
                - `config.json` = the `config-meta.jsonl` line of R plus `properties`: the state at R of every key, sorted by
                  key.
                - `extensions.json` = `{version: R, extensions}`: the state at R of every id, sorted by id.
                """;
    }
}
