package io.quarkiverse.almanac.cli;

import io.quarkiverse.almanac.Main;
import io.quarkiverse.almanac.ScratchDir;
import io.quarkiverse.almanac.maven.DependencyProfile.Root;
import io.quarkiverse.almanac.maven.DependencyProfile.Use;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// [ProfileCommand#roots]: what a release's profile starts from, in each layout the BOM has had; and `--missing`.
class ProfileCommandTest {

    @Test
    void missingPrintsTheReleasesOfTheSiteWithoutAProfile() throws Exception {
        try (ScratchDir dir = ScratchDir.create()) {
            Path data = Files.createDirectories(dir.resolve("site/data"));
            Files.writeString(data.resolve("versions.json"), "{\"versions\":[{\"v\":\"3.34.0\"},{\"v\":\"3.35.0\"}]}");
            Files.writeString(data.resolve("profile.json"), "{\"versions\":[1],\"releases\":[\"3.35.0\"]}");
            StringWriter out = new StringWriter();
            CommandLine commandLine = new CommandLine(new Main()).setOut(new PrintWriter(out));

            int exitCode = commandLine.execute("profile", "--missing", "--site", dir.resolve("site").toString());

            assertEquals(0, exitCode);
            assertEquals("3.34.0\n", out.toString().replace("\r\n", "\n"));
        }
    }

    @Test
    void missingReadsTheSiteOfTheRepositoryUnlessToldOtherwise() {
        // the new-releases workflow names no --site
        CommandLine profile = new CommandLine(new Main()).getSubcommands().get("profile");

        assertEquals("site", profile.getCommandSpec().findOption("--site").defaultValue());
    }

    @Test
    void withoutMissingItNeedsTheReleases() {
        CommandLine commandLine = new CommandLine(new Main()).setErr(new PrintWriter(new StringWriter()));

        assertEquals(2, commandLine.execute("profile"));
    }

    @Test
    void theCatalogsExtensionsWithTheirDeploymentArtifactsThePluginAndTheTestFrameworks() throws IOException {
        Map<String, String> managed = Map.of("io.quarkus:quarkus-rest", "3.35.0",
                "io.quarkus:quarkus-rest-deployment", "3.35.0", "io.quarkus:quarkus-core", "3.35.0",
                "io.quarkus:quarkus-junit5", "3.35.0", "io.quarkus:quarkus-arquillian", "3.35.0");
        assertEquals(Map.of("quarkus-rest", Use.RUNTIME, "quarkus-rest-deployment", Use.BUILD,
                        "quarkus-core", Use.RUNTIME, "quarkus-maven-plugin", Use.BUILD,
                        "quarkus-junit5", Use.TEST, "quarkus-arquillian", Use.TEST),
                uses(ProfileCommand.roots("3.35.0", managed, Map.of(), List.of("quarkus-rest", "quarkus-core"),
                        artifactId -> false)),
                "no deployment artifact the BOM does not list and the repository does not have");
    }

    @Test
    void beforeOneSixTheDeploymentArtifactsThatTheRepositoryHas() throws IOException {
        Map<String, String> managed = Map.of("io.quarkus:quarkus-rest", "1.0.0.Final");
        assertEquals(Map.of("quarkus-rest", Use.RUNTIME, "quarkus-rest-deployment", Use.BUILD,
                        "quarkus-maven-plugin", Use.BUILD),
                uses(ProfileCommand.roots("1.0.0.Final", managed, Map.of(), List.of("quarkus-rest"),
                        Set.of("quarkus-rest-deployment")::contains)));
    }

    @Test
    void beforeTheCatalogTheArtifactsOfQuarkusInTheBomStandForTheExtensions() throws IOException {
        Map<String, String> managed = Map.of("io.quarkus:quarkus-core", "0.20.0", "io.quarkus:quarkus-arc", "0.20.0",
                "io.quarkus:quarkus-junit5", "0.20.0", "io.quarkus:quarkus-arquillian", "0.20.0",
                "org.jboss:jandex", "2.1");
        assertEquals(Map.of("quarkus-core", Use.RUNTIME, "quarkus-core-deployment", Use.BUILD,
                        "quarkus-arc", Use.RUNTIME, "quarkus-arc-deployment", Use.BUILD,
                        "quarkus-junit5", Use.TEST, "quarkus-arquillian", Use.TEST, "quarkus-maven-plugin", Use.BUILD),
                uses(ProfileCommand.roots("0.20.0", managed, Map.of(), List.of(),
                        Set.of("quarkus-core-deployment", "quarkus-arc-deployment")::contains)));
    }

    @Test
    void inZeroElevenTheArtifactsOfQuarkusInTheBomAreTheDeploymentModules() throws IOException {
        Map<String, String> managed = Map.of("io.quarkus:quarkus-core", "0.11.0", "io.quarkus:quarkus-agroal", "0.11.0",
                "io.quarkus:quarkus-junit5", "0.11.0");
        Map<String, String> scopes = new HashMap<>();
        managed.keySet().forEach(id -> scopes.put(id, "provided"));
        assertEquals(Map.of("quarkus-core", Use.BUILD, "quarkus-core-runtime", Use.RUNTIME,
                        "quarkus-agroal", Use.BUILD, "quarkus-agroal-runtime", Use.RUNTIME,
                        "quarkus-junit5", Use.TEST, "quarkus-maven-plugin", Use.BUILD),
                uses(ProfileCommand.roots("0.11.0", managed, scopes, List.of(),
                        Set.of("quarkus-core-runtime", "quarkus-agroal-runtime")::contains)));
    }

    @Test
    void theTestFrameworks() {
        for (String artifactId : List.of("quarkus-junit5", "quarkus-junit5-deployment", "quarkus-test-common",
                "quarkus-vertx-http-dev-ui-tests", "quarkus-jacoco", "quarkus-arquillian")) {
            assertEquals(true, ProfileCommand.forTests(artifactId), artifactId);
        }
        assertEquals(false, ProfileCommand.forTests("quarkus-rest"));
    }

    private static Map<String, Use> uses(Map<String, Root> roots) {
        Map<String, Use> uses = new HashMap<>();
        roots.forEach((artifactId, root) -> uses.put(artifactId, root.use()));
        return uses;
    }
}
