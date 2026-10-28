package io.quarkiverse.almanac.maven;

import io.quarkiverse.almanac.util.AtomicFiles;
import io.quarkiverse.almanac.util.Log;
import org.w3c.dom.Element;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;

/// A Maven repository (Maven Central by default) with a local cache under `work/cache` that mirrors the
/// repository layout. Artifacts are immutable and cached forever, 404s are cached as `.404`
/// marker files, the `Last-Modified` time of each download is kept in a `.last-modified` file, and
/// `maven-metadata.xml` is refreshed after 6 hours (in offline mode any cached copy is used).
public final class MavenRepo {

    public static final URI CENTRAL = URI.create("https://repo1.maven.org/maven2/");

    private static final Duration METADATA_MAX_AGE = Duration.ofHours(6);

    /// A downloaded or cached artifact file.
    ///
    /// @param lastModified the server's `Last-Modified` time, null when unknown
    public record Download(byte[] content, Instant lastModified) {
    }

    private final URI base;
    private final Path cacheRoot;
    private final Http http;
    private final RemoteJar.Options jarOptions;

    public MavenRepo(URI base, Path cacheRoot, Http http, RemoteJar.Options jarOptions) {
        String url = base.toString();
        this.base = URI.create(url.endsWith("/") ? url : url + "/");
        this.cacheRoot = cacheRoot;
        this.http = http;
        this.jarOptions = jarOptions;
    }

    public Http http() {
        return http;
    }

    /// This repository over another cache folder, with the same base URL and HTTP client: for reading what another work
    /// folder cached. What it downloads goes to that folder; a jar it has cached is only read.
    public MavenRepo withCache(Path otherCacheRoot) {
        return new MavenRepo(base, otherCacheRoot, http, jarOptions);
    }

    /// Whether the cache knows a jar without a request: it holds the jar, its central directory or its 404 marker.
    public boolean hasCachedJar(String groupId, String artifactId, String version) {
        Path file = cacheRoot.resolve(artifactPath(groupId, artifactId, version, "jar"));
        return Files.exists(file) || Files.exists(sibling(file, ".cd")) || Files.exists(sibling(file, ".404"));
    }

    /// Repository path of an artifact file, e.g. `io/quarkus/quarkus-bom/3.20.6/quarkus-bom-3.20.6.pom`.
    public static String artifactPath(String groupId, String artifactId, String version, String extension) {
        return groupId.replace('.', '/') + "/" + artifactId + "/" + version + "/"
                + artifactId + "-" + version + "." + extension;
    }

    /// The versions listed in an artifact's `maven-metadata.xml`, in file order.
    public List<String> versions(String groupId, String artifactId) throws IOException {
        String path = groupId.replace('.', '/') + "/" + artifactId + "/maven-metadata.xml";
        Path file = cacheRoot.resolve(path);
        byte[] xml;
        if (Files.exists(file) && (http.offline() || isFresh(file))) {
            xml = Files.readAllBytes(file);
        } else {
            Http.Response response = http.get(base.resolve(path));
            if (response.status() != 200) {
                throw new IOException("HTTP " + response.status() + " for " + base.resolve(path));
            }
            xml = response.body();
            AtomicFiles.write(file, xml);
        }
        Element metadata = Xml.parse(xml);
        return Xml.path(metadata, "versioning", "versions")
                .map(versions -> Xml.children(versions, "version").stream()
                        .map(v -> v.getTextContent().strip())
                        .toList())
                .orElse(List.of());
    }

    /// An artifact file such as a pom; empty when the repository answers 404.
    public Optional<Download> artifact(String groupId, String artifactId, String version, String extension)
            throws IOException {
        String path = artifactPath(groupId, artifactId, version, extension);
        Path file = cacheRoot.resolve(path);
        Path missing = sibling(file, ".404");
        Path lastModifiedFile = sibling(file, ".last-modified");
        if (Files.exists(missing)) {
            return Optional.empty();
        }
        if (Files.exists(file)) {
            Instant lastModified = Files.exists(lastModifiedFile)
                    ? Instant.parse(Files.readString(lastModifiedFile).strip())
                    : null;
            return Optional.of(new Download(Files.readAllBytes(file), lastModified));
        }
        URI uri = base.resolve(path);
        Http.Response response = http.get(uri);
        if (response.status() == 404) {
            AtomicFiles.write(missing, new byte[0]);
            return Optional.empty();
        }
        if (response.status() != 200) {
            throw new IOException("HTTP " + response.status() + " for " + uri);
        }
        Instant lastModified = response.header("Last-Modified").flatMap(MavenRepo::parseHttpDate).orElse(null);
        if (lastModified != null) {
            AtomicFiles.writeString(lastModifiedFile, lastModified + "\n");
        }
        AtomicFiles.write(file, response.body());
        return Optional.of(new Download(response.body(), lastModified));
    }

