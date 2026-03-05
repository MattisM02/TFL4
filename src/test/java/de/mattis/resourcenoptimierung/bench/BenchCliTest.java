package de.mattis.resourcenoptimierung.bench;

import org.junit.jupiter.api.Test;

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
    void parseScenario_ebicsDownload_variants() {
        assertEquals(BenchmarkScenario.EBICS_DOWNLOAD, BenchCli.parseScenario("ebics-download"));
        assertEquals(BenchmarkScenario.EBICS_DOWNLOAD, BenchCli.parseScenario("download"));
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

    @Test
    void resolveWorkloadN_defaultEbicsDownload() {
        assertEquals(10, BenchCli.resolveWorkloadN(new String[]{}, BenchmarkScenario.EBICS_DOWNLOAD));
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
}
