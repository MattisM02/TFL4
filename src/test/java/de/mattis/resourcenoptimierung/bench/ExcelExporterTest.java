package de.mattis.resourcenoptimierung.bench;

import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests fuer ExcelExporter: writeExcel, mergeFromCsvDirectory, parseCsv, parseCsvLine, extractTimestamp.
 */
class ExcelExporterTest {

    @TempDir
    Path tempDir;

    // ======================== Helpers ========================

    private RunResult createSampleResult(String name) {
        return new RunResult(
                name,
                "img:jvm",
                1200L,
                0.25,
                List.of(0.010, 0.012, 0.015, 0.011, 0.020, 0.013, 0.014, 0.016, 0.018, 0.022),
                1.5,
                66.67,
                "-XX:-UseCompressedOops",
                ReadinessCheckUsed.ACTUATOR_READINESS,
                null,
                BenchmarkScenario.PAYLOAD_HEAVY_JSON,
                200000,
                "/json?n=200000",
                MeasurementProfile.defaults(),
                List.of(DockerStatSample.parse("0.12%|151.9MiB / 768MiB|19.78%|4.9kB / 2.93kB|40.9MB / 0B|29")),
                List.of(DockerStatSample.parse("45.5%|280MiB / 768MiB|36.46%|10kB / 5kB|50MB / 1MB|35")),
                List.of(DockerStatSample.parse("2.1%|200MiB / 768MiB|26.04%|12kB / 6kB|55MB / 2MB|30")),
                1,
                null,
                null
        );
    }

    private RunResult createMinimalResult(String name) {
        return new RunResult(
                name,
                "img:jvm",
                500L,
                0.1,
                List.of(0.005, 0.006),
                0.5,
                200.0,
                "",
                ReadinessCheckUsed.ACTUATOR_HEALTH,
                null,
                BenchmarkScenario.ALLOC_HEAVY_OK,
                1000,
                "/alloc?n=1000",
                new MeasurementProfile(5, 50, 2, 100),
                List.of(),
                List.of(),
                List.of(),
                1,
                null,
                null
        );
    }

    private void writeSampleCsv(Path file, String configName, String jvmFlags) throws IOException {
        String header = "scenario,workloadN,workloadPath,configName,dockerImage,effectiveJavaToolOptions,"
                + "readinessCheckUsed,readinessMs,firstSeconds,latencyCount,latencyMean,latencyP50,"
                + "latencyP95,latencyP99,totalMeasureTimeSeconds,throughputReqPerSec,"
                + "warmupRequests,measureRequests,concurrency,sleepBetweenRequestsMs";
        String dataRow = "PAYLOAD_HEAVY_JSON,200000,/json?n=200000," + configName + ",img:jvm,"
                + jvmFlags + ",ACTUATOR_READINESS,1200,0.25,10,0.015,0.012,0.020,0.022,1.5,66.67,20,100,1,0";
        Files.writeString(file, header + "\n" + dataRow + "\n", StandardCharsets.UTF_8);
    }

    // ======================== writeExcel ========================

    @Test
    void writeExcel_createsFile() throws IOException {
        Path xlsx = tempDir.resolve("test.xlsx");
        ExcelExporter.writeExcel(List.of(createSampleResult("baseline")), xlsx);

        assertTrue(Files.exists(xlsx));
        assertTrue(Files.size(xlsx) > 0);
    }

    @Test
    void writeExcel_containsFiveSheets() throws IOException {
        Path xlsx = tempDir.resolve("test.xlsx");
        ExcelExporter.writeExcel(List.of(
                createSampleResult("baseline"),
                createMinimalResult("coops-off")
        ), xlsx);

        try (InputStream is = Files.newInputStream(xlsx);
             XSSFWorkbook wb = new XSSFWorkbook(is)) {
            assertEquals(7, wb.getNumberOfSheets());
            assertEquals("Übersicht", wb.getSheetName(0));
            assertEquals("Latenzen", wb.getSheetName(1));
            assertEquals("Startup & Throughput", wb.getSheetName(2));
            assertEquals("Ressourcen", wb.getSheetName(3));
            assertEquals("GC-Verhalten", wb.getSheetName(4));
            assertEquals("GC-Timeline", wb.getSheetName(5));
            assertEquals("Rohdaten", wb.getSheetName(6));
        }
    }

