package io.quarkiverse.almanac.classes;

import io.quarkiverse.almanac.config.InMemoryJar;
import io.quarkiverse.almanac.model.ArtifactClasses;
import io.quarkiverse.almanac.model.ClassesSnapshot;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Which entries of a jar are its classes, and the snapshot of a release's jars.
class ClassesReaderTest {

    @Test
    void classesAreTheJarsOwnClassEntriesByBinaryName() {
        assertEquals(List.of(
                        "Main",
                        "io.quarkus.test.QuarkusUnitTest",
                        "io.quarkus.test.QuarkusUnitTest$1",
                        "io.quarkus.test.QuarkusUnitTest$Builder"),
                ClassesReader.classNames(List.of(
                        "io/", "io/quarkus/", "io/quarkus/test/",
                        "io/quarkus/test/QuarkusUnitTest$Builder.class",
                        "io/quarkus/test/QuarkusUnitTest.class",
                        "io/quarkus/test/QuarkusUnitTest$1.class",
                        "Main.class",
                        // not classes
                        "module-info.class",
                        "io/quarkus/test/package-info.class",
                        "io/quarkus/test/messages.properties",
                        "io/quarkus/test/QuarkusUnitTest.java",
                        // under META-INF: multi-release variants, and what quarkus-ide-launcher carries for others
                        "META-INF/versions/11/io/quarkus/test/QuarkusUnitTest.class",
                        "META-INF/versions/9/module-info.class",
                        "META-INF/ide-deps/io/quarkus/bootstrap/BootstrapConstants.class.ide-launcher-res",
                        "META-INF/ide-deps/org/acme/Shaded.class")));
    }

    @Test
    void aReleaseListsTheJarsThatHoldClassesByArtifactId() throws IOException {
        ClassesSnapshot snapshot = ClassesReader.read("3.31.0", List.of(
                InMemoryJar.of("quarkus-junit5-internal", "3.31.0").with("META-INF/MANIFEST.MF", "relocated"),
                InMemoryJar.of("quarkus-junit-internal", "3.31.0")
                        .with("io/quarkus/test/QuarkusUnitTest.class", new byte[0]),
                InMemoryJar.missing("quarkus-gone", "3.31.0"),
                InMemoryJar.of("quarkus-core", "3.31.0").with("io/quarkus/runtime/Quarkus.class", new byte[0])
                        .with("io/quarkus/runtime/Application.class", new byte[0])));
        assertEquals(new ClassesSnapshot("3.31.0", List.of(
                new ArtifactClasses("quarkus-core", List.of("io.quarkus.runtime.Application",
                        "io.quarkus.runtime.Quarkus")),
                new ArtifactClasses("quarkus-junit-internal", List.of("io.quarkus.test.QuarkusUnitTest")))), snapshot);
        assertEquals(3, ClassesReader.classCount(snapshot));
    }
}
