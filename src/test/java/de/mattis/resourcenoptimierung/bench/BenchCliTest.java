package de.mattis.resourcenoptimierung.bench;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests fuer BenchCli: Argument-Parsing, Szenario-Aufloesung, Profil-Erstellung.
 */
class BenchCliTest {

    // ==================== findArgValue ====================

    @Test
    void findArgValue_spaceSeparated() {
        String[] args = {"--scenario", "json", "--n", "500"};
        assertEquals("json", BenchCli.findArgValue(args, "--scenario"));
        assertEquals("500", BenchCli.findArgValue(args, "--n"));
    }

    @Test
    void findArgValue_equalsSeparated() {
        String[] args = {"--scenario=alloc", "--n=1000"};
        assertEquals("alloc", BenchCli.findArgValue(args, "--scenario"));
        assertEquals("1000", BenchCli.findArgValue(args, "--n"));
    }

    @Test
    void findArgValue_notPresent_returnsNull() {
        String[] args = {"--scenario", "json"};
        assertNull(BenchCli.findArgValue(args, "--n"));
    }

    @Test
    void findArgValue_emptyArgs() {
        assertNull(BenchCli.findArgValue(new String[]{}, "--scenario"));
    }

    @Test
    void findArgValue_keyAtEnd_noValue_returnsNull() {
        String[] args = {"--scenario"};
        assertNull(BenchCli.findArgValue(args, "--scenario"));
    }

    // ==================== resolveIntArg ====================

    @Test
    void resolveIntArg_present_returnsValue() {
        String[] args = {"--warmupRequests", "50"};
        assertEquals(50, BenchCli.resolveIntArg(args, "--warmupRequests", 20));
    }

    @Test
    void resolveIntArg_notPresent_returnsDefault() {
        String[] args = {};
        assertEquals(20, BenchCli.resolveIntArg(args, "--warmupRequests", 20));
    }

    @Test
    void resolveIntArg_equalsForm() {
        String[] args = {"--warmupRequests=30"};
        assertEquals(30, BenchCli.resolveIntArg(args, "--warmupRequests", 20));
    }

    // ==================== resolveLongArg ====================

    @Test
    void resolveLongArg_present_returnsValue() {
        String[] args = {"--sleepBetweenRequestsMs", "200"};
        assertEquals(200L, BenchCli.resolveLongArg(args, "--sleepBetweenRequestsMs", 0));
    }

    @Test
    void resolveLongArg_notPresent_returnsDefault() {
        assertEquals(0L, BenchCli.resolveLongArg(new String[]{}, "--sleepBetweenRequestsMs", 0));
    }

    // ==================== parseScenario ====================

    @Test
    void parseScenario_json_variants() {
        assertEquals(BenchmarkScenario.PAYLOAD_HEAVY_JSON, BenchCli.parseScenario("json"));
        assertEquals(BenchmarkScenario.PAYLOAD_HEAVY_JSON, BenchCli.parseScenario("payload"));
        assertEquals(BenchmarkScenario.PAYLOAD_HEAVY_JSON, BenchCli.parseScenario("/json"));
        assertEquals(BenchmarkScenario.PAYLOAD_HEAVY_JSON, BenchCli.parseScenario("payload-heavy-json"));
    }

    @Test
    void parseScenario_alloc_variants() {
        assertEquals(BenchmarkScenario.ALLOC_HEAVY_OK, BenchCli.parseScenario("alloc"));
        assertEquals(BenchmarkScenario.ALLOC_HEAVY_OK, BenchCli.parseScenario("ok"));
        assertEquals(BenchmarkScenario.ALLOC_HEAVY_OK, BenchCli.parseScenario("/alloc"));
    }

    @Test
    void parseScenario_ebicsUpload_variants() {
        assertEquals(BenchmarkScenario.EBICS_UPLOAD, BenchCli.parseScenario("ebics-upload"));
        assertEquals(BenchmarkScenario.EBICS_UPLOAD, BenchCli.parseScenario("upload"));
        assertEquals(BenchmarkScenario.EBICS_UPLOAD, BenchCli.parseScenario("ebics"));
    }

    @Test
    void parseScenario_caseInsensitive() {
        assertEquals(BenchmarkScenario.PAYLOAD_HEAVY_JSON, BenchCli.parseScenario("JSON"));
        assertEquals(BenchmarkScenario.ALLOC_HEAVY_OK, BenchCli.parseScenario("ALLOC"));
    }

