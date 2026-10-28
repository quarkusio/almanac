package io.quarkiverse.almanac.config;

import io.quarkiverse.almanac.maven.RemoteJar;

import java.io.IOException;
import java.util.Optional;
import java.util.Set;

/// Read access to one jar of a Quarkus release, as handed to [ConfigReader]s by [VersionJars].
///
/// The production implementation reads a jar from Maven Central lazily (range requests, on-disk cache); tests can
/// implement it over in-memory entries. Implementations must be thread-safe: readers may read entries of the same or
/// different jars from several threads.
public interface JarSource {

    /// The production view of a jar of a repository.
    static JarSource of(RemoteJar jar) {
        return new RemoteJarSource(jar);
    }

    String groupId();

    String artifactId();

    /// The jar's own version (from the BOM entry, not necessarily the Quarkus release version).
    String version();

    /// False when the repository has no such jar (HTTP 404).
    boolean exists() throws IOException;

    /// Names of all entries as stored in the zip (`/`-separated, directories end with `/`), e.g.
    /// `io/quarkus/runtime/ApplicationConfig.class`; empty when the jar does not exist.
    Set<String> entryNames() throws IOException;

    /// Whether the jar has an entry with exactly this name.
    boolean contains(String entry) throws IOException;

    /// The uncompressed content of an entry; empty when the entry (or the jar) does not exist.
    Optional<byte[]> read(String entry) throws IOException;
}
