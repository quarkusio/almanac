package io.quarkiverse.almanac.maven;

import io.quarkiverse.almanac.maven.DependencyProfile.Root;
import io.quarkiverse.almanac.maven.DependencyProfile.Use;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// [DependencyProfile]: what Quarkus depends on, collected from poms as Maven collects a dependency's dependencies.
class DependencyProfileTest {

    @Test
    void followsTheCompileAndRuntimeDependencies() throws IOException {
        Map<String, String> poms = new HashMap<>();
        poms.put("io.quarkus:quarkus-rest:1.0", pom("io.quarkus", "quarkus-rest", "1.0", null, "",
                dependency("io.vertx", "vertx-web", null, null, "")
                        + dependency("org.example", "unmanaged", null, null, "<version>2.0</version>")
                        + dependency("junit", "junit", "test", null, "")
                        + dependency("org.example", "optional-lib", null, "true", "")
                        + dependency("org.example", "provided-lib", "provided", null, "")));
        // an artifact that the BOM does not manage is followed at its own version, and leads to one it manages
        poms.put("org.example:unmanaged:2.0", pom("org.example", "unmanaged", "2.0", null, "",
                dependency("org.example", "below-unmanaged", null, null, "<version>0.1</version>")));
        poms.put("org.example:below-unmanaged:1.0", pom("org.example", "below-unmanaged", "1.0", null, "", ""));
        // the parent's dependencies, properties and dependency management are inherited; ${project.groupId} is the
        // child's; a dependency without a scope takes the managed one, so the parent's test framework is no dependency
        poms.put("io.vertx:vertx-parent:20", pom("io.vertx", "vertx-parent", "20", null,
                "<properties><core>vertx-core</core></properties><dependencyManagement><dependencies>"
                        + dependency("org.junit.jupiter", "junit-jupiter", "test", null, "<version>5.12</version>")
                        + "</dependencies></dependencyManagement>",
                dependency("${project.groupId}", "vertx-codegen", null, null, "")));
        poms.put("io.vertx:vertx-web:4.5.26", pom(null, "vertx-web", "4.5.26", "io.vertx:vertx-parent:20", "",
                dependency("${project.groupId}", "${core}", "compile", null,
                        "<exclusions><exclusion><groupId>io.netty</groupId><artifactId>netty-resolver</artifactId>"
                                + "</exclusion></exclusions>")
                        + dependency("org.junit.jupiter", "junit-jupiter", null, null, "")));
        poms.put("io.vertx:vertx-core:4.5.26", pom("io.vertx", "vertx-core", "4.5.26", null, "",
                dependency("io.netty", "netty-handler", null, null, "")
                        + dependency("io.netty", "netty-codec-http2", "runtime", null, "")
                        + dependency("io.netty", "netty-resolver", null, null, "")));
        poms.put("io.vertx:vertx-codegen:4.5.26", pom("io.vertx", "vertx-codegen", "4.5.26", null, "", ""));
        poms.put("io.netty:netty-handler:4.1.132", pom("io.netty", "netty-handler", "4.1.132", null, "", ""));
        poms.put("io.quarkus:quarkus-rest-deployment:1.0", pom("io.quarkus", "quarkus-rest-deployment", "1.0", null,
                "", dependency("io.quarkus", "quarkus-rest", null, null, "")
                        + dependency("org.jboss", "jandex", null, null, "")));
        poms.put("org.jboss:jandex:3.0", pom("org.jboss", "jandex", "3.0", null, "", ""));
        poms.put("io.quarkus:quarkus-junit:1.0", pom("io.quarkus", "quarkus-junit", "1.0", null, "",
                dependency("org.junit.jupiter", "junit-jupiter", null, null, "")
                        + dependency("org.jboss", "jandex", null, null, "")));
        poms.put("org.junit.jupiter:junit-jupiter:5.12",
                pom("org.junit.jupiter", "junit-jupiter", "5.12", null, "", ""));

        Map<String, String> managed = new HashMap<>();
        for (String id : List.of("io.quarkus:quarkus-rest", "io.quarkus:quarkus-rest-deployment",
                "io.quarkus:quarkus-junit")) {
            managed.put(id, "1.0");
        }
        managed.put("io.vertx:vertx-web", "4.5.26");
        managed.put("io.vertx:vertx-core", "4.5.26");
        managed.put("io.vertx:vertx-codegen", "4.5.26");
        managed.put("io.netty:netty-handler", "4.1.132");
        managed.put("io.netty:netty-codec-http2", "4.1.132");
        managed.put("io.netty:netty-resolver", "4.1.132");
        managed.put("org.jboss:jandex", "3.0");
        managed.put("org.junit.jupiter:junit-jupiter", "5.12");
        managed.put("junit:junit", "4.13");
        managed.put("org.example:optional-lib", "1.0");
        managed.put("org.example:provided-lib", "1.0");
        managed.put("org.example:below-unmanaged", "1.0");
        // the BOM's entry of vertx-core excludes netty-codec-http2 wherever vertx-core is
        Map<String, List<String>> exclusions = Map.of("io.vertx:vertx-core", List.of("io.netty:netty-codec-http2"));

        DependencyProfile.Result result = new DependencyProfile(source(poms), "1.0", managed, exclusions, Map.of())
                .collect(List.of(new Root("io.quarkus", "quarkus-rest", "1.0", Use.RUNTIME),
                        new Root("io.quarkus", "quarkus-rest-deployment", "1.0", Use.BUILD),
                        new Root("io.quarkus", "quarkus-junit", "1.0", Use.TEST)));

        assertEquals(Map.of(
                "io.quarkus:quarkus-rest", Use.RUNTIME,
                "io.vertx:vertx-web", Use.RUNTIME,
                "io.vertx:vertx-codegen", Use.RUNTIME,
                "io.vertx:vertx-core", Use.RUNTIME,
                "io.netty:netty-handler", Use.RUNTIME,
                "org.example:below-unmanaged", Use.RUNTIME,
                "io.quarkus:quarkus-rest-deployment", Use.BUILD,
                "org.jboss:jandex", Use.BUILD,
                "io.quarkus:quarkus-junit", Use.TEST,
                "org.junit.jupiter:junit-jupiter", Use.TEST), result.used(),
                "no test, provided or optional dependency, nothing excluded, the strongest use, the BOM's version "
                        + "below an artifact it does not manage");
        assertEquals("[org.example:unmanaged]", result.unmanaged().toString());
        assertTrue(result.problems().isEmpty(), result.problems().toString());
        // the graph upward, of the walk of each node's strongest use: the test framework's way to jandex, a build
        // artifact, is not one; nor is the deployment artifact's way to its runtime artifact, a root at run time
        assertEquals("[io.quarkus:quarkus-rest-deployment]", result.parents().get("org.jboss:jandex").toString());
        assertFalse(result.parents().containsKey("io.quarkus:quarkus-rest"));
        assertEquals("[io.quarkus:quarkus-rest]", result.parents().get("io.vertx:vertx-web").toString());
        // an artifact the BOM does not manage, at the one version the release has of it
        assertEquals("[io.quarkus:quarkus-rest]", result.parents().get("org.example:unmanaged").toString());
        assertEquals("[org.example:unmanaged]", result.parents().get("org.example:below-unmanaged").toString());
        // vertx-core's path leaves out netty-resolver below it (the BOM's own exclusion of netty-codec-http2 goes
        // without saying), so its node says so, and its ways up start there
        assertEquals("[io.vertx:vertx-web]",
                result.parents().get("io.vertx:vertx-core!io.netty:netty-resolver").toString());
        assertEquals("[io.vertx:vertx-core!io.netty:netty-resolver]",
                result.parents().get("io.netty:netty-handler").toString());
        assertEquals(Map.of("io.vertx:vertx-core", "[io.vertx:vertx-core!io.netty:netty-resolver]"),
                strings(result.starts()));
    }

