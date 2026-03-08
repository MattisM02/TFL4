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
        assertEquals(32, plan.configs.size(),
                "Default plan should contain 32 configs (20 flag-analysis + 12 profiles)");
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
    void defaultPlan_flagAnalysisUseSameImage() {
        BenchmarkPlan plan = BenchmarkPlan.defaultPlan();
        long distinctImages = plan.configs.subList(0, 20).stream()
                .map(BenchmarkConfig::dockerImage)
                .distinct()
                .count();
        assertEquals(1, distinctImages, "All flag-analysis configs should use the same Docker image");
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
                new BenchmarkConfig("custom", "myimage:latest", List.of("-Xmx256m"), RuntimeType.HOTSPOT, "test-cat", "HotSpot")
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
    void defaultPlan_flagAnalysisAllHotspot() {
        BenchmarkPlan plan = BenchmarkPlan.defaultPlan();
        for (BenchmarkConfig cfg : plan.configs.subList(0, 20)) {
            assertEquals(RuntimeType.HOTSPOT, cfg.runtimeType(),
                    "Config '" + cfg.name() + "' should be HOTSPOT in flag-analysis portion");
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
    void profilePlan_containsTwelveProfiles() {
        BenchmarkPlan plan = BenchmarkPlan.profilePlan();
        assertEquals(12, plan.configs.size(),
                "Profile plan should contain 12 profiles (P01-P12)");
    }

    @Test
    void profilePlan_namesStartWithP() {
        BenchmarkPlan plan = BenchmarkPlan.profilePlan();
        for (BenchmarkConfig cfg : plan.configs) {
            assertTrue(cfg.name().matches("P\\d{2}-.*"),
                    "Profile name should match 'Pxx-...', got: " + cfg.name());
        }
    }

    @Test
    void profilePlan_allNamesUnique() {
        BenchmarkPlan plan = BenchmarkPlan.profilePlan();
        long distinctNames = plan.configs.stream()
                .map(BenchmarkConfig::name)
                .distinct()
                .count();
        assertEquals(plan.configs.size(), distinctNames, "All profile names must be unique");
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
        BenchmarkConfig cfg = findConfig(BenchmarkPlan.profilePlan(), "P05-native");
        assertEquals(RuntimeType.NATIVE, cfg.runtimeType());
        assertEquals("tfl4-ek-bench:native", cfg.dockerImage());
        assertTrue(cfg.jvmArgs().isEmpty(), "Native image should have no JVM args");
        assertEquals("Laufzeitprofil", cfg.category());
        assertEquals("Native", cfg.runtimeModel());
    }

    @Test
    void profilePlan_p06Openj9Balanced() {
        BenchmarkConfig cfg = findConfig(BenchmarkPlan.profilePlan(), "P06-openj9-balanced");
        assertEquals(RuntimeType.OPENJ9, cfg.runtimeType());
        assertEquals("tfl4-ek-bench:openj9", cfg.dockerImage());
        assertTrue(cfg.jvmArgs().contains("-Xgcpolicy:balanced"));
        assertTrue(cfg.jvmArgs().contains("-XX:MaxRAMPercentage=75"));
    }

    @Test
    void profilePlan_p07Openj9Optthruput() {
        BenchmarkConfig cfg = findConfig(BenchmarkPlan.profilePlan(), "P07-openj9-optthruput");
        assertEquals(RuntimeType.OPENJ9, cfg.runtimeType());
        assertTrue(cfg.jvmArgs().contains("-Xgcpolicy:optthruput"));
    }

    @Test
    void profilePlan_p08Openj9Optavgpause() {
        BenchmarkConfig cfg = findConfig(BenchmarkPlan.profilePlan(), "P08-openj9-optavgpause");
        assertEquals(RuntimeType.OPENJ9, cfg.runtimeType());
        assertTrue(cfg.jvmArgs().contains("-Xgcpolicy:optavgpause"));
    }

    @Test
    void profilePlan_p09HotspotHeap256m() {
        BenchmarkConfig cfg = findConfig(BenchmarkPlan.profilePlan(), "P09-hotspot-heap-256m");
        assertEquals(RuntimeType.HOTSPOT, cfg.runtimeType());
        assertTrue(cfg.jvmArgs().contains("-Xmx256m"));
        assertTrue(cfg.jvmArgs().contains("-XX:+UseG1GC"));
    }

    @Test
    void profilePlan_p10Openj9Heap256m() {
        BenchmarkConfig cfg = findConfig(BenchmarkPlan.profilePlan(), "P10-openj9-heap-256m");
        assertEquals(RuntimeType.OPENJ9, cfg.runtimeType());
        assertTrue(cfg.jvmArgs().contains("-Xmx256m"));
        // No MaxRAMPercentage — explicit heap overrides it
        assertFalse(cfg.jvmArgs().contains("-XX:MaxRAMPercentage=75"));
    }

    @Test
    void profilePlan_p11HotspotCds() {
        BenchmarkConfig cfg = findConfig(BenchmarkPlan.profilePlan(), "P11-hotspot-cds");
        assertEquals(RuntimeType.HOTSPOT, cfg.runtimeType());
        assertEquals("tfl4-ek-bench:jvm-cds", cfg.dockerImage());
        assertTrue(cfg.jvmArgs().contains("-XX:+UseG1GC"));
        assertTrue(cfg.jvmArgs().contains("-XX:MaxRAMPercentage=75"));
    }

    @Test
    void profilePlan_p12GraalvmJit() {
        BenchmarkConfig cfg = findConfig(BenchmarkPlan.profilePlan(), "P12-graalvm-jit");
        assertEquals(RuntimeType.HOTSPOT, cfg.runtimeType());
        assertEquals("tfl4-ek-bench:graalvm-jit", cfg.dockerImage());
        assertTrue(cfg.jvmArgs().contains("-XX:+UseG1GC"));
        assertTrue(cfg.jvmArgs().contains("-XX:MaxRAMPercentage=75"));
    }

    @Test
    void profilePlan_containsAllRuntimeTypes() {
        BenchmarkPlan plan = BenchmarkPlan.profilePlan();
        assertTrue(plan.configs.stream().anyMatch(c -> c.runtimeType() == RuntimeType.HOTSPOT));
        assertTrue(plan.configs.stream().anyMatch(c -> c.runtimeType() == RuntimeType.OPENJ9));
        assertTrue(plan.configs.stream().anyMatch(c -> c.runtimeType() == RuntimeType.NATIVE));
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
    void withEbicsImages_mapsCdsToJvmCdsEk() {
        BenchmarkPlan plan = BenchmarkPlan.profilePlan();
        BenchmarkPlan ebics = plan.withEbicsImages();
        BenchmarkConfig p11 = findConfig(ebics, "P11-hotspot-cds");
        assertEquals("tfl4-ek-bench:jvm-cds-ek", p11.dockerImage());
    }

    @Test
    void withEbicsImages_mapsGraalvmJitToGraalvmJitEk() {
        BenchmarkPlan plan = BenchmarkPlan.profilePlan();
        BenchmarkPlan ebics = plan.withEbicsImages();
        BenchmarkConfig p12 = findConfig(ebics, "P12-graalvm-jit");
        assertEquals("tfl4-ek-bench:graalvm-jit-ek", p12.dockerImage());
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

    // ==================== toEbicsImage ====================

    @Test
    void toEbicsImage_appendsEkSuffix() {
        assertEquals("tfl4-ek-bench:jvm-ek", BenchmarkPlan.toEbicsImage("tfl4-ek-bench:jvm"));
    }

    @Test
    void toEbicsImage_openj9() {
        assertEquals("tfl4-ek-bench:openj9-ek", BenchmarkPlan.toEbicsImage("tfl4-ek-bench:openj9"));
    }

    @Test
    void toEbicsImage_native() {
        assertEquals("tfl4-ek-bench:native-ek", BenchmarkPlan.toEbicsImage("tfl4-ek-bench:native"));
    }

    @Test
    void toEbicsImage_graalvmJit() {
        assertEquals("tfl4-ek-bench:graalvm-jit-ek", BenchmarkPlan.toEbicsImage("tfl4-ek-bench:graalvm-jit"));
    }

    @Test
    void toEbicsImage_jvmCds() {
        assertEquals("tfl4-ek-bench:jvm-cds-ek", BenchmarkPlan.toEbicsImage("tfl4-ek-bench:jvm-cds"));
    }

    @Test
    void toEbicsImage_alreadyEk_unchanged() {
        assertEquals("tfl4-ek-bench:jvm-ek", BenchmarkPlan.toEbicsImage("tfl4-ek-bench:jvm-ek"));
    }

    @Test
    void toEbicsImage_alreadyEk_openj9() {
        assertEquals("tfl4-ek-bench:openj9-ek", BenchmarkPlan.toEbicsImage("tfl4-ek-bench:openj9-ek"));
    }

    // ==================== combinedPlan ====================

    @Test
    void combinedPlan_contains32Configs() {
        BenchmarkPlan plan = BenchmarkPlan.combinedPlan();
        assertEquals(32, plan.configs.size(),
                "Combined plan should contain 32 configs (unified plan)");
    }

    @Test
    void combinedPlan_startsWithBaseline() {
        BenchmarkPlan plan = BenchmarkPlan.combinedPlan();
        assertEquals("baseline", plan.configs.get(0).name(),
                "Combined plan should start with baseline");
    }

    @Test
    void combinedPlan_profilesAfterDefaults() {
        BenchmarkPlan plan = BenchmarkPlan.combinedPlan();
        // Config at index 20 should be P01 (first profile)
        assertEquals("P01-hotspot-standard", plan.configs.get(20).name(),
                "Profiles should start at index 20 (after 20 default configs)");
    }

    @Test
    void combinedPlan_endsWithP12() {
        BenchmarkPlan plan = BenchmarkPlan.combinedPlan();
        assertEquals("P12-graalvm-jit", plan.configs.get(31).name(),
                "Combined plan should end with P12");
    }

    @Test
    void combinedPlan_allNamesUnique() {
        BenchmarkPlan plan = BenchmarkPlan.combinedPlan();
        long distinctNames = plan.configs.stream()
                .map(BenchmarkConfig::name)
                .distinct()
                .count();
        assertEquals(plan.configs.size(), distinctNames, "All config names must be unique");
    }

    @Test
    void combinedPlan_withEbicsImages_allEndWithEk() {
        BenchmarkPlan plan = BenchmarkPlan.combinedPlan().withEbicsImages();
        for (BenchmarkConfig cfg : plan.configs) {
            assertTrue(cfg.dockerImage().endsWith("-ek"),
                    "EBICS combined plan: image for '" + cfg.name() + "' should end with '-ek', got: " + cfg.dockerImage());
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
