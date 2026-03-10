package de.mattis.resourcenoptimierung.bench;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests fuer DurationEstimator: CSV-Parsing, Lookup-Strategie, Skalierung, Formatierung.
 */
class DurationEstimatorTest {

    // ======================== formatDuration ========================

    @Test
    void formatDuration_seconds() {
        assertEquals("45s", DurationEstimator.formatDuration(45));
    }

    @Test
    void formatDuration_minutes() {
        assertEquals("2m 15s", DurationEstimator.formatDuration(135));
    }

    @Test
    void formatDuration_hours() {
        assertEquals("1h 30m", DurationEstimator.formatDuration(5400));
    }

    @Test
    void formatDuration_zero() {
        assertEquals("0s", DurationEstimator.formatDuration(0));
    }

    @Test
    void formatDuration_negative() {
        assertEquals("?", DurationEstimator.formatDuration(-1));
    }

    @Test
    void formatDuration_largeHours() {
        assertEquals("8h 15m", DurationEstimator.formatDuration(8 * 3600 + 15 * 60));
    }

    // ======================== splitCsvLine ========================

    @Test
    void splitCsvLine_simple() {
        String[] parts = DurationEstimator.splitCsvLine("a,b,c");
        assertArrayEquals(new String[]{"a", "b", "c"}, parts);
    }

    @Test
    void splitCsvLine_quoted() {
        String[] parts = DurationEstimator.splitCsvLine("\"hello, world\",b,c");
        assertEquals("hello, world", parts[0]);
        assertEquals("b", parts[1]);
    }

    @Test
    void splitCsvLine_escapedQuotes() {
        String[] parts = DurationEstimator.splitCsvLine("\"he said \"\"hi\"\"\",b");
        assertEquals("he said \"hi\"", parts[0]);
        assertEquals("b", parts[1]);
    }

    @Test
    void splitCsvLine_emptyFields() {
        String[] parts = DurationEstimator.splitCsvLine("a,,c,");
        assertEquals(4, parts.length);
        assertEquals("", parts[1]);
        assertEquals("", parts[3]);
    }

    // ======================== parseCsv ========================

    @Test
    void parseCsv_withWallClock(@TempDir Path tmpDir) throws IOException {
        String csv = "scenario,configName,dockerImage,warmupRequests,measureRequests,wallClockSeconds,readinessMs,totalMeasureTimeSeconds\n"
                + "EBICS_UPLOAD,P01-hotspot-standard,tfl4-ek-bench:jvm-ek,200,500,145.3,7247,120.5\n"
                + "EBICS_UPLOAD,P05-native,tfl4-ek-bench:native-ek,200,500,98.7,1077,85.2\n";
        Path file = tmpDir.resolve("results-test.csv");
        Files.writeString(file, csv, StandardCharsets.UTF_8);

        List<DurationEstimator.HistoricalRun> runs = DurationEstimator.parseCsv(file);
        assertEquals(2, runs.size());

        DurationEstimator.HistoricalRun r0 = runs.get(0);
        assertEquals("P01-hotspot-standard", r0.configName());
        assertEquals("tfl4-ek-bench:jvm-ek", r0.dockerImage());
        assertEquals("EBICS_UPLOAD", r0.scenario());
        assertEquals(200, r0.warmupRequests());
        assertEquals(500, r0.measureRequests());
        assertEquals(145.3, r0.wallClockSeconds(), 0.01);
        assertEquals(700, r0.totalRequests());

        // effectiveWallClock uses explicit value when > 0
        assertEquals(145.3, r0.effectiveWallClock(), 0.01);
    }