    @Test
    void anArtifactThatAPathExcludesBelowIsANodeOfItsOwn() throws IOException {
        // quarkus-hibernate-reactive depends on quarkus-hibernate-orm without quarkus-agroal: agroal-api comes from
        // quarkus-hibernate-orm, not from quarkus-hibernate-reactive, though both have hibernate-core
        Map<String, String> poms = new HashMap<>();
        poms.put("io.quarkus:quarkus-hibernate-orm:1.0", pom("io.quarkus", "quarkus-hibernate-orm", "1.0", null, "",
                dependency("io.quarkus", "quarkus-agroal", null, null, "")
                        + dependency("org.hibernate", "hibernate-core", null, null, "")));
        poms.put("io.quarkus:quarkus-hibernate-reactive:1.0", pom("io.quarkus", "quarkus-hibernate-reactive", "1.0",
                null, "", dependency("io.quarkus", "quarkus-hibernate-orm", null, null,
                        "<exclusions><exclusion><groupId>io.quarkus</groupId><artifactId>quarkus-agroal</artifactId>"
                                + "</exclusion></exclusions>")));
        poms.put("io.quarkus:quarkus-agroal:1.0", pom("io.quarkus", "quarkus-agroal", "1.0", null, "",
                dependency("io.agroal", "agroal-api", null, null, "")));
        poms.put("io.agroal:agroal-api:2.0", pom("io.agroal", "agroal-api", "2.0", null, "", ""));
        poms.put("org.hibernate:hibernate-core:7.0", pom("org.hibernate", "hibernate-core", "7.0", null, "", ""));
        Map<String, String> managed = Map.of("io.quarkus:quarkus-hibernate-orm", "1.0",
                "io.quarkus:quarkus-hibernate-reactive", "1.0", "io.quarkus:quarkus-agroal", "1.0",
                "io.agroal:agroal-api", "2.0", "org.hibernate:hibernate-core", "7.0");

        DependencyProfile.Result result = new DependencyProfile(source(poms), "1.0", managed, Map.of(), Map.of())
                .collect(List.of(new Root("io.quarkus", "quarkus-hibernate-orm", "1.0", Use.RUNTIME),
                        new Root("io.quarkus", "quarkus-hibernate-reactive", "1.0", Use.RUNTIME)));

        assertEquals(Map.of(
                "io.agroal:agroal-api", "[io.quarkus:quarkus-agroal]",
                "io.quarkus:quarkus-agroal", "[io.quarkus:quarkus-hibernate-orm]",
                "io.quarkus:quarkus-hibernate-orm!io.quarkus:quarkus-agroal", "[io.quarkus:quarkus-hibernate-reactive]",
                // nothing below hibernate-core is left out: one node for both paths
                "org.hibernate:hibernate-core",
                "[io.quarkus:quarkus-hibernate-orm, io.quarkus:quarkus-hibernate-orm!io.quarkus:quarkus-agroal]"),
                strings(result.parents()));
        assertEquals(Map.of("io.quarkus:quarkus-hibernate-orm",
                "[io.quarkus:quarkus-hibernate-orm, io.quarkus:quarkus-hibernate-orm!io.quarkus:quarkus-agroal]"),
                strings(result.starts()));
        assertTrue(result.problems().isEmpty(), result.problems().toString());
    }

