package io.quarkiverse.almanac.dataset;

import io.quarkiverse.almanac.api.ApiReader;
import io.quarkiverse.almanac.model.ApiSnapshot;
import io.quarkiverse.almanac.model.ArtifactApi;
import io.quarkiverse.almanac.model.ArtifactClasses;
import io.quarkiverse.almanac.model.BomSnapshot;
import io.quarkiverse.almanac.model.ClassApi;
import io.quarkiverse.almanac.model.ClassesSnapshot;
import io.quarkiverse.almanac.model.ConfigProperty;
import io.quarkiverse.almanac.model.ConfigSnapshot;
import io.quarkiverse.almanac.model.ExtensionInfo;
import io.quarkiverse.almanac.model.ExtensionsSnapshot;
import io.quarkiverse.almanac.model.ManagedDep;
import io.quarkiverse.almanac.model.MemberApi;
import io.quarkiverse.almanac.util.ConfigKeys;
import io.quarkiverse.almanac.util.SnapshotStore;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/// Synthetic releases for the dataset tests: 3.0.0.Final and the pre-release 3.2.0.CR1 with a BOM only, 3.1.0 and
/// 3.2.0 with BOM, config and extensions. The values exercise every field: `"="` versions, removed and re-added
/// artifacts, null and empty lists, deprecation without version, enum values, descriptions with quotes, non-ASCII
/// characters, a newline and a U+2028 line separator.
final class Samples {

    static final String DESC_WITH_SEPARATORS = "Line one\nline two and “quoted” text… with \"escapes\" \\";

    private Samples() {
    }

    static BomSnapshot bom(String version, String date, String... idThenVersion) {
        List<ManagedDep> deps = new ArrayList<>();
        for (int i = 0; i < idThenVersion.length; i += 2) {
            deps.add(new ManagedDep(idThenVersion[i], idThenVersion[i + 1]));
        }
        deps.sort((a, b) -> a.id().compareTo(b.id()));
        return new BomSnapshot(version, date, List.copyOf(deps));
    }

    static ConfigProperty prop(String name, String type, String defaultValue, String phase, String deprecated,
            List<String> enumValues, String desc) {
        return new ConfigProperty(name, ConfigKeys.canonical(name), List.of(), "quarkus-core", type, false, false,
                false, defaultValue, phase, deprecated, enumValues, desc, "io.quarkus.Config#" + name);
    }

    static ConfigSnapshot config(String version, List<String> warnings, ConfigProperty... properties) {
        List<ConfigProperty> sorted = new ArrayList<>(Arrays.asList(properties));
        sorted.sort((a, b) -> a.key().compareTo(b.key()));
        return new ConfigSnapshot(version, "model", "model-json/1", properties.length, warnings, List.copyOf(sorted));
    }

    static ExtensionInfo extension(String id, String name, String status, String relocatedTo) {
        return new ExtensionInfo(id, name, name == null ? null : "The " + name + " extension", status,
                name == null ? null : List.of("web"), null, false, name == null ? null : List.of("quarkus." + id),
                relocatedTo);
    }

    static ExtensionsSnapshot extensions(String version, ExtensionInfo... extensions) {
        List<ExtensionInfo> sorted = new ArrayList<>(Arrays.asList(extensions));
        sorted.sort((a, b) -> a.id().compareTo(b.id()));
        return new ExtensionsSnapshot(version, List.copyOf(sorted));
    }

    static final BomSnapshot BOM_300 = bom("3.0.0.Final", "2023-04-26", "io.quarkus:quarkus-core", "3.0.0.Final",
            "io.vertx:vertx-core", "4.4.1");
    static final BomSnapshot BOM_310 = bom("3.1.0", "2023-05-31", "io.quarkus:quarkus-core", "3.1.0",
            "io.vertx:vertx-core", "4.4.1", "io.netty:netty-common", "4.1.100.Final");
    static final BomSnapshot BOM_320_CR1 = bom("3.2.0.CR1", "2023-06-28", "io.quarkus:quarkus-core", "3.2.0.CR1",
            "io.vertx:vertx-core", "4.4.4", "io.netty:netty-common", "4.1.100.Final");
    static final BomSnapshot BOM_320 = bom("3.2.0", "2023-07-05", "io.quarkus:quarkus-core", "3.2.0",
            "io.netty:netty-common", "4.1.101.Final", "io.quarkus:quarkus-core:pom:${quarkus.version}", "3.2.0");

