package io.quarkiverse.almanac.model;

import java.util.List;

/// `work/snapshots/<v>/api.json`: the API of the release's `io.quarkus` jars, read from their class files. Only the
/// classes that are API are listed, with their public and protected members; the values follow the grammar of the
/// dataset's `README.md` (`io.quarkiverse.almanac.api.ApiGrammar`).
///
/// @param version   the Quarkus release
/// @param reader    the reader and its version, e.g. `api/1`
/// @param artifacts the jars of the release that have an API class, sorted by id
public record ApiSnapshot(String version, String reader, List<ArtifactApi> artifacts) {}
