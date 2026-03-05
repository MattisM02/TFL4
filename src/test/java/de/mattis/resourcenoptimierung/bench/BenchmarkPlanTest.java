package de.mattis.resourcenoptimierung.bench;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests fuer BenchmarkPlan: Default-Plan und Struktur.
 */
class BenchmarkPlanTest {

    @Test
    void defaultPlan_isNotNull() {
        BenchmarkPlan plan = BenchmarkPlan.defaultPlan();
        assertNotNull(plan);
        assertNotNull(plan.configs);
    }

    @Test
    void defaultPlan_containsTenConfigs() {
        BenchmarkPlan plan = BenchmarkPlan.defaultPlan();
        assertEquals(10, plan.configs.size(),
                "Default plan should contain 10 configs (5 GC + 3 G1-tuning + 2 JVM-interna)");
    }

    @Test
    void defaultPlan_containsBaseline() {
        BenchmarkPlan plan = BenchmarkPlan.defaultPlan();
        boolean hasBaseline = plan.configs.stream()
                .anyMatch(c -> "baseline".equals(c.name()));
        assertTrue(hasBaseline, "Default plan should contain a 'baseline' config");
    }

    @Test
    void defaultPlan_baselineHasNoFlags() {
        BenchmarkPlan plan = BenchmarkPlan.defaultPlan();
        BenchmarkConfig baseline = findConfig(plan, "baseline");
        assertTrue(baseline.jvmArgs().isEmpty());
    }

    @Test
    void defaultPlan_containsCoopsOff() {
        BenchmarkPlan plan = BenchmarkPlan.defaultPlan();
        BenchmarkConfig coopsOff = findConfig(plan, "coops-off");
        assertTrue(coopsOff.jvmArgs().contains("-XX:-UseCompressedOops"));
    }

    @Test
    void defaultPlan_containsZgc() {
        BenchmarkConfig cfg = findConfig(BenchmarkPlan.defaultPlan(), "zgc");
        assertTrue(cfg.jvmArgs().contains("-XX:+UseZGC"));
    }

    @Test
    void defaultPlan_containsShenandoah() {
        BenchmarkConfig cfg = findConfig(BenchmarkPlan.defaultPlan(), "shenandoah");
        assertTrue(cfg.jvmArgs().contains("-XX:+UseShenandoahGC"));
    }

    @Test
    void defaultPlan_containsParallelGc() {
        BenchmarkConfig cfg = findConfig(BenchmarkPlan.defaultPlan(), "parallel-gc");
        assertTrue(cfg.jvmArgs().contains("-XX:+UseParallelGC"));
    }

    @Test
    void defaultPlan_containsSerialGc() {
        BenchmarkConfig cfg = findConfig(BenchmarkPlan.defaultPlan(), "serial-gc");
        assertTrue(cfg.jvmArgs().contains("-XX:+UseSerialGC"));
    }

    @Test
    void defaultPlan_containsG1LowPause() {
        BenchmarkConfig cfg = findConfig(BenchmarkPlan.defaultPlan(), "g1-low-pause");
        assertTrue(cfg.jvmArgs().contains("-XX:+UseG1GC"));
        assertTrue(cfg.jvmArgs().contains("-XX:MaxGCPauseMillis=50"));
    }

    @Test
    void defaultPlan_containsG1Heap256m() {
        BenchmarkConfig cfg = findConfig(BenchmarkPlan.defaultPlan(), "g1-heap-256m");
        assertTrue(cfg.jvmArgs().contains("-Xmx256m"));
    }

    @Test
    void defaultPlan_containsG1Heap512m() {
        BenchmarkConfig cfg = findConfig(BenchmarkPlan.defaultPlan(), "g1-heap-512m");
        assertTrue(cfg.jvmArgs().contains("-Xmx512m"));
    }

    @Test
    void defaultPlan_containsCohOn() {
        BenchmarkConfig cfg = findConfig(BenchmarkPlan.defaultPlan(), "coh-on");
        assertTrue(cfg.jvmArgs().contains("-XX:+UnlockExperimentalVMOptions"));
        assertTrue(cfg.jvmArgs().contains("-XX:+UseCompactObjectHeaders"));
    }

    @Test
    void defaultPlan_allUseSameImage() {
        BenchmarkPlan plan = BenchmarkPlan.defaultPlan();
        long distinctImages = plan.configs.stream()
                .map(BenchmarkConfig::dockerImage)
                .distinct()
                .count();
        assertEquals(1, distinctImages, "All default configs should use the same Docker image");
    }

    @Test
    void defaultPlan_allNamesUnique() {
        BenchmarkPlan plan = BenchmarkPlan.defaultPlan();
        long distinctNames = plan.configs.stream()
                .map(BenchmarkConfig::name)
                .distinct()
                .count();
        assertEquals(plan.configs.size(), distinctNames, "All config names must be unique");
    }

    @Test
    void defaultPlan_baselineIsFirst() {
        BenchmarkPlan plan = BenchmarkPlan.defaultPlan();
        assertEquals("baseline", plan.configs.get(0).name(),
                "Baseline should be the first config in the plan");
    }

    @Test
    void customPlan_constructsCorrectly() {
        List<BenchmarkConfig> configs = List.of(
                new BenchmarkConfig("custom", "myimage:latest", List.of("-Xmx256m"))
        );
        BenchmarkPlan plan = new BenchmarkPlan(configs);
        assertEquals(1, plan.configs.size());
        assertEquals("custom", plan.configs.get(0).name());
    }

    // ==================== withDockerImage ====================

    @Test
    void withDockerImage_replacesAllImages() {
        BenchmarkPlan original = BenchmarkPlan.defaultPlan();
        BenchmarkPlan updated = original.withDockerImage("tfl4-ek-bench:jvm-ek");

        assertEquals(original.configs.size(), updated.configs.size(),
                "withDockerImage should preserve number of configs");

        for (BenchmarkConfig cfg : updated.configs) {
            assertEquals("tfl4-ek-bench:jvm-ek", cfg.dockerImage(),
                    "Config '" + cfg.name() + "' should use the new image");
        }
    }

    @Test
    void withDockerImage_preservesNamesAndJvmArgs() {
        BenchmarkPlan original = BenchmarkPlan.defaultPlan();
        BenchmarkPlan updated = original.withDockerImage("custom:image");

        for (int i = 0; i < original.configs.size(); i++) {
            BenchmarkConfig orig = original.configs.get(i);
            BenchmarkConfig upd = updated.configs.get(i);
            assertEquals(orig.name(), upd.name(), "Config name should be preserved");
            assertEquals(orig.jvmArgs(), upd.jvmArgs(), "JVM args should be preserved");
        }
    }

    @Test
    void withDockerImage_returnsNewInstance() {
        BenchmarkPlan original = BenchmarkPlan.defaultPlan();
        BenchmarkPlan updated = original.withDockerImage("other:image");

        assertNotSame(original, updated, "withDockerImage should return a new instance");
        // Original should be unchanged
        assertEquals("tfl4-ek-bench:jvm", original.configs.get(0).dockerImage());
    }

    /** Helper: finds a config by name or fails the test. */
    private static BenchmarkConfig findConfig(BenchmarkPlan plan, String name) {
        Optional<BenchmarkConfig> cfg = plan.configs.stream()
                .filter(c -> name.equals(c.name()))
                .findFirst();
        assertTrue(cfg.isPresent(), "Default plan should contain config '" + name + "'");
        return cfg.get();
    }
}
