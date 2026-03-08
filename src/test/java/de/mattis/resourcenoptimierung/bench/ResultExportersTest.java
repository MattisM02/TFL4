package de.mattis.resourcenoptimierung.bench;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests fuer ResultExporters: CSV- und JSON-Export mit temporaeren Dateien.
 */
class ResultExportersTest {

    @TempDir
    Path tempDir;

    private RunResult createSampleResult(String name, BenchmarkScenario scenario) {
        return RunResult.of(
                name,
                "img:jvm",
                1200L,
                0.25,
                List.of(0.010, 0.012, 0.015, 0.011, 0.020),
                1.5,
                66.67,
                "-XX:-UseCompressedOops",
                ReadinessCheckUsed.ACTUATOR_READINESS,
                null,
                scenario,
                200000,
                "/json?n=200000",
                MeasurementProfile.defaults(),
                List.of(),
                List.of(),
                List.of(),
                1,
                null,
                null,
                null,
                null
        );
    }

    // ==================== CSV ====================

    @Test
    void writeCsv_createsFile() throws IOException {
        Path csvPath = tempDir.resolve("test.csv");
        List<RunResult> results = List.of(createSampleResult("baseline", BenchmarkScenario.PAYLOAD_HEAVY_JSON));

        ResultExporters.writeCsv(results, csvPath);

        assertTrue(Files.exists(csvPath));
        assertTrue(Files.size(csvPath) > 0);
    }

    @Test
    void writeCsv_headerContainsExpectedColumns() throws IOException {
        Path csvPath = tempDir.resolve("test.csv");
        ResultExporters.writeCsv(List.of(createSampleResult("test", BenchmarkScenario.PAYLOAD_HEAVY_JSON)), csvPath);

        List<String> lines = Files.readAllLines(csvPath);
        assertFalse(lines.isEmpty());

        String header = lines.get(0);
        assertTrue(header.contains("scenario"));
        assertTrue(header.contains("configName"));
        assertTrue(header.contains("readinessMs"));
        assertTrue(header.contains("latencyP50"));
        assertTrue(header.contains("latencyP95"));
        assertTrue(header.contains("latencyP99"));
        assertTrue(header.contains("totalMeasureTimeSeconds"));
        assertTrue(header.contains("throughputReqPerSec"));
        assertTrue(header.contains("warmupRequests"));
        assertTrue(header.contains("measureRequests"));
        assertTrue(header.contains("concurrency"));
        assertTrue(header.contains("sleepBetweenRequestsMs"));
        assertTrue(header.contains("repetition"));
    }

    @Test
    void writeCsv_dataRowContainsValues() throws IOException {
        Path csvPath = tempDir.resolve("test.csv");
        ResultExporters.writeCsv(List.of(createSampleResult("baseline", BenchmarkScenario.PAYLOAD_HEAVY_JSON)), csvPath);

        List<String> lines = Files.readAllLines(csvPath);
        assertEquals(2, lines.size()); // header + 1 data row

        String data = lines.get(1);
        assertTrue(data.contains("PAYLOAD_HEAVY_JSON"));
        assertTrue(data.contains("baseline"));
        assertTrue(data.contains("1200"));
        assertTrue(data.contains("200000"));
    }

    @Test
    void writeCsv_multipleResults() throws IOException {
        Path csvPath = tempDir.resolve("test.csv");
        List<RunResult> results = List.of(
                createSampleResult("baseline", BenchmarkScenario.PAYLOAD_HEAVY_JSON),
                createSampleResult("coops-off", BenchmarkScenario.PAYLOAD_HEAVY_JSON)
        );

        ResultExporters.writeCsv(results, csvPath);

        List<String> lines = Files.readAllLines(csvPath);
        assertEquals(3, lines.size()); // header + 2 data rows
    }

    @Test
    void writeCsv_emptyResults() throws IOException {
        Path csvPath = tempDir.resolve("test.csv");
        ResultExporters.writeCsv(List.of(), csvPath);

        List<String> lines = Files.readAllLines(csvPath);
        assertEquals(1, lines.size()); // header only
    }

    @Test
    void writeCsv_profileValuesInOutput() throws IOException {
        Path csvPath = tempDir.resolve("test.csv");
        RunResult result = RunResult.of(
                "custom", "img:jvm", 500, 0.1, List.of(0.01), 0.5, 200.0,
                "", ReadinessCheckUsed.ACTUATOR_HEALTH, null,
                BenchmarkScenario.ALLOC_HEAVY_OK, 1000, "/alloc?n=1000",
                new MeasurementProfile(5, 50, 4, 100),
                List.of(), List.of(), List.of(), 1, null, null, null, null
        );

        ResultExporters.writeCsv(List.of(result), csvPath);

        String content = Files.readString(csvPath);
        // Last four values in CSV should be profile: 5,50,4,100
        assertTrue(content.contains("5,50,4,100"));
    }

    // ==================== JSON ====================

    @Test
    void writeJson_createsFile() throws IOException {
        Path jsonPath = tempDir.resolve("test.json");
        ResultExporters.writeJson(List.of(createSampleResult("baseline", BenchmarkScenario.PAYLOAD_HEAVY_JSON)), jsonPath);

        assertTrue(Files.exists(jsonPath));
        assertTrue(Files.size(jsonPath) > 0);
    }

