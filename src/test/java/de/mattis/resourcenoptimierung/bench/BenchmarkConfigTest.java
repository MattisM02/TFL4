package de.mattis.resourcenoptimierung.bench;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests fuer BenchmarkConfig: isNative()-Heuristik und Record-Verhalten.
 */
class BenchmarkConfigTest {

    @Test
    void isNative_nativeImage_returnsTrue() {
        BenchmarkConfig config = new BenchmarkConfig("native", "tfl4-ek-bench:native", List.of());
        assertTrue(config.isNative());
    }

    @Test
    void isNative_jvmImage_returnsFalse() {
        BenchmarkConfig config = new BenchmarkConfig("baseline", "tfl4-ek-bench:jvm", List.of());
        assertFalse(config.isNative());
    }

    @Test
    void isNative_nullDockerImage_returnsFalse() {
        BenchmarkConfig config = new BenchmarkConfig("test", null, List.of());
        assertFalse(config.isNative());
    }

    @Test
    void isNative_nativeInMiddle_returnsFalse() {
        // "native" must be at the end after ":"
        BenchmarkConfig config = new BenchmarkConfig("test", "native-image:latest", List.of());
        assertFalse(config.isNative());
    }

    @Test
    void isNative_endsWithNativeButNoColon_returnsFalse() {
        // Must end with exactly ":native"
        BenchmarkConfig config = new BenchmarkConfig("test", "imagenative", List.of());
        assertFalse(config.isNative());
    }

    @Test
    void jvmArgs_storedCorrectly() {
        List<String> flags = List.of("-XX:-UseCompressedOops", "-Xmx512m");
        BenchmarkConfig config = new BenchmarkConfig("custom", "img:jvm", flags);
        assertEquals(flags, config.jvmArgs());
        assertEquals("custom", config.name());
        assertEquals("img:jvm", config.dockerImage());
    }

    @Test
    void emptyJvmArgs_isValid() {
        BenchmarkConfig config = new BenchmarkConfig("baseline", "img:jvm", List.of());
        assertTrue(config.jvmArgs().isEmpty());
    }

    @Test
    void recordEquality() {
        BenchmarkConfig a = new BenchmarkConfig("test", "img:jvm", List.of("-Xmx256m"));
        BenchmarkConfig b = new BenchmarkConfig("test", "img:jvm", List.of("-Xmx256m"));
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }
}