    /// Platform BOMs: 3.1.0's manages vertx-core at another version and a Quarkiverse extension that the BOM does not;
    /// 3.2.0's keeps the extension, leaves out the BOM's pom entry of quarkus-core and manages its own descriptor at
    /// the release's version. The first came out two days after its BOM; the date of the second is unknown.
    static final BomSnapshot PLATFORM_310 = bom("3.1.0", "2023-06-02", "io.quarkus:quarkus-core", "3.1.0",
            "io.vertx:vertx-core", "4.4.2", "io.netty:netty-common", "4.1.100.Final",
            "io.quarkiverse.vault:quarkus-vault", "3.0.0");
    static final BomSnapshot PLATFORM_320 = bom("3.2.0", null, "io.quarkus:quarkus-core", "3.2.0",
            "io.netty:netty-common", "4.1.101.Final", "io.quarkiverse.vault:quarkus-vault", "3.0.0",
            "io.quarkus.platform:quarkus-bom-quarkus-platform-descriptor:json:${quarkus.version}", "3.2.0");

    static final ConfigProperty PORT_8080 = prop("quarkus.http.port", "int", "8080", "RUN_TIME", null, null,
            "The HTTP port.");
    static final ConfigProperty PORT_8081 = prop("quarkus.http.port", "int", "8081", "RUN_TIME", null, null,
            "The HTTP port.");
    static final ConfigProperty LEVEL = new ConfigProperty("quarkus.log.category.\"categories\".level",
            "quarkus.log.category.*.level", List.of("quarkus.log.level"), "quarkus-core", "Level", false, true, true,
            "inherit", "BUILD_AND_RUN_TIME_FIXED", "", List.of("OFF", "INFO", "DEBUG"), DESC_WITH_SEPARATORS,
            "io.quarkus.LogConfig#level");
    static final ConfigProperty OLD_FLAG = new ConfigProperty("quarkus.old.flag", "quarkus.old.flag", null,
            "quarkus-core", "boolean", true, false, false, null, "BUILD_TIME", "3.1", List.of(), null, null);
    static final ConfigProperty NEW_FLAG = prop("quarkus.new.flag[2].enabled", "boolean", "true", "BOOTSTRAP", null,
            null, "Enabled.");

    static final ConfigSnapshot CONFIG_310 = config("3.1.0", List.of("duplicate key quarkus.x: a and b"), PORT_8080,
            LEVEL, OLD_FLAG);
    static final ConfigSnapshot CONFIG_320 = config("3.2.0", List.of(), PORT_8081, LEVEL, NEW_FLAG);

    static final ExtensionInfo REST = extension("quarkus-rest", "REST", "stable", null);
    static final ExtensionInfo RESTEASY_REACTIVE = extension("quarkus-resteasy-reactive", "RESTEasy Reactive",
            "stable", null);
    static final ExtensionInfo RESTEASY_REACTIVE_RELOCATED = extension("quarkus-resteasy-reactive", null, null,
            "io.quarkus:quarkus-rest");

    static final ExtensionsSnapshot EXTENSIONS_310 = extensions("3.1.0", RESTEASY_REACTIVE);
    static final ExtensionsSnapshot EXTENSIONS_320 = extensions("3.2.0", REST, RESTEASY_REACTIVE_RELOCATED);

    static ClassesSnapshot classes(String version, Object... idThenClasses) {
        List<ArtifactClasses> artifacts = new ArrayList<>();
        for (int i = 0; i < idThenClasses.length; i += 2) {
            List<String> names = new ArrayList<>(Arrays.asList((String[]) idThenClasses[i + 1]));
            names.sort(null);
            artifacts.add(new ArtifactClasses((String) idThenClasses[i], List.copyOf(names)));
        }
        artifacts.sort((a, b) -> a.id().compareTo(b.id()));
        return new ClassesSnapshot(version, List.copyOf(artifacts));
    }

    private static String[] names(String... names) {
        return names;
    }

    static final String UNIT_TEST = "io.quarkus.test.QuarkusUnitTest";

