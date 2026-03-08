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
    void defaultPlan_containsTwentyConfigs() {
        BenchmarkPlan plan = BenchmarkPlan.defaultPlan();
        assertEquals(20, plan.configs.size(),
                "Default plan should contain 20 configs (5 GC + 3 G1-tuning + 2 JVM-interna + 2 cloud + 8 flag-combos)");
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
    void defaultPlan_containsRamPercentage75() {
        BenchmarkConfig cfg = findConfig(BenchmarkPlan.defaultPlan(), "ram-percentage-75");
        assertTrue(cfg.jvmArgs().contains("-XX:MaxRAMPercentage=75"));
    }

    @Test
    void defaultPlan_containsTieredStop1() {
        BenchmarkConfig cfg = findConfig(BenchmarkPlan.defaultPlan(), "tiered-stop-1");
        assertTrue(cfg.jvmArgs().contains("-XX:TieredStopAtLevel=1"));
    }

    // ==================== Flag-Kombinationen ====================

    @Test
    void defaultPlan_containsSerialGc256m() {
        BenchmarkConfig cfg = findConfig(BenchmarkPlan.defaultPlan(), "serial-gc-256m");
        assertTrue(cfg.jvmArgs().contains("-XX:+UseSerialGC"));
        assertTrue(cfg.jvmArgs().contains("-Xmx256m"));
    }

    @Test
    void defaultPlan_containsZgcHeap512m() {
        BenchmarkConfig cfg = findConfig(BenchmarkPlan.defaultPlan(), "zgc-heap-512m");
        assertTrue(cfg.jvmArgs().contains("-XX:+UseZGC"));
        assertTrue(cfg.jvmArgs().contains("-Xmx512m"));
    }

    @Test
    void defaultPlan_containsShenandoahHeap512m() {
        BenchmarkConfig cfg = findConfig(BenchmarkPlan.defaultPlan(), "shenandoah-heap-512m");
        assertTrue(cfg.jvmArgs().contains("-XX:+UseShenandoahGC"));
        assertTrue(cfg.jvmArgs().contains("-Xmx512m"));
    }

    @Test
    void defaultPlan_containsTieredStop1Serial() {
        BenchmarkConfig cfg = findConfig(BenchmarkPlan.defaultPlan(), "tiered-stop-1-serial");
        assertTrue(cfg.jvmArgs().contains("-XX:TieredStopAtLevel=1"));
        assertTrue(cfg.jvmArgs().contains("-XX:+UseSerialGC"));
    }

    @Test
    void defaultPlan_containsG1CohOn() {
        BenchmarkConfig cfg = findConfig(BenchmarkPlan.defaultPlan(), "g1-coh-on");
        assertTrue(cfg.jvmArgs().contains("-XX:+UseG1GC"));
        assertTrue(cfg.jvmArgs().contains("-XX:+UnlockExperimentalVMOptions"));
        assertTrue(cfg.jvmArgs().contains("-XX:+UseCompactObjectHeaders"));
    }

    @Test
    void defaultPlan_containsParallelGc256m() {
        BenchmarkConfig cfg = findConfig(BenchmarkPlan.defaultPlan(), "parallel-gc-256m");
        assertTrue(cfg.jvmArgs().contains("-XX:+UseParallelGC"));
        assertTrue(cfg.jvmArgs().contains("-Xmx256m"));
    }

    @Test
    void defaultPlan_containsG1LargeYoung() {
        BenchmarkConfig cfg = findConfig(BenchmarkPlan.defaultPlan(), "g1-large-young");
        assertTrue(cfg.jvmArgs().contains("-XX:+UseG1GC"));
        assertTrue(cfg.jvmArgs().contains("-XX:NewRatio=1"));
    }

    @Test
    void defaultPlan_containsZgcTieredStop1() {
        BenchmarkConfig cfg = findConfig(BenchmarkPlan.defaultPlan(), "zgc-tiered-stop-1");
        assertTrue(cfg.jvmArgs().contains("-XX:+UseZGC"));
        assertTrue(cfg.jvmArgs().contains("-XX:TieredStopAtLevel=1"));
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
                new BenchmarkConfig("custom", "myimage:latest", List.of("-Xmx256m"), RuntimeType.HOTSPOT)
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

    // ==================== defaultPlan RuntimeType ====================

    @Test
    void defaultPlan_allConfigsAreHotspot() {
        BenchmarkPlan plan = BenchmarkPlan.defaultPlan();
        for (BenchmarkConfig cfg : plan.configs) {
            assertEquals(RuntimeType.HOTSPOT, cfg.runtimeType(),
                    "Config '" + cfg.name() + "' should be HOTSPOT in defaultPlan");
        }
    }

    @Test
    void withDockerImage_preservesRuntimeType() {
        BenchmarkPlan original = BenchmarkPlan.defaultPlan();
        BenchmarkPlan updated = original.withDockerImage("custom:image");
        for (int i = 0; i < original.configs.size(); i++) {
            assertEquals(original.configs.get(i).runtimeType(), updated.configs.get(i).runtimeType(),
                    "RuntimeType should be preserved by withDockerImage");
        }
    }

    // ==================== profilePlan ====================

    @Test
    void profilePlan_isNotNull() {
        BenchmarkPlan plan = BenchmarkPlan.profilePlan();
        assertNotNull(plan);
        assertNotNull(plan.configs);
    }

    @Test
    void profilePlan_containsFiveProfiles() {
        BenchmarkPlan plan = BenchmarkPlan.profilePlan();
        assertEquals(5, plan.configs.size(),
                "Profile plan should contain 5 profiles (P01-P05)");
    }

    @Test
    void profilePlan_namesStartWithP() {
        BenchmarkPlan plan = BenchmarkPlan.profilePlan();
        for (BenchmarkConfig cfg : plan.configs) {
            assertTrue(cfg.name().startsWith("P0"),
                    "Profile name should start with 'P0', got: " + cfg.name());
        }
    }

    @Test
    void profilePlan_p01HotspotStandard() {
        BenchmarkPlan plan = BenchmarkPlan.profilePlan();
        BenchmarkConfig p01 = findConfig(plan, "P01-hotspot-standard");
        assertEquals(RuntimeType.HOTSPOT, p01.runtimeType());
        assertEquals("tfl4-ek-bench:jvm", p01.dockerImage());
        assertTrue(p01.jvmArgs().contains("-XX:+UseG1GC"));
        assertTrue(p01.jvmArgs().contains("-XX:MaxRAMPercentage=75"));
    }

    @Test
    void profilePlan_p02HotspotFastStartup() {
        BenchmarkPlan plan = BenchmarkPlan.profilePlan();
        BenchmarkConfig p02 = findConfig(plan, "P02-hotspot-fast-startup");
        assertEquals(RuntimeType.HOTSPOT, p02.runtimeType());
        assertTrue(p02.jvmArgs().contains("-XX:TieredStopAtLevel=1"));
    }

    @Test
    void profilePlan_p03HotspotLowLatency() {
        BenchmarkPlan plan = BenchmarkPlan.profilePlan();
        BenchmarkConfig p03 = findConfig(plan, "P03-hotspot-low-latency");
        assertEquals(RuntimeType.HOTSPOT, p03.runtimeType());
        assertTrue(p03.jvmArgs().contains("-XX:+UseZGC"));
    }

    @Test
    void profilePlan_p04Openj9LowMemory() {
        BenchmarkPlan plan = BenchmarkPlan.profilePlan();
        BenchmarkConfig p04 = findConfig(plan, "P04-openj9-low-memory");
        assertEquals(RuntimeType.OPENJ9, p04.runtimeType());
        assertEquals("tfl4-ek-bench:openj9", p04.dockerImage());
    }

    @Test
    void profilePlan_p05Native() {
        BenchmarkPlan plan = BenchmarkPlan.profilePlan();
        BenchmarkConfig p05 = findConfig(plan, "P05-native");
        assertEquals(RuntimeType.NATIVE, p05.runtimeType());
        assertEquals("tfl4-ek-bench:native", p05.dockerImage());
        assertTrue(p05.jvmArgs().isEmpty());
    }

    // ==================== withEbicsImages ====================

    @Test
    void withEbicsImages_mapsHotspotToJvmEk() {
        BenchmarkPlan plan = BenchmarkPlan.profilePlan();
        BenchmarkPlan ebics = plan.withEbicsImages();
        BenchmarkConfig p01 = findConfig(ebics, "P01-hotspot-standard");
        assertEquals("tfl4-ek-bench:jvm-ek", p01.dockerImage());
    }

    @Test
    void withEbicsImages_mapsOpenj9ToOpenj9Ek() {
        BenchmarkPlan plan = BenchmarkPlan.profilePlan();
        BenchmarkPlan ebics = plan.withEbicsImages();
        BenchmarkConfig p04 = findConfig(ebics, "P04-openj9-low-memory");
        assertEquals("tfl4-ek-bench:openj9-ek", p04.dockerImage());
    }

    @Test
    void withEbicsImages_mapsNativeToNativeEk() {
        BenchmarkPlan plan = BenchmarkPlan.profilePlan();
        BenchmarkPlan ebics = plan.withEbicsImages();
        BenchmarkConfig p05 = findConfig(ebics, "P05-native");
        assertEquals("tfl4-ek-bench:native-ek", p05.dockerImage());
    }

    @Test
    void withEbicsImages_preservesRuntimeType() {
        BenchmarkPlan plan = BenchmarkPlan.profilePlan();
        BenchmarkPlan ebics = plan.withEbicsImages();
        for (int i = 0; i < plan.configs.size(); i++) {
            assertEquals(plan.configs.get(i).runtimeType(), ebics.configs.get(i).runtimeType(),
                    "RuntimeType should be preserved by withEbicsImages");
        }
    }

    @Test
    void withEbicsImages_preservesJvmArgs() {
        BenchmarkPlan plan = BenchmarkPlan.profilePlan();
        BenchmarkPlan ebics = plan.withEbicsImages();
        for (int i = 0; i < plan.configs.size(); i++) {
            assertEquals(plan.configs.get(i).jvmArgs(), ebics.configs.get(i).jvmArgs(),
                    "JVM args should be preserved by withEbicsImages");
        }
    }

    /** Helper: finds a config by name or fails the test. */
    private static BenchmarkConfig findConfig(BenchmarkPlan plan, String name) {
        Optional<BenchmarkConfig> cfg = plan.configs.stream()
                .filter(c -> name.equals(c.name()))
                .findFirst();
        assertTrue(cfg.isPresent(), "Plan should contain config '" + name + "'");
        return cfg.get();
    }
}
