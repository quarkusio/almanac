package io.quarkiverse.almanac.config.sourcedoc;

import io.quarkiverse.almanac.config.JarSource;
import io.quarkiverse.almanac.config.VersionJars;
import io.quarkiverse.almanac.maven.BomSnapshots;
import io.quarkiverse.almanac.maven.Http;
import io.quarkiverse.almanac.maven.MavenRepo;
import io.quarkiverse.almanac.maven.Pacer;
import io.quarkiverse.almanac.maven.QuarkusVersion;
import io.quarkiverse.almanac.maven.RemoteJar;
import io.quarkiverse.almanac.util.AtomicFiles;
import io.quarkiverse.almanac.util.Log;
import io.quarkiverse.almanac.util.Parallel;
import io.quarkiverse.almanac.util.SnapshotStore;
import org.objectweb.asm.tree.ClassNode;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;

/// Self-check of [SourceDocAnnotations] on one release, run as
/// `java -cp quarkus-almanac.jar io.quarkiverse.almanac.config.sourcedoc.SourceDocCheck <version> [--work DIR]`.
///
/// The release's jars come from its BOM snapshot ([VersionJars#of]). The _checked jars_ are the jars
/// with {@value ConfigMembers#ROOTS_ENTRY} plus the jars that declare config types reachable from those roots (groups
/// and super-types that live elsewhere, e.g. in `quarkus-oidc-common`).
///
/// - **Coverage** (every release): the config members the bytecode reader visits ([ConfigMembers]) are
///   looked up as the reader does, and the report gives owners resolved, sources jars found or missing, files
///   parsed, parse failures, annotated members per annotation and unresolved constant values.
/// - **Agreement** (releases from {@value #RUNTIME_RETENTION_SINCE}, whose class files carry the annotations):
///   every field and method of a checked jar's classes that carries one of the four annotations must get the same
///   [DocAnnotations] from the sources (from [SourceDocAnnotations#get] for the members config readers
///   ask for, interface methods and class fields; from the member of the same kind otherwise); and every annotated
///   member found by parsing the checked jars' sources must carry the same annotations in its class file.
///   Annotated members that no root reaches are compared too, and listed.
///
/// The report goes to stdout (and to `--report FILE`), progress and warnings to stderr. Exit status 0 means the
/// check passed: no mismatch from {@value #RUNTIME_RETENTION_SINCE} on; no missing sources jar and no unparsable file
/// before.
@Command(name = "SourceDocCheck", mixinStandardHelpOptions = true,
        description = "Checks the @ConfigDoc* annotations read from -sources jars against one Quarkus release.")
public final class SourceDocCheck implements Callable<Integer> {

    /// First release whose class files carry the doc annotations (RUNTIME retention).
    static final String RUNTIME_RETENTION_SINCE = "3.14.0";

    private static final String CLASS_SUFFIX = ".class";
    private static final String JAVA_SUFFIX = ".java";
    /// Every doc annotation name contains it, so source files without it need no parsing.
    private static final byte[] DOC_MARKER = "ConfigDoc".getBytes(StandardCharsets.US_ASCII);

    @Parameters(index = "0", paramLabel = "VERSION", description = "Quarkus release, e.g. 3.8.6.1.")
    String version;

    @Option(names = "--work", defaultValue = "work", paramLabel = "DIR",
            description = "Working folder with the HTTP cache and the BOM snapshots (default: ${DEFAULT-VALUE}).")
    Path work;

    @Option(names = "--repo", paramLabel = "URL", description = "Maven repository (default: Maven Central).")
    URI repository = MavenRepo.CENTRAL;

    @Option(names = "--concurrency", defaultValue = "8", paramLabel = "N",
            description = "Maximum parallel HTTP requests (default: ${DEFAULT-VALUE}).")
    int concurrency;

    @Option(names = "--pause", defaultValue = "0.5-1.5", paramLabel = "SECONDS",
            description = "Random pause between two requests to the repository, in seconds: 2-5, 3, or 0 for none; "
                    + "Maven Central needs at least 0.001, one request at a time (default: ${DEFAULT-VALUE}).")
    String pause;

    @Option(names = "--offline", description = "Use cached files only; fail when something is not cached.")
    boolean offline;

