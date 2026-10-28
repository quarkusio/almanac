package io.quarkiverse.almanac.model;

import java.util.List;

/// The classes of one jar.
///
/// @param id      artifactId (groupId `io.quarkus`)
/// @param classes binary names, sorted, nested classes included: `io.quarkus.test.QuarkusUnitTest`,
///                `io.quarkus.test.QuarkusUnitTest$1`
public record ArtifactClasses(String id, List<String> classes) {}