    @Test
    void versionsComeFromImportedBomsAndRelocationsLeadOn() throws IOException {
        Map<String, String> poms = new HashMap<>();
        poms.put("io.quarkus:quarkus-x:1.0", pom("io.quarkus", "quarkus-x", "1.0", null, "",
                dependency("org.example", "client", null, null, "<version>3.0</version>")
                        + dependency("org.example", "old-name", null, null, "")));
        // the client's parent imports a BOM, which gives the version of its transport, which the BOM does not manage
        poms.put("org.example:client-parent:3.0", pom("org.example", "client-parent", "3.0", null,
                "<properties><stack.version>9</stack.version></properties><dependencyManagement><dependencies>"
                        + dependency("org.example", "stack-bom", "import", null,
                        "<type>pom</type><version>${stack.version}</version>")
                        + "</dependencies></dependencyManagement>", ""));
        poms.put("org.example:client:3.0", pom(null, "client", "3.0", "org.example:client-parent:3.0", "",
                dependency("org.example", "transport", null, null, "")));
        poms.put("org.example:stack-bom:9", pom("org.example", "stack-bom", "9", null,
                "<dependencyManagement><dependencies>"
                        + dependency("org.example", "transport", null, null, "<version>9.1</version>")
                        + "</dependencies></dependencyManagement>", ""));
        poms.put("org.example:transport:9.1", pom("org.example", "transport", "9.1", null, "",
                dependency("io.netty", "netty-buffer", null, null, "")));
        poms.put("io.netty:netty-buffer:4.2", pom("io.netty", "netty-buffer", "4.2", null, "", ""));
        // a relocated pom leads to the artifact it names
        poms.put("org.example:old-name:1", "<project><groupId>org.example</groupId><artifactId>old-name</artifactId>"
                + "<version>1</version><distributionManagement><relocation><artifactId>new-name</artifactId>"
                + "</relocation></distributionManagement></project>");
        poms.put("org.example:new-name:1", pom("org.example", "new-name", "1", null, "", ""));
        Map<String, String> managed = Map.of("io.quarkus:quarkus-x", "1.0", "io.netty:netty-buffer", "4.2",
                "org.example:old-name", "1", "org.example:new-name", "1");

        DependencyProfile.Result result = new DependencyProfile(source(poms), "1.0", managed, Map.of(), Map.of())
                .collect(List.of(new Root("io.quarkus", "quarkus-x", "1.0", Use.RUNTIME)));

        assertEquals(List.of("io.netty:netty-buffer", "io.quarkus:quarkus-x", "org.example:new-name",
                "org.example:old-name"), List.copyOf(result.used().keySet()));
        assertEquals("[org.example:transport]", result.parents().get("io.netty:netty-buffer").toString());
        assertEquals("[org.example:client]", result.parents().get("org.example:transport").toString());
        assertEquals("[org.example:old-name]", result.parents().get("org.example:new-name").toString());
        assertEquals("[org.example:client, org.example:transport]", result.unmanaged().toString());
        assertTrue(result.problems().isEmpty(), result.problems().toString());
    }