    @Option(names = "--full-jar-max", defaultValue = "268435456", paramLabel = "BYTES",
            description = "Jars up to this size are downloaded whole when an entry is read (default: ${DEFAULT-VALUE}:"
                    + " the check reads most entries of the checked jars, so one download beats many ranges).")
    long fullJarMax;

    @Option(names = "--report", paramLabel = "FILE", description = "Also write the report to this file.")
    Path reportFile;

    @Option(names = "--verbose", description = "Log every HTTP request and other details.")
    boolean verbose;

    public static void main(String[] args) {
        CommandLine commandLine = new CommandLine(new SourceDocCheck())
                .setExecutionExceptionHandler((e, command, parseResult) -> {
                    Log.error("%s", e.getMessage() != null ? e.getMessage() : e.toString());
                    if (Log.isVerbose()) {
                        e.printStackTrace();
                    }
                    return 2;
                });
        System.exit(commandLine.execute(args));
    }

    @Override
    public Integer call() throws IOException {
        Log.setVerbose(verbose);
        long start = System.nanoTime();
        Pacer.Pause pacing = Pacer.Pause.parse(pause);
        Http.checkPause(pacing, repository);
        try (Http http = new Http(concurrency, offline, Pacer.forCache(pacing, work.resolve("cache")))) {
            MavenRepo repo = new MavenRepo(repository, work.resolve("cache"), http,
                    new RemoteJar.Options(RemoteJar.Options.DEFAULT_TAIL_SIZE, fullJarMax));
            SnapshotStore store = new SnapshotStore(work.resolve("snapshots"));
            BomSnapshots boms = new BomSnapshots(repo, store);
            String release = store.exists(version, SnapshotStore.BOM)
                    ? version
                    : boms.selector().explicit(List.of(version)).get(0).text();
            VersionJars jars = VersionJars.of(boms.load(release, false), repo);
            Report report = new Checker(jars, SourceDocAnnotations.open(repo, jars), http.concurrency()).run();
            String text = report.format();
            System.out.print(text);
            System.out.flush();
            if (reportFile != null) {
                AtomicFiles.writeString(reportFile, text);
            }
            Log.info("HTTP: %d requests, %.1f MB received, %.1f s elapsed", http.requestCount(),
                    http.bytesReceived() / 1e6, (System.nanoTime() - start) / 1e9);
            return report.passed() ? 0 : 1;
        }
    }

    /// Whether the class files of a release carry the doc annotations.
    static boolean hasRuntimeRetention(String release) {
        return QuarkusVersion.parse(release).compareNumbers(QuarkusVersion.parse(RUNTIME_RETENTION_SINCE)) >= 0;
    }

    /// A member whose annotations differ between class file and source.
    ///
    /// @param member             `owner#name`, owner as internal name
    /// @param method             true for a method, false for a field
    /// @param inClass            annotations in the class file, null when the class or member is missing
    /// @param inSources          annotations read from the sources
    /// @param annotatedInSources whether the source carries a doc annotation, even one whose value is unknown
    record Mismatch(String member, boolean method, DocAnnotations inClass, DocAnnotations inSources,
            boolean annotatedInSources) {

        @Override
        public String toString() {
            String sources = annotatedInSources && inSources.equals(DocAnnotations.NONE)
                    ? "an annotation whose value is not a constant of its file"
                    : describe(inSources);
            return member + (method ? "()" : "") + ": class file "
                    + (inClass == null ? "has no such member" : describe(inClass)) + ", sources " + sources;
        }
    }

    /// One direction of the comparison.
    ///
    /// @param units      classes read (class files to sources) or `.java` files (sources to class files)
    /// @param parsed     source files parsed because they mention a doc annotation (sources to class files only)
    /// @param members    the annotated members compared, `owner#field` or `owner#method()`, sorted
    /// @param skipped    annotated methods with parameters, which no config reader asks for (class files only)
    /// @param counts     the compared members per annotation, as found on the side that was read
    /// @param mismatches every member that differs, sorted
    record Comparison(int units, int parsed, List<String> members, int skipped,
            SourceDocAnnotations.AnnotationCounts counts, List<Mismatch> mismatches) {

        int compared() {
            return members.size();
        }

        int agreeing() {
            return compared() - mismatches.size();
        }
    }

