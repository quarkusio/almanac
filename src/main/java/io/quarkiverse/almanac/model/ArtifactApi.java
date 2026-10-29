package io.quarkiverse.almanac.model;

import java.util.List;

/// The API classes of one jar.
///
/// @param id      artifactId (groupId `io.quarkus`)
/// @param classes the classes of the jar that are API, sorted by binary name
public record ArtifactApi(String id, List<ClassApi> classes) {}