    @Test
    void writeExcel_uebersichtHasCorrectRowCount() throws IOException {
        Path xlsx = tempDir.resolve("test.xlsx");
        List<RunResult> results = List.of(
                createSampleResult("baseline"),
                createMinimalResult("coops-off")
        );
        ExcelExporter.writeExcel(results, xlsx);

        try (InputStream is = Files.newInputStream(xlsx);
             XSSFWorkbook wb = new XSSFWorkbook(is)) {
            XSSFSheet sheet = wb.getSheet("Übersicht");
            // Row 0 = section headers, Row 1 = column headers, Rows 2..3 = data
            assertEquals(3, sheet.getLastRowNum());
        }
    }

    @Test
    void writeExcel_latenzenHasDataRows() throws IOException {
        Path xlsx = tempDir.resolve("test.xlsx");
        ExcelExporter.writeExcel(List.of(createSampleResult("baseline"), createMinimalResult("alt")), xlsx);

        try (InputStream is = Files.newInputStream(xlsx);
             XSSFWorkbook wb = new XSSFWorkbook(is)) {
            XSSFSheet sheet = wb.getSheet("Latenzen");
            // Row 0 = header, Rows 1..2 = data
            assertEquals(2, sheet.getLastRowNum());
            assertEquals("baseline", sheet.getRow(1).getCell(0).getStringCellValue());
        }
    }

    @Test
    void writeExcel_rohdatenContainsAllLatencies() throws IOException {
        Path xlsx = tempDir.resolve("test.xlsx");
        // 10 latencies in baseline + 2 in minimal = 12 data rows
        ExcelExporter.writeExcel(List.of(createSampleResult("baseline"), createMinimalResult("alt")), xlsx);

        try (InputStream is = Files.newInputStream(xlsx);
             XSSFWorkbook wb = new XSSFWorkbook(is)) {
            XSSFSheet sheet = wb.getSheet("Rohdaten");
            // Row 0 = header, then 10 + 2 = 12 data rows
            assertEquals(12, sheet.getLastRowNum());
        }
    }

    @Test
    void writeExcel_nullResults_noOp() throws IOException {
        Path xlsx = tempDir.resolve("test.xlsx");
        ExcelExporter.writeExcel(null, xlsx);
        assertFalse(Files.exists(xlsx));
    }

    @Test
    void writeExcel_emptyResults_noOp() throws IOException {
        Path xlsx = tempDir.resolve("test.xlsx");
        ExcelExporter.writeExcel(List.of(), xlsx);
        assertFalse(Files.exists(xlsx));
    }

    @Test
    void writeExcel_singleResult_noChart() throws IOException {
        // With only 1 result, charts require >= 2, so no exception should occur
        Path xlsx = tempDir.resolve("test.xlsx");
        ExcelExporter.writeExcel(List.of(createSampleResult("solo")), xlsx);

        try (InputStream is = Files.newInputStream(xlsx);
             XSSFWorkbook wb = new XSSFWorkbook(is)) {
            assertEquals(7, wb.getNumberOfSheets());
        }
    }

    @Test
    void writeExcel_nullDockerSamples_handledGracefully() throws IOException {
        RunResult result = new RunResult(
                "native", "img:native", 200, 0.05, List.of(0.003), 0.3, 333.0,
                null, null, null,
                BenchmarkScenario.PAYLOAD_HEAVY_JSON, 100, "/json?n=100",
                MeasurementProfile.defaults(),
                null, null, null, 1, null, null
        );
        Path xlsx = tempDir.resolve("test.xlsx");
        ExcelExporter.writeExcel(List.of(result), xlsx);
        assertTrue(Files.exists(xlsx));
    }

    // ======================== mergeFromCsvDirectory ========================

    @Test
    void mergeFromCsvDirectory_createsExcel() throws IOException {
        Path csvDir = tempDir.resolve("csvs");
        Files.createDirectory(csvDir);
        writeSampleCsv(csvDir.resolve("results-2026-03-01T10-00-00.000Z.csv"), "baseline", "");
        writeSampleCsv(csvDir.resolve("results-2026-03-02T10-00-00.000Z.csv"), "zgc", "-XX:+UseZGC");

        Path excelOut = tempDir.resolve("merged.xlsx");
        ExcelExporter.mergeFromCsvDirectory(csvDir, excelOut);

        assertTrue(Files.exists(excelOut));
        assertTrue(Files.size(excelOut) > 0);
    }

