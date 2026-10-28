package io.quarkiverse.almanac.maven;

import io.quarkiverse.almanac.model.ManagedDep;
import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// How a platform BOM is kept as its differences from the BOM of the same release, and restored from them.
class BomDifferencesTest {

    private static final List<ManagedDep> BOM = List.of(new ManagedDep("io.quarkus:quarkus-core", "3.2.0"),
            new ManagedDep("io.vertx:vertx-core", "4.4.1"), new ManagedDep("io.vertx:vertx-grpc-parent", "4.4.1"));

    @Test
    void theDifferencesAreWhatThePlatformBomManagesOtherwise() {
        List<ManagedDep> platform = List.of(new ManagedDep("io.quarkus:quarkus-core", "3.2.0"),
                new ManagedDep("io.vertx:vertx-core", "4.4.2"), new ManagedDep("io.quarkiverse.vault:quarkus-vault",
                        "3.2.0.1"), new ManagedDep("io.quarkus.platform:quarkus-bom-quarkus-platform-properties:"
                        + "properties", "3.2.0"));
        Map<String, String> differences = BomDifferences.of("3.2.0", BOM, platform);
        assertEquals(Map.of("io.vertx:vertx-core", "4.4.2", "io.quarkiverse.vault:quarkus-vault", "3.2.0.1",
                "io.quarkus.platform:quarkus-bom-quarkus-platform-properties:properties", "=",
                "io.vertx:vertx-grpc-parent", "-"), differences);
        assertEquals(platform.stream().sorted(Comparator.comparing(ManagedDep::id)).toList(),
                BomDifferences.apply("3.2.0", BOM, differences));
        assertTrue(BomDifferences.of("3.2.0", BOM, BOM).isEmpty());
        assertEquals(BOM, BomDifferences.apply("3.2.0", BOM, Map.of()));
    }

    @Test
    void differencesThatDoNotFitTheBomAreRefused() {
        IllegalArgumentException notManaged = assertThrows(IllegalArgumentException.class,
                () -> BomDifferences.apply("3.2.0", BOM, Map.of("io.netty:netty-common", BomDifferences.NOT_MANAGED)));
        assertEquals("platform BOM 3.2.0: io.netty:netty-common is marked as not managed, but the release's BOM does "
                + "not manage it either", notManaged.getMessage());
        assertThrows(IllegalArgumentException.class, () -> BomDifferences.of("3.2.0", List.of(
                new ManagedDep("io.vertx:vertx-core", "4.4.1"), new ManagedDep("io.vertx:vertx-core", "4.4.2")), BOM));
    }
}