    @Test
    void parseCsv_withoutWallClock_fallsBackToCalculation(@TempDir Path tmpDir) throws IOException {
        // Aeltere CSVs ohne wallClockSeconds-Spalte
        String csv = "scenario,configName,dockerImage,warmupRequests,measureRequests,readinessMs,totalMeasureTimeSeconds\n"
                + "EBICS_UPLOAD,P01-hotspot,img:jvm,50,150,7000,90.0\n";
        Path file = tmpDir.resolve("results-old.csv");
        Files.writeString(file, csv, StandardCharsets.UTF_8);

        List<DurationEstimator.HistoricalRun> runs = DurationEstimator.parseCsv(file);
        assertEquals(1, runs.size());

        DurationEstimator.HistoricalRun r = runs.get(0);
        assertEquals(0.0, r.wallClockSeconds()); // nicht vorhanden
        // effectiveWallClock berechnet: readiness + warmup*avgLatency + measure + overhead
        double avgLatency = 90.0 / 150; // 0.6s
        double expectedWarmup = 50 * avgLatency; // 30s
        double expected = 7.0 + expectedWarmup + 90.0 + DurationEstimator.FIXED_OVERHEAD_SECONDS;
        assertEquals(expected, r.effectiveWallClock(), 0.1);
    }

    @Test
    void parseCsv_emptyFile(@TempDir Path tmpDir) throws IOException {
        Path file = tmpDir.resolve("results-empty.csv");
        Files.writeString(file, "", StandardCharsets.UTF_8);

        List<DurationEstimator.HistoricalRun> runs = DurationEstimator.parseCsv(file);
        assertTrue(runs.isEmpty());
    }

    @Test
    void parseCsv_headerOnly(@TempDir Path tmpDir) throws IOException {
        Path file = tmpDir.resolve("results-header.csv");
        Files.writeString(file, "scenario,configName,dockerImage\n", StandardCharsets.UTF_8);

        List<DurationEstimator.HistoricalRun> runs = DurationEstimator.parseCsv(file);
        assertTrue(runs.isEmpty());
    }

    // ======================== loadHistory ========================

    @Test
    void loadHistory_multipleFiles(@TempDir Path tmpDir) throws IOException {
        String csv1 = "scenario,configName,dockerImage,warmupRequests,measureRequests,wallClockSeconds,readinessMs,totalMeasureTimeSeconds\n"
                + "EBICS_UPLOAD,P01,img:jvm,200,500,120.0,5000,100.0\n";
        String csv2 = "scenario,configName,dockerImage,warmupRequests,measureRequests,wallClockSeconds,readinessMs,totalMeasureTimeSeconds\n"
                + "EBICS_UPLOAD,P05,img:native,200,500,80.0,1000,70.0\n";

        Files.writeString(tmpDir.resolve("results-2026-01.csv"), csv1, StandardCharsets.UTF_8);
        Files.writeString(tmpDir.resolve("results-2026-02.csv"), csv2, StandardCharsets.UTF_8);
        Files.writeString(tmpDir.resolve("not-a-result.csv"), "ignored", StandardCharsets.UTF_8); // should be ignored

        List<DurationEstimator.HistoricalRun> runs = DurationEstimator.loadHistory(tmpDir);
        assertEquals(2, runs.size());
    }

    @Test
    void loadHistory_nonexistentDir() {
        List<DurationEstimator.HistoricalRun> runs = DurationEstimator.loadHistory(Path.of("/nonexistent/path"));
        assertTrue(runs.isEmpty());
    }

    // ======================== scaleEstimate ========================

    @Test
    void scaleEstimate_sameRequestCount() {
        var run = new DurationEstimator.HistoricalRun(
                "cfg", "img", "EBICS", 200, 500, 120.0, 5000, 100.0);
        // Same request count (700) -> same estimate
        double est = DurationEstimator.scaleEstimate(List.of(run), 700);
        assertEquals(120.0, est, 0.1);
    }