    @Test
    void writeJson_containsExpectedFields() throws IOException {
        Path jsonPath = tempDir.resolve("test.json");
        ResultExporters.writeJson(List.of(createSampleResult("baseline", BenchmarkScenario.PAYLOAD_HEAVY_JSON)), jsonPath);

        String json = Files.readString(jsonPath);
        assertTrue(json.contains("\"results\":["));
        assertTrue(json.contains("\"configName\":\"baseline\""));
        assertTrue(json.contains("\"scenario\":\"PAYLOAD_HEAVY_JSON\""));
        assertTrue(json.contains("\"readinessMs\":1200"));
        assertTrue(json.contains("\"totalMeasureTimeSeconds\":"));
        assertTrue(json.contains("\"throughputReqPerSec\":"));
        assertTrue(json.contains("\"latenciesSeconds\":["));
        assertTrue(json.contains("\"measurementProfile\":{"));
        assertTrue(json.contains("\"warmupRequests\":200"));
        assertTrue(json.contains("\"measureRequests\":500"));
        assertTrue(json.contains("\"repetition\":1"));
    }

    @Test
    void writeJson_multipleResults() throws IOException {
        Path jsonPath = tempDir.resolve("test.json");
        List<RunResult> results = List.of(
                createSampleResult("baseline", BenchmarkScenario.PAYLOAD_HEAVY_JSON),
                createSampleResult("coops-off", BenchmarkScenario.ALLOC_HEAVY_OK)
        );

        ResultExporters.writeJson(results, jsonPath);

        String json = Files.readString(jsonPath);
        assertTrue(json.contains("\"configName\":\"baseline\""));
        assertTrue(json.contains("\"configName\":\"coops-off\""));
    }

    @Test
    void writeJson_emptyResults() throws IOException {
        Path jsonPath = tempDir.resolve("test.json");
        ResultExporters.writeJson(List.of(), jsonPath);

        String json = Files.readString(jsonPath);
        assertEquals("{\"results\":[]}", json);
    }

    @Test
    void writeJson_nullEffectiveFlags_writesNull() throws IOException {
        Path jsonPath = tempDir.resolve("test.json");
        RunResult result = RunResult.of(
                "native", "img:native", 200, 0.05, List.of(0.003), 0.3, 333.0,
                null, null, null,
                BenchmarkScenario.PAYLOAD_HEAVY_JSON, 100, "/json?n=100",
                MeasurementProfile.defaults(),
                null, null, null, 1, null, null, null, null
        );

        ResultExporters.writeJson(List.of(result), jsonPath);

        String json = Files.readString(jsonPath);
        assertTrue(json.contains("\"effectiveJavaToolOptions\":null"));
        assertTrue(json.contains("\"readinessCheckUsed\":null"));
    }

    // ==================== appendCsvRow ====================

    @Test
    void appendCsvRow_createsFileWithHeaderOnFirstCall() throws IOException {
        Path csvPath = tempDir.resolve("incremental.csv");
        RunResult result = createSampleResult("baseline", BenchmarkScenario.PAYLOAD_HEAVY_JSON);

        ResultExporters.appendCsvRow(csvPath, result);

        assertTrue(Files.exists(csvPath));
        List<String> lines = Files.readAllLines(csvPath);
        assertEquals(2, lines.size(), "Should have header + 1 data row");
        assertTrue(lines.get(0).contains("scenario"));
        assertTrue(lines.get(0).contains("configName"));
        assertTrue(lines.get(1).contains("baseline"));
    }

    @Test
    void appendCsvRow_appendsWithoutDuplicateHeader() throws IOException {
        Path csvPath = tempDir.resolve("incremental.csv");
        RunResult r1 = createSampleResult("baseline", BenchmarkScenario.PAYLOAD_HEAVY_JSON);
        RunResult r2 = createSampleResult("zgc", BenchmarkScenario.PAYLOAD_HEAVY_JSON);

        ResultExporters.appendCsvRow(csvPath, r1);
        ResultExporters.appendCsvRow(csvPath, r2);

        List<String> lines = Files.readAllLines(csvPath);
        assertEquals(3, lines.size(), "Should have header + 2 data rows");
        assertTrue(lines.get(0).contains("scenario"), "First line should be header");
        assertTrue(lines.get(1).contains("baseline"));
        assertTrue(lines.get(2).contains("zgc"));
    }

    @Test
    void appendCsvRow_matchesWriteCsvOutput() throws IOException {
        RunResult r1 = createSampleResult("baseline", BenchmarkScenario.PAYLOAD_HEAVY_JSON);
        RunResult r2 = createSampleResult("zgc", BenchmarkScenario.PAYLOAD_HEAVY_JSON);

        // Write via batch method
        Path batchCsv = tempDir.resolve("batch.csv");
        ResultExporters.writeCsv(List.of(r1, r2), batchCsv);

        // Write via incremental method
        Path incrCsv = tempDir.resolve("incr.csv");
        ResultExporters.appendCsvRow(incrCsv, r1);
        ResultExporters.appendCsvRow(incrCsv, r2);

        // Both files should be identical
        String batchContent = Files.readString(batchCsv);
        String incrContent = Files.readString(incrCsv);
        assertEquals(batchContent, incrContent, "Incremental append should produce identical output to batch write");
    }
}
