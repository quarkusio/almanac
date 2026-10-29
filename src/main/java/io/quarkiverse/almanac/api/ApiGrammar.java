package io.quarkiverse.almanac.api;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// The values and member keys of the API data (`api.json`, the dataset's `api/<major>.x.jsonl`, the site data): a fixed
/// vocabulary in a fixed order, which [ApiReader] writes and `dataset import` and `dataset verify` check.
///
/// - Class value: `<access><kind><modifiers>[X][D|R][ extends <binary name>][ implements <name>,<name>...][ since
///   <text>]`, e.g. `+fR extends io.quarkus.builder.item.MultiBuildItem since 3.32`.
/// - Member value: `<access><modifiers>[X][D|R][ since <text>]`, e.g. `+s` or `#aD`.
/// - access: `+` public, `#` protected. kind (classes only): none for a class, `i` interface, `n` annotation, `e` enum,
///   `r` record. Class modifiers (classes of no kind only, since the others are implicit): `a` abstract, `f` final,
///   `s` static (a nested class, but not a member class of an interface or an annotation, which is static
///   implicitly). Member modifiers: `s` static, `a` abstract, `f` final, `d` default (an interface method with a
///   body), `v` varargs, `e` enum constant; implicit ones are left out (the fields of an interface are not marked
///   `sf`, nor an enum constant; the abstract methods of an interface are not marked `a`).
/// - `X`: `@io.smallrye.common.annotation.Experimental`. `D`: deprecated (the `Deprecated` attribute, which javac
///   writes for `@Deprecated` and for a javadoc `@deprecated`, or a `@java.lang.Deprecated` annotation). `R`:
///   `@Deprecated(forRemoval = true)`, which implies deprecated (never both letters).
/// - `extends`: the superclass by binary name (dots, `$` for nested classes), left out for `java.lang.Object`,
///   `java.lang.Enum`, `java.lang.Record` and for interfaces. `implements`: the interfaces in declaration order (an
///   interface's super-interfaces), `java.lang.annotation.Annotation` left out.
/// - `since`: the non-blank `since` of `@Deprecated`, as written, to the end of the value (only with `D` or `R`).
/// - Member key: the name and JVM descriptor of a method or constructor (`<init>()V`), `name:descriptor` of a field
///   (`INSTANCE:Lio/quarkus/Foo;`). Names with whitespace, `(` or `:` are refused, so that the key tells a field from
///   a method and a dataset id can be split at its spaces; so are the names the JVM refuses (with `.`, `;`, `[` or
///   `/`, or a method name with `<` or `>` other than `<init>`), `<clinit>`, which is never a member, and a
///   constructor that does not return `void`.
///
/// The checks refuse what the reader never writes, among it what the implicit modifiers and supertypes leave out: a
/// superclass `java.lang.Object`, `java.lang.Enum` or `java.lang.Record`, the interface
/// `java.lang.annotation.Annotation`, an interface named twice, a class both abstract and final, an enum constant
/// marked static or final, an abstract method that is also static, final or default, a default method that is static
/// or final, and a constructor with any modifier but varargs. A member class of an interface or an annotation marked
/// static is refused too, by the checks that see the whole release ([#marksStatic] and [#classKind] of the enclosing
/// class): what encloses a class is not in its value.
public final class ApiGrammar {

    /// Access of a public class or member.
    public static final char PUBLIC = '+';
    /// Access of a protected class or member.
    public static final char PROTECTED = '#';
    public static final char INTERFACE = 'i';
    public static final char ANNOTATION = 'n';
    public static final char ENUM = 'e';
    public static final char RECORD = 'r';
    public static final char ABSTRACT = 'a';
    public static final char FINAL = 'f';
    public static final char STATIC = 's';
    public static final char DEFAULT = 'd';
    public static final char VARARGS = 'v';
    public static final char ENUM_CONSTANT = 'e';
    public static final char EXPERIMENTAL = 'X';
    public static final char DEPRECATED = 'D';
    public static final char FOR_REMOVAL = 'R';
    public static final String EXTENDS = " extends ";
    public static final String IMPLEMENTS = " implements ";
    public static final String SINCE = " since ";