    /// 3.0.0.Final, a BOM-only release: the jars of classes need only the BOM.
    static final ClassesSnapshot CLASSES_300 = classes("3.0.0.Final",
            "quarkus-core", names("io.quarkus.runtime.Application"),
            "quarkus-junit5-internal", names(UNIT_TEST, UNIT_TEST + "$1"));
    static final ClassesSnapshot CLASSES_310 = classes("3.1.0",
            "quarkus-core", names("io.quarkus.runtime.Application", "io.quarkus.runtime.Old"),
            "quarkus-junit5-internal", names(UNIT_TEST, UNIT_TEST + "$1"));
    /// QuarkusUnitTest moves to quarkus-junit-internal, which also gets a new class; one class is in two jars.
    static final ClassesSnapshot CLASSES_320 = classes("3.2.0",
            "quarkus-amazon-lambda-http", names("io.quarkus.amazon.lambda.http.LambdaHttpHandler"),
            "quarkus-amazon-lambda-rest", names("io.quarkus.amazon.lambda.http.LambdaHttpHandler"),
            "quarkus-core", names("io.quarkus.runtime.Application"),
            "quarkus-junit-internal", names(UNIT_TEST, UNIT_TEST + "$1", "io.quarkus.test.QuarkusExtensionTest"));

    static final BomSnapshot BOM_400_ALPHA1 = bom("4.0.0.Alpha1", "2026-07-29", "io.quarkus:quarkus-core",
            "4.0.0.Alpha1", "io.vertx:vertx-core", "5.0.0");
    /// 4.0.0.Alpha1: classes of another major line, which have a file of their own.
    static final ClassesSnapshot CLASSES_400_ALPHA1 = classes("4.0.0.Alpha1",
            "quarkus-core", names("io.quarkus.runtime.Application"),
            "quarkus-junit-internal", names(UNIT_TEST, "io.quarkus.test.QuarkusExtensionTest"));

    static ApiSnapshot api(String version, ArtifactApi... artifacts) {
        return new ApiSnapshot(version, ApiReader.READER, List.of(artifacts));
    }

    static ArtifactApi artifact(String id, ClassApi... classes) {
        return new ArtifactApi(id, List.of(classes));
    }

    static ClassApi type(String name, String value, MemberApi... members) {
        return new ClassApi(name, value, List.of(members));
    }

    static MemberApi member(String key, String value) {
        return new MemberApi(key, value);
    }

    static final String UNIT_TEST_VALUE = "+ implements org.junit.jupiter.api.extension.BeforeAllCallback";
    static final MemberApi UNIT_TEST_INIT = member("<init>()V", "+");
    static final MemberApi ARCHIVE = member(
            "setArchiveProducer(Ljava/util/function/Supplier;)Lio/quarkus/test/QuarkusUnitTest;", "+D");
    static final MemberApi START = member("start([Ljava/lang/String;)V", "+v");
    /// The API of 3.0.0.Final: QuarkusUnitTest$1, anonymous, is no API class.
    static final ApiSnapshot API_300 = api("3.0.0.Final",
            artifact("quarkus-core", type("io.quarkus.runtime.Application", "+a", member("<init>()V", "+"), START)),
            artifact("quarkus-junit5-internal", type(UNIT_TEST, UNIT_TEST_VALUE, UNIT_TEST_INIT, ARCHIVE)));
    /// Application gets a deprecated member, Old comes deprecated for removal.
    static final ApiSnapshot API_310 = api("3.1.0",
            artifact("quarkus-core",
                    type("io.quarkus.runtime.Application", "+a", member("<init>()V", "+"), START,
                            member("stop()V", "+D since 3.1")),
                    type("io.quarkus.runtime.Old", "+fR since 3.1", member("COUNT:I", "+sf"))),
            artifact("quarkus-junit5-internal", type(UNIT_TEST, UNIT_TEST_VALUE, UNIT_TEST_INIT, ARCHIVE)));
    /// QuarkusUnitTest moves with the same API; a class in two jars; Old and Application's stop() are gone.
    static final ApiSnapshot API_320 = api("3.2.0",
            artifact("quarkus-amazon-lambda-http", type("io.quarkus.amazon.lambda.http.LambdaHttpHandler", "+")),
            artifact("quarkus-amazon-lambda-rest", type("io.quarkus.amazon.lambda.http.LambdaHttpHandler", "+")),
            artifact("quarkus-core", type("io.quarkus.runtime.Application", "+a", member("<init>()V", "+"), START)),
            artifact("quarkus-junit-internal",
                    type("io.quarkus.test.QuarkusExtensionTest", "+X", member("<init>()V", "+")),
                    type(UNIT_TEST, UNIT_TEST_VALUE, UNIT_TEST_INIT, ARCHIVE)));
    /// 4.0.0.Alpha1: QuarkusUnitTest goes for removal.
    static final ApiSnapshot API_400_ALPHA1 = api("4.0.0.Alpha1",
            artifact("quarkus-core", type("io.quarkus.runtime.Application", "+a", member("<init>()V", "+"), START)),
            artifact("quarkus-junit-internal",
                    type("io.quarkus.test.QuarkusExtensionTest", "+", member("<init>()V", "+")),
                    type(UNIT_TEST, "+R implements org.junit.jupiter.api.extension.BeforeAllCallback since 4.0",
                            UNIT_TEST_INIT)));

