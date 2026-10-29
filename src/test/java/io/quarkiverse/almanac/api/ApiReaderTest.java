package io.quarkiverse.almanac.api;

import io.quarkiverse.almanac.config.bytecode.ClassFile;
import io.quarkiverse.almanac.config.sourcedoc.TestCompiler;
import io.quarkiverse.almanac.model.ApiSnapshot;
import io.quarkiverse.almanac.model.ArtifactApi;
import io.quarkiverse.almanac.model.ClassApi;
import io.quarkiverse.almanac.model.MemberApi;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// [ApiReader] over class files that javac writes for fixture sources: which classes are API, their values and the
/// values of their members, in the grammar of [ApiGrammar].
class ApiReaderTest {

    private static final String P = "io/quarkus/fixture/";
    /// The package of the member classes of interfaces and annotations, apart from the other fixtures.
    private static final String M = "io/quarkus/members/";

    private static Map<String, byte[]> classes;

    @BeforeAll
    static void compile() {
        Map<String, String> sources = new LinkedHashMap<>();
        // a stand-in of SmallRye's annotation, kept in the class file only (invisible at run time)
        sources.put("io/smallrye/common/annotation/Experimental.java", """
                package io.smallrye.common.annotation;
                import java.lang.annotation.*;
                @Retention(RetentionPolicy.CLASS)
                public @interface Experimental { String value(); }
                """);
        sources.put(P + "Base.java", """
                package io.quarkus.fixture;
                import io.smallrye.common.annotation.Experimental;
                public abstract class Base implements java.io.Serializable, Comparable<Base> {
                    public static final String NAME = "base";
                    protected int count;
                    int hidden;
                    private String secret;
                    public Base() {}
                    protected Base(int count) { this.count = count; }
                    public abstract void run();
                    protected abstract void hook();
                    public final String name() { return NAME; }
                    public static Base of(String... names) { return null; }
                    public int compareTo(Base other) { return 0; }
                    @Deprecated public void old() {}
                    @Deprecated(since = "3.32", forRemoval = true) public void gone() {}
                    /** @deprecated use {@link #run()} */
                    public void javadocOnly() {}
                    @Deprecated(since = " ") public void blankSince() {}
                    @Experimental("new") public void fresh() {}
                    void packagePrivate() {}
                    private void privateMethod() {}
                    public Runnable anonymous() { return new Runnable() { public void run() {} }; }
                    public Object local() { class Local {} return new Local(); }
                    public static class Nested { public void inNested() {} }
                    protected static final class ProtectedNested {}
                    private static class PrivateNested { public static class Inner {} }
                    public class InnerClass {}
                    public interface Callback {
                        int LIMIT = 3;
                        void call();
                        default void twice() { call(); call(); }
                        static Callback none() { return () -> {}; }
                    }
                    public enum Color {
                        RED, GREEN { @Override public String code() { return "g"; } };
                        public String code() { return "c"; }
                    }
                    public record Point(int x, int y) implements java.io.Serializable {}
                    public @interface Marker { String value() default ""; }
                }
                """);
        sources.put(P + "Gone.java", """
                package io.quarkus.fixture;
                @Deprecated(since = "3.32", forRemoval = true)
                public final class Gone extends Base {
                    public void run() {}
                    protected void hook() {}
                }
                """);
        sources.put(P + "OldStyle.java", """
                package io.quarkus.fixture;
                /** @deprecated no longer used */
                public class OldStyle {}
                """);
        sources.put(P + "Api.java", """
                package io.quarkus.fixture;
                @io.smallrye.common.annotation.Experimental("soon")
                public interface Api extends Runnable, AutoCloseable {}
                """);
        sources.put(P + "Hidden.java", """
                package io.quarkus.fixture;
                class Hidden { public static class Exposed {} }
                """);
        // member classes of an interface or an annotation are static implicitly (JLS 9.5); javac marks them static
        sources.put(M + "Holder.java", """
                package io.quarkus.members;
                public interface Holder {
                    class Impl implements Holder { public static class Inner {} }
                    final class Sealed {}
                    abstract class Partial {}
                    @Deprecated class Old {}
                    interface Nested { class Deep {} }
                    enum Mode { ON }
                }
                """);
        sources.put(M + "Qualifier.java", """
                package io.quarkus.members;
                public @interface Qualifier {
                    final class Literal implements Qualifier {
                        public Class<? extends java.lang.annotation.Annotation> annotationType() {
                            return Qualifier.class;
                        }
                    }
                }
                """);
        sources.put(M + "Plain.java", """
                package io.quarkus.members;
                public class Plain {
                    public static class Nested {}
                    public interface Contract { class Impl {} }
                }
                """);
        sources.put(P + "UsesSwitch.java", """
                package io.quarkus.fixture;
                public class UsesSwitch {
                    public int code(Base.Color color) {
                        switch (color) { case RED: return 1; default: return 0; }
                    }
                }
                """);
        classes = TestCompiler.compile(sources);
    }

