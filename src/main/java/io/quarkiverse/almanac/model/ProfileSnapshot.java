package io.quarkiverse.almanac.model;

import java.util.List;
import java.util.SortedMap;

/// `work/snapshots/<v>/profile.json`: the dependency profile of a release, the artifacts of its BOM that Quarkus
/// itself depends on ([io.quarkiverse.almanac.maven.DependencyProfile]).
///
/// @param version   the Quarkus release
/// @param roots     the artifacts of Quarkus the profile starts from: extensions' runtime and deployment artifacts,
///                  the Maven plugin, the test frameworks
/// @param poms      the poms read, parents and imported BOMs included
/// @param unmanaged the dependencies that the BOM does not manage (distinct ids), followed at their own versions
/// @param problems  the poms that could not be read, the dependencies that could not be interpolated or followed
/// @param used      each [ManagedDep#id()] that Quarkus depends on → `runtime`, `build` or `test`, the strongest use
/// @param parents   the graph of the walks upward: each node → the nodes whose dependency brought it, sorted, in the
///                  walk of its strongest use, for the nodes on a way up from a used id. A node is an id, then `@` and
///                  the version for an artifact that the BOM does not manage and that the release reaches at more than
///                  one version, then `!` and the exclusions of its path that leave out something below it, if any:
///                  following the parents up from an artifact's nodes finds exactly what brings it in
/// @param starts    each used id whose nodes of its strongest use are not just the node written as the id → those
///                  nodes, sorted
public record ProfileSnapshot(String version, int roots, int poms, int unmanaged, List<String> problems,
        SortedMap<String, String> used, SortedMap<String, List<String>> parents,
        SortedMap<String, List<String>> starts) {}
