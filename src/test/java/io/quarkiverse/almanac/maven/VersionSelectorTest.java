package io.quarkiverse.almanac.maven;

import io.quarkiverse.almanac.maven.VersionSelector.PreReleases;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.quarkiverse.almanac.maven.VersionSelector.PreReleases.ALL;
import static io.quarkiverse.almanac.maven.VersionSelector.PreReleases.NONE;
import static io.quarkiverse.almanac.maven.VersionSelector.PreReleases.UPCOMING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionSelectorTest {

    /// In maven-metadata.xml order (4-part versions are listed before their base version).
    private static final List<String> PUBLISHED = List.of("2.16.12.Final", "3.0.0.Alpha1", "3.0.0.CR1",
            "3.0.0.Final", "3.0.4.Final", "3.2.0.CR1", "3.2.12.Final", "3.8.6.1", "3.8.6", "3.9.0.CR1", "3.9.0",
            "3.40.0.CR1", "3.40.0", "3.40.1.1", "3.40.1", "4.0.0.Beta1", "999-SNAPSHOT");

    private final VersionSelector selector = new VersionSelector(PUBLISHED);
    private final QuarkusVersion since = QuarkusVersion.parse(VersionSelector.DEFAULT_SINCE);

    @Test
    void allIsEveryFinalSinceTheBoundPlusANewerPreRelease() {
        assertEquals(List.of("3.0.0.Final", "3.0.4.Final", "3.2.12.Final", "3.8.6", "3.8.6.1", "3.9.0", "3.40.0",
                "3.40.1", "3.40.1.1", "4.0.0.Beta1"), texts(selector.select("all", since)));
    }

    @Test
    void versionsSelectedByNameOnlyStayOutOfAllAndStreams() {
        VersionSelector byName = new VersionSelector(List.of("0.11.0", "0.12.0"), List.of("0.10.0", "0.11.0"));
        QuarkusVersion zero = QuarkusVersion.parse("0.0");
        assertEquals(List.of("0.11.0", "0.12.0"), texts(byName.all(zero)));
        assertEquals(List.of("0.11.0", "0.12.0"), texts(byName.streams(zero)));
        assertEquals(List.of("0.10.0", "0.11.0", "0.12.0"), texts(byName.select("0.12.0,0.10.0,0.11.0", zero)));
        assertThrows(IllegalArgumentException.class, () -> byName.explicit(List.of("0.9.0")));
    }

    @Test
    void allWithEachPreReleaseMode() {
        assertEquals(List.of("3.0.0.Final", "3.0.4.Final", "3.2.12.Final", "3.8.6", "3.8.6.1", "3.9.0", "3.40.0",
                "3.40.1", "3.40.1.1"), texts(selector.all(since, NONE)));
        assertEquals(List.of("3.0.0.Final", "3.0.4.Final", "3.2.12.Final", "3.8.6", "3.8.6.1", "3.9.0", "3.40.0",
                "3.40.1", "3.40.1.1", "4.0.0.Beta1"), texts(selector.all(since, UPCOMING)));
        assertEquals(List.of("3.0.0.Alpha1", "3.0.0.CR1", "3.0.0.Final", "3.0.4.Final", "3.2.0.CR1", "3.2.12.Final",
                "3.8.6", "3.8.6.1", "3.9.0.CR1", "3.9.0", "3.40.0.CR1", "3.40.0", "3.40.1", "3.40.1.1",
                "4.0.0.Beta1"), texts(selector.all(since, ALL)));
    }

    @Test
    void streamsIsTheNewestFinalOfEachStreamPlusANewerPreRelease() {
        assertEquals(List.of("3.0.4.Final", "3.2.12.Final", "3.8.6.1", "3.9.0", "3.40.1.1", "4.0.0.Beta1"),
                texts(selector.select("streams", since)));
    }

    @Test
    void streamsWithEachPreReleaseMode() {
        assertEquals(List.of("3.0.4.Final", "3.2.12.Final", "3.8.6.1", "3.9.0", "3.40.1.1"),
                texts(selector.streams(since, NONE)));
        assertEquals(List.of("3.0.4.Final", "3.2.12.Final", "3.8.6.1", "3.9.0", "3.40.1.1", "4.0.0.Beta1"),
                texts(selector.streams(since, UPCOMING)));
        // the newest pre-release of every stream: 3.0.0.CR1 (not Alpha1), none for 3.8
        assertEquals(List.of("3.0.0.CR1", "3.0.4.Final", "3.2.0.CR1", "3.2.12.Final", "3.8.6.1", "3.9.0.CR1",
                "3.9.0", "3.40.0.CR1", "3.40.1.1", "4.0.0.Beta1"), texts(selector.streams(since, ALL)));
    }

    @Test
    void selectPassesTheModeOnAndTakesExplicitListsAsGiven() {
        assertEquals(selector.all(since, ALL), selector.select("all", since, ALL));
        assertEquals(selector.streams(since, NONE), selector.select(" streams ", since, NONE));
        assertEquals(selector.all(since, UPCOMING), selector.select("all", since));
        assertEquals(List.of("3.9.0.CR1", "4.0.0.Beta1"), texts(selector.select("4.0.0.Beta1,3.9.0.CR1", since, NONE)));
    }

    @Test
    void preReleaseOlderThanTheNewestFinalIsLeftOut() {
        VersionSelector withoutBeta = new VersionSelector(List.of("3.39.0", "3.40.0.CR1", "3.40.0"));
        assertEquals(List.of("3.39.0", "3.40.0"), texts(withoutBeta.all(since)));
        assertEquals(List.of("3.39.0", "3.40.0"), texts(withoutBeta.streams(since)));
        assertEquals(List.of("3.39.0", "3.40.0.CR1", "3.40.0"), texts(withoutBeta.all(since, ALL)));
        assertEquals(List.of("3.39.0", "3.40.0.CR1", "3.40.0"), texts(withoutBeta.streams(since, ALL)));
    }

    @Test
    void upcomingIsTheNewestPreReleaseOnly() {
        VersionSelector upcomingLine = new VersionSelector(List.of("3.40.0", "4.0.0.Alpha1", "4.0.0.Beta1"));
        assertEquals(List.of("3.40.0", "4.0.0.Beta1"), texts(upcomingLine.all(since, UPCOMING)));
        assertEquals(List.of("3.40.0", "4.0.0.Alpha1", "4.0.0.Beta1"), texts(upcomingLine.all(since, ALL)));
        assertEquals(List.of("3.40.0", "4.0.0.Beta1"), texts(upcomingLine.streams(since, ALL)));
    }

    @Test
    void sinceComparesStreams() {
        assertEquals(List.of("3.40.0", "3.40.1", "3.40.1.1", "4.0.0.Beta1"),
                texts(selector.all(QuarkusVersion.parse("3.40"))));
        assertEquals(List.of("4.0.0.Beta1"), texts(selector.streams(QuarkusVersion.parse("4.0"))));
        assertEquals(List.of(), texts(selector.all(QuarkusVersion.parse("4.0"), NONE)));
        // the pre-releases of the oldest stream are in, the streams before it are not
        assertEquals("3.0.0.Alpha1", selector.all(since, ALL).getFirst().text());
        assertEquals(List.of("3.9.0.CR1", "3.9.0", "3.40.0.CR1", "3.40.0", "3.40.1", "3.40.1.1", "4.0.0.Beta1"),
                texts(selector.all(QuarkusVersion.parse("3.9"), ALL)));
        assertEquals("2.16.12.Final", selector.all(QuarkusVersion.parse("2.16"), NONE).getFirst().text());
        // a longer bound counts as its stream
        assertEquals(List.of("3.40.0", "3.40.1", "3.40.1.1"),
                texts(selector.all(QuarkusVersion.parse("3.40.1"), NONE)));
        assertEquals(selector.all(QuarkusVersion.parse("3.9"), ALL),
                selector.all(QuarkusVersion.parse("3.9.0.CR2"), ALL));
    }

    @Test
    void preReleaseModesParseInAnyCase() {
        assertEquals(UPCOMING, PreReleases.parse("upcoming"));
        assertEquals(ALL, PreReleases.parse(" All "));
        assertEquals(NONE, PreReleases.parse("NONE"));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> PreReleases.parse("cr"));
        assertTrue(error.getMessage().contains("none, upcoming or all"), error.getMessage());
    }

    @Test
    void explicitListsResolveAgainstPublishedVersions() {
        assertEquals(List.of("3.0.4.Final", "3.40.1", "4.0.0.Beta1"),
                texts(selector.select("4.0.0.Beta1, 3.40.1,3.0.4", since)));
        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () -> selector.select("3.99.0", since));
        assertTrue(error.getMessage().contains("3.99.0"));
    }

    @Test
    void ltsStreams() {
        assertTrue(VersionSelector.isLts(QuarkusVersion.parse("3.20.6.2")));
        assertTrue(VersionSelector.isLts(QuarkusVersion.parse("3.33.4.1")));
        assertTrue(VersionSelector.isLts(QuarkusVersion.parse("3.40.1.1")));
        assertFalse(VersionSelector.isLts(QuarkusVersion.parse("3.21.0")));
        assertFalse(VersionSelector.isLts(QuarkusVersion.parse("3.39.5")));
    }

    private static List<String> texts(List<QuarkusVersion> versions) {
        return versions.stream().map(QuarkusVersion::text).toList();
    }
}