    @Test
    void readsTheClassValuesWithTheirKindModifiersAndSupertypes() {
        // abstract, with interfaces in declaration order (generics are not read); java.lang.Object is implied
        assertEquals("+a implements java.io.Serializable,java.lang.Comparable", value("Base"));
        // nested classes by their own InnerClasses entry: the class file says public for the protected one
        assertEquals("+s", value("Base$Nested"));
        assertEquals("#fs", value("Base$ProtectedNested"));
        assertEquals("+", value("Base$InnerClass"));
        // the kinds, whose implied modifiers (abstract, final, static) and supertypes are left out
        assertEquals("+i", value("Base$Callback"));
        assertEquals("+e", value("Base$Color"));
        assertEquals("+r implements java.io.Serializable", value("Base$Point"));
        assertEquals("+n", value("Base$Marker"));
        assertEquals("+iX implements java.lang.Runnable,java.lang.AutoCloseable", value("Api"));
        // deprecated for removal with a since, deprecated by a javadoc tag alone
        assertEquals("+fR extends io.quarkus.fixture.Base since 3.32", value("Gone"));
        assertEquals("+D", value("OldStyle"));
        for (String name : List.of("Base", "Gone", "OldStyle", "Api", "Base$Nested", "Base$Color")) {
            assertEquals(List.of(), ApiGrammar.classValueProblem(value(name)).stream().toList(), name);
        }
    }

    @Test
    void privatePackageSyntheticAnonymousAndLocalClassesAreNotApi() {
        assertFalse(parse("Hidden").api());
        assertFalse(parse("Base$PrivateNested").api());
        // public itself, but in a private class: the release decides ([ApiReader#snapshot])
        assertTrue(parse("Base$PrivateNested$Inner").api());
        assertEquals(P + "Base$PrivateNested", parse("Base$PrivateNested$Inner").outer());
        assertTrue(parse("Hidden$Exposed").api());
        // the anonymous Runnable, the local class, the constant body of GREEN
        assertFalse(parse("Base$1").api());
        assertFalse(parse("Base$1Local").api());
        assertFalse(parse("Base$Color$1").api());
        assertNull(parse("Base$1").outer());
        // the switch map javac writes for a switch over an enum of another class
        assertTrue(classes.containsKey(P + "UsesSwitch$1"), classes.keySet().toString());
        assertFalse(parse("UsesSwitch$1").api());
        // a class that is not API keeps no value or member
        assertNull(parse("Hidden").value());
        assertEquals(List.of(), parse("Hidden").members());
    }

