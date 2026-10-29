package io.quarkiverse.almanac.api;

import io.quarkiverse.almanac.config.bytecode.ClassFile;
import io.quarkiverse.almanac.model.ApiSnapshot;
import io.quarkiverse.almanac.model.ArtifactApi;
import io.quarkiverse.almanac.model.ClassApi;
import io.quarkiverse.almanac.model.MemberApi;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InnerClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

/// Reads the API of class files (ASM, without code, debug information or frames): which classes are API, and the value
/// of each class and of its public and protected members in the [ApiGrammar].
///
/// Reading happens in two steps. [#parse] reads one class file and depends on its bytes alone, so that its result can
/// be kept per (entry name, CRC-32, size) and reused by every release whose jar has the same file. [#snapshot] then
/// applies the rules that need the release: a class is API when it is public or protected (a nested class by its own
/// `InnerClasses` entry), every enclosing class is API too, and it is neither synthetic, anonymous nor local; and a
/// member class of an interface or an annotation, which is static implicitly, is not marked `s` (its own class file
/// does not tell what kind of class encloses it). Not read: code, generic signatures, `throws` clauses, annotation
/// defaults, javadoc.
public final class ApiReader {

    /// The reader and its version, kept in `api.json` and `releases.jsonl`. What it reads and how it writes values is
    /// part of it: a change needs a new version, and the releases of a major line are then extracted again, since the
    /// dataset never mixes readers within a line.
    public static final String READER = "api/1";

    static final String EXPERIMENTAL_ANNOTATION = "Lio/smallrye/common/annotation/Experimental;";
    static final String DEPRECATED_ANNOTATION = "Ljava/lang/Deprecated;";
    /// Superclasses that the kind or the language implies, left out of class values.
    private static final Set<String> IMPLIED_SUPERCLASSES = Set.of("java/lang/Object", "java/lang/Enum",
            "java/lang/Record");
    private static final String ANNOTATION_INTERFACE = "java/lang/annotation/Annotation";
    private static final int VISIBLE = Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED;
    /// Nested enclosing classes followed at most, against malformed `InnerClasses` attributes.
    private static final int MAX_NESTING = 64;

    /// What a class file declares as far as the API goes. It depends on the bytes alone.
    ///
    /// @param name    internal name, e.g. `io/quarkus/test/QuarkusUnitTest$Builder`
    /// @param outer   internal name of the class that encloses a member class, null for a top-level class
    /// @param api     whether the class is API by itself: public or protected (a nested class by its own `InnerClasses`
    ///                entry), neither synthetic, anonymous nor local; a nested class also needs its enclosing classes
    ///                to be API ([#snapshot])
    /// @param value   the class value when `api`, else null; [#snapshot] leaves out its `s` when the enclosing class is
    ///                an interface or an annotation
    /// @param members the API members sorted by key when `api`, else empty
    public record Parsed(String name, String outer, boolean api, String value, List<MemberApi> members) {
    }

    /// `X`, `D`/`R` and `since` of a declaration.
    private record Marks(boolean experimental, boolean deprecated, boolean forRemoval, String since) {
    }

    private ApiReader() {
    }

    /// Reads one class file.
    ///
    /// @throws IllegalArgumentException when the bytes are not a readable class file, or an API member's name has
    ///                                  whitespace, `(` or `:` (the dataset could not key it)
    public static Parsed parse(byte[] bytes) {
        ClassNode node;
        try {
            node = ClassFile.parse(bytes).node();
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("not a readable class file: " + e, e);
        }
        InnerClassNode self = null;
        if (node.innerClasses != null) {
            for (InnerClassNode inner : node.innerClasses) {
                if (inner.name.equals(node.name)) {
                    self = inner;
                    break;
                }
            }
        }
        // a nested class's own access and modifiers are in its InnerClasses entry (the class file says public for a
        // protected class, package for a private one); local and anonymous classes have no outer class there
        int access = self != null ? self.access : node.access;
        boolean localOrAnonymous = node.outerClass != null
                || (self != null && (self.outerName == null || self.innerName == null));
        boolean synthetic = ((node.access | access) & Opcodes.ACC_SYNTHETIC) != 0;
        String outer = self != null && !localOrAnonymous ? self.outerName : null;
        if ((access & VISIBLE) == 0 || synthetic || localOrAnonymous) {
            return new Parsed(node.name, outer, false, null, List.of());
        }
        char kind = kindOf(node.access);
        return new Parsed(node.name, outer, true, classValue(node, access, kind), members(node));
    }