    @Test
    void parseScenario_unknown_throwsException() {
        assertThrows(IllegalArgumentException.class, () -> BenchCli.parseScenario("invalid"));
    }

    // ==================== resolveScenario ====================

    @Test
    void resolveScenario_withCliArg() throws Exception {
        String[] args = {"--scenario", "alloc"};
        assertEquals(BenchmarkScenario.ALLOC_HEAVY_OK, BenchCli.resolveScenario(args));
    }

    @Test
    void resolveScenario_withEqualsArg() throws Exception {
        String[] args = {"--scenario=ebics-upload"};
        assertEquals(BenchmarkScenario.EBICS_UPLOAD, BenchCli.resolveScenario(args));
    }

    // ==================== resolveWorkloadN ====================

    @Test
    void resolveWorkloadN_explicit() {
        String[] args = {"--n", "999"};
        assertEquals(999, BenchCli.resolveWorkloadN(args, BenchmarkScenario.PAYLOAD_HEAVY_JSON));
    }

    @Test
    void resolveWorkloadN_defaultJson() {
        assertEquals(200_000, BenchCli.resolveWorkloadN(new String[]{}, BenchmarkScenario.PAYLOAD_HEAVY_JSON));
    }

    @Test
    void resolveWorkloadN_defaultAlloc() {
        assertEquals(10_000_000, BenchCli.resolveWorkloadN(new String[]{}, BenchmarkScenario.ALLOC_HEAVY_OK));
    }

    @Test
    void resolveWorkloadN_defaultEbicsUpload() {
        assertEquals(10, BenchCli.resolveWorkloadN(new String[]{}, BenchmarkScenario.EBICS_UPLOAD));
    }

    // ==================== resolveProfile ====================

    @Test
    void resolveProfile_defaults() {
        MeasurementProfile p = BenchCli.resolveProfile(new String[]{});
        assertEquals(MeasurementProfile.defaults(), p);
    }

    @Test
    void resolveProfile_customValues() {
        String[] args = {
                "--warmupRequests", "5",
                "--measureRequests", "50",
                "--concurrency", "4",
                "--sleepBetweenRequestsMs", "100"
        };
        MeasurementProfile p = BenchCli.resolveProfile(args);
        assertEquals(5, p.warmupRequests());
        assertEquals(50, p.measureRequests());
        assertEquals(4, p.concurrency());
        assertEquals(100, p.sleepBetweenRequestsMs());
    }

    @Test
    void resolveProfile_partialOverride() {
        String[] args = {"--concurrency", "8"};
        MeasurementProfile p = BenchCli.resolveProfile(args);
        assertEquals(200, p.warmupRequests());  // default
        assertEquals(500, p.measureRequests()); // default
        assertEquals(8, p.concurrency());       // overridden
        assertEquals(0, p.sleepBetweenRequestsMs()); // default
    }

    // ==================== parseJvmArgs ====================

    @Test
    void parseJvmArgs_multipleFlags() {
        List<String> result = BenchCli.parseJvmArgs("-XX:+UseZGC -Xmx1g");
        assertEquals(List.of("-XX:+UseZGC", "-Xmx1g"), result);
    }

    @Test
    void parseJvmArgs_singleFlag() {
        List<String> result = BenchCli.parseJvmArgs("-XX:-UseCompressedOops");
        assertEquals(List.of("-XX:-UseCompressedOops"), result);
    }

    @Test
    void parseJvmArgs_emptyString_returnsEmptyList() {
        assertEquals(List.of(), BenchCli.parseJvmArgs(""));
    }

    @Test
    void parseJvmArgs_blankString_returnsEmptyList() {
        assertEquals(List.of(), BenchCli.parseJvmArgs("   "));
    }

    @Test
    void parseJvmArgs_null_returnsEmptyList() {
        assertEquals(List.of(), BenchCli.parseJvmArgs(null));
    }

    @Test
    void parseJvmArgs_extraWhitespace_trimmed() {
        List<String> result = BenchCli.parseJvmArgs("  -Xmx512m   -Xms256m  ");
        assertEquals(List.of("-Xmx512m", "-Xms256m"), result);
    }

    // ==================== resolvePlan ====================