    @Test
    void mergeFromCsvDirectory_containsFourSheets() throws IOException {
        Path csvDir = tempDir.resolve("csvs");
        Files.createDirectory(csvDir);
        writeSampleCsv(csvDir.resolve("results-2026-03-01T10-00-00.000Z.csv"), "baseline", "");
        writeSampleCsv(csvDir.resolve("results-2026-03-02T10-00-00.000Z.csv"), "zgc", "-XX:+UseZGC");

        Path excelOut = tempDir.resolve("merged.xlsx");
        ExcelExporter.mergeFromCsvDirectory(csvDir, excelOut);

        try (InputStream is = Files.newInputStream(excelOut);
             XSSFWorkbook wb = new XSSFWorkbook(is)) {
            assertEquals(4, wb.getNumberOfSheets());
            assertEquals("Übersicht (alle Runs)", wb.getSheetName(0));
            assertEquals("Latenzen (alle Runs)", wb.getSheetName(1));
            assertEquals("Startup (alle Runs)", wb.getSheetName(2));
            assertEquals("Hinweis Ressourcen", wb.getSheetName(3));
        }
    }

    @Test
    void mergeFromCsvDirectory_mergedOverviewHasAllRows() throws IOException {
        Path csvDir = tempDir.resolve("csvs");
        Files.createDirectory(csvDir);
        writeSampleCsv(csvDir.resolve("results-2026-03-01T10-00-00.000Z.csv"), "baseline", "");
        writeSampleCsv(csvDir.resolve("results-2026-03-02T10-00-00.000Z.csv"), "zgc", "-XX:+UseZGC");

        Path excelOut = tempDir.resolve("merged.xlsx");
        ExcelExporter.mergeFromCsvDirectory(csvDir, excelOut);

        try (InputStream is = Files.newInputStream(excelOut);
             XSSFWorkbook wb = new XSSFWorkbook(is)) {
            XSSFSheet sheet = wb.getSheet("Übersicht (alle Runs)");
            // Row 0 = header, Rows 1..2 = data (one row per CSV)
            assertEquals(2, sheet.getLastRowNum());
        }
    }

    @Test
    void mergeFromCsvDirectory_emptyDir_noExcelCreated() throws IOException {
        Path csvDir = tempDir.resolve("csvs");
        Files.createDirectory(csvDir);
        Path excelOut = tempDir.resolve("merged.xlsx");

        ExcelExporter.mergeFromCsvDirectory(csvDir, excelOut);

        assertFalse(Files.exists(excelOut));
    }

    @Test
    void mergeFromCsvDirectory_ignoresNonCsvFiles() throws IOException {
        Path csvDir = tempDir.resolve("csvs");
        Files.createDirectory(csvDir);
        writeSampleCsv(csvDir.resolve("results-2026-03-01T10-00-00.000Z.csv"), "baseline", "");
        Files.writeString(csvDir.resolve("notes.txt"), "not a csv", StandardCharsets.UTF_8);
        Files.writeString(csvDir.resolve("data.json"), "{}", StandardCharsets.UTF_8);

        Path excelOut = tempDir.resolve("merged.xlsx");
        ExcelExporter.mergeFromCsvDirectory(csvDir, excelOut);

        try (InputStream is = Files.newInputStream(excelOut);
             XSSFWorkbook wb = new XSSFWorkbook(is)) {
            XSSFSheet sheet = wb.getSheet("Übersicht (alle Runs)");
            assertEquals(1, sheet.getLastRowNum()); // 1 data row
        }
    }

    // ======================== parseCsv ========================

    @Test
    void parseCsv_parsesSingleRow() throws IOException {
        Path csv = tempDir.resolve("test.csv");
        writeSampleCsv(csv, "baseline", "-XX:+UseZGC");

        List<ExcelExporter.CsvRow> rows = ExcelExporter.parseCsv(csv, "2026-03-01 10:00:00");

        assertEquals(1, rows.size());
        ExcelExporter.CsvRow row = rows.get(0);
        assertEquals("2026-03-01 10:00:00", row.timestamp());
        assertEquals("baseline", row.configName());
        assertEquals("PAYLOAD_HEAVY_JSON", row.scenario());
        assertEquals("-XX:+UseZGC", row.jvmFlags());
        assertEquals(200000, row.workloadN());
        assertEquals(1200.0, row.readinessMs(), 0.01);
        assertEquals(0.25, row.firstSeconds(), 0.001);
        assertEquals(0.012, row.p50(), 0.001);
        assertEquals(0.020, row.p95(), 0.001);
        assertEquals(0.022, row.p99(), 0.001);
        assertEquals(66.67, row.throughput(), 0.01);
        assertEquals(20, row.warmup());
        assertEquals(100, row.measureReqs());
        assertEquals(1, row.concurrency());
        assertEquals(0, row.sleepMs());
    }

