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
        assertEquals(20, p.warmupRequests());  // default
        assertEquals(100, p.measureRequests()); // default
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
    void resolvePlan_noJvmArgs_returnsDefaultPlan() {
        String[] args = {"--scenario", "json"};
        BenchmarkPlan plan = BenchCli.resolvePlan(args, BenchmarkScenario.PAYLOAD_HEAVY_JSON);
        BenchmarkPlan defaultPlan = BenchmarkPlan.defaultPlan();
        assertEquals(defaultPlan.configs.size(), plan.configs.size());
        assertEquals("baseline", plan.configs.get(0).name());
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
    void resolvePlan_ebicsUpload_defaultPlan_usesEkImage() {
        String[] args = {};
        BenchmarkPlan plan = BenchCli.resolvePlan(args, BenchmarkScenario.EBICS_UPLOAD);
        // Alle Configs muessen auf das EK-Image umgestellt sein
        for (BenchmarkConfig cfg : plan.configs) {
            assertEquals("tfl4-ek-bench:jvm-ek", cfg.dockerImage(),
                    "EBICS scenario should auto-select jvm-ek image for config '" + cfg.name() + "'");
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
    void resolvePlan_nonEbics_defaultPlan_usesStandardImage() {
        String[] args = {};
        BenchmarkPlan plan = BenchCli.resolvePlan(args, BenchmarkScenario.ALLOC_HEAVY_OK);
        for (BenchmarkConfig cfg : plan.configs) {
            assertEquals("tfl4-ek-bench:jvm", cfg.dockerImage());
        }
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
}