    /// The API of a release: of each jar, the classes that are API, with their members; only jars with an API class.
    ///
    /// @param jars artifactId → binary name → parsed class file, for every class of the release in [ApiScope] (an
    ///             enclosing class is looked for in the same jar, then in the others)
    public static ApiSnapshot snapshot(String version, SortedMap<String, ? extends Map<String, Parsed>> jars) {
        List<ArtifactApi> artifacts = new ArrayList<>();
        for (Map.Entry<String, ? extends Map<String, Parsed>> jar : jars.entrySet()) {
            List<ClassApi> classes = new ArrayList<>();
            for (Map.Entry<String, Parsed> parsed : new TreeMap<>(jar.getValue()).entrySet()) {
                if (isApi(jars, jar.getKey(), parsed.getKey(), 0)) {
                    classes.add(new ClassApi(parsed.getKey(), valueIn(jars, jar.getKey(), parsed.getValue()),
                            parsed.getValue().members()));
                }
            }
            if (!classes.isEmpty()) {
                artifacts.add(new ArtifactApi(jar.getKey(), List.copyOf(classes)));
            }
        }
        return new ApiSnapshot(version, READER, List.copyOf(artifacts));
    }

    /// The binary name of an internal name: `io.quarkus.test.QuarkusUnitTest$Builder`.
    public static String binaryName(String internalName) {
        return internalName.replace('/', '.');
    }

    private static boolean isApi(SortedMap<String, ? extends Map<String, Parsed>> jars, String artifactId,
            String binaryName, int depth) {
        Parsed parsed = jars.get(artifactId).get(binaryName);
        if (parsed == null || !parsed.api()) {
            return false;
        }
        if (parsed.outer() == null) {
            return true;
        }
        String outer = binaryName(parsed.outer());
        if (depth >= MAX_NESTING) {
            return false;
        }
        String outerJar = enclosingJar(jars, artifactId, outer);
        // an enclosing class this release does not have (in scope): nothing says it is API
        return outerJar != null && isApi(jars, outerJar, outer, depth + 1);
    }

    /// The jar of the release with the enclosing class of a member class: the member class's own jar when it has it,
    /// else the first other jar that has it; null when none has it.
    private static String enclosingJar(SortedMap<String, ? extends Map<String, Parsed>> jars, String artifactId,
            String outer) {
        if (jars.get(artifactId).containsKey(outer)) {
            return artifactId;
        }
        for (Map.Entry<String, ? extends Map<String, Parsed>> other : jars.entrySet()) {
            if (other.getValue().containsKey(outer)) {
                return other.getKey();
            }
        }
        return null;
    }

    /// The value of an API class in its release: that of its class file, without the `s` of a member class of an
    /// interface or an annotation (JLS 9.5), which is static implicitly; the class file of a member class does not
    /// tell what kind of class encloses it.
    private static String valueIn(SortedMap<String, ? extends Map<String, Parsed>> jars, String artifactId,
            Parsed parsed) {
        if (parsed.outer() == null) {
            return parsed.value();
        }
        String outer = binaryName(parsed.outer());
        String outerJar = enclosingJar(jars, artifactId, outer);
        String outerValue = outerJar == null ? null : jars.get(outerJar).get(outer).value();
        char outerKind = outerValue == null ? 0 : ApiGrammar.classKind(outerValue);
        return outerKind == ApiGrammar.INTERFACE || outerKind == ApiGrammar.ANNOTATION
                ? ApiGrammar.withoutStatic(parsed.value()) : parsed.value();
    }