    @Test
    void profilesActiveInSomeBuildCountAndThoseActiveByDefaultOnlyWithoutThem() throws IOException {
        String profiles = "<profiles>"
                + "<profile><id>default</id><activation><activeByDefault>true</activeByDefault></activation>"
                + "<dependencies>" + dependency("org.example", "by-default", null, null, "") + "</dependencies>"
                + "</profile>"
                + "<profile><id>jdk</id><activation><jdk>[17,)</jdk></activation>"
                + "<dependencies>" + dependency("org.example", "modern", null, null, "") + "</dependencies></profile>"
                + "<profile><id>old</id><activation><jdk>1.8</jdk></activation>"
                + "<dependencies>" + dependency("org.example", "old", null, null, "") + "</dependencies></profile>"
                // each operating system has its own native: every one of them is a dependency of some application
                + "<profile><id>linux</id><activation><os><family>Linux</family><arch>amd64</arch></os></activation>"
                + "<dependencies>" + dependency("org.example", "native-linux", null, null, "") + "</dependencies>"
                + "</profile>"
                + "<profile><id>mac</id><activation><os><family>mac</family></os><jdk>[17,)</jdk></activation>"
                + "<dependencies>" + dependency("org.example", "native-mac", null, null, "") + "</dependencies>"
                + "</profile>"
                + "<profile><id>file</id><activation><file><exists>pom.xml</exists></file></activation>"
                + "<dependencies>" + dependency("org.example", "by-file", null, null, "") + "</dependencies>"
                + "</profile>"
                + "</profiles>";
        Map<String, String> poms = new HashMap<>();
        poms.put("io.quarkus:quarkus-x:1.0", pom("io.quarkus", "quarkus-x", "1.0", null, profiles, ""));
        Map<String, String> managed = new HashMap<>(Map.of("io.quarkus:quarkus-x", "1.0"));
        for (String artifactId : List.of("by-default", "modern", "old", "native-linux", "native-mac", "by-file")) {
            managed.put("org.example:" + artifactId, "1");
            poms.put("org.example:" + artifactId + ":1", pom("org.example", artifactId, "1", null, "", ""));
        }
        DependencyProfile.Result result = new DependencyProfile(source(poms), "1.0", managed, Map.of(), Map.of())
                .collect(List.of(new Root("io.quarkus", "quarkus-x", "1.0", Use.RUNTIME)));
        assertEquals(List.of("io.quarkus:quarkus-x", "org.example:modern", "org.example:native-linux",
                "org.example:native-mac"), List.copyOf(result.used().keySet()));
    }

