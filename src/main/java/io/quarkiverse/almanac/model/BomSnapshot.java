package io.quarkiverse.almanac.model;

import java.util.List;

/// `work/snapshots/<v>/bom.json`.
///
/// @param version the Quarkus release
/// @param date    UTC `yyyy-MM-dd` of the BOM pom's `Last-Modified`; for a release never published on Maven Central,
///                of the commit its git tag names
/// @param git     where the BOM of a release never published on Maven Central comes from, null for a published one
/// @param deps    managed dependencies sorted by id
public record BomSnapshot(String version, String date, GitSource git, List<ManagedDep> deps) {

    /// A BOM published on Maven Central.
    public BomSnapshot(String version, String date, List<ManagedDep> deps) {
        this(version, date, null, deps);
    }
}