    /// The kind letter of a class, 0 for a class of no kind.
    private static char kindOf(int access) {
        if ((access & Opcodes.ACC_ANNOTATION) != 0) {
            return ApiGrammar.ANNOTATION;
        }
        if ((access & Opcodes.ACC_INTERFACE) != 0) {
            return ApiGrammar.INTERFACE;
        }
        if ((access & Opcodes.ACC_ENUM) != 0) {
            return ApiGrammar.ENUM;
        }
        if ((access & Opcodes.ACC_RECORD) != 0) {
            return ApiGrammar.RECORD;
        }
        return 0;
    }

    /// The class value of the class file. Only a class of no kind has modifiers: an interface or an annotation is
    /// abstract, an enum is final (or abstract, with constant bodies), a record final, and a nested interface,
    /// annotation, enum or record static, all implicitly. A member class of an interface or an annotation is static
    /// implicitly too, which only its enclosing class tells: [#snapshot] leaves that `s` out.
    private static String classValue(ClassNode node, int access, char kind) {
        StringBuilder value = new StringBuilder();
        value.append(accessOf(access));
        if (kind != 0) {
            value.append(kind);
        } else {
            appendIf(value, access, Opcodes.ACC_ABSTRACT, ApiGrammar.ABSTRACT);
            appendIf(value, access, Opcodes.ACC_FINAL, ApiGrammar.FINAL);
            appendIf(value, access, Opcodes.ACC_STATIC, ApiGrammar.STATIC);
        }
        Marks marks = marks(node.access, node.visibleAnnotations, node.invisibleAnnotations);
        appendMarks(value, marks);
        if (kind == 0 && node.superName != null && !IMPLIED_SUPERCLASSES.contains(node.superName)) {
            value.append(ApiGrammar.EXTENDS).append(binaryName(node.superName));
        }
        List<String> interfaces = new ArrayList<>();
        if (node.interfaces != null) {
            for (String type : node.interfaces) {
                if (!type.equals(ANNOTATION_INTERFACE)) {
                    interfaces.add(binaryName(type));
                }
            }
        }
        if (!interfaces.isEmpty()) {
            value.append(ApiGrammar.IMPLEMENTS).append(String.join(",", interfaces));
        }
        appendSince(value, marks);
        return value.toString();
    }

    /// The public and protected fields, methods and constructors that are neither synthetic nor bridges, and not
    /// `<clinit>`, sorted by key.
    private static List<MemberApi> members(ClassNode node) {
        boolean inInterface = (node.access & Opcodes.ACC_INTERFACE) != 0;
        List<MemberApi> members = new ArrayList<>();
        for (FieldNode field : node.fields) {
            if ((field.access & VISIBLE) == 0 || (field.access & Opcodes.ACC_SYNTHETIC) != 0) {
                continue;
            }
            StringBuilder value = new StringBuilder().append(accessOf(field.access));
            boolean enumConstant = (field.access & Opcodes.ACC_ENUM) != 0;
            // the fields of an interface are public static final, and so is an enum constant
            if (!inInterface && !enumConstant) {
                appendIf(value, field.access, Opcodes.ACC_STATIC, ApiGrammar.STATIC);
                appendIf(value, field.access, Opcodes.ACC_FINAL, ApiGrammar.FINAL);
            }
            if (enumConstant) {
                value.append(ApiGrammar.ENUM_CONSTANT);
            }
            Marks marks = marks(field.access, field.visibleAnnotations, field.invisibleAnnotations);
            appendMarks(value, marks);
            appendSince(value, marks);
            members.add(new MemberApi(key(node, field.name, ":" + field.desc), value.toString()));
        }
        for (MethodNode method : node.methods) {
            if ((method.access & VISIBLE) == 0 || (method.access & (Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) != 0
                    || method.name.equals("<clinit>")) {
                continue;
            }
            StringBuilder value = new StringBuilder().append(accessOf(method.access));
            appendIf(value, method.access, Opcodes.ACC_STATIC, ApiGrammar.STATIC);
            // the abstract methods of an interface are abstract implicitly; its instance methods with a body default
            if (!inInterface) {
                appendIf(value, method.access, Opcodes.ACC_ABSTRACT, ApiGrammar.ABSTRACT);
            }
            appendIf(value, method.access, Opcodes.ACC_FINAL, ApiGrammar.FINAL);
            if (inInterface && (method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_STATIC)) == 0) {
                value.append(ApiGrammar.DEFAULT);
            }
            appendIf(value, method.access, Opcodes.ACC_VARARGS, ApiGrammar.VARARGS);
            Marks marks = marks(method.access, method.visibleAnnotations, method.invisibleAnnotations);
            appendMarks(value, marks);
            appendSince(value, marks);
            members.add(new MemberApi(key(node, method.name, method.desc), value.toString()));
        }
        members.sort(Comparator.comparing(MemberApi::id));
        return List.copyOf(members);
    }