    @Test
    void readsThePublicAndProtectedMembersWithoutSyntheticOrBridgeOnes() {
        Map<String, String> base = members("Base");
        assertEquals(Map.ofEntries(
                Map.entry("NAME:Ljava/lang/String;", "+sf"),
                Map.entry("count:I", "#"),
                Map.entry("<init>()V", "+"),
                Map.entry("<init>(I)V", "#"),
                Map.entry("run()V", "+a"),
                Map.entry("hook()V", "#a"),
                Map.entry("name()Ljava/lang/String;", "+f"),
                Map.entry("of([Ljava/lang/String;)Lio/quarkus/fixture/Base;", "+sv"),
                Map.entry("compareTo(Lio/quarkus/fixture/Base;)I", "+"),
                Map.entry("old()V", "+D"),
                Map.entry("gone()V", "+R since 3.32"),
                Map.entry("javadocOnly()V", "+D"),
                Map.entry("blankSince()V", "+D"),
                Map.entry("fresh()V", "+X"),
                Map.entry("anonymous()Ljava/lang/Runnable;", "+"),
                Map.entry("local()Ljava/lang/Object;", "+")), base);
        // sorted by key in Java String order, and every key and value in the grammar
        List<String> keys = parse("Base").members().stream().map(MemberApi::id).toList();
        assertEquals(keys.stream().sorted().toList(), keys);
        for (Map.Entry<String, String> member : base.entrySet()) {
            assertEquals(List.of(), ApiGrammar.memberKeyProblem(member.getKey()).stream().toList());
            assertEquals(List.of(), ApiGrammar.memberValueProblem(member.getKey(), member.getValue()).stream()
                    .toList());
        }
        // the inner class's synthetic this$0 is left out, its constructor takes the outer instance
        assertEquals(Map.of("<init>(Lio/quarkus/fixture/Base;)V", "+"), members("Base$InnerClass"));
        assertEquals(Map.of("<init>()V", "+", "run()V", "+", "hook()V", "#"), members("Gone"));
    }

    @Test
    void leavesTheImplicitModifiersOfInterfacesEnumsRecordsAndAnnotationsOut() {
        // the field is public static final, the abstract method abstract, implicitly; the lambda is synthetic
        assertEquals(Map.of("LIMIT:I", "+", "call()V", "+", "twice()V", "+d",
                "none()Lio/quarkus/fixture/Base$Callback;", "+s"), members("Base$Callback"));
        // the constants are public static final implicitly; $VALUES, $values() and the constructor are not API
        assertEquals(Map.of("RED:Lio/quarkus/fixture/Base$Color;", "+e", "GREEN:Lio/quarkus/fixture/Base$Color;", "+e",
                "code()Ljava/lang/String;", "+", "values()[Lio/quarkus/fixture/Base$Color;", "+s",
                "valueOf(Ljava/lang/String;)Lio/quarkus/fixture/Base$Color;", "+s"), members("Base$Color"));
        // the record's components are private fields; its implicit members are as javac writes them
        assertEquals(Map.of("<init>(II)V", "+", "x()I", "+", "y()I", "+", "toString()Ljava/lang/String;", "+f",
                "hashCode()I", "+f", "equals(Ljava/lang/Object;)Z", "+f"), members("Base$Point"));
        assertEquals(Map.of("value()Ljava/lang/String;", "+"), members("Base$Marker"));
    }