    @Test
    void aPomThatUsesAPrefixItDoesNotDeclareIsReadAsMavenReadsIt() throws IOException {
        Map<String, String> poms = new HashMap<>();
        poms.put("io.quarkus:quarkus-x:1.0", pom("io.quarkus", "quarkus-x", "1.0", null, "",
                dependency("com.amazonaws", "aws-lambda-java-events", null, null, "")));
        // no xmlns:xsi for its xsi:schemaLocation, and a plugin's configuration element with a prefix of its own
        poms.put("com.amazonaws:aws-lambda-java-events:2.2.5", "<project xmlns=\"http://maven.apache.org/POM/4.0.0\" "
                + "xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd\">"
                + "<groupId>com.amazonaws</groupId><artifactId>aws-lambda-java-events</artifactId>"
                + "<version>2.2.5</version><build><plugins><plugin><configuration><Xlint:all/></configuration></plugin>"
                + "</plugins></build><dependencies>" + dependency("joda-time", "joda-time", null, null, "")
                + "</dependencies></project>");
        poms.put("joda-time:joda-time:2.10", pom("joda-time", "joda-time", "2.10", null, "", ""));
        Map<String, String> managed = Map.of("io.quarkus:quarkus-x", "1.0", "com.amazonaws:aws-lambda-java-events",
                "2.2.5", "joda-time:joda-time", "2.10");
        DependencyProfile.Result result = new DependencyProfile(source(poms), "1.0", managed, Map.of(), Map.of())
                .collect(List.of(new Root("io.quarkus", "quarkus-x", "1.0", Use.RUNTIME)));
        assertEquals(Use.RUNTIME, result.used().get("joda-time:joda-time"));
        assertTrue(result.problems().isEmpty(), result.problems().toString());
    }

    @Test
    void jdkConditionsHoldAsMavenReadsThem() {
        assertTrue(DependencyProfile.jdkHolds("[11,)", "21"));
        assertTrue(DependencyProfile.jdkHolds("(,21]", "21"));
        assertFalse(DependencyProfile.jdkHolds("(,21)", "21"));
        assertFalse(DependencyProfile.jdkHolds("[9,17)", "21"));
        assertFalse(DependencyProfile.jdkHolds("1.8", "21"));
        assertTrue(DependencyProfile.jdkHolds("!1.8", "21"));
        assertTrue(DependencyProfile.jdkHolds("21", "21"));
    }

    @Test
    void theBomsExclusionsAreReadByEntry() throws IOException {
        String bom = "<project><dependencyManagement><dependencies>"
                + dependency("io.vertx", "vertx-core", null, null, "<version>4.5.26</version><exclusions><exclusion>"
                        + "<groupId>io.netty</groupId><artifactId>*</artifactId></exclusion></exclusions>")
                + dependency("io.quarkus", "quarkus-core", null, null, "<version>1.0</version>")
                + "</dependencies></dependencyManagement></project>";
        assertEquals(Map.of("io.vertx:vertx-core", List.of("io.netty:*")),
                DependencyProfile.exclusions(bom.getBytes(StandardCharsets.UTF_8), "1.0"));
    }

    @Test
    void anArtifactThatTheBomManagesInScopeProvidedIsNeitherFollowedNorUsedBelowTheRoots() throws IOException {
        String bom = "<project><dependencyManagement><dependencies>"
                + dependency("org.graalvm.sdk", "nativeimage", "provided", null, "<version>24</version>")
                + dependency("io.quarkus", "quarkus-x", null, null, "<version>1.0</version>")
                + dependency("io.quarkus", "quarkus-x-runtime", "runtime", null, "<version>1.0</version>")
                + dependency("org.example", "stack-bom", "import", null, "<type>pom</type><version>9</version>")
                + "</dependencies></dependencyManagement></project>";
        Map<String, String> scopes = DependencyProfile.scopes(bom.getBytes(StandardCharsets.UTF_8), "1.0");
        assertEquals(Map.of("org.graalvm.sdk:nativeimage", "provided", "io.quarkus:quarkus-x-runtime", "runtime"),
                scopes, "the scopes given, imports aside");
        Map<String, String> poms = new HashMap<>();
        poms.put("io.quarkus:quarkus-x:1.0", pom("io.quarkus", "quarkus-x", "1.0", null, "",
                dependency("org.graalvm.sdk", "nativeimage", null, null, "")
                        + dependency("io.quarkus", "quarkus-x-runtime", null, null, "")));
        poms.put("io.quarkus:quarkus-x-runtime:1.0", pom("io.quarkus", "quarkus-x-runtime", "1.0", null, "", ""));
        Map<String, String> managed = Map.of("io.quarkus:quarkus-x", "1.0", "io.quarkus:quarkus-x-runtime", "1.0",
                "org.graalvm.sdk:nativeimage", "24");
        // nativeimage's pom is never read: no source has it
        DependencyProfile.Result result = new DependencyProfile(source(poms), "1.0", managed, Map.of(), scopes)
                .collect(List.of(new Root("io.quarkus", "quarkus-x", "1.0", Use.RUNTIME)));
        assertEquals(List.of("io.quarkus:quarkus-x", "io.quarkus:quarkus-x-runtime"),
                List.copyOf(result.used().keySet()), "runtime is a scope that reaches the application");
        assertTrue(result.problems().isEmpty(), result.problems().toString());
    }