    /// A binary class name: dots between packages, `$` for nested classes, never `/`.
    private static final String NAME = "[^\\s,/]+";
    /// The modifiers of a class of no kind, in their order.
    private static final String CLASS_MODIFIERS = "" + ABSTRACT + FINAL + STATIC;
    private static final Pattern CLASS_VALUE = Pattern.compile("[+#](?<kind>[inre]?)(?<modifiers>a?f?s?)X?"
            + "(?<deprecation>[DR]?)(?: extends (?<extends>" + NAME + "))?(?: implements (?<implements>" + NAME
            + "(?:," + NAME + ")*))?(?<since> since .+)?", Pattern.DOTALL);
    /// Superclasses that a class value never names: its kind or the language implies them.
    private static final Set<String> IMPLIED_SUPERCLASSES = Set.of("java.lang.Object", "java.lang.Enum",
            "java.lang.Record");
    private static final String ANNOTATION_INTERFACE = "java.lang.annotation.Annotation";
    private static final Pattern MEMBER_VALUE = Pattern.compile("[+#](?<modifiers>s?a?f?d?v?e?)X?(?<deprecation>[DR]?)"
            + "(?<since> since .+)?", Pattern.DOTALL);
    private static final String FIELD_DESCRIPTOR = "\\[*(?:[ZBCSIJFD]|L[^;\\s]+;)";
    /// A member name: none of the characters a key or a dataset id uses to split (whitespace, `(`, `:`), nor those the
    /// JVM refuses in any name (`.`, `;`, `[`, `/`).
    private static final String MEMBER_NAME = "[^\\s(:.;\\[/]+";
    private static final Pattern METHOD_KEY = Pattern.compile("(?<name>" + MEMBER_NAME + ")\\((?:" + FIELD_DESCRIPTOR
            + ")*\\)(?<returns>V|" + FIELD_DESCRIPTOR + ")");
    private static final Pattern FIELD_KEY = Pattern.compile(MEMBER_NAME + ":" + FIELD_DESCRIPTOR);
    private static final String CONSTRUCTOR = "<init>";

    private ApiGrammar() {
    }

    /// Whether a member key is a field's (`name:descriptor`) rather than a method's or constructor's.
    public static boolean isField(String key) {
        int colon = key.indexOf(':');
        int paren = key.indexOf('(');
        return colon >= 0 && (paren < 0 || colon < paren);
    }

    /// Whether a class or member value marks it deprecated (`D` or `R`, before the first space).
    public static boolean isDeprecated(String value) {
        String flags = flags(value);
        return flags.indexOf(DEPRECATED) >= 0 || flags.indexOf(FOR_REMOVAL) >= 0;
    }

    /// The kind of a class value: `i`, `n`, `e` or `r`, 0 for a class of no kind.
    public static char classKind(String value) {
        char kind = value.length() > 1 ? value.charAt(1) : 0;
        return kind == INTERFACE || kind == ANNOTATION || kind == ENUM || kind == RECORD ? kind : 0;
    }

    /// Whether a class value marks the class static: `s` among the modifiers of a class of no kind.
    public static boolean marksStatic(String value) {
        int end = classModifiersEnd(value);
        return end > 1 && value.charAt(end - 1) == STATIC;
    }

    /// A class value without the `s` that marks the class static; the value itself when it has none.
    public static String withoutStatic(String value) {
        if (!marksStatic(value)) {
            return value;
        }
        int end = classModifiersEnd(value);
        return value.substring(0, end - 1) + value.substring(end);
    }

    /// The end of the modifiers of a class value: the index after the `a`, `f` and `s` that follow its access.
    private static int classModifiersEnd(String value) {
        int end = 1;
        while (end < value.length() && CLASS_MODIFIERS.indexOf(value.charAt(end)) >= 0) {
            end++;
        }
        return end;
    }