    @Test
    void aReleaseKeepsTheClassesWhoseEnclosingClassesAreApiToo() {
        SortedMap<String, Map<String, ApiReader.Parsed>> jars = new TreeMap<>();
        Map<String, ApiReader.Parsed> fixtures = new TreeMap<>();
        for (String internalName : classes.keySet()) {
            if (internalName.startsWith(P)) {
                fixtures.put(ApiReader.binaryName(internalName), ApiReader.parse(classes.get(internalName)));
            }
        }
        // a nested class whose outer class is in another jar of the release
        Map<String, ApiReader.Parsed> other = new TreeMap<>();
        other.put("io.quarkus.fixture.Base$Nested", fixtures.remove("io.quarkus.fixture.Base$Nested"));
        // and one whose outer class the release does not have
        other.put("io.quarkus.fixture.Missing$Nested", new ApiReader.Parsed(P + "Missing$Nested", P + "Missing", true,
                "+s", List.of()));
        jars.put("quarkus-fixture", fixtures);
        jars.put("quarkus-other", other);
        ApiSnapshot snapshot = ApiReader.snapshot("3.40.1", jars);

        assertEquals("3.40.1", snapshot.version());
        assertEquals(ApiReader.READER, snapshot.reader());
        assertEquals(List.of("quarkus-fixture", "quarkus-other"), snapshot.artifacts().stream().map(ArtifactApi::id)
                .toList());
        assertEquals(List.of("io.quarkus.fixture.Api", "io.quarkus.fixture.Base", "io.quarkus.fixture.Base$Callback",
                "io.quarkus.fixture.Base$Color", "io.quarkus.fixture.Base$InnerClass",
                "io.quarkus.fixture.Base$Marker", "io.quarkus.fixture.Base$Point",
                "io.quarkus.fixture.Base$ProtectedNested", "io.quarkus.fixture.Gone", "io.quarkus.fixture.OldStyle",
                "io.quarkus.fixture.UsesSwitch"), names(snapshot.artifacts().get(0)));
        assertEquals(List.of("io.quarkus.fixture.Base$Nested"), names(snapshot.artifacts().get(1)));
        // a jar without an API class is left out
        jars.put("quarkus-hidden", Map.of("io.quarkus.fixture.Hidden", fixtures.get("io.quarkus.fixture.Hidden")));
        assertEquals(2, ApiReader.snapshot("3.40.1", jars).artifacts().size());
    }

    @Test
    void aMemberClassOfAnInterfaceOrAnAnnotationIsNotMarkedStatic() {
        // the class file says static, and it is all that parse() reads
        assertEquals("+s implements io.quarkus.members.Holder", membersValue("Holder$Impl"));
        assertEquals("+fs implements io.quarkus.members.Qualifier", membersValue("Qualifier$Literal"));
        assertEquals("+s", membersValue("Holder$Nested$Deep"));
        // the release tells what encloses a class: an interface or an annotation leaves its member classes' s out
        SortedMap<String, Map<String, ApiReader.Parsed>> jars = new TreeMap<>();
        Map<String, ApiReader.Parsed> members = new TreeMap<>();
        for (String internalName : classes.keySet()) {
            if (internalName.startsWith(M)) {
                members.put(ApiReader.binaryName(internalName), ApiReader.parse(classes.get(internalName)));
            }
        }
        // a member class whose interface is in another jar of the release
        Map<String, ApiReader.Parsed> other = new TreeMap<>();
        other.put("io.quarkus.members.Holder$Sealed", members.remove("io.quarkus.members.Holder$Sealed"));
        jars.put("quarkus-members", members);
        jars.put("quarkus-other", other);
        ApiSnapshot snapshot = ApiReader.snapshot("3.40.1", jars);

        Map<String, String> values = new TreeMap<>();
        for (ClassApi type : snapshot.artifacts().get(0).classes()) {
            values.put(type.name().substring("io.quarkus.members.".length()), type.v());
        }
        assertEquals(Map.ofEntries(
                Map.entry("Holder", "+i"),
                Map.entry("Holder$Impl", "+ implements io.quarkus.members.Holder"),
                // a member class of a class is static by its modifier, even inside an interface
                Map.entry("Holder$Impl$Inner", "+s"),
                Map.entry("Holder$Partial", "+a"),
                Map.entry("Holder$Old", "+D"),
                Map.entry("Holder$Nested", "+i"),
                Map.entry("Holder$Nested$Deep", "+"),
                Map.entry("Holder$Mode", "+e"),
                Map.entry("Qualifier", "+n"),
                Map.entry("Qualifier$Literal", "+f implements io.quarkus.members.Qualifier"),
                Map.entry("Plain", "+"),
                Map.entry("Plain$Nested", "+s"),
                Map.entry("Plain$Contract", "+i"),
                Map.entry("Plain$Contract$Impl", "+")), values);
        assertEquals(List.of("io.quarkus.members.Holder$Sealed"), names(snapshot.artifacts().get(1)));
        assertEquals("+f", snapshot.artifacts().get(1).classes().getFirst().v());
        // the members are those of the class file
        assertEquals(members.get("io.quarkus.members.Holder$Impl").members(), snapshot.artifacts().get(0).classes()
                .stream().filter(type -> type.name().equals("io.quarkus.members.Holder$Impl")).findFirst()
                .orElseThrow().members());
    }

