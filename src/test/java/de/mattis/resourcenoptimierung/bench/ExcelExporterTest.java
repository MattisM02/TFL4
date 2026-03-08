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
                + "warmupRequests,measureRequests,concurrency,sleepBetweenRequestsMs,"
                + "cpuLoadAvg,memLoadAvg,memLoadMax,"
                + "gcCount,gcFullCount,gcTotalPauseMs,gcMaxPauseMs,gcOverheadPercent,gcPeakHeapAfterMb,"
                + "repetition";
        String dataRow = "PAYLOAD_HEAVY_JSON,200000,/json?n=200000," + configName + ",img:jvm,"
                + jvmFlags + ",ACTUATOR_READINESS,1200,0.25,10,0.015,0.012,0.020,0.022,1.5,66.67,20,100,1,0,"
                + "42.5,35.2,38.1,120,2,1658.3,45.7,1.85,256.0,1";
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
    void mergeFromCsvDirectory_containsSixSheets() throws IOException {
        Path csvDir = tempDir.resolve("csvs");
        Files.createDirectory(csvDir);
        writeSampleCsv(csvDir.resolve("results-2026-03-01T10-00-00.000Z.csv"), "baseline", "");
        writeSampleCsv(csvDir.resolve("results-2026-03-02T10-00-00.000Z.csv"), "zgc", "-XX:+UseZGC");

        Path excelOut = tempDir.resolve("merged.xlsx");
        ExcelExporter.mergeFromCsvDirectory(csvDir, excelOut);

        try (InputStream is = Files.newInputStream(excelOut);
             XSSFWorkbook wb = new XSSFWorkbook(is)) {
            assertEquals(6, wb.getNumberOfSheets());
            assertEquals("Übersicht (alle Runs)", wb.getSheetName(0));
            assertEquals("Latenzen (alle Runs)", wb.getSheetName(1));
            assertEquals("Startup (alle Runs)", wb.getSheetName(2));
            assertEquals("Ressourcen (alle Runs)", wb.getSheetName(3));
            assertEquals("Zusammenfassung", wb.getSheetName(4));
            assertEquals("Ranking", wb.getSheetName(5));
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

    // ======================== Aggregation (Fix 4) ========================

    @Test
    void writeExcel_latenzenAggregatesRepetitions() throws IOException {
        Path xlsx = tempDir.resolve("test-agg.xlsx");
        // Two repetitions of "baseline" + one "alt" → should produce 2 rows (not 3)
        ExcelExporter.writeExcel(List.of(
                createSampleResult("baseline"),
                createSampleResult("baseline"),
                createMinimalResult("alt")
        ), xlsx);

        try (InputStream is = Files.newInputStream(xlsx);
             XSSFWorkbook wb = new XSSFWorkbook(is)) {
            XSSFSheet sheet = wb.getSheet("Latenzen");
            // Row 0 = header, Rows 1..2 = data (2 configs, not 3 results)
            assertEquals(2, sheet.getLastRowNum());
            assertEquals("baseline", sheet.getRow(1).getCell(0).getStringCellValue());
            assertEquals("alt", sheet.getRow(2).getCell(0).getStringCellValue());
        }
    }

    @Test
    void writeExcel_startupAggregatesRepetitions() throws IOException {
        Path xlsx = tempDir.resolve("test-agg2.xlsx");
        ExcelExporter.writeExcel(List.of(
                createSampleResult("baseline"),
                createSampleResult("baseline"),
                createMinimalResult("alt")
        ), xlsx);

        try (InputStream is = Files.newInputStream(xlsx);
             XSSFWorkbook wb = new XSSFWorkbook(is)) {
            XSSFSheet sheet = wb.getSheet("Startup & Throughput");
            assertEquals(2, sheet.getLastRowNum());
        }
    }

    @Test
    void writeExcel_ressourcenAggregatesRepetitions() throws IOException {
        Path xlsx = tempDir.resolve("test-agg3.xlsx");
        ExcelExporter.writeExcel(List.of(
                createSampleResult("baseline"),
                createSampleResult("baseline"),
                createMinimalResult("alt")
        ), xlsx);

        try (InputStream is = Files.newInputStream(xlsx);
             XSSFWorkbook wb = new XSSFWorkbook(is)) {
            XSSFSheet sheet = wb.getSheet("Ressourcen");
            assertEquals(2, sheet.getLastRowNum());
        }
    }

    @Test
    void writeExcel_gcVerhaltenAggregatesRepetitions() throws IOException {
        Path xlsx = tempDir.resolve("test-agg4.xlsx");
        ExcelExporter.writeExcel(List.of(
                createSampleResult("baseline"),
                createSampleResult("baseline"),
                createMinimalResult("alt")
        ), xlsx);

        try (InputStream is = Files.newInputStream(xlsx);
             XSSFWorkbook wb = new XSSFWorkbook(is)) {
            XSSFSheet sheet = wb.getSheet("GC-Verhalten");
            assertEquals(2, sheet.getLastRowNum());
        }
    }

    @Test
    void writeExcel_rohdatenKeepsAllRows() throws IOException {
        Path xlsx = tempDir.resolve("test-agg5.xlsx");
        // 2x baseline (10 latencies each) + 1x alt (2 latencies) = 22 raw rows
        ExcelExporter.writeExcel(List.of(
                createSampleResult("baseline"),
                createSampleResult("baseline"),
                createMinimalResult("alt")
        ), xlsx);

        try (InputStream is = Files.newInputStream(xlsx);
             XSSFWorkbook wb = new XSSFWorkbook(is)) {
            XSSFSheet sheet = wb.getSheet("Rohdaten");
            assertEquals(22, sheet.getLastRowNum());
        }
    }

    @Test
    void writeExcel_uebersichtKeepsAllRows() throws IOException {
        Path xlsx = tempDir.resolve("test-agg6.xlsx");
        // Uebersicht should NOT aggregate – it shows raw per-repetition data
        ExcelExporter.writeExcel(List.of(
                createSampleResult("baseline"),
                createSampleResult("baseline"),
                createMinimalResult("alt")
        ), xlsx);

        try (InputStream is = Files.newInputStream(xlsx);
             XSSFWorkbook wb = new XSSFWorkbook(is)) {
            XSSFSheet sheet = wb.getSheet("Übersicht");
            // Row 0 = section headers, Row 1 = column headers, Rows 2..4 = data (3 results)
            assertEquals(4, sheet.getLastRowNum());
        }
    }

    @Test
    void mergeFromCsvDirectory_latencyChartAggregatesByConfig() throws IOException {
        Path csvDir = tempDir.resolve("csvs-lat-agg");
        Files.createDirectory(csvDir);
        // Two CSVs for "baseline" → should produce 1 aggregated row, + 1 for "zgc"
        writeSampleCsv(csvDir.resolve("results-2026-03-01T10-00-00.000Z.csv"), "baseline", "");
        writeSampleCsv(csvDir.resolve("results-2026-03-02T10-00-00.000Z.csv"), "baseline", "");
        writeSampleCsv(csvDir.resolve("results-2026-03-03T10-00-00.000Z.csv"), "zgc", "-XX:+UseZGC");

        Path excelOut = tempDir.resolve("merged-lat-agg.xlsx");
        ExcelExporter.mergeFromCsvDirectory(csvDir, excelOut);

        try (InputStream is = Files.newInputStream(excelOut);
             XSSFWorkbook wb = new XSSFWorkbook(is)) {
            XSSFSheet sheet = wb.getSheet("Latenzen (alle Runs)");
            // 2 config groups, not 3 raw rows
            assertEquals(2, sheet.getLastRowNum());
            assertEquals("baseline", sheet.getRow(1).getCell(0).getStringCellValue());
            assertEquals("zgc", sheet.getRow(2).getCell(0).getStringCellValue());
        }
    }

    @Test
    void mergeFromCsvDirectory_overviewKeepsAllRows() throws IOException {
        Path csvDir = tempDir.resolve("csvs-ov-all");
        Files.createDirectory(csvDir);
        writeSampleCsv(csvDir.resolve("results-2026-03-01T10-00-00.000Z.csv"), "baseline", "");
        writeSampleCsv(csvDir.resolve("results-2026-03-02T10-00-00.000Z.csv"), "baseline", "");
        writeSampleCsv(csvDir.resolve("results-2026-03-03T10-00-00.000Z.csv"), "zgc", "-XX:+UseZGC");

        Path excelOut = tempDir.resolve("merged-ov-all.xlsx");
        ExcelExporter.mergeFromCsvDirectory(csvDir, excelOut);

        try (InputStream is = Files.newInputStream(excelOut);
             XSSFWorkbook wb = new XSSFWorkbook(is)) {
            XSSFSheet sheet = wb.getSheet("Übersicht (alle Runs)");
            // Overview keeps all 3 raw rows
            assertEquals(3, sheet.getLastRowNum());
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
        assertEquals(42.5, row.cpuLoadAvg(), 0.01);
        assertEquals(35.2, row.memLoadAvg(), 0.01);
        assertEquals(38.1, row.memLoadMax(), 0.01);
        assertEquals(120, row.gcCount());
        assertEquals(2, row.gcFullCount());
        assertEquals(1658.3, row.gcTotalPauseMs(), 0.1);
        assertEquals(45.7, row.gcMaxPauseMs(), 0.1);
        assertEquals(1.85, row.gcOverheadPercent(), 0.01);
        assertEquals(256.0, row.gcPeakHeapAfterMb(), 0.1);
        assertEquals(1, row.repetition());
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
                + "warmupRequests,measureRequests,concurrency,sleepBetweenRequestsMs,"
                + "cpuLoadAvg,memLoadAvg,memLoadMax,"
                + "gcCount,gcFullCount,gcTotalPauseMs,gcMaxPauseMs,gcOverheadPercent,gcPeakHeapAfterMb,"
                + "repetition";
        String row1 = "ALLOC_HEAVY_OK,1000,/alloc?n=1000,baseline,img:jvm,,ACTUATOR_HEALTH,500,0.1,5,0.01,0.008,0.015,0.02,0.5,200,10,50,2,50,"
                + "30.0,25.0,28.0,80,0,500.0,20.0,1.2,128.0,1";
        String row2 = "ALLOC_HEAVY_OK,1000,/alloc?n=1000,zgc,img:jvm,-XX:+UseZGC,ACTUATOR_HEALTH,600,0.12,5,0.011,0.009,0.016,0.021,0.55,180,10,50,2,50,"
                + "35.0,28.0,32.0,60,1,400.0,15.0,0.9,150.0,1";
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
                + "warmupRequests,measureRequests,concurrency,sleepBetweenRequestsMs,"
                + "cpuLoadAvg,memLoadAvg,memLoadMax,"
                + "gcCount,gcFullCount,gcTotalPauseMs,gcMaxPauseMs,gcOverheadPercent,gcPeakHeapAfterMb,"
                + "repetition";
        // Quoted value with commas inside
        String dataRow = "PAYLOAD_HEAVY_JSON,200000,/json?n=200000,test,img:jvm,\"-XX:+UseZGC, -Xmx1g\","
                + "ACTUATOR_READINESS,1000,0.2,10,0.01,0.008,0.015,0.02,1.0,100,20,100,1,0,"
                + "40.0,30.0,35.0,100,1,800.0,30.0,1.5,200.0,1";
        Files.writeString(csv, header + "\n" + dataRow + "\n", StandardCharsets.UTF_8);

        List<ExcelExporter.CsvRow> rows = ExcelExporter.parseCsv(csv, "ts");
        assertEquals(1, rows.size());
        assertEquals("-XX:+UseZGC, -Xmx1g", rows.get(0).jvmFlags());
    }

    // ======================== Zusammenfassung (Aggregation) ========================

    @Test
    void mergeFromCsvDirectory_zusammenfassungGroupsByConfig() throws IOException {
        Path csvDir = tempDir.resolve("csvs-agg");
        Files.createDirectory(csvDir);
        // Zwei CSVs fuer "baseline" (simuliert 2 Repetitionen)
        writeSampleCsv(csvDir.resolve("results-2026-03-01T10-00-00.000Z.csv"), "baseline", "");
        writeSampleCsv(csvDir.resolve("results-2026-03-02T10-00-00.000Z.csv"), "baseline", "");
        writeSampleCsv(csvDir.resolve("results-2026-03-03T10-00-00.000Z.csv"), "zgc", "-XX:+UseZGC");

        Path excelOut = tempDir.resolve("merged-agg.xlsx");
        ExcelExporter.mergeFromCsvDirectory(csvDir, excelOut);

        try (InputStream is = Files.newInputStream(excelOut);
             XSSFWorkbook wb = new XSSFWorkbook(is)) {
            XSSFSheet sheet = wb.getSheet("Zusammenfassung");
            assertNotNull(sheet, "Zusammenfassung sheet must exist");
            // 2 config groups: baseline (n=2), zgc (n=1)
            assertEquals(2, sheet.getLastRowNum()); // row 0=header, rows 1-2=data
            // Check baseline n=2
            assertEquals(2.0, sheet.getRow(1).getCell(1).getNumericCellValue(), 0.01);
            // Check zgc n=1
            assertEquals(1.0, sheet.getRow(2).getCell(1).getNumericCellValue(), 0.01);
        }
    }

    @Test
    void mergeFromCsvDirectory_zusammenfassungHasMeanColumns() throws IOException {
        Path csvDir = tempDir.resolve("csvs-agg2");
        Files.createDirectory(csvDir);
        writeSampleCsv(csvDir.resolve("results-2026-03-01T10-00-00.000Z.csv"), "baseline", "");
        writeSampleCsv(csvDir.resolve("results-2026-03-02T10-00-00.000Z.csv"), "zgc", "-XX:+UseZGC");

        Path excelOut = tempDir.resolve("merged-agg2.xlsx");
        ExcelExporter.mergeFromCsvDirectory(csvDir, excelOut);

        try (InputStream is = Files.newInputStream(excelOut);
             XSSFWorkbook wb = new XSSFWorkbook(is)) {
            XSSFSheet sheet = wb.getSheet("Zusammenfassung");
            // Header row should contain "Readiness (ms) Mean"
            String firstMetricHeader = sheet.getRow(0).getCell(2).getStringCellValue();
            assertTrue(firstMetricHeader.contains("Readiness"), "First metric should be Readiness");
            assertTrue(firstMetricHeader.contains("Mean"), "Should contain Mean");
            // Readiness mean for baseline = 1200.0
            assertEquals(1200.0, sheet.getRow(1).getCell(2).getNumericCellValue(), 0.1);
        }
    }

    // ======================== Ranking ========================

    @Test
    void mergeFromCsvDirectory_rankingShowsRelativeValues() throws IOException {
        Path csvDir = tempDir.resolve("csvs-rank");
        Files.createDirectory(csvDir);
        writeSampleCsv(csvDir.resolve("results-2026-03-01T10-00-00.000Z.csv"), "baseline", "");
        writeSampleCsv(csvDir.resolve("results-2026-03-02T10-00-00.000Z.csv"), "zgc", "-XX:+UseZGC");

        Path excelOut = tempDir.resolve("merged-rank.xlsx");
        ExcelExporter.mergeFromCsvDirectory(csvDir, excelOut);

        try (InputStream is = Files.newInputStream(excelOut);
             XSSFWorkbook wb = new XSSFWorkbook(is)) {
            XSSFSheet sheet = wb.getSheet("Ranking");
            assertNotNull(sheet, "Ranking sheet must exist");
            // 2 configs
            assertEquals(2, sheet.getLastRowNum());
            // Baseline should have 100% relative value (col 3 = Readiness rel.)
            assertEquals(100.0, sheet.getRow(1).getCell(3).getNumericCellValue(), 0.01);
        }
    }

    @Test
    void mergeFromCsvDirectory_rankingBaselineIsFirst() throws IOException {
        Path csvDir = tempDir.resolve("csvs-rank2");
        Files.createDirectory(csvDir);
        writeSampleCsv(csvDir.resolve("results-2026-03-01T10-00-00.000Z.csv"), "01-baseline", "");
        writeSampleCsv(csvDir.resolve("results-2026-03-02T10-00-00.000Z.csv"), "02-zgc", "-XX:+UseZGC");

        Path excelOut = tempDir.resolve("merged-rank2.xlsx");
        ExcelExporter.mergeFromCsvDirectory(csvDir, excelOut);

        try (InputStream is = Files.newInputStream(excelOut);
             XSSFWorkbook wb = new XSSFWorkbook(is)) {
            XSSFSheet sheet = wb.getSheet("Ranking");
            // First data row should be the baseline
            assertEquals("01-baseline", sheet.getRow(1).getCell(0).getStringCellValue());
            // All relative values for baseline should be 100%
            // col 3 = first rel. column (Readiness rel.)
            assertEquals(100.0, sheet.getRow(1).getCell(3).getNumericCellValue(), 0.01);
        }
    }

    // ======================== inferRuntimeType ========================

    @Test
    void inferRuntimeType_hotspotDefault() {
        assertEquals(RuntimeType.HOTSPOT, ExcelExporter.inferRuntimeType("tfl4-ek-bench:jvm"));
    }

    @Test
    void inferRuntimeType_openj9() {
        assertEquals(RuntimeType.OPENJ9, ExcelExporter.inferRuntimeType("tfl4-ek-bench:openj9"));
    }

    @Test
    void inferRuntimeType_semeru() {
        assertEquals(RuntimeType.OPENJ9, ExcelExporter.inferRuntimeType("ibm-semeru-runtimes:25-jre"));
    }

    @Test
    void inferRuntimeType_native() {
        assertEquals(RuntimeType.NATIVE, ExcelExporter.inferRuntimeType("tfl4-ek-bench:native"));
    }

    @Test
    void inferRuntimeType_graalvmNative() {
        assertEquals(RuntimeType.NATIVE, ExcelExporter.inferRuntimeType("ghcr.io/graalvm/graalvm-native:latest"));
    }

    @Test
    void inferRuntimeType_null() {
        assertEquals(RuntimeType.HOTSPOT, ExcelExporter.inferRuntimeType(null));
    }

    @Test
    void inferRuntimeType_caseInsensitive() {
        assertEquals(RuntimeType.OPENJ9, ExcelExporter.inferRuntimeType("IMG:OPENJ9-EK"));
        assertEquals(RuntimeType.NATIVE, ExcelExporter.inferRuntimeType("IMG:NATIVE-EK"));
    }

    // ======================== Runtime Color-Coding (end-to-end) ========================

    private RunResult createResultWithImage(String name, String dockerImage) {
        return new RunResult(
                name,
                dockerImage,
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

    @Test
    void writeExcel_multiRuntime_noException() throws IOException {
        // Three configs with different runtimes should trigger runtime coloring
        Path xlsx = tempDir.resolve("test-multiruntime.xlsx");
        ExcelExporter.writeExcel(List.of(
                createResultWithImage("P01-hotspot", "tfl4-ek-bench:jvm"),
                createResultWithImage("P04-openj9", "tfl4-ek-bench:openj9"),
                createResultWithImage("P05-native", "tfl4-ek-bench:native")
        ), xlsx);

        assertTrue(Files.exists(xlsx));
        try (InputStream is = Files.newInputStream(xlsx);
             XSSFWorkbook wb = new XSSFWorkbook(is)) {
            assertEquals(7, wb.getNumberOfSheets());
            // Verify aggregated chart data rows exist
            XSSFSheet latenzen = wb.getSheet("Latenzen");
            assertEquals(3, latenzen.getLastRowNum()); // 3 configs
        }
    }

    @Test
    void writeExcel_singleRuntime_noColoringNoException() throws IOException {
        // All same runtime — applyRuntimeColors should be a no-op (distinct count <= 1)
        Path xlsx = tempDir.resolve("test-singleruntime.xlsx");
        ExcelExporter.writeExcel(List.of(
                createResultWithImage("baseline", "tfl4-ek-bench:jvm"),
                createResultWithImage("tuned", "tfl4-ek-bench:jvm")
        ), xlsx);

        assertTrue(Files.exists(xlsx));
        try (InputStream is = Files.newInputStream(xlsx);
             XSSFWorkbook wb = new XSSFWorkbook(is)) {
            assertEquals(7, wb.getNumberOfSheets());
        }
    }

    private void writeSampleCsvWithImage(Path file, String configName, String dockerImage, String jvmFlags)
            throws IOException {
        String header = "scenario,workloadN,workloadPath,configName,dockerImage,effectiveJavaToolOptions,"
                + "readinessCheckUsed,readinessMs,firstSeconds,latencyCount,latencyMean,latencyP50,"
                + "latencyP95,latencyP99,totalMeasureTimeSeconds,throughputReqPerSec,"
                + "warmupRequests,measureRequests,concurrency,sleepBetweenRequestsMs,"
                + "cpuLoadAvg,memLoadAvg,memLoadMax,"
                + "gcCount,gcFullCount,gcTotalPauseMs,gcMaxPauseMs,gcOverheadPercent,gcPeakHeapAfterMb,"
                + "repetition";
        String dataRow = "PAYLOAD_HEAVY_JSON,200000,/json?n=200000," + configName + "," + dockerImage + ","
                + jvmFlags + ",ACTUATOR_READINESS,1200,0.25,10,0.015,0.012,0.020,0.022,1.5,66.67,20,100,1,0,"
                + "42.5,35.2,38.1,120,2,1658.3,45.7,1.85,256.0,1";
        Files.writeString(file, header + "\n" + dataRow + "\n", StandardCharsets.UTF_8);
    }

    @Test
    void mergeFromCsvDirectory_multiRuntime_noException() throws IOException {
        Path csvDir = tempDir.resolve("csvs-multirt");
        Files.createDirectory(csvDir);
        writeSampleCsvWithImage(csvDir.resolve("results-2026-03-01T10-00-00.000Z.csv"),
                "P01-hotspot", "tfl4-ek-bench:jvm", "");
        writeSampleCsvWithImage(csvDir.resolve("results-2026-03-02T10-00-00.000Z.csv"),
                "P04-openj9", "tfl4-ek-bench:openj9", "");
        writeSampleCsvWithImage(csvDir.resolve("results-2026-03-03T10-00-00.000Z.csv"),
                "P05-native", "tfl4-ek-bench:native", "");

        Path excelOut = tempDir.resolve("merged-multirt.xlsx");
        ExcelExporter.mergeFromCsvDirectory(csvDir, excelOut);

        assertTrue(Files.exists(excelOut));
        try (InputStream is = Files.newInputStream(excelOut);
             XSSFWorkbook wb = new XSSFWorkbook(is)) {
            assertEquals(6, wb.getNumberOfSheets());
            // Verify chart sheets have 3 aggregated rows
            XSSFSheet latenzen = wb.getSheet("Latenzen (alle Runs)");
            assertEquals(3, latenzen.getLastRowNum());
        }
    }
}
