package io.quarkiverse.almanac.api;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

/// Which classes of the classes kind the API data covers: every class of a release's `classes.json` (top-level
/// `.class` entries of the `io.quarkus` jars, by the `ClassesReader` rules), except the copies of other projects that
/// some jars bundle, named here and in the README:
///
/// - `quarkus-grpc-cli` and `quarkus-bootstrap-gradle-resolver`, entirely (copies of ArC, grpc and Gradle classes);
/// - `quarkus-ide-launcher` outside the package `io.quarkus.launcher` and its subpackages;
/// - every class in a package with a `shaded` segment (`io.quarkus.launcher.shaded.org.apache...`).
///
/// The other jars keep their classes outside `io.quarkus` (the Spring API shims, the grpc stubs). A jar with more than
/// [#BUNDLE_WARNING] such classes, or classes in shaded packages, is likely a new bundle: `api` warns about it.
public final class ApiScope {

    /// Jars whose classes are all copies of other projects' classes.
    public static final Set<String> EXCLUDED_JARS = Set.of("quarkus-grpc-cli", "quarkus-bootstrap-gradle-resolver");
    /// Jars of which only one package and its subpackages count: artifactId → package.
    public static final Map<String, String> ONLY_PACKAGE = Map.of("quarkus-ide-launcher", "io.quarkus.launcher");
    /// A package with this segment holds relocated copies of another project's classes.
    public static final String SHADED_SEGMENT = "shaded";
    /// More classes than this outside `io.quarkus` or in shaded packages in a jar not named here: `api` warns.
    public static final int BUNDLE_WARNING = 500;

    private static final String QUARKUS_PREFIX = "io.quarkus.";

    private ApiScope() {
    }

    /// Whether the API data covers a class of a jar.
    ///
    /// @param binaryName the class as the classes kind names it: `io.quarkus.test.QuarkusUnitTest$Builder`
    public static boolean covers(String artifactId, String binaryName) {
        if (EXCLUDED_JARS.contains(artifactId)) {
            return false;
        }
        String only = ONLY_PACKAGE.get(artifactId);
        String pkg = packageOf(binaryName);
        if (only != null && !pkg.equals(only) && !pkg.startsWith(only + ".")) {
            return false;
        }
        return !isShaded(pkg);
    }

    /// Whether a jar is one of those this scope names, whose bundled classes are known.
    public static boolean isNamed(String artifactId) {
        return EXCLUDED_JARS.contains(artifactId) || ONLY_PACKAGE.containsKey(artifactId);
    }

    /// How many of a jar's classes are outside `io.quarkus` or in a shaded package: copies of other projects' classes,
    /// as a jar that bundles its dependencies has.
    public static int foreignClasses(Collection<String> binaryNames) {
        int count = 0;
        for (String name : binaryNames) {
            if (!name.startsWith(QUARKUS_PREFIX) || isShaded(packageOf(name))) {
                count++;
            }
        }
        return count;
    }

    /// Whether a package has a `shaded` segment.
    static boolean isShaded(String pkg) {
        int start = 0;
        while (start <= pkg.length()) {
            int dot = pkg.indexOf('.', start);
            int end = dot < 0 ? pkg.length() : dot;
            if (end - start == SHADED_SEGMENT.length() && pkg.startsWith(SHADED_SEGMENT, start)) {
                return true;
            }
            if (dot < 0) {
                return false;
            }
            start = dot + 1;
        }
        return false;
    }

    /// The package of a binary name, empty for the unnamed package.
    static String packageOf(String binaryName) {
        int dot = binaryName.lastIndexOf('.');
        return dot < 0 ? "" : binaryName.substring(0, dot);
    }
}
