package de.mattis.resourcenoptimierung.bench;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests fuer BenchmarkConfig: isNative(), isOpenJ9(), RuntimeType und Record-Verhalten.
 */
class BenchmarkConfigTest {

    @Test
    void isNative_nativeRuntimeType_returnsTrue() {
        BenchmarkConfig config = new BenchmarkConfig("native", "tfl4-ek-bench:native", List.of(), RuntimeType.NATIVE);
        assertTrue(config.isNative());
    }

    @Test
    void isNative_hotspotRuntimeType_returnsFalse() {
        BenchmarkConfig config = new BenchmarkConfig("baseline", "tfl4-ek-bench:jvm", List.of(), RuntimeType.HOTSPOT);
        assertFalse(config.isNative());
    }

    @Test
    void isNative_openj9RuntimeType_returnsFalse() {
        BenchmarkConfig config = new BenchmarkConfig("openj9", "tfl4-ek-bench:openj9", List.of(), RuntimeType.OPENJ9);
        assertFalse(config.isNative());
    }

    @Test
    void isOpenJ9_openj9RuntimeType_returnsTrue() {
        BenchmarkConfig config = new BenchmarkConfig("openj9", "tfl4-ek-bench:openj9", List.of(), RuntimeType.OPENJ9);
        assertTrue(config.isOpenJ9());
    }

    @Test
    void isOpenJ9_hotspotRuntimeType_returnsFalse() {
        BenchmarkConfig config = new BenchmarkConfig("baseline", "tfl4-ek-bench:jvm", List.of(), RuntimeType.HOTSPOT);
        assertFalse(config.isOpenJ9());
    }

    @Test
    void isOpenJ9_nativeRuntimeType_returnsFalse() {
        BenchmarkConfig config = new BenchmarkConfig("native", "tfl4-ek-bench:native", List.of(), RuntimeType.NATIVE);
        assertFalse(config.isOpenJ9());
    }

    @Test
    void runtimeType_storedCorrectly() {
        BenchmarkConfig config = new BenchmarkConfig("test", "img:jvm", List.of(), RuntimeType.HOTSPOT);
        assertEquals(RuntimeType.HOTSPOT, config.runtimeType());
    }

    @Test
    void jvmArgs_storedCorrectly() {
        List<String> flags = List.of("-XX:-UseCompressedOops", "-Xmx512m");
        BenchmarkConfig config = new BenchmarkConfig("custom", "img:jvm", flags, RuntimeType.HOTSPOT);
        assertEquals(flags, config.jvmArgs());
        assertEquals("custom", config.name());
        assertEquals("img:jvm", config.dockerImage());
        assertEquals(RuntimeType.HOTSPOT, config.runtimeType());
    }

    @Test
    void emptyJvmArgs_isValid() {
        BenchmarkConfig config = new BenchmarkConfig("baseline", "img:jvm", List.of(), RuntimeType.HOTSPOT);
        assertTrue(config.jvmArgs().isEmpty());
    }

    @Test
    void recordEquality() {
        BenchmarkConfig a = new BenchmarkConfig("test", "img:jvm", List.of("-Xmx256m"), RuntimeType.HOTSPOT);
        BenchmarkConfig b = new BenchmarkConfig("test", "img:jvm", List.of("-Xmx256m"), RuntimeType.HOTSPOT);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void recordEquality_differentRuntimeType_notEqual() {
        BenchmarkConfig a = new BenchmarkConfig("test", "img:jvm", List.of(), RuntimeType.HOTSPOT);
        BenchmarkConfig b = new BenchmarkConfig("test", "img:jvm", List.of(), RuntimeType.OPENJ9);
        assertNotEquals(a, b);
    }
}
