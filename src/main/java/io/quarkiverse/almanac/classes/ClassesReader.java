package io.quarkiverse.almanac.classes;

import io.quarkiverse.almanac.config.JarSource;
import io.quarkiverse.almanac.model.ArtifactClasses;
import io.quarkiverse.almanac.model.ClassesSnapshot;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;

/// The `classes.json` snapshot of a release: the classes of each of its `io.quarkus` jars, from the jars' entry names
/// alone (the central directory, which reading a jar fetches first: no class file is read).
///
/// A class is a `.class` entry of the jar itself, named by its binary name with nested classes included
/// (`io.quarkus.test.QuarkusUnitTest$Builder`). `module-info` and `package-info` are not classes. Entries under
/// `META-INF/` are not the jar's own classes: multi-release variants of classes it has at the top level, and what a jar
/// carries for others, such as the dependencies `quarkus-ide-launcher` embeds under `META-INF/ide-deps/` (renamed, so
/// they are no `.class` entries either). Classes a jar bundles at the top level, as `quarkus-grpc-cli` does with its
/// dependencies, are entries of that jar and count as its classes.
public final class ClassesReader {

    private static final String CLASS_SUFFIX = ".class";
    private static final String META_INF = "META-INF/";

    private ClassesReader() {
    }

    /// The binary names of the classes among a jar's entry names, sorted and without duplicates.
    public static List<String> classNames(Collection<String> entryNames) {
        TreeSet<String> names = new TreeSet<>();
        for (String entry : entryNames) {
            if (!entry.endsWith(CLASS_SUFFIX) || entry.startsWith(META_INF)) {
                continue;
            }
            String path = entry.substring(0, entry.length() - CLASS_SUFFIX.length());
            String simpleName = path.substring(path.lastIndexOf('/') + 1);
            if (simpleName.isEmpty() || simpleName.equals("module-info") || simpleName.equals("package-info")) {
                continue;
            }
            names.add(path.replace('/', '.'));
        }
        return List.copyOf(names);
    }

    /// The snapshot of a release from its jars: every existing jar that holds a class, sorted by artifactId. Reads
    /// each jar's entry names, so a jar whose central directory is not cached is fetched (or, offline, fails).
    ///
    /// @param jars the release's `io.quarkus` jars, existing or not
    public static ClassesSnapshot read(String version, Collection<? extends JarSource> jars) throws IOException {
        List<JarSource> sorted = new ArrayList<>(jars);
        sorted.sort(Comparator.comparing(JarSource::artifactId));
        List<ArtifactClasses> artifacts = new ArrayList<>();
        for (JarSource jar : sorted) {
            if (!jar.exists()) {
                continue;
            }
            List<String> classes = classNames(jar.entryNames());
            if (!classes.isEmpty()) {
                artifacts.add(new ArtifactClasses(jar.artifactId(), classes));
            }
        }
        return new ClassesSnapshot(version, List.copyOf(artifacts));
    }

    /// How many classes a snapshot lists, over all its jars.
    public static int classCount(ClassesSnapshot snapshot) {
        return snapshot.artifacts().stream().mapToInt(artifact -> artifact.classes().size()).sum();
    }
}