    @Test
    void readsClassFilesNewerThanAsmKnowsAsItsNewest() {
        byte[] bytes = classes.get(P + "Gone").clone();
        // class file version 99 (a Java far ahead)
        bytes[6] = 0;
        bytes[7] = 99;
        ApiReader.Parsed parsed = ApiReader.parse(bytes);
        assertEquals(parse("Gone"), parsed);
        assertThrows(IllegalArgumentException.class, () -> ApiReader.parse(new byte[] {(byte) 0xCA, (byte) 0xFE, 0}));
    }

    @Test
    void theGrammarRefusesWhatTheReaderNeverWrites() {
        for (String value : List.of("+", "#", "+as", "+fs", "+fR extends a.B since 3.32", "+iX implements a.B,c.D",
                "+D since free text, with spaces\nand lines", "+e implements a.B", "+rD",
                "+ extends java.lang.Objects", "+n implements a.Annotation")) {
            assertEquals(List.of(), ApiGrammar.classValueProblem(value).stream().toList(), value);
        }
        for (String value : List.of("", "-", "+fa", "+ie", "+if", "+e extends a.B", "+DR", "+RX", "+ since 3.2",
                "+ implements a.B extends c.D", "+ extends a.B, c.D", "+  extends a.B",
                // implied supertypes, internal names, impossible classes
                "+ extends java.lang.Object", "+ extends java.lang.Enum", "+f extends java.lang.Record",
                "+n implements java.lang.annotation.Annotation", "+i implements a.B,java.lang.annotation.Annotation",
                "+ extends io/quarkus/Base", "+ implements io/quarkus/I", "+af", "+afs", "+ implements a.B,a.B")) {
            assertTrue(ApiGrammar.classValueProblem(value).isPresent(), value);
        }
        Map<String, String> accepted = Map.of("f:I", "#sfXR since 1", "RED:Lio/quarkus/Color;", "+eD",
                "m()V", "+sfvX", "n()V", "+a", "o()V", "+dD", "<init>([Ljava/lang/String;)V", "#v");
        accepted.forEach((key, value) -> assertEquals(List.of(), ApiGrammar.memberValueProblem(key, value).stream()
                .toList(), key + " " + value));
        assertTrue(ApiGrammar.memberValueProblem("f:I", "+a").isPresent(), "a field is not abstract");
        assertTrue(ApiGrammar.memberValueProblem("m()V", "+e").isPresent(), "a method is no enum constant");
        assertTrue(ApiGrammar.memberValueProblem("m()V", "+fs").isPresent(), "modifiers in a fixed order");
        assertTrue(ApiGrammar.memberValueProblem("m()V", "+ since 3").isPresent(), "since needs D or R");
        // implicit or contradictory modifiers
        for (String[] refused : List.of(new String[] {"RED:Lio/quarkus/Color;", "+sfe"},
                new String[] {"RED:Lio/quarkus/Color;", "+fe"}, new String[] {"m()V", "+ad"},
                new String[] {"m()V", "+sa"}, new String[] {"m()V", "+af"}, new String[] {"m()V", "+sd"},
                new String[] {"m()V", "+fd"}, new String[] {"<init>()V", "+sad"}, new String[] {"<init>()V", "+s"},
                new String[] {"<init>()V", "+f"})) {
            assertTrue(ApiGrammar.memberValueProblem(refused[0], refused[1]).isPresent(), refused[0] + " "
                    + refused[1]);
        }
        for (String key : List.of("<init>()V", "of([Ljava/lang/String;)Lio/quarkus/Base;", "INSTANCE:Lio/quarkus/Foo;",
                "values()[[J", "lambda$x$0(I)Z", "count:I", "getFoo-impl(I)I", "$$delegatedProperties:[I",
                // only method names are kept from < and >: a field may have any name the JVM takes
                "<init>:I", "<x>:I")) {
            assertEquals(List.of(), ApiGrammar.memberKeyProblem(key).stream().toList(), key);
        }
        for (String key : List.of("a b()V", "m(I)", "m()", "f:", ":I", "m()Q", "f:Lfoo", "m(Ljava/lang/String)V",
                "<clinit>()V", "<init>()I", "<get-x>()I", "a.b()V", "f;x:I", "a[0:I", "a/b()V")) {
            assertTrue(ApiGrammar.memberKeyProblem(key).isPresent(), key);
        }
        assertTrue(ApiGrammar.isField("INSTANCE:Lio/quarkus/Foo;"));
        // what the checks of a whole release read of a class value: its kind, and whether it says static
        assertEquals(ApiGrammar.INTERFACE, ApiGrammar.classKind("+iX implements a.B"));
        assertEquals(ApiGrammar.ANNOTATION, ApiGrammar.classKind("#nD"));
        assertEquals(ApiGrammar.RECORD, ApiGrammar.classKind("+r"));
        assertEquals(0, ApiGrammar.classKind("+s"));
        assertEquals(0, ApiGrammar.classKind("+"));
        assertTrue(ApiGrammar.marksStatic("+fsD extends a.B since s"));
        assertTrue(ApiGrammar.marksStatic("#s"));
        assertFalse(ApiGrammar.marksStatic("+fD extends a.Bs since s"));
        assertFalse(ApiGrammar.marksStatic("+e"));
        assertFalse(ApiGrammar.marksStatic("+"));
        assertEquals("+fD extends a.B since s", ApiGrammar.withoutStatic("+fsD extends a.B since s"));
        assertEquals("+ implements java.lang.Runnable", ApiGrammar.withoutStatic("+s implements java.lang.Runnable"));
        assertEquals("#X", ApiGrammar.withoutStatic("#sX"));
        assertEquals("+i", ApiGrammar.withoutStatic("+i"));
        assertFalse(ApiGrammar.isField("<init>(Ljava/lang/String;)V"));
        assertFalse(ApiGrammar.isMemberName("a b"));
        assertFalse(ApiGrammar.isMemberName("a(b"));
        assertFalse(ApiGrammar.isMemberName("a:b"));
        assertTrue(ApiGrammar.isMemberName("access$000"));
        assertTrue(ApiGrammar.isDeprecated("#aD extends a.B"));
        assertTrue(ApiGrammar.isDeprecated("+R since 3"));
        assertFalse(ApiGrammar.isDeprecated("+ since D and R"));
    }

