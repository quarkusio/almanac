package io.quarkiverse.almanac.model;

/// Where the BOM of a release never published on Maven Central comes from: the poms of github.com/quarkusio/quarkus
/// at the commit that the release's git tag names.
///
/// @param tag    the release's git tag, e.g. `0.10.0`
/// @param commit the full SHA-1 of the commit the tag names
/// @param bom    the BOM's `groupId:artifactId` at that commit, e.g. `io.quarkus:quarkus-bom`, or
///               `org.jboss.shamrock:shamrock-bom` before the project was renamed Quarkus
public record GitSource(String tag, String commit, String bom) {}