    @Test
    void whatEveryPathExcludesIsNeitherUnmanagedNorAProblem() throws IOException {
        Map<String, String> poms = new HashMap<>();
        poms.put("io.quarkus:quarkus-x:1.0", pom("io.quarkus", "quarkus-x", "1.0", null, "",
                dependency("org.example", "lib", null, null, "<exclusions><exclusion><groupId>org.example</groupId>"
                        + "<artifactId>ranged</artifactId></exclusion></exclusions>")));
        poms.put("org.example:lib:1", pom("org.example", "lib", "1", null, "",
                dependency("org.example", "ranged", null, null, "<version>[1,2)</version>")));
        Map<String, String> managed = Map.of("io.quarkus:quarkus-x", "1.0", "org.example:lib", "1");
        DependencyProfile.Result result = new DependencyProfile(source(poms), "1.0", managed, Map.of(), Map.of())
                .collect(List.of(new Root("io.quarkus", "quarkus-x", "1.0", Use.RUNTIME)));
        assertEquals(List.of("io.quarkus:quarkus-x", "org.example:lib"), List.copyOf(result.used().keySet()));
        assertTrue(result.unmanaged().isEmpty(), result.unmanaged().toString());
        assertTrue(result.problems().isEmpty(), result.problems().toString());
    }

    @Test
    void aBomThatAnImportCannotReadFailsTheProfile() {
        Map<String, String> poms = new HashMap<>();
        poms.put("io.quarkus:quarkus-x:1.0", pom("io.quarkus", "quarkus-x", "1.0", null, "",
                dependency("org.example", "client", null, null, "<version>3.0</version>")));
        poms.put("org.example:client:3.0", pom("org.example", "client", "3.0", null,
                "<dependencyManagement><dependencies>"
                        + dependency("org.example", "stack-bom", "import", null, "<type>pom</type><version>9</version>")
                        + "</dependencies></dependencyManagement>",
                dependency("org.example", "transport", null, null, "")));
        BomResolver.PomSource readable = source(poms);
        // the repository stops answering (a 429, a 403) when the BOM is asked for: no profile without its versions
        BomResolver.PomSource source = (groupId, artifactId, version) -> {
            if (artifactId.equals("stack-bom")) {
                throw new IOException("requests stopped");
            }
            return readable.pom(groupId, artifactId, version);
        };
        Map<String, String> managed = Map.of("io.quarkus:quarkus-x", "1.0");
        assertThrows(IOException.class, () -> new DependencyProfile(source, "1.0", managed, Map.of(), Map.of())
                .collect(List.of(new Root("io.quarkus", "quarkus-x", "1.0", Use.RUNTIME))));
    }

