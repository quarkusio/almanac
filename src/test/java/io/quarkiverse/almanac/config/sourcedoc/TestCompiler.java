package io.quarkiverse.almanac.config.sourcedoc;

import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Compiles Java sources in memory with the JDK's javac, so that tests can compare what the sources say with what
/// javac writes into class files (folded constants, retained annotations, `SourceFile` attributes); the API reader's
/// tests read its class files too.
public final class TestCompiler {

    private TestCompiler() {
    }

    /// Compiles sources at `--release 21` without annotation processing; a test is skipped when no compiler is
    /// available (tests running on a JRE) and fails when the sources do not compile.
    ///
    /// @param sources file path as in a sources jar (e.g. `com/acme/Foo.java`) → content
    /// @return internal class name (e.g. `com/acme/Foo$Bar`) → class file
    public static Map<String, byte[]> compile(Map<String, String> sources) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assumeTrue(compiler != null, "javac is not available");
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        Map<String, ByteArrayOutputStream> outputs = new ConcurrentHashMap<>();
        try (StandardJavaFileManager standard = compiler.getStandardFileManager(diagnostics, Locale.ROOT,
                StandardCharsets.UTF_8)) {
            JavaFileManager manager = new ForwardingJavaFileManager<>(standard) {
                @Override
                public JavaFileObject getJavaFileForOutput(Location location, String className,
                        JavaFileObject.Kind kind, FileObject sibling) {
                    String internalName = className.replace('.', '/');
                    return new SimpleJavaFileObject(URI.create("mem:///" + internalName + kind.extension), kind) {
                        @Override
                        public OutputStream openOutputStream() {
                            ByteArrayOutputStream out = new ByteArrayOutputStream();
                            outputs.put(internalName, out);
                            return out;
                        }
                    };
                }
            };
            List<JavaFileObject> units = new ArrayList<>();
            sources.forEach((path, content) -> units.add(
                    new SimpleJavaFileObject(URI.create("mem:///" + path), JavaFileObject.Kind.SOURCE) {
                        @Override
                        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                            return content;
                        }
                    }));
            List<String> options = List.of("--release", "21", "-proc:none", "-Xlint:none");
            boolean compiled = compiler.getTask(null, manager, diagnostics, options, null, units).call();
            assertTrue(compiled, () -> "javac failed: " + diagnostics.getDiagnostics());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Map<String, byte[]> classes = new TreeMap<>();
        outputs.forEach((name, out) -> classes.put(name, out.toByteArray()));
        return classes;
    }
}