    /// Repository path of an artifact file with a classifier, e.g.
    /// `io/quarkus/quarkus-core/3.8.6.1/quarkus-core-3.8.6.1-sources.jar`; without a classifier (null or empty) the
    /// path of [#artifactPath(String, String, String, String)].
    public static String artifactPath(String groupId, String artifactId, String version, String classifier,
            String extension) {
        if (classifier == null || classifier.isEmpty()) {
            return artifactPath(groupId, artifactId, version, extension);
        }
        return groupId.replace('.', '/') + "/" + artifactId + "/" + version + "/"
                + artifactId + "-" + version + "-" + classifier + "." + extension;
    }

    /// An artifact file with a classifier, such as the extension catalog
    /// `io.quarkus:quarkus-bom-quarkus-platform-descriptor:3.20.6:json:3.20.6` (classifier `3.20.6`, extension
    /// `json`), cached like [#artifact(String, String, String, String)]; empty when the repository answers 404. Without
    /// a classifier (null or empty) it is that method.
    public Optional<Download> artifact(String groupId, String artifactId, String version, String classifier,
            String extension) throws IOException {
        if (classifier == null || classifier.isEmpty()) {
            return artifact(groupId, artifactId, version, extension);
        }
        return download(artifactPath(groupId, artifactId, version, classifier, extension));
    }

    /// The artifact file at a repository path: from the cache, else downloaded and cached with its `Last-Modified`
    /// time (a 404 as a `.404` marker, read as empty).
    private Optional<Download> download(String path) throws IOException {
        Path file = cacheRoot.resolve(path);
        Path missing = sibling(file, ".404");
        Path lastModifiedFile = sibling(file, ".last-modified");
        if (Files.exists(missing)) {
            return Optional.empty();
        }
        if (Files.exists(file)) {
            Instant lastModified = Files.exists(lastModifiedFile)
                    ? Instant.parse(Files.readString(lastModifiedFile).strip())
                    : null;
            return Optional.of(new Download(Files.readAllBytes(file), lastModified));
        }
        URI uri = base.resolve(path);
        Http.Response response = http.get(uri);
        if (response.status() == 404) {
            AtomicFiles.write(missing, new byte[0]);
            return Optional.empty();
        }
        if (response.status() != 200) {
            throw new IOException("HTTP " + response.status() + " for " + uri);
        }
        Instant lastModified = response.header("Last-Modified").flatMap(MavenRepo::parseHttpDate).orElse(null);
        if (lastModified != null) {
            AtomicFiles.writeString(lastModifiedFile, lastModified + "\n");
        }
        AtomicFiles.write(file, response.body());
        return Optional.of(new Download(response.body(), lastModified));
    }

    /// A jar of this repository, indexed lazily (no request is made until it is used).
    public RemoteJar jar(String groupId, String artifactId, String version) {
        String path = artifactPath(groupId, artifactId, version, "jar");
        return new RemoteJar(groupId, artifactId, version, new HttpRangeFetcher(http, base.resolve(path)),
                cacheRoot.resolve(path), jarOptions);
    }

    /// A jar with a classifier, e.g. `sources` for `quarkus-core-3.8.6.1-sources.jar`; lazy like [#jar].
    public RemoteJar jar(String groupId, String artifactId, String version, String classifier) {
        String path = groupId.replace('.', '/') + "/" + artifactId + "/" + version + "/"
                + artifactId + "-" + version + "-" + classifier + ".jar";
        return new RemoteJar(groupId, artifactId, version, new HttpRangeFetcher(http, base.resolve(path)),
                cacheRoot.resolve(path), jarOptions);
    }

    private static boolean isFresh(Path file) throws IOException {
        Instant modified = Files.getLastModifiedTime(file).toInstant();
        return Duration.between(modified, Instant.now()).compareTo(METADATA_MAX_AGE) < 0;
    }

    private static Optional<Instant> parseHttpDate(String value) {
        try {
            return Optional.of(ZonedDateTime.parse(value.strip(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
        } catch (DateTimeParseException e) {
            Log.debug("unparsable Last-Modified '%s'", value);
            return Optional.empty();
        }
    }

    private static Path sibling(Path file, String suffix) {
        return file.resolveSibling(file.getFileName() + suffix);
    }
}
