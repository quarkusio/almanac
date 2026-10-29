package io.quarkiverse.almanac.model;

/// A public or protected field, method or constructor of an API class.
///
/// @param id the key: name and JVM descriptor of a method or constructor (`<init>()V`,
///           `withApplicationRoot(Ljava/util/function/Consumer;)Lio/quarkus/test/QuarkusUnitTest;`), `name:descriptor`
///           of a field (`INSTANCE:Lio/quarkus/Foo;`)
/// @param v  access, modifiers and deprecation, e.g. `+s` or `+R since 3.32`
public record MemberApi(String id, String v) {}