    @Test
    void enclosingClassesThatFormACycleEndTheWalkInsteadOfTheParse() {
        // A$B's outer class is A, A's is A$B: the JVM loads such a class all the same
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, P + "A$B", null, "java/lang/Object", null);
        writer.visitInnerClass(P + "A$B", P + "A", "B", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC);
        writer.visitInnerClass(P + "A", P + "A$B", "A", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC);
        writer.visitEnd();
        byte[] bytes = writer.toByteArray();

        assertEquals("io.quarkus.fixture.A.B.A.B", ClassFile.parse(bytes).canonicalName());
        ApiReader.Parsed parsed = ApiReader.parse(bytes);
        assertEquals("+s", parsed.value());
        assertEquals(P + "A", parsed.outer());
        // its enclosing class is not in the release: it is no API class, and the release is read all the same
        SortedMap<String, Map<String, ApiReader.Parsed>> jars = new TreeMap<>();
        jars.put("quarkus-fixture", Map.of("io.quarkus.fixture.A$B", parsed));
        assertEquals(List.of(), ApiReader.snapshot("3.40.1", jars).artifacts());
    }

    @Test
    void aClassFileTooDeepForTheParserThrowsAnError() {
        // what the extractor turns into a failure of the release ([ApiExtractor]): ASM reads nested annotation values
        // recursively, as deep as the bytes say
        assertThrows(StackOverflowError.class, () -> ApiReader.parse(deeplyAnnotated(P + "Deep", 300_000)));
    }

    /// A class whose annotation holds an annotation, `depth` times.
    static byte[] deeplyAnnotated(String internalName, int depth) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, internalName, null, "java/lang/Object",
                null);
        List<AnnotationVisitor> nested = new ArrayList<>();
        AnnotationVisitor annotation = writer.visitAnnotation("Lio/quarkus/fixture/Nest;", true);
        nested.add(annotation);
        for (int i = 0; i < depth; i++) {
            annotation = annotation.visitAnnotation("value", "Lio/quarkus/fixture/Nest;");
            nested.add(annotation);
        }
        for (int i = nested.size() - 1; i >= 0; i--) {
            nested.get(i).visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    @Test
    void theScopeLeavesBundledCopiesOut() {
        assertFalse(ApiScope.covers("quarkus-grpc-cli", "io.quarkus.grpc.cli.Main"));
        assertFalse(ApiScope.covers("quarkus-bootstrap-gradle-resolver", "io.quarkus.bootstrap.Resolver"));
        assertTrue(ApiScope.covers("quarkus-ide-launcher", "io.quarkus.launcher.QuarkusLauncher"));
        assertTrue(ApiScope.covers("quarkus-ide-launcher", "io.quarkus.launcher.runtime.Launcher$1"));
        assertFalse(ApiScope.covers("quarkus-ide-launcher", "io.quarkus.launcher.shaded.org.apache.Foo"));
        assertFalse(ApiScope.covers("quarkus-ide-launcher", "io.quarkus.bootstrap.Copy"));
        assertFalse(ApiScope.covers("quarkus-ide-launcher", "io.quarkus.launcherx.Foo"));
        assertFalse(ApiScope.covers("quarkus-core", "io.quarkus.shaded.Thing"));
        assertFalse(ApiScope.covers("quarkus-core", "shaded.Thing"));
        assertTrue(ApiScope.covers("quarkus-core", "io.quarkus.unshaded.Thing"));
        assertTrue(ApiScope.covers("quarkus-spring-di", "org.springframework.Bean"));
        assertTrue(ApiScope.covers("quarkus-core", "Main"));
        assertEquals(3, ApiScope.foreignClasses(List.of("io.quarkus.A", "io.quarkus.shaded.B", "org.C", "io.quarkusx.D",
                "io.quarkus.E$1")));
        assertTrue(ApiScope.isNamed("quarkus-ide-launcher"));
        assertFalse(ApiScope.isNamed("quarkus-core"));
    }

    private static ApiReader.Parsed parse(String simpleName) {
        byte[] bytes = classes.get(P + simpleName);
        if (bytes == null) {
            throw new AssertionError("no class file " + P + simpleName + " in " + classes.keySet());
        }
        return ApiReader.parse(bytes);
    }

    private static String membersValue(String simpleName) {
        ApiReader.Parsed parsed = ApiReader.parse(classes.get(M + simpleName));
        assertTrue(parsed.api(), simpleName + " is API");
        return parsed.value();
    }

    private static String value(String simpleName) {
        ApiReader.Parsed parsed = parse(simpleName);
        assertTrue(parsed.api(), simpleName + " is API");
        return parsed.value();
    }

    private static Map<String, String> members(String simpleName) {
        return parse(simpleName).members().stream().collect(Collectors.toMap(MemberApi::id, MemberApi::v));
    }

    private static List<String> names(ArtifactApi artifact) {
        return artifact.classes().stream().map(ClassApi::name).sorted(Comparator.naturalOrder()).toList();
    }
}