    /// The outcome of a check.
    ///
    /// @param release        the Quarkus version
    /// @param runtime        whether its class files carry the doc annotations (then `classes` and
    ///                       `sources` are set)
    /// @param candidates     jars listed by the BOM
    /// @param existing       jars that exist
    /// @param configJars     artifactIds of the jars with config roots
    /// @param ownerJars      artifactIds of the other jars that declare reachable config types
    /// @param members        the config members the bytecode reader visits
    /// @param coverage       statistics right after looking those members up
    /// @param classes        class files to sources, or null
    /// @param sources        sources to class files, or null
    /// @param total          statistics at the end of the check
    /// @param warnings       every warning of the annotation source
    record Report(String release, boolean runtime, int candidates, int existing, List<String> configJars,
            List<String> ownerJars, ConfigMembers.Result members, SourceDocAnnotations.Stats coverage,
            Comparison classes, Comparison sources, SourceDocAnnotations.Stats total, List<String> warnings) {

        /// The annotated members of the class files that are not among the config members the bytecode reader visits
        /// (e.g. in a class of a config jar that no root reaches); they are compared all the same.
        List<String> notVisited() {
            if (classes == null) {
                return List.of();
            }
            Set<String> visited = new HashSet<>();
            members.members().forEach(member -> visited.add(member.key()));
            return classes.members().stream().filter(member -> !visited.contains(member)).toList();
        }

        boolean passed() {
            if (runtime) {
                return classes.mismatches().isEmpty() && sources.mismatches().isEmpty();
            }
            return coverage.sourcesJarsMissing() == 0 && coverage.filesUnparsable() == 0;
        }

        String format() {
            StringBuilder out = new StringBuilder();
            line(out, "Source doc annotations of Quarkus %s", release);
            line(out, "  %s", runtime
                    ? "class files carry the annotations (RUNTIME retention since " + RUNTIME_RETENTION_SINCE
                            + "): coverage and agreement in both directions"
                    : "class files lack the annotations (SOURCE retention before " + RUNTIME_RETENTION_SINCE
                            + "): coverage only");
            line(out, "");
            line(out, "Jars");
            line(out, "  %,d io.quarkus jars in the BOM, %,d exist, %,d with %s", candidates, existing,
                    configJars.size(), ConfigMembers.ROOTS_ENTRY);
            line(out, "  checked: %,d jars (%,d with config roots + %,d declaring reachable config types%s)",
                    configJars.size() + ownerJars.size(), configJars.size(), ownerJars.size(),
                    ownerJars.isEmpty() ? "" : ": " + String.join(", ", ownerJars));
            line(out, "");
            line(out, "Coverage: the config members the bytecode reader looks up");
            Set<String> owners = new LinkedHashSet<>();
            members.members().forEach(member -> owners.add(member.owner()));
            line(out, "  %,d members of %,d types (%,d types visited: roots, super-types, groups)",
                    members.members().size(), owners.size(), members.types());
            if (!members.missingClasses().isEmpty()) {
                line(out, "  %,d roots or super-types in no io.quarkus jar: %s", members.missingClasses().size(),
                        String.join(", ", members.missingClasses()));
            }
            line(out, "  lookups: %,d, %,d with annotations", coverage.lookups(), coverage.annotatedLookups());
            line(out, "  annotated members found: %s", coverage.lookedUp());
            line(out, "  owners: %,d resolved in their source file (%,d in a file of another name, found through "
                            + "the SourceFile attribute), %,d in no jar, %,d types missing", coverage.ownersResolved(),
                    coverage.ownersInOtherFiles(), coverage.ownersWithoutJar(), coverage.typesMissing());
            line(out, "  sources jars: %,d found, %,d missing", coverage.sourcesJarsFound(),
                    coverage.sourcesJarsMissing());
            line(out, "  files: %,d parsed (%,d only at %s), %,d missing, %,d unparsable", coverage.filesParsed(),
                    coverage.filesParsedAsNewer(), SourceFileIndex.NEWER_LANGUAGE_LEVEL, coverage.filesMissing(),
                    coverage.filesUnparsable());
            line(out, "  members missing from their source: %,d", coverage.membersMissing());
            line(out, "  unresolved annotation values: %,d", coverage.unresolvedValues());
            line(out, "  annotated members in the parsed files: %s", coverage.inParsedFiles());
            if (runtime) {
                line(out, "");
                line(out, "Class files -> sources");
                line(out, "  %,d classes read, %,d annotated members compared (%,d methods with parameters skipped)",
                        classes.units(), classes.compared(), classes.skipped());
                line(out, "  in class files: %s", classes.counts());
                List<String> notVisited = notVisited();
                line(out, "  not among the config members the reader looks up: %,d%s", notVisited.size(),
                        notVisited.isEmpty() ? "" : " (" + String.join(", ", notVisited) + ")");
                agreement(out, classes);
                line(out, "");
                line(out, "Sources -> class files");
                line(out, "  %,d .java files, %,d mention ConfigDoc and were parsed, %,d annotated members compared",
                        sources.units(), sources.parsed(), sources.compared());
                line(out, "  in sources: %s", sources.counts());
                agreement(out, sources);
            }
            line(out, "");
            line(out, "All files parsed during the check: %,d (%,d only at %s), %,d unparsable, %,d unresolved values",
                    total.filesParsed(), total.filesParsedAsNewer(), SourceFileIndex.NEWER_LANGUAGE_LEVEL,
                    total.filesUnparsable(), total.unresolvedValues());
            line(out, "");
            line(out, "Warnings: %,d", warnings.size());
            warnings.forEach(warning -> line(out, "  %s", warning));
            line(out, "");
            line(out, "Result: %s", passed() ? "PASS" : "FAIL");
            return out.toString();
        }

        private static void agreement(StringBuilder out, Comparison comparison) {
            line(out, "  agree: %,d / %,d (%s)", comparison.agreeing(), comparison.compared(),
                    percent(comparison.agreeing(), comparison.compared()));
            line(out, "  mismatches: %,d", comparison.mismatches().size());
            comparison.mismatches().forEach(mismatch -> line(out, "    %s", mismatch));
        }

        private static String percent(int part, int whole) {
            return whole == 0 ? "n/a" : String.format(Locale.ROOT, "%.2f %%", 100.0 * part / whole);
        }

        private static void line(StringBuilder out, String format, Object... args) {
            out.append(args.length == 0 ? format : String.format(Locale.ROOT, format, args)).append('\n');
        }
    }

