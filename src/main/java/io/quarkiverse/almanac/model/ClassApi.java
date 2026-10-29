package io.quarkiverse.almanac.model;

import java.util.List;

/// A class that is API: public or protected, every enclosing class too, and neither synthetic, anonymous nor local.
///
/// @param name    binary name, nested classes with `$`: `io.quarkus.test.QuarkusUnitTest$Builder`
/// @param v       access, kind, modifiers, deprecation and supertypes, e.g.
///                `+fR extends io.quarkus.builder.item.MultiBuildItem since 3.32`
/// @param members the public and protected fields, methods and constructors, sorted by key
public record ClassApi(String name, String v, List<MemberApi> members) {}