    /// Whether a member name can be part of a key: not empty, without whitespace, `(` or `:`.
    public static boolean isMemberName(String name) {
        if (name.isEmpty()) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isWhitespace(c) || Character.isSpaceChar(c) || c == '(' || c == ':') {
                return false;
            }
        }
        return true;
    }

    /// Why a class value breaks the grammar; empty when it follows it.
    public static Optional<String> classValueProblem(String value) {
        Matcher m = CLASS_VALUE.matcher(value);
        if (!m.matches()) {
            return Optional.of("class value '" + value + "' does not follow the grammar");
        }
        if (m.group("since") != null && m.group("deprecation").isEmpty()) {
            return Optional.of("class value '" + value + "' has a since but is not deprecated");
        }
        if (!m.group("kind").isEmpty() && (m.group("modifiers").length() > 0 || m.group("extends") != null)) {
            return Optional.of("class value '" + value + "' has a modifier or a superclass that its kind implies");
        }
        if (m.group("modifiers").contains("af")) {
            return Optional.of("class value '" + value + "' is abstract and final");
        }
        if (m.group("extends") != null && IMPLIED_SUPERCLASSES.contains(m.group("extends"))) {
            return Optional.of("class value '" + value + "' names the superclass " + m.group("extends")
                    + ", which is implied");
        }
        if (m.group("implements") != null) {
            List<String> interfaces = List.of(m.group("implements").split(","));
            if (interfaces.contains(ANNOTATION_INTERFACE)) {
                return Optional.of("class value '" + value + "' names " + ANNOTATION_INTERFACE + ", which is implied");
            }
            if (Set.copyOf(interfaces).size() != interfaces.size()) {
                return Optional.of("class value '" + value + "' names an interface twice");
            }
        }
        return Optional.empty();
    }

    /// Why a member key breaks the grammar; empty when it follows it.
    public static Optional<String> memberKeyProblem(String key) {
        if (isField(key)) {
            return FIELD_KEY.matcher(key).matches() ? Optional.empty() : Optional.of("member key '" + key
                    + "' is neither name(descriptor)descriptor nor name:descriptor");
        }
        Matcher m = METHOD_KEY.matcher(key);
        if (!m.matches()) {
            return Optional.of("member key '" + key + "' is neither name(descriptor)descriptor nor name:descriptor");
        }
        String name = m.group("name");
        if (name.equals("<clinit>")) {
            return Optional.of("member key '" + key + "' is a static initializer, never a member");
        }
        if (name.equals(CONSTRUCTOR) ? !m.group("returns").equals("V") : name.indexOf('<') >= 0
                || name.indexOf('>') >= 0) {
            return Optional.of("member key '" + key + "' is no method or constructor the JVM accepts");
        }
        return Optional.empty();
    }

    /// Why a member value breaks the grammar, for the member of that key; empty when it follows it. A field takes `s`,
    /// `f` and `e` (an enum constant, implicitly static and final: alone), a constructor `v` only, a method `s`, `a`,
    /// `f`, `d` and `v` (abstract neither static, final nor default, default neither static nor final).
    public static Optional<String> memberValueProblem(String key, String value) {
        Matcher m = MEMBER_VALUE.matcher(value);
        if (!m.matches()) {
            return Optional.of("value '" + value + "' of member " + key + " does not follow the grammar");
        }
        if (m.group("since") != null && m.group("deprecation").isEmpty()) {
            return Optional.of("value '" + value + "' of member " + key + " has a since but is not deprecated");
        }
        boolean field = isField(key);
        boolean constructor = !field && key.startsWith(CONSTRUCTOR + "(");
        String modifiers = m.group("modifiers");
        String allowed = field ? "sfe" : constructor ? "v" : "safdv";
        for (char modifier : modifiers.toCharArray()) {
            if (allowed.indexOf(modifier) < 0) {
                return Optional.of("value '" + value + "' of " + (field ? "field " : constructor ? "constructor "
                        : "method ") + key + " has the modifier " + modifier);
            }
        }
        if (field && modifiers.indexOf(ENUM_CONSTANT) >= 0 && modifiers.length() > 1) {
            return Optional.of("value '" + value + "' of field " + key + " marks an enum constant static or final, "
                    + "which it is implicitly");
        }
        if (!field && modifiers.indexOf(ABSTRACT) >= 0 && (modifiers.indexOf(STATIC) >= 0
                || modifiers.indexOf(FINAL) >= 0 || modifiers.indexOf(DEFAULT) >= 0)) {
            return Optional.of("value '" + value + "' of method " + key + " is abstract and static, final or "
                    + "default");
        }
        if (!field && modifiers.indexOf(DEFAULT) >= 0 && (modifiers.indexOf(STATIC) >= 0
                || modifiers.indexOf(FINAL) >= 0)) {
            return Optional.of("value '" + value + "' of method " + key + " is default and static or final");
        }
        return Optional.empty();
    }

    /// The part of a value before its first space: access, kind, modifiers and flags.
    private static String flags(String value) {
        int space = value.indexOf(' ');
        return space < 0 ? value : value.substring(0, space);
    }
}