    /// The check of one release, apart from the command line so that tests can run it on in-memory jars.
    static final class Checker {

        private final VersionJars jars;
        private final SourceDocAnnotations sources;
        private final int parallelism;

        Checker(VersionJars jars, SourceDocAnnotations sources, int parallelism) {
            this.jars = jars;
            this.sources = sources;
            this.parallelism = parallelism;
        }

        Report run() throws IOException {
            String release = jars.quarkusVersion();
            boolean runtime = hasRuntimeRetention(release);
            List<JarSource> configJars = jars.jarsContaining(ConfigMembers.ROOTS_ENTRY);
            Log.info("sourcedoc check %s: %d jars with config roots", release, configJars.size());
            // whole-jar downloads in parallel, before the sequential walk over the config types
            Parallel.map(configJars, parallelism, jar -> jar.read(ConfigMembers.ROOTS_ENTRY));
            ConfigMembers.Result members = ConfigMembers.collect(jars, configJars);
            Log.info("sourcedoc check %s: looking up %d config members", release, members.members().size());
            lookUp(members.members());
            SourceDocAnnotations.Stats coverage = sources.stats();
            List<JarSource> ownerJars = ownerJars(members, configJars);
            List<JarSource> checked = new ArrayList<>(configJars);
            checked.addAll(ownerJars);
            Comparison classes = null;
            Comparison sourceFiles = null;
            if (runtime) {
                Log.info("sourcedoc check %s: comparing the annotations of %d jars", release, checked.size());
                classes = classesToSources(checked);
                sourceFiles = sourcesToClasses(checked);
            }
            return new Report(release, runtime, jars.candidateCount(), jars.jars().size(), artifactIds(configJars),
                    artifactIds(ownerJars), members, coverage, classes, sourceFiles, sources.stats(),
                    sources.warnings());
        }

