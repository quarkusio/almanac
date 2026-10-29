package io.quarkiverse.almanac.maven;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// A Quarkus release version (mirrored in `site/assets/core.js`): numeric parts followed by an
/// optional `.Final`, `.CR<n>`, `.Beta<n>` or `.Alpha<n>` qualifier (`-` is accepted as
/// separator too).
///
/// Ordering compares the numeric parts element-wise (missing parts count as 0, so `3.8.6 < 3.8.6.1`), then
/// the qualifier rank (`Alpha < Beta < CR < Final`, where "no qualifier" ranks as `Final`), then the
/// qualifier number. The ordering is _not_ consistent with `equals`: `3.0.0.Final` and
/// `3.0.0` compare as equal but keep their distinct [#text()].
///
/// @param text            the version exactly as published
/// @param numbers         the numeric parts
/// @param qualifier       the qualifier ([Qualifier#FINAL] when absent)
/// @param qualifierNumber the number after the qualifier, 0 when absent
public record QuarkusVersion(String text, List<Integer> numbers, Qualifier qualifier, int qualifierNumber)
        implements Comparable<QuarkusVersion> {

    /// Qualifiers in ascending rank.
    public enum Qualifier { ALPHA, BETA, CR, FINAL }

    private static final Pattern PATTERN =
            Pattern.compile("(\\d+(?:\\.\\d+)*)(?:[.-](Final|CR|Beta|Alpha)(\\d*))?", Pattern.CASE_INSENSITIVE);

    public QuarkusVersion {
        numbers = List.copyOf(numbers);
    }

    /// Parses a version, failing with [IllegalArgumentException] when it does not follow the pattern.
    public static QuarkusVersion parse(String text) {
        return tryParse(text).orElseThrow(() -> new IllegalArgumentException("not a Quarkus version: " + text));
    }

    /// Parses a version; empty when it does not follow the pattern (e.g. `999-SNAPSHOT`).
    public static Optional<QuarkusVersion> tryParse(String text) {
        Matcher m = PATTERN.matcher(text == null ? "" : text.strip());
        if (!m.matches()) {
            return Optional.empty();
        }
        try {
            List<Integer> numbers = Arrays.stream(m.group(1).split("\\.")).map(Integer::valueOf).toList();
            Qualifier qualifier = m.group(2) == null
                    ? Qualifier.FINAL
                    : Qualifier.valueOf(m.group(2).toUpperCase(Locale.ROOT));
            int qualifierNumber = m.group(3) == null || m.group(3).isEmpty() ? 0 : Integer.parseInt(m.group(3));
            return Optional.of(new QuarkusVersion(text.strip(), numbers, qualifier, qualifierNumber));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /// True for releases without qualifier or with `Final`.
    public boolean isFinal() {
        return qualifier == Qualifier.FINAL;
    }

    public int major() {
        return number(0);
    }

    /// Whether the release surely lists its extensions in an extension catalog: from 1.0 on. Of the 0.x releases only
    /// the last ones (0.27 and 0.28) have one, so the config of a 0.x release may come without extensions.
    public boolean hasExtensionCatalog() {
        return major() >= 1;
    }

    public int minor() {
        return number(1);
    }

    /// `major.minor`, e.g. `"3.20"`.
    public String stream() {
        return major() + "." + minor();
    }

    /// Compares only the numeric parts (missing parts count as 0).
    public int compareNumbers(QuarkusVersion other) {
        int length = Math.max(numbers.size(), other.numbers.size());
        for (int i = 0; i < length; i++) {
            int c = Integer.compare(number(i), other.number(i));
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    @Override
    public int compareTo(QuarkusVersion other) {
        int c = compareNumbers(other);
        if (c == 0) {
            c = qualifier.compareTo(other.qualifier);
        }
        return c != 0 ? c : Integer.compare(qualifierNumber, other.qualifierNumber);
    }

    @Override
    public String toString() {
        return text;
    }

    private int number(int index) {
        return index < numbers.size() ? numbers.get(index) : 0;
    }
}