    /// [#contentsWithClasses()] with the API of 3.0.0.Final, 3.1.0, 3.2.0 and 4.0.0.Alpha1.
    static Contents contentsWithApi() {
        Contents contents = contentsWithClasses();
        for (ApiSnapshot api : List.of(API_300, API_310, API_320, API_400_ALPHA1)) {
            contents.put(api.version(), Kind.API, api);
        }
        return contents;
    }

    /// [#contents()] with the classes of 3.0.0.Final, 3.1.0 and 3.2.0, and 4.0.0.Alpha1 with a BOM and classes.
    static Contents contentsWithClasses() {
        Contents contents = contents();
        contents.put(BOM_400_ALPHA1.version(), Kind.BOM, BOM_400_ALPHA1);
        for (ClassesSnapshot classes : List.of(CLASSES_300, CLASSES_310, CLASSES_320, CLASSES_400_ALPHA1)) {
            contents.put(classes.version(), Kind.CLASSES, classes);
        }
        return contents;
    }

    /// The four sample releases plus 2.16.12.Final, known without data (and without date).
    static Contents contents() {
        Contents contents = new Contents();
        contents.addRelease("2.16.12.Final", null);
        put(contents, BOM_300, null, null);
        put(contents, BOM_310, CONFIG_310, EXTENSIONS_310);
        put(contents, BOM_320_CR1, null, null);
        put(contents, BOM_320, CONFIG_320, EXTENSIONS_320);
        return contents;
    }

    /// [#contents()] with the platform BOMs of 3.1.0 and 3.2.0.
    static Contents contentsWithPlatforms() {
        Contents contents = contents();
        contents.put("3.1.0", Kind.PLATFORM, PLATFORM_310);
        contents.put("3.2.0", Kind.PLATFORM, PLATFORM_320);
        return contents;
    }

    static void put(Contents contents, BomSnapshot bom, ConfigSnapshot config, ExtensionsSnapshot extensions) {
        contents.put(bom.version(), Kind.BOM, bom);
        if (config != null) {
            contents.put(bom.version(), Kind.CONFIG, config);
            contents.put(bom.version(), Kind.EXTENSIONS, extensions);
        }
    }

    /// Writes the sample snapshots into a work snapshots folder, as `bom` and `config` would.
    static void writeWork(SnapshotStore work) throws IOException {
        for (BomSnapshot bom : List.of(BOM_300, BOM_310, BOM_320_CR1, BOM_320)) {
            work.write(bom.version(), SnapshotStore.BOM, bom);
        }
        work.write("3.1.0", SnapshotStore.CONFIG, CONFIG_310);
        work.write("3.1.0", SnapshotStore.EXTENSIONS, EXTENSIONS_310);
        work.write("3.2.0", SnapshotStore.CONFIG, CONFIG_320);
        work.write("3.2.0", SnapshotStore.EXTENSIONS, EXTENSIONS_320);
    }

    /// Writes the platform BOMs of 3.1.0 and 3.2.0 into a work snapshots folder, as `platform` would.
    static void writePlatformWork(SnapshotStore work) throws IOException {
        work.write("3.1.0", SnapshotStore.PLATFORM_BOM, PLATFORM_310);
        work.write("3.2.0", SnapshotStore.PLATFORM_BOM, PLATFORM_320);
    }

    /// The lines of each file that `after` added and removed compared with `before` (lines are unique per file).
    static Map<String, String> lineChanges(Map<String, String> before, Map<String, String> after) {
        Map<String, String> changes = new TreeMap<>();
        for (String file : after.keySet()) {
            List<String> old = DatasetFormat.lines(before.getOrDefault(file, ""));
            List<String> now = DatasetFormat.lines(after.get(file));
            long added = now.stream().filter(line -> !old.contains(line)).count();
            long removed = old.stream().filter(line -> !now.contains(line)).count();
            if (added + removed > 0) {
                changes.put(file, "+" + added + " -" + removed);
            }
        }
        return changes;
    }
}