    /// A member key: the name and the descriptor (after `:` for a field).
    private static String key(ClassNode node, String name, String descriptor) {
        if (!ApiGrammar.isMemberName(name)) {
            throw new IllegalArgumentException(binaryName(node.name) + ": the member name '" + name + "' has "
                    + "whitespace, ( or :, which the API data cannot key");
        }
        return name + descriptor;
    }

    private static char accessOf(int access) {
        return (access & Opcodes.ACC_PUBLIC) != 0 ? ApiGrammar.PUBLIC : ApiGrammar.PROTECTED;
    }

    private static void appendIf(StringBuilder value, int access, int flag, char letter) {
        if ((access & flag) != 0) {
            value.append(letter);
        }
    }

    private static void appendMarks(StringBuilder value, Marks marks) {
        if (marks.experimental()) {
            value.append(ApiGrammar.EXPERIMENTAL);
        }
        if (marks.forRemoval()) {
            value.append(ApiGrammar.FOR_REMOVAL);
        } else if (marks.deprecated()) {
            value.append(ApiGrammar.DEPRECATED);
        }
    }

    private static void appendSince(StringBuilder value, Marks marks) {
        if (marks.since() != null) {
            value.append(ApiGrammar.SINCE).append(marks.since());
        }
    }

    /// Experimental and deprecation of a declaration: deprecated by the `Deprecated` attribute (which ASM reports as
    /// [Opcodes#ACC_DEPRECATED]) or a `@Deprecated` annotation, for removal and since by the annotation.
    private static Marks marks(int access, List<AnnotationNode> visible, List<AnnotationNode> invisible) {
        AnnotationNode deprecated = annotation(DEPRECATED_ANNOTATION, visible, invisible);
        boolean experimental = annotation(EXPERIMENTAL_ANNOTATION, visible, invisible) != null;
        boolean isDeprecated = (access & Opcodes.ACC_DEPRECATED) != 0 || deprecated != null;
        boolean forRemoval = Boolean.TRUE.equals(value(deprecated, "forRemoval"));
        String since = null;
        if (isDeprecated && value(deprecated, "since") instanceof String text && !text.isBlank()) {
            since = text;
        }
        return new Marks(experimental, isDeprecated || forRemoval, forRemoval, since);
    }

    private static AnnotationNode annotation(String descriptor, List<AnnotationNode> visible,
            List<AnnotationNode> invisible) {
        for (List<AnnotationNode> annotations : List.of(listOrEmpty(visible), listOrEmpty(invisible))) {
            for (AnnotationNode annotation : annotations) {
                if (annotation.desc.equals(descriptor)) {
                    return annotation;
                }
            }
        }
        return null;
    }

    private static List<AnnotationNode> listOrEmpty(List<AnnotationNode> annotations) {
        return annotations == null ? List.of() : annotations;
    }

    /// An explicit element value of an annotation, null when the class file does not hold it.
    private static Object value(AnnotationNode annotation, String element) {
        if (annotation == null || annotation.values == null) {
            return null;
        }
        for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
            if (element.equals(annotation.values.get(i))) {
                return annotation.values.get(i + 1);
            }
        }
        return null;
    }
}
