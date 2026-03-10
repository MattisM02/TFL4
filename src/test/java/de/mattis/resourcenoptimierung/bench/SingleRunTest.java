package de.mattis.resourcenoptimierung.bench;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests fuer SingleRun-Hilfsmethoden (package-private static).
 */
class SingleRunTest {

    // ==================== computeEffectiveJavaToolOptions ====================

    @Test
    void hotspot_noJvmArgs_returnsGcLogFlag() {
        BenchmarkConfig cfg = new BenchmarkConfig(
                "baseline", "img:jvm", List.of(), RuntimeType.HOTSPOT, null, null);
        String result = SingleRun.computeEffectiveJavaToolOptions(cfg);
        assertEquals("-Xlog:gc*:stdout", result);
    }

    @Test
    void hotspot_withJvmArgs_prependsGcLogFlag() {
        BenchmarkConfig cfg = new BenchmarkConfig(
                "zgc", "img:jvm", List.of("-XX:+UseZGC", "-Xmx1g"), RuntimeType.HOTSPOT, null, null);
        String result = SingleRun.computeEffectiveJavaToolOptions(cfg);
        assertEquals("-Xlog:gc*:stdout -XX:+UseZGC -Xmx1g", result);
    }

    @Test
    void openj9_noJvmArgs_returnsVerboseGc() {
        BenchmarkConfig cfg = new BenchmarkConfig(
                "openj9", "img:openj9", List.of(), RuntimeType.OPENJ9, null, null);
        String result = SingleRun.computeEffectiveJavaToolOptions(cfg);
        assertEquals("-verbose:gc", result);
    }

    @Test
    void openj9_withJvmArgs_prependsVerboseGc() {
        BenchmarkConfig cfg = new BenchmarkConfig(
                "openj9-tuned", "img:openj9", List.of("-Xmx512m"), RuntimeType.OPENJ9, null, null);
        String result = SingleRun.computeEffectiveJavaToolOptions(cfg);
        assertEquals("-verbose:gc -Xmx512m", result);
    }

    @Test
    void native_returnsNull() {
        BenchmarkConfig cfg = new BenchmarkConfig(
                "native", "img:native", null, RuntimeType.NATIVE, null, null);
        String result = SingleRun.computeEffectiveJavaToolOptions(cfg);
        assertNull(result);
    }

    @Test
    void hotspot_nullJvmArgs_returnsOnlyGcLogFlag() {
        BenchmarkConfig cfg = new BenchmarkConfig(
                "baseline", "img:jvm", null, RuntimeType.HOTSPOT, null, null);
        String result = SingleRun.computeEffectiveJavaToolOptions(cfg);
        assertEquals("-Xlog:gc*:stdout", result);
    }

    @Test
    void hotspot_multipleArgs_allPresent() {
        BenchmarkConfig cfg = new BenchmarkConfig(
                "tuned", "img:jvm",
                List.of("-XX:+UseG1GC", "-XX:MaxGCPauseMillis=100", "-Xmx512m"),
                RuntimeType.HOTSPOT, null, null);
        String result = SingleRun.computeEffectiveJavaToolOptions(cfg);
        assertEquals("-Xlog:gc*:stdout -XX:+UseG1GC -XX:MaxGCPauseMillis=100 -Xmx512m", result);
    }
}
