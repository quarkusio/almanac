package io.quarkiverse.almanac.maven;

import io.quarkiverse.almanac.model.GitSource;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// [GitReleases]: the poms of the releases never published on Maven Central are those of their git tags, byte for byte,
/// and nothing else of those releases is ever asked of the repository.
class GitReleasesTest {

    @Test
    void theBlobIdIsTheOneGitGives() {
        // git hash-object of "hello\n"
        assertEquals("ce013625030ba8dba906f756967f9e9ca394464a",
                GitReleases.blobId("hello\n".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void everyReleaseServesTheBomAndTheParentOfItsTag() throws IOException {
        assertEquals(List.of("0.2.0", "0.3.0", "0.4.0", "0.5.0", "0.6.0", "0.7.0", "0.8.0", "0.9.1", "0.10.0"),
                GitReleases.versions());
        for (String version : GitReleases.versions()) {
            GitSource source = GitReleases.source(version).orElseThrow();
            assertEquals(version, source.tag());
            assertTrue(source.commit().matches("[0-9a-f]{40}"), version);
            assertTrue(GitReleases.date(version).matches("\\d{4}-\\d{2}-\\d{2}"), version);
            // both reads pass the check of the git blob id: the resources are the tag's files
            String[] bom = source.bom().split(":");
            assertArrayEquals(GitReleases.bom(version), GitReleases.pom(bom[0], bom[1], version).orElseThrow());
            assertTrue(new String(GitReleases.bom(version), StandardCharsets.UTF_8)
                    .contains("<artifactId>" + bom[1] + "</artifactId>"), version);
            String parent = bom[0].equals("io.quarkus") ? "quarkus-parent" : "shamrock-parent";
            assertTrue(GitReleases.pom(bom[0], parent, version).isPresent(), version);
        }
        assertEquals(new GitSource("0.2.0", "97a42b3fc1fd62b8e7a8ba61b9016324e9bcd20c", "org.jboss.shamrock:shamrock-bom"),
                GitReleases.source("0.2.0").orElseThrow());
        assertEquals("2019-03-01", GitReleases.date("0.10.0"));
    }

    @Test
    void otherPomsComeFromTheRepositoryButNoOtherPomOfTheseReleases() throws IOException {
        assertEquals(Optional.empty(), GitReleases.pom("io.quarkus", "quarkus-bom", "0.11.0"), "a published release");
        assertEquals(Optional.empty(), GitReleases.pom("org.jboss", "jboss-parent", "32"), "a parent from elsewhere");
        IOException e = assertThrows(IOException.class, () -> GitReleases.pom("io.quarkus", "quarkus-core", "0.10.0"));
        assertTrue(e.getMessage().contains("never published on Maven Central"), e.getMessage());
        assertEquals(Optional.empty(), GitReleases.source("0.9.0"), "canceled");
        assertEquals(Optional.empty(), GitReleases.source("0.11.0"));
        assertThrows(IllegalArgumentException.class, () -> GitReleases.date("0.11.0"));
    }

    @Test
    void theReleasesWithNothingForTheDatasetAreListedForReference() {
        assertEquals(List.of("0.0.1 no-bom", "0.1.0 no-bom", "0.9.0 canceled"),
                GitReleases.references().stream().map(r -> r.version() + " " + r.why()).toList());
        for (GitReleases.Reference reference : GitReleases.references()) {
            assertTrue(GitReleases.source(reference.version()).isEmpty(), reference.version());
            assertTrue(reference.commit().matches("[0-9a-f]{40}"), reference.version());
        }
    }
}