        /// Looks every member up, in parallel per owner (one owner is one source file).
        void lookUp(List<ConfigMembers.Member> members) throws IOException {
            Map<String, List<String>> byOwner = new LinkedHashMap<>();
            members.forEach(member -> byOwner.computeIfAbsent(member.owner(), owner -> new ArrayList<>())
                    .add(member.name()));
            Parallel.map(byOwner.entrySet(), parallelism, owner -> {
                for (String member : owner.getValue()) {
                    sources.get(owner.getKey(), member);
                }
                return null;
            });
        }

        /// The jars, other than the config jars, that declare a config member's owner.
        private List<JarSource> ownerJars(ConfigMembers.Result members, List<JarSource> configJars)
                throws IOException {
            Set<JarSource> result = new LinkedHashSet<>();
            Set<String> owners = new LinkedHashSet<>();
            members.members().forEach(member -> owners.add(member.owner()));
            for (String owner : owners) {
                Optional<JarSource> jar = jars.jarOf(owner);
                if (jar.isPresent() && !configJars.contains(jar.get())) {
                    result.add(jar.get());
                }
            }
            return result.stream().sorted(Comparator.comparing(JarSource::artifactId)).toList();
        }

        /// Every annotated field and method of the jars' classes against [SourceDocAnnotations#get].
        Comparison classesToSources(List<JarSource> checked) throws IOException {
            List<Comparison> perJar = Parallel.map(checked, parallelism, jar -> {
                int classes = 0;
                int skipped = 0;
                List<String> compared = new ArrayList<>();
                List<DocAnnotations> found = new ArrayList<>();
                List<Mismatch> mismatches = new ArrayList<>();
                for (String entry : jar.entryNames()) {
                    if (!entry.endsWith(CLASS_SUFFIX) || entry.startsWith("META-INF/")) {
                        continue;
                    }
                    classes++;
                    ClassNode node = ClassDocAnnotations.read(jar.read(entry)
                            .orElseThrow(() -> new IOException(entry + " disappeared from " + jar.artifactId())));
                    for (ClassDocAnnotations.Member member : ClassDocAnnotations.annotatedMembers(node)) {
                        if (!member.isConfigShaped()) {
                            skipped++;
                            continue;
                        }
                        compared.add(member.owner() + "#" + member.name() + (member.method() ? "()" : ""));
                        found.add(member.annotations());
                        // what the bytecode reader gets for the members it asks for (interface methods, class
                        // fields); the exact member for the other kind, where get() would prefer a namesake
                        DocAnnotations fromSources = member.method() == ClassDocAnnotations.isInterface(node)
                                ? sources.get(member.owner(), member.name())
                                : sources.declared(member.owner(), member.name(), member.method())
                                        .orElse(DocAnnotations.NONE);
                        if (!fromSources.equals(member.annotations())) {
                            mismatches.add(new Mismatch(member.owner() + "#" + member.name(), member.method(),
                                    member.annotations(), fromSources, false));
                        }
                    }
                }
                return new Comparison(classes, 0, compared, skipped, SourceDocAnnotations.AnnotationCounts.of(found),
                        mismatches);
            });
            return merge(perJar);
        }

