package io.quarkiverse.almanac.model;

import java.util.List;

/// `work/snapshots/<v>/classes.json`.
///
/// @param version   the Quarkus release
/// @param artifacts the `io.quarkus` jars of the release's BOM that hold classes, sorted by id
public record ClassesSnapshot(String version, List<ArtifactClasses> artifacts) {}