    @Test
    void twoVersionsOfAnArtifactTheBomDoesNotManageAreTwoNodes() throws IOException {
        Map<String, String> poms = new HashMap<>();
        poms.put("io.quarkus:quarkus-a:1.0", pom("io.quarkus", "quarkus-a", "1.0", null, "",
                dependency("org.example", "client", null, null, "<version>1</version>")));
        poms.put("io.quarkus:quarkus-b:1.0", pom("io.quarkus", "quarkus-b", "1.0", null, "",
                dependency("org.example", "client", null, null, "<version>2</version>")));
        // the older client brings netty-buffer, the newer one netty-common
        poms.put("org.example:client:1", pom("org.example", "client", "1", null, "",
                dependency("io.netty", "netty-buffer", null, null, "")));
        poms.put("org.example:client:2", pom("org.example", "client", "2", null, "",
                dependency("io.netty", "netty-common", null, null, "")));
        poms.put("io.netty:netty-buffer:4.2", pom("io.netty", "netty-buffer", "4.2", null, "", ""));
        poms.put("io.netty:netty-common:4.2", pom("io.netty", "netty-common", "4.2", null, "", ""));
        Map<String, String> managed = Map.of("io.quarkus:quarkus-a", "1.0", "io.quarkus:quarkus-b", "1.0",
                "io.netty:netty-buffer", "4.2", "io.netty:netty-common", "4.2");
        DependencyProfile.Result result = new DependencyProfile(source(poms), "1.0", managed, Map.of(), Map.of())
                .collect(List.of(new Root("io.quarkus", "quarkus-a", "1.0", Use.RUNTIME),
                        new Root("io.quarkus", "quarkus-b", "1.0", Use.RUNTIME)));
        assertEquals(Map.of("io.netty:netty-buffer", "[org.example:client@1]",
                "io.netty:netty-common", "[org.example:client@2]",
                "org.example:client@1", "[io.quarkus:quarkus-a]",
                "org.example:client@2", "[io.quarkus:quarkus-b]"), strings(result.parents()));
    }

    @Test
    void onlyTheNodesOnAWayUpFromAUsedArtifactAreKept() throws IOException {
        Map<String, String> poms = new HashMap<>();
        poms.put("io.quarkus:quarkus-x:1.0", pom("io.quarkus", "quarkus-x", "1.0", null, "",
                dependency("org.example", "leaf", null, null, "<version>1</version>")
                        + dependency("io.netty", "netty-buffer", null, null, "")));
        poms.put("org.example:leaf:1", pom("org.example", "leaf", "1", null, "", ""));
        poms.put("io.netty:netty-buffer:4.2", pom("io.netty", "netty-buffer", "4.2", null, "", ""));
        Map<String, String> managed = Map.of("io.quarkus:quarkus-x", "1.0", "io.netty:netty-buffer", "4.2");
        DependencyProfile.Result result = new DependencyProfile(source(poms), "1.0", managed, Map.of(), Map.of())
                .collect(List.of(new Root("io.quarkus", "quarkus-x", "1.0", Use.RUNTIME)));
        assertEquals(Map.of("io.netty:netty-buffer", "[io.quarkus:quarkus-x]"), strings(result.parents()),
                "not the leaf that the BOM does not manage, which leads to nothing used");
        assertEquals(3, result.poms(), "the poms read");
    }

    /// Each key's set as its string, for comparing.
    private static Map<String, String> strings(Map<String, ? extends java.util.SortedSet<String>> sets) {
        Map<String, String> strings = new HashMap<>();
        sets.forEach((key, set) -> strings.put(key, set.toString()));
        return strings;
    }

    private static BomResolver.PomSource source(Map<String, String> poms) {
        return (groupId, artifactId, version) -> Optional
                .ofNullable(poms.get(groupId + ":" + artifactId + ":" + version))
                .map(xml -> xml.getBytes(StandardCharsets.UTF_8));
    }

    private static String pom(String groupId, String artifactId, String version, String parent, String extra,
            String dependencies) {
        StringBuilder xml = new StringBuilder("<project xmlns=\"http://maven.apache.org/POM/4.0.0\">");
        if (parent != null) {
            String[] p = parent.split(":");
            xml.append("<parent><groupId>").append(p[0]).append("</groupId><artifactId>").append(p[1])
                    .append("</artifactId><version>").append(p[2]).append("</version></parent>");
        }
        if (groupId != null) {
            xml.append("<groupId>").append(groupId).append("</groupId>");
        }
        xml.append("<artifactId>").append(artifactId).append("</artifactId><version>").append(version)
                .append("</version>").append(extra);
        if (!dependencies.isEmpty()) {
            xml.append("<dependencies>").append(dependencies).append("</dependencies>");
        }
        return xml.append("</project>").toString();
    }

    private static String dependency(String groupId, String artifactId, String scope, String optional, String extra) {
        return "<dependency><groupId>" + groupId + "</groupId><artifactId>" + artifactId + "</artifactId>"
                + (scope != null ? "<scope>" + scope + "</scope>" : "")
                + (optional != null ? "<optional>" + optional + "</optional>" : "") + extra + "</dependency>";
    }
}