        /// Every annotated member of the jars' sources against its class file.
        Comparison sourcesToClasses(List<JarSource> checked) throws IOException {
            List<Comparison> perJar = Parallel.map(checked, parallelism, jar -> {
                Optional<JarSource> existing = sources.existingSourcesJar(jar);
                if (existing.isEmpty()) {
                    // counted and reported by the annotation source
                    return new Comparison(0, 0, List.of(), 0, SourceDocAnnotations.AnnotationCounts.of(List.of()),
                            List.of());
                }
                JarSource sourcesJar = existing.get();
                int files = 0;
                int parsed = 0;
                List<String> compared = new ArrayList<>();
                List<DocAnnotations> found = new ArrayList<>();
                List<Mismatch> mismatches = new ArrayList<>();
                for (String entry : List.copyOf(sourcesJar.entryNames())) {
                    if (!entry.endsWith(JAVA_SUFFIX)) {
                        continue;
                    }
                    files++;
                    Optional<byte[]> content = sourcesJar.read(entry);
                    if (content.isEmpty() || indexOf(content.get(), DOC_MARKER) < 0) {
                        continue;
                    }
                    Optional<SourceFileIndex> index = sources.sourceFile(jar, entry);
                    if (index.isEmpty()) {
                        // unparsable: counted and reported by the annotation source
                        continue;
                    }
                    parsed++;
                    String packagePath = index.get().packageName().replace('.', '/');
                    for (SourceFileIndex.AnnotatedMember member : index.get().annotatedMembers()) {
                        String owner = packagePath.isEmpty() ? member.type() : packagePath + "/" + member.type();
                        compared.add(owner + "#" + member.name() + (member.method() ? "()" : ""));
                        found.add(member.annotations());
                        Optional<DocAnnotations> inClass = classAnnotations(jar, owner, member);
                        if (inClass.isEmpty() || !inClass.get().equals(member.annotations())) {
                            mismatches.add(new Mismatch(owner + "#" + member.name(), member.method(),
                                    inClass.orElse(null), member.annotations(), true));
                        }
                    }
                }
                return new Comparison(files, parsed, compared, 0, SourceDocAnnotations.AnnotationCounts.of(found),
                        mismatches);
            });
            return merge(perJar);
        }

        /// The member's annotations in its class file: the class from the same jar, else from any jar.
        private Optional<DocAnnotations> classAnnotations(JarSource jar, String owner,
                SourceFileIndex.AnnotatedMember member) throws IOException {
            Optional<byte[]> bytes = jar.read(owner + CLASS_SUFFIX);
            if (bytes.isEmpty()) {
                bytes = jars.classBytes(owner);
            }
            if (bytes.isEmpty()) {
                return Optional.empty();
            }
            return ClassDocAnnotations.member(ClassDocAnnotations.read(bytes.get()), member.name(), member.method());
        }

        private static Comparison merge(List<Comparison> parts) {
            int units = 0;
            int parsed = 0;
            List<String> compared = new ArrayList<>();
            int skipped = 0;
            int defaults = 0;
            int mapKeys = 0;
            int ignores = 0;
            int sections = 0;
            List<Mismatch> mismatches = new ArrayList<>();
            for (Comparison part : parts) {
                units += part.units();
                parsed += part.parsed();
                compared.addAll(part.members());
                skipped += part.skipped();
                defaults += part.counts().defaults();
                mapKeys += part.counts().mapKeys();
                ignores += part.counts().ignores();
                sections += part.counts().sections();
                mismatches.addAll(part.mismatches());
            }
            compared.sort(Comparator.naturalOrder());
            mismatches.sort(Comparator.comparing(Mismatch::member).thenComparing(Mismatch::method));
            return new Comparison(units, parsed, List.copyOf(compared), skipped,
                    new SourceDocAnnotations.AnnotationCounts(defaults, mapKeys, ignores, sections),
                    List.copyOf(mismatches));
        }

        private static List<String> artifactIds(List<JarSource> jars) {
            return jars.stream().map(JarSource::artifactId).toList();
        }
    }

    /// The annotations as they would be written, e.g. `@ConfigDocDefault("10s") @ConfigDocSection`.
    static String describe(DocAnnotations annotations) {
        if (annotations.equals(DocAnnotations.NONE)) {
            return "none";
        }
        List<String> parts = new ArrayList<>();
        if (annotations.defaultValue() != null) {
            parts.add("@" + SourceFileIndex.DOC_DEFAULT + "(" + quote(annotations.defaultValue()) + ")");
        }
        if (annotations.mapKey() != null) {
            parts.add("@" + SourceFileIndex.DOC_MAP_KEY + "(" + quote(annotations.mapKey()) + ")");
        }
        if (annotations.ignore()) {
            parts.add("@" + SourceFileIndex.DOC_IGNORE);
        }
        if (annotations.section()) {
            parts.add("@" + SourceFileIndex.DOC_SECTION);
        }
        return String.join(" ", parts);
    }

    /// A Java string literal of the value (quotes, backslashes and control characters escaped).
    private static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