    @Test
    void resolvePlan_noJvmArgs_returnsCombinedPlan() {
        String[] args = {"--scenario", "json"};
        BenchmarkPlan plan = BenchCli.resolvePlan(args, BenchmarkScenario.PAYLOAD_HEAVY_JSON);
        assertEquals(32, plan.configs.size(),
                "Without --profiles, combined plan should have 32 configs (20 default + 12 profiles)");
        assertEquals("baseline", plan.configs.get(0).name());
        assertEquals("P01-hotspot-standard", plan.configs.get(20).name());
    }

    @Test
    void resolvePlan_withJvmArgs_returnsSingleConfig() {
        String[] args = {"--jvmArgs", "-XX:+UseZGC -Xmx1g"};
        BenchmarkPlan plan = BenchCli.resolvePlan(args, BenchmarkScenario.PAYLOAD_HEAVY_JSON);
        assertEquals(1, plan.configs.size());
        assertEquals("cli-custom", plan.configs.get(0).name());
        assertEquals(List.of("-XX:+UseZGC", "-Xmx1g"), plan.configs.get(0).jvmArgs());
        assertEquals("tfl4-ek-bench:jvm", plan.configs.get(0).dockerImage());
    }

    @Test
    void resolvePlan_withJvmArgs_emptyString_returnsBaselineRun() {
        String[] args = {"--jvmArgs", ""};
        BenchmarkPlan plan = BenchCli.resolvePlan(args, BenchmarkScenario.PAYLOAD_HEAVY_JSON);
        assertEquals(1, plan.configs.size());
        assertTrue(plan.configs.get(0).jvmArgs().isEmpty());
    }

    @Test
    void resolvePlan_customConfigName() {
        String[] args = {"--jvmArgs", "-XX:+UseZGC", "--configName", "zgc-test"};
        BenchmarkPlan plan = BenchCli.resolvePlan(args, BenchmarkScenario.PAYLOAD_HEAVY_JSON);
        assertEquals("zgc-test", plan.configs.get(0).name());
    }

    @Test
    void resolvePlan_customDockerImage() {
        String[] args = {"--jvmArgs", "-Xmx2g", "--dockerImage", "myapp:latest"};
        BenchmarkPlan plan = BenchCli.resolvePlan(args, BenchmarkScenario.PAYLOAD_HEAVY_JSON);
        assertEquals("myapp:latest", plan.configs.get(0).dockerImage());
    }

    @Test
    void resolvePlan_allCustomOptions() {
        String[] args = {
                "--jvmArgs", "-XX:+UseZGC -Xmx2g",
                "--configName", "zgc-big-heap",
                "--dockerImage", "tfl4-ek-bench:custom"
        };
        BenchmarkPlan plan = BenchCli.resolvePlan(args, BenchmarkScenario.PAYLOAD_HEAVY_JSON);
        assertEquals(1, plan.configs.size());
        BenchmarkConfig cfg = plan.configs.get(0);
        assertEquals("zgc-big-heap", cfg.name());
        assertEquals("tfl4-ek-bench:custom", cfg.dockerImage());
        assertEquals(List.of("-XX:+UseZGC", "-Xmx2g"), cfg.jvmArgs());
    }

    // ==================== resolvePlan — EBICS image auto-selection ====================

    @Test
    void resolvePlan_ebicsUpload_defaultPlan_usesEkImages() {
        String[] args = {};
        BenchmarkPlan plan = BenchCli.resolvePlan(args, BenchmarkScenario.EBICS_UPLOAD);
        assertEquals(32, plan.configs.size(), "EBICS combined plan should have 32 configs");
        // Alle Configs muessen auf EK-Images enden
        for (BenchmarkConfig cfg : plan.configs) {
            assertTrue(cfg.dockerImage().endsWith("-ek"),
                    "EBICS scenario: image for '" + cfg.name() + "' should end with '-ek', got: " + cfg.dockerImage());
        }
    }

    @Test
    void resolvePlan_ebicsUpload_withJvmArgs_usesEkImage() {
        String[] args = {"--jvmArgs", "-XX:+UseZGC"};
        BenchmarkPlan plan = BenchCli.resolvePlan(args, BenchmarkScenario.EBICS_UPLOAD);
        assertEquals(1, plan.configs.size());
        assertEquals("tfl4-ek-bench:jvm-ek", plan.configs.get(0).dockerImage());
    }