    @Test
    void parseCsv_emptyFile_returnsEmpty() throws IOException {
        Path csv = tempDir.resolve("empty.csv");
        Files.writeString(csv, "", StandardCharsets.UTF_8);

        List<ExcelExporter.CsvRow> rows = ExcelExporter.parseCsv(csv, "ts");
        assertTrue(rows.isEmpty());
    }

    @Test
    void parseCsv_headerOnly_returnsEmpty() throws IOException {
        Path csv = tempDir.resolve("headeronly.csv");
        Files.writeString(csv, "scenario,configName,readinessMs\n", StandardCharsets.UTF_8);

        List<ExcelExporter.CsvRow> rows = ExcelExporter.parseCsv(csv, "ts");
        assertTrue(rows.isEmpty());
    }

    @Test
    void parseCsv_multipleRows() throws IOException {
        Path csv = tempDir.resolve("multi.csv");
        String header = "scenario,workloadN,workloadPath,configName,dockerImage,effectiveJavaToolOptions,"
                + "readinessCheckUsed,readinessMs,firstSeconds,latencyCount,latencyMean,latencyP50,"
                + "latencyP95,latencyP99,totalMeasureTimeSeconds,throughputReqPerSec,"
                + "warmupRequests,measureRequests,concurrency,sleepBetweenRequestsMs";
        String row1 = "ALLOC_HEAVY_OK,1000,/alloc?n=1000,baseline,img:jvm,,ACTUATOR_HEALTH,500,0.1,5,0.01,0.008,0.015,0.02,0.5,200,10,50,2,50";
        String row2 = "ALLOC_HEAVY_OK,1000,/alloc?n=1000,zgc,img:jvm,-XX:+UseZGC,ACTUATOR_HEALTH,600,0.12,5,0.011,0.009,0.016,0.021,0.55,180,10,50,2,50";
        Files.writeString(csv, header + "\n" + row1 + "\n" + row2 + "\n", StandardCharsets.UTF_8);

        List<ExcelExporter.CsvRow> rows = ExcelExporter.parseCsv(csv, "ts");
        assertEquals(2, rows.size());
        assertEquals("baseline", rows.get(0).configName());
        assertEquals("zgc", rows.get(1).configName());
    }

    // ======================== extractTimestamp ========================

    @Test
    void extractTimestamp_standardFilename() {
        String ts = ExcelExporter.extractTimestamp("results-2026-03-05T11-49-59.609588200Z.csv");
        assertEquals("2026-03-05 11:49:59", ts);
    }

    @Test
    void extractTimestamp_noFractionalSeconds() {
        String ts = ExcelExporter.extractTimestamp("results-2026-01-15T08-30-00.csv");
        assertEquals("2026-01-15 08:30:00", ts);
    }

    @Test
    void extractTimestamp_noTimePart() {
        String ts = ExcelExporter.extractTimestamp("results-2026-03-05.csv");
        // No T separator, just returns date as-is
        assertEquals("2026-03-05", ts);
    }

    // ======================== parseCsvLine (via parseCsv) ========================

    @Test
    void parseCsv_quotedFieldsWithCommas() throws IOException {
        Path csv = tempDir.resolve("quoted.csv");
        // effectiveJavaToolOptions field contains a quoted value with commas
        String header = "scenario,workloadN,workloadPath,configName,dockerImage,effectiveJavaToolOptions,"
                + "readinessCheckUsed,readinessMs,firstSeconds,latencyCount,latencyMean,latencyP50,"
                + "latencyP95,latencyP99,totalMeasureTimeSeconds,throughputReqPerSec,"
                + "warmupRequests,measureRequests,concurrency,sleepBetweenRequestsMs";
        // Quoted value with commas inside
        String dataRow = "PAYLOAD_HEAVY_JSON,200000,/json?n=200000,test,img:jvm,\"-XX:+UseZGC, -Xmx1g\","
                + "ACTUATOR_READINESS,1000,0.2,10,0.01,0.008,0.015,0.02,1.0,100,20,100,1,0";
        Files.writeString(csv, header + "\n" + dataRow + "\n", StandardCharsets.UTF_8);

        List<ExcelExporter.CsvRow> rows = ExcelExporter.parseCsv(csv, "ts");
        assertEquals(1, rows.size());
        assertEquals("-XX:+UseZGC, -Xmx1g", rows.get(0).jvmFlags());
    }
}