    @Test
    void scaleEstimate_doubleRequests() {
        var run = new DurationEstimator.HistoricalRun(
                "cfg", "img", "EBICS", 200, 500, 120.0, 5000, 100.0);
        // Double requests (1400) -> variable part doubles, fixed stays
        // variable = 120 - 10 = 110; scaled = 110 * 2 = 220; total = 10 + 220 = 230
        double est = DurationEstimator.scaleEstimate(List.of(run), 1400);
        assertEquals(230.0, est, 0.1);
    }

    @Test
    void scaleEstimate_halfRequests() {
        var run = new DurationEstimator.HistoricalRun(
                "cfg", "img", "EBICS", 200, 500, 120.0, 5000, 100.0);
        // Half requests (350) -> variable halves
        // variable = 110; scaled = 110 * 0.5 = 55; total = 10 + 55 = 65
        double est = DurationEstimator.scaleEstimate(List.of(run), 350);
        assertEquals(65.0, est, 0.1);
    }

    @Test
    void scaleEstimate_averagesMultipleRuns() {
        var run1 = new DurationEstimator.HistoricalRun(
                "cfg", "img", "EBICS", 200, 500, 100.0, 5000, 80.0);
        var run2 = new DurationEstimator.HistoricalRun(
                "cfg", "img", "EBICS", 200, 500, 140.0, 7000, 110.0);
        // Average wallClock = 120, average requests = 700
        double est = DurationEstimator.scaleEstimate(List.of(run1, run2), 700);
        assertEquals(120.0, est, 0.1);
    }

    // ======================== estimateSingleRun (lookup priority) ========================

    @Test
    void estimateSingleRun_exactMatch() {
        var exact = new DurationEstimator.HistoricalRun(
                "P01-hotspot-standard", "img:jvm", "EBICS_UPLOAD", 200, 500, 120.0, 5000, 100.0);
        var other = new DurationEstimator.HistoricalRun(
                "P05-native", "img:native", "EBICS_UPLOAD", 200, 500, 80.0, 1000, 70.0);

        BenchmarkConfig cfg = new BenchmarkConfig("P01-hotspot-standard", "img:jvm", List.of(),
                RuntimeType.HOTSPOT, "Laufzeitprofil", "HotSpot");

        double est = DurationEstimator.estimateSingleRun(cfg, "EBICS_UPLOAD", 700, List.of(exact, other));
        // Should use exact match (120s), not average of both
        assertEquals(120.0, est, 0.1);
    }

    @Test
    void estimateSingleRun_imageMatchFallback() {
        // No exact config match, but same image
        var imageMatch = new DurationEstimator.HistoricalRun(
                "other-config", "img:jvm", "EBICS_UPLOAD", 200, 500, 100.0, 5000, 80.0);

        BenchmarkConfig cfg = new BenchmarkConfig("new-config", "img:jvm", List.of(),
                RuntimeType.HOTSPOT, "Test", "HotSpot");

        double est = DurationEstimator.estimateSingleRun(cfg, "EBICS_UPLOAD", 700, List.of(imageMatch));
        assertEquals(100.0, est, 0.1);
    }

    @Test
    void estimateSingleRun_scenarioMatchFallback() {
        // No config or image match, but same scenario
        var scenarioMatch = new DurationEstimator.HistoricalRun(
                "unrelated", "img:other", "EBICS_UPLOAD", 200, 500, 90.0, 3000, 70.0);

        BenchmarkConfig cfg = new BenchmarkConfig("brand-new", "img:brand-new", List.of(),
                RuntimeType.HOTSPOT, "Test", "HotSpot");

        double est = DurationEstimator.estimateSingleRun(cfg, "EBICS_UPLOAD", 700, List.of(scenarioMatch));
        assertEquals(90.0, est, 0.1);
    }

    @Test
    void estimateSingleRun_fallback_noHistory() {
        BenchmarkConfig cfg = new BenchmarkConfig("brand-new", "img:new", List.of(),
                RuntimeType.HOTSPOT, "Test", "HotSpot");

        double est = DurationEstimator.estimateSingleRun(cfg, "EBICS_UPLOAD", 700, List.of());
        assertEquals(DurationEstimator.FALLBACK_SECONDS, est);
    }