    @Test
    void resolvePlan_ebics_explicitDockerImage_overridesDefault() {
        String[] args = {"--jvmArgs", "-Xmx1g", "--dockerImage", "my-custom:ebics"};
        BenchmarkPlan plan = BenchCli.resolvePlan(args, BenchmarkScenario.EBICS_UPLOAD);
        assertEquals("my-custom:ebics", plan.configs.get(0).dockerImage(),
                "Explicit --dockerImage should override EBICS auto-selection");
    }

    @Test
    void resolvePlan_nonEbics_combinedPlan_defaultImagesNotEk() {
        String[] args = {};
        BenchmarkPlan plan = BenchCli.resolvePlan(args, BenchmarkScenario.ALLOC_HEAVY_OK);
        assertEquals(32, plan.configs.size());
        // No image should end with -ek in non-EBICS mode
        for (BenchmarkConfig cfg : plan.configs) {
            assertFalse(cfg.dockerImage().endsWith("-ek"),
                    "Non-EBICS: image for '" + cfg.name() + "' should not end with '-ek'");
        }
    }

    // ==================== resolvePlan — --profiles flag ====================

    @Test
    void resolvePlan_profilesFlag_returnsProfilePlan() {
        String[] args = {"--profiles"};
        BenchmarkPlan plan = BenchCli.resolvePlan(args, BenchmarkScenario.PAYLOAD_HEAVY_JSON);
        assertEquals(12, plan.configs.size(), "Profile plan should have 12 configs (P01-P12)");
        assertTrue(plan.configs.get(0).name().startsWith("P01"));
    }

    @Test
    void resolvePlan_profilesFlag_ebics_usesEbicsImages() {
        String[] args = {"--profiles"};
        BenchmarkPlan plan = BenchCli.resolvePlan(args, BenchmarkScenario.EBICS_UPLOAD);
        assertEquals(12, plan.configs.size());
        // P01 (HOTSPOT) should use jvm-ek
        assertEquals("tfl4-ek-bench:jvm-ek", plan.configs.get(0).dockerImage());
        // P04 (OPENJ9) should use openj9-ek
        BenchmarkConfig p04 = plan.configs.stream()
                .filter(c -> c.name().contains("P04"))
                .findFirst().orElseThrow();
        assertEquals("tfl4-ek-bench:openj9-ek", p04.dockerImage());
        // P05 (NATIVE) should use native-ek
        BenchmarkConfig p05 = plan.configs.stream()
                .filter(c -> c.name().contains("P05"))
                .findFirst().orElseThrow();
        assertEquals("tfl4-ek-bench:native-ek", p05.dockerImage());
        // P11 (CDS) should use jvm-cds-ek
        BenchmarkConfig p11 = plan.configs.stream()
                .filter(c -> c.name().contains("P11"))
                .findFirst().orElseThrow();
        assertEquals("tfl4-ek-bench:jvm-cds-ek", p11.dockerImage());
        // P12 (GraalVM JIT) should use graalvm-jit-ek
        BenchmarkConfig p12 = plan.configs.stream()
                .filter(c -> c.name().contains("P12"))
                .findFirst().orElseThrow();
        assertEquals("tfl4-ek-bench:graalvm-jit-ek", p12.dockerImage());
    }

    @Test
    void resolvePlan_profilesFlag_containsAllRuntimeTypes() {
        String[] args = {"--profiles"};
        BenchmarkPlan plan = BenchCli.resolvePlan(args, BenchmarkScenario.PAYLOAD_HEAVY_JSON);
        assertTrue(plan.configs.stream().anyMatch(c -> c.runtimeType() == RuntimeType.HOTSPOT));
        assertTrue(plan.configs.stream().anyMatch(c -> c.runtimeType() == RuntimeType.OPENJ9));
        assertTrue(plan.configs.stream().anyMatch(c -> c.runtimeType() == RuntimeType.NATIVE));
    }

    @Test
    void resolvePlan_jvmArgs_alwaysHotspot() {
        String[] args = {"--jvmArgs", "-XX:+UseZGC"};
        BenchmarkPlan plan = BenchCli.resolvePlan(args, BenchmarkScenario.PAYLOAD_HEAVY_JSON);
        assertEquals(RuntimeType.HOTSPOT, plan.configs.get(0).runtimeType(),
                "CLI custom config should default to HOTSPOT");
    }

