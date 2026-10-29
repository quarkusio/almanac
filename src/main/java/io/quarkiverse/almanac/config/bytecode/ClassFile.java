package io.quarkiverse.almanac.config.bytecode;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InnerClassNode;

import java.util.HashMap;
import java.util.Map;

/// A parsed class file: its ASM tree without code, plus the names the processor uses for it. The API reader
/// (`io.quarkiverse.almanac.api.ApiReader`) reads its declarations too.
///
/// @param node          the class without method bodies, debug information and frames
/// @param canonicalName Java canonical name, nested types joined with `.` (`a.b.Outer.Inner`)
/// @param simpleName    innermost simple name (`Inner`)
/// @param annotations   declaration annotations of the class by type descriptor
public record ClassFile(ClassNode node, String canonicalName, String simpleName,
        Map<String, AnnotationNode> annotations) {

    /// Highest class file version ASM 9.7 parses; newer files (e.g. the running JDK's) are read as this version.
    private static final int MAX_VERSION = Opcodes.V24;

    /// Parses a class file. Files newer than ASM supports are read as if they had its newest version: the reader only
    /// needs declarations, whose format has not changed.
    ///
    /// @throws IllegalArgumentException when the bytes are not a readable class file
    public static ClassFile parse(byte[] bytes) {
        byte[] readable = bytes;
        if (bytes.length > 8 && (((bytes[6] & 0xFF) << 8) | (bytes[7] & 0xFF)) > MAX_VERSION) {
            readable = bytes.clone();
            readable[6] = (byte) (MAX_VERSION >>> 8);
            readable[7] = (byte) MAX_VERSION;
        }
        ClassNode node = new ClassNode(Opcodes.ASM9);
        new ClassReader(readable).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG
                | ClassReader.SKIP_FRAMES);
        Map<String, InnerClassNode> inner = new HashMap<>();
        if (node.innerClasses != null) {
            for (InnerClassNode innerClass : node.innerClasses) {
                inner.put(innerClass.name, innerClass);
            }
        }
        InnerClassNode self = inner.get(node.name);
        String simpleName = self != null && self.innerName != null ? self.innerName : simpleName(node.name);
        return new ClassFile(node, canonicalName(node.name, inner), simpleName,
                Annotations.declared(node.visibleAnnotations, node.invisibleAnnotations));
    }

    /// Canonical name of an internal name, following the `InnerClasses` attribute (inner → outer) where it knows the
    /// class; otherwise every `/` and `$` becomes `.`. The walk takes at most as many steps as the attribute has
    /// entries: a malformed attribute whose outer classes form a cycle (which the JVM loads all the same) ends it.
    static String canonicalName(String internalName, Map<String, InnerClassNode> innerClasses) {
        StringBuilder suffix = new StringBuilder();
        String name = internalName;
        for (int steps = 0; steps < innerClasses.size(); steps++) {
            InnerClassNode inner = innerClasses.get(name);
            if (inner == null || inner.outerName == null || inner.innerName == null) {
                break;
            }
            suffix.insert(0, "." + inner.innerName);
            name = inner.outerName;
        }
        return canonicalName(name) + suffix;
    }

    /// Canonical name of an internal name without class file: every `/` and `$` becomes `.`.
    static String canonicalName(String internalName) {
        return internalName.replace('/', '.').replace('$', '.');
    }

    /// The part of an internal name after the last `/` and `$`.
    static String simpleName(String internalName) {
        int cut = Math.max(internalName.lastIndexOf('/'), internalName.lastIndexOf('$'));
        return internalName.substring(cut + 1);
    }

    String internalName() {
        return node.name;
    }

    boolean isInterface() {
        return (node.access & Opcodes.ACC_INTERFACE) != 0 && !isAnnotation();
    }

    boolean isAnnotation() {
        return (node.access & Opcodes.ACC_ANNOTATION) != 0;
    }

    boolean isEnum() {
        return (node.access & Opcodes.ACC_ENUM) != 0;
    }
}