    @Test
    void estimateSingleRun_wrongScenario_notMatched() {
        // History has PAYLOAD_HEAVY_JSON, but we're estimating EBICS_UPLOAD
        var wrongScenario = new DurationEstimator.HistoricalRun(
                "P01-hotspot-standard", "img:jvm", "PAYLOAD_HEAVY_JSON", 200, 500, 30.0, 5000, 20.0);

        BenchmarkConfig cfg = new BenchmarkConfig("P01-hotspot-standard", "img:jvm", List.of(),
                RuntimeType.HOTSPOT, "Test", "HotSpot");

        double est = DurationEstimator.estimateSingleRun(cfg, "EBICS_UPLOAD", 700, List.of(wrongScenario));
        // Should fall through to fallback because scenario doesn't match
        assertEquals(DurationEstimator.FALLBACK_SECONDS, est);
    }

    // ======================== estimate (full plan) ========================

    @Test
    void estimate_fullPlan() {
        var h1 = new DurationEstimator.HistoricalRun(
                "cfg-a", "img:jvm", "EBICS_UPLOAD", 200, 500, 100.0, 5000, 80.0);
        var h2 = new DurationEstimator.HistoricalRun(
                "cfg-b", "img:native", "EBICS_UPLOAD", 200, 500, 60.0, 1000, 50.0);

        BenchmarkPlan plan = new BenchmarkPlan(List.of(
                new BenchmarkConfig("cfg-a", "img:jvm", List.of(), RuntimeType.HOTSPOT, "Test", "HotSpot"),
                new BenchmarkConfig("cfg-b", "img:native", List.of(), RuntimeType.NATIVE, "Test", "Native")
        ));
        MeasurementProfile profile = new MeasurementProfile(200, 500, 1, 0);

        DurationEstimator.Estimate est = DurationEstimator.estimate(plan, BenchmarkScenario.EBICS_UPLOAD,
                profile, 3, List.of(h1, h2));

        assertEquals(2, est.perConfigEstimates().size());
        assertEquals(6, est.totalRuns());
        assertEquals(2, est.historicalRunCount());

        // cfg-a: 100s * 3 reps = 300s; cfg-b: 60s * 3 reps = 180s; total = 480s
        assertEquals(100.0, est.perConfigEstimates().get("cfg-a"), 0.1);
        assertEquals(60.0, est.perConfigEstimates().get("cfg-b"), 0.1);
        assertEquals(480.0, est.totalSeconds(), 0.1);
    }

    @Test
    void estimate_scalingWithDifferentRequestCount() {
        // Historical: 700 requests (200+500), wallClock=120s
        var h = new DurationEstimator.HistoricalRun(
                "cfg-a", "img:jvm", "EBICS_UPLOAD", 200, 500, 120.0, 5000, 100.0);

        BenchmarkPlan plan = new BenchmarkPlan(List.of(
                new BenchmarkConfig("cfg-a", "img:jvm", List.of(), RuntimeType.HOTSPOT, "Test", "HotSpot")
        ));
        // Plan with fewer requests: 50+150 = 200 total
        MeasurementProfile profile = new MeasurementProfile(50, 150, 1, 0);

        DurationEstimator.Estimate est = DurationEstimator.estimate(plan, BenchmarkScenario.EBICS_UPLOAD,
                profile, 1, List.of(h));

        // variable = 120-10 = 110; ratio = 200/700 ≈ 0.2857; scaled = 10 + 110*0.2857 ≈ 41.4
        double expected = DurationEstimator.FIXED_OVERHEAD_SECONDS + (120.0 - DurationEstimator.FIXED_OVERHEAD_SECONDS) * (200.0 / 700.0);
        assertEquals(expected, est.perConfigEstimates().get("cfg-a"), 0.5);
    }
}