    // ==================== hasFlag ====================

    @Test
    void hasFlag_present_returnsTrue() {
        String[] args = {"--merge-excel", "--scenario", "json"};
        assertTrue(BenchCli.hasFlag(args, "--merge-excel"));
    }

    @Test
    void hasFlag_notPresent_returnsFalse() {
        String[] args = {"--scenario", "json"};
        assertFalse(BenchCli.hasFlag(args, "--merge-excel"));
    }

    @Test
    void hasFlag_emptyArgs_returnsFalse() {
        assertFalse(BenchCli.hasFlag(new String[]{}, "--merge-excel"));
    }

    @Test
    void hasFlag_partialMatch_returnsFalse() {
        String[] args = {"--merge-excel-extra"};
        assertFalse(BenchCli.hasFlag(args, "--merge-excel"));
    }

    @Test
    void hasFlag_flagAtEnd() {
        String[] args = {"--scenario", "json", "--merge-excel"};
        assertTrue(BenchCli.hasFlag(args, "--merge-excel"));
    }

    // ==================== --repetitions ====================

    @Test
    void repetitions_defaultIs3() {
        String[] args = {};
        assertEquals(3, BenchCli.resolveIntArg(args, "--repetitions", 3));
    }

    @Test
    void repetitions_explicitValue() {
        String[] args = {"--repetitions", "5"};
        assertEquals(5, BenchCli.resolveIntArg(args, "--repetitions", 3));
    }

    @Test
    void repetitions_equalsForm() {
        String[] args = {"--repetitions=10"};
        assertEquals(10, BenchCli.resolveIntArg(args, "--repetitions", 3));
    }

    @Test
    void repetitions_one_disablesRepetitions() {
        String[] args = {"--repetitions", "1"};
        assertEquals(1, BenchCli.resolveIntArg(args, "--repetitions", 3));
    }

    // ==================== --quick ====================

    @Test
    void resolveProfile_quick_usesQuickDefaults() {
        String[] args = {"--quick"};
        MeasurementProfile p = BenchCli.resolveProfile(args);
        assertEquals(10, p.warmupRequests());
        assertEquals(30, p.measureRequests());
        assertEquals(1, p.concurrency());
        assertEquals(0, p.sleepBetweenRequestsMs());
    }

    @Test
    void resolveProfile_quick_withOverride() {
        String[] args = {"--quick", "--measureRequests", "50"};
        MeasurementProfile p = BenchCli.resolveProfile(args);
        assertEquals(10, p.warmupRequests());   // quick default
        assertEquals(50, p.measureRequests());   // overridden
        assertEquals(1, p.concurrency());        // quick default
        assertEquals(0, p.sleepBetweenRequestsMs());
    }

    @Test
    void resolveProfile_quick_partialOverride() {
        String[] args = {"--quick", "--concurrency", "4"};
        MeasurementProfile p = BenchCli.resolveProfile(args);
        assertEquals(10, p.warmupRequests());    // quick default
        assertEquals(30, p.measureRequests());   // quick default
        assertEquals(4, p.concurrency());        // overridden
        assertEquals(0, p.sleepBetweenRequestsMs());
    }

    @Test
    void repetitions_quick_defaultIs1() {
        // Simulates what main() does: quick flag -> default repetitions = 1
        String[] args = {"--quick"};
        int defaultReps = BenchCli.hasFlag(args, "--quick") ? 1 : 3;
        int reps = BenchCli.resolveIntArg(args, "--repetitions", defaultReps);
        assertEquals(1, reps);
    }

    @Test
    void repetitions_quick_explicitOverride() {
        // --quick setzt default auf 1, aber --repetitions 5 ueberschreibt
        String[] args = {"--quick", "--repetitions", "5"};
        int defaultReps = BenchCli.hasFlag(args, "--quick") ? 1 : 3;
        int reps = BenchCli.resolveIntArg(args, "--repetitions", defaultReps);
        assertEquals(5, reps);
    }

    // ==================== --rebuild ====================

    @Test
    void hasFlag_rebuild_present() {
        String[] args = {"--scenario", "json", "--rebuild"};
        assertTrue(BenchCli.hasFlag(args, "--rebuild"));
    }

    @Test
    void hasFlag_rebuild_notPresent() {
        String[] args = {"--scenario", "json"};
        assertFalse(BenchCli.hasFlag(args, "--rebuild"));
    }

    @Test
    void hasFlag_rebuild_combinedWithProfiles() {
        String[] args = {"--profiles", "--rebuild", "--scenario", "json"};
        assertTrue(BenchCli.hasFlag(args, "--rebuild"));
        assertTrue(BenchCli.hasFlag(args, "--profiles"));
    }

    @Test
    void hasFlag_rebuild_combinedWithQuick() {
        String[] args = {"--quick", "--rebuild"};
        assertTrue(BenchCli.hasFlag(args, "--rebuild"));
        assertTrue(BenchCli.hasFlag(args, "--quick"));
    }

    // ==================== --smoke ====================

    @Test
    void resolveProfile_smoke_usesSmokeDefaults() {
        String[] args = {"--smoke"};
        MeasurementProfile p = BenchCli.resolveProfile(args);
        assertEquals(3, p.warmupRequests());
        assertEquals(5, p.measureRequests());
        assertEquals(1, p.concurrency());
        assertEquals(0, p.sleepBetweenRequestsMs());
    }

    @Test
    void resolveProfile_smoke_withOverride() {
        String[] args = {"--smoke", "--measureRequests", "10"};
        MeasurementProfile p = BenchCli.resolveProfile(args);
        assertEquals(3, p.warmupRequests());    // smoke default
        assertEquals(10, p.measureRequests());  // overridden
        assertEquals(1, p.concurrency());       // smoke default
        assertEquals(0, p.sleepBetweenRequestsMs());
    }

    @Test
    void resolveProfile_smoke_overridesQuick() {
        // --smoke has precedence over --quick
        String[] args = {"--smoke", "--quick"};
        MeasurementProfile p = BenchCli.resolveProfile(args);
        assertEquals(3, p.warmupRequests());    // smoke, not quick (10)
        assertEquals(5, p.measureRequests());   // smoke, not quick (30)
    }

    @Test
    void resolveWorkloadN_smoke_ebics_reduced() {
        String[] args = {"--smoke"};
        assertEquals(3, BenchCli.resolveWorkloadN(args, BenchmarkScenario.EBICS_UPLOAD));
    }

    @Test
    void resolveWorkloadN_smoke_json_unchanged() {
        String[] args = {"--smoke"};
        assertEquals(200_000, BenchCli.resolveWorkloadN(args, BenchmarkScenario.PAYLOAD_HEAVY_JSON));
    }

    @Test
    void resolveWorkloadN_smoke_alloc_unchanged() {
        String[] args = {"--smoke"};
        assertEquals(10_000_000, BenchCli.resolveWorkloadN(args, BenchmarkScenario.ALLOC_HEAVY_OK));
    }

    @Test
    void resolveWorkloadN_smoke_explicitN_overrides() {
        String[] args = {"--smoke", "--n", "7"};
        assertEquals(7, BenchCli.resolveWorkloadN(args, BenchmarkScenario.EBICS_UPLOAD));
    }

    @Test
    void repetitions_smoke_defaultIs1() {
        // Simulates what main() does: smoke flag -> default repetitions = 1
        String[] args = {"--smoke"};
        int defaultReps = (BenchCli.hasFlag(args, "--smoke") || BenchCli.hasFlag(args, "--quick")) ? 1 : 3;
        int reps = BenchCli.resolveIntArg(args, "--repetitions", defaultReps);
        assertEquals(1, reps);
    }

    @Test
    void repetitions_smoke_explicitOverride() {
        // --smoke setzt default auf 1, aber --repetitions 3 ueberschreibt
        String[] args = {"--smoke", "--repetitions", "3"};
        int defaultReps = (BenchCli.hasFlag(args, "--smoke") || BenchCli.hasFlag(args, "--quick")) ? 1 : 3;
        int reps = BenchCli.resolveIntArg(args, "--repetitions", defaultReps);
        assertEquals(3, reps);
    }

    @Test
    void hasFlag_smoke_present() {
        String[] args = {"--smoke", "--scenario", "json"};
        assertTrue(BenchCli.hasFlag(args, "--smoke"));
    }

    @Test
    void hasFlag_smoke_notPresent() {
        String[] args = {"--scenario", "json"};
        assertFalse(BenchCli.hasFlag(args, "--smoke"));
    }
}
