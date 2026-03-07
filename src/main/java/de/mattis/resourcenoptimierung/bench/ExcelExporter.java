package de.mattis.resourcenoptimierung.bench;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xddf.usermodel.XDDFColor;
import org.apache.poi.xddf.usermodel.XDDFSolidFillProperties;
import org.apache.poi.xddf.usermodel.chart.*;
import org.apache.poi.xssf.usermodel.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * Exportiert Benchmark-Ergebnisse als formatiertes Excel-Workbook (.xlsx).
 *
 * <p>Erzeugt ein Workbook mit folgenden Sheets:
 * <ol>
 *   <li><b>Uebersicht</b> – Alle Kennzahlen tabellarisch mit bedingter Formatierung und AutoFilter</li>
 *   <li><b>Latenzen</b> – p50/p95/p99-Vergleich als Balkendiagramm</li>
 *   <li><b>Startup &amp; Throughput</b> – Readiness + Throughput als Diagramme</li>
 *   <li><b>Ressourcen</b> – Docker CPU/Mem fuer IDLE, LOAD und POST</li>
 *   <li><b>Rohdaten</b> – Einzellatenzen fuer eigene Auswertungen</li>
 * </ol>
 *
 * <p>Zusaetzlich: {@link #mergeFromCsvDirectory} liest alle CSVs aus bench-results/
 * und erzeugt ein zusammengefuehrtes Excel fuer Vergleiche ueber mehrere Runs.
 */
public final class ExcelExporter {

    private ExcelExporter() {}

    // ───────────────────── Farbpalette (modern flat) ─────────────────────

    private static final byte[] CLR_PRIMARY     = rgb(0x2B, 0x57, 0x9A);  // dunkelblau – Header
    private static final byte[] CLR_PRIMARY_LT  = rgb(0xD6, 0xE4, 0xF0);  // hellblau   – Section-Header
    private static final byte[] CLR_STRIPE      = rgb(0xF5, 0xF7, 0xFA);  // kuehlgrau  – Zebrastreifen
    private static final byte[] CLR_WHITE       = rgb(0xFF, 0xFF, 0xFF);

    private static final byte[] CLR_GREEN       = rgb(0x27, 0xAE, 0x60);
    private static final byte[] CLR_ORANGE      = rgb(0xF3, 0x9C, 0x12);
    private static final byte[] CLR_RED         = rgb(0xE7, 0x4C, 0x3C);
    private static final byte[] CLR_DARK_BLUE   = rgb(0x2C, 0x3E, 0x50);

    private static byte[] rgb(int r, int g, int b) {
        return new byte[]{(byte) r, (byte) g, (byte) b};
    }

    // ───────────────────── Chart-Serien-Definition ─────────────────────

    /** Beschreibt eine Datenreihe innerhalb eines Balkendiagramms. */
    private record ChartSeries(String title, int column, byte[] color) {}

    // ───────────────────── Public API ─────────────────────

    /**
     * Schreibt Benchmark-Ergebnisse als Excel-Datei.
     *
     * @param results Ergebnisse eines Benchmark-Durchlaufs
     * @param path    Zielpfad (.xlsx)
     */
    public static void writeExcel(List<RunResult> results, Path path) throws IOException {
        if (results == null || results.isEmpty()) return;

        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Styles styles = createStyles(wb);

            writeUebersicht(wb, styles, results);
            writeLatenzen(wb, styles, results);
            writeStartupThroughput(wb, styles, results);
            writeRessourcen(wb, styles, results);
            writeGcVerhalten(wb, styles, results);
            writeGcTimeline(wb, styles, results);
            writeRohdaten(wb, styles, results);

            try (OutputStream os = Files.newOutputStream(path)) { wb.write(os); }
        }
    }

    /**
     * Liest alle CSV-Dateien aus einem Verzeichnis und erzeugt ein
     * zusammengefuehrtes Excel-Workbook fuer Run-uebergreifende Vergleiche.
     *
     * @param csvDir   Verzeichnis mit CSV-Dateien (z.B. bench-results/)
     * @param excelOut Zielpfad fuer die Excel-Datei
     */
    public static void mergeFromCsvDirectory(Path csvDir, Path excelOut) throws IOException {
        List<CsvRow> allRows = new ArrayList<>();

        try (Stream<Path> files = Files.list(csvDir)) {
            List<Path> csvFiles = files
                    .filter(p -> p.getFileName().toString().endsWith(".csv"))
                    .sorted()
                    .toList();

            for (Path csv : csvFiles) {
                String timestamp = extractTimestamp(csv.getFileName().toString());
                allRows.addAll(parseCsv(csv, timestamp));
            }
        }

        if (allRows.isEmpty()) {
            System.out.println("No CSV files found in " + csvDir);
            return;
        }

        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Styles styles = createStyles(wb);
            writeMergedOverview(wb, styles, allRows);
            writeMergedLatencyChart(wb, styles, allRows);
            writeMergedStartupChart(wb, styles, allRows);
            writeMergedRessourcenNote(wb, styles);

            try (OutputStream os = Files.newOutputStream(excelOut)) { wb.write(os); }
        }

        System.out.println("Merged Excel: " + excelOut + " (" + allRows.size() + " rows from CSV)");
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Sheet 1: Uebersicht
    // ═══════════════════════════════════════════════════════════════════

    private static void writeUebersicht(XSSFWorkbook wb, Styles s, List<RunResult> results) {
        XSSFSheet sheet = wb.createSheet("Übersicht");
        sheet.setDefaultColumnWidth(14);

        String[] headers = {
                "Config", "Szenario", "JVM-Flags", "Docker-Image",
                "Readiness (ms)", "First Req (s)",
                "p50 (s)", "p95 (s)", "p99 (s)", "Mean (s)", "Latenz-Count",
                "Messzeit (s)", "Throughput (req/s)",
                "CPU% (LOAD avg)", "Mem% (LOAD avg)", "Mem% (LOAD max)",
                "Mem% (IDLE avg)",
                "GC Count", "Full GC Count", "GC Pause Total (ms)", "GC Max Pause (ms)", "GC Overhead (%)", "Peak Heap nach GC (MB)",
                "Warmup", "Mess-Req", "Concurrency", "Sleep (ms)",
                "Workload-Pfad", "Workload-N", "Repetition"
        };

        // Section-Header-Zeile (Kategorien ueber den Spalten)
        XSSFRow sectionRow = sheet.createRow(0);
        sectionRow.setHeightInPoints(22);
        writeSectionHeaders(sectionRow, s, new String[]{
                "Konfiguration", "", "", "",
                "Startup", "",
                "Latenzen", "", "", "", "",
                "Durchsatz", "",
                "Docker (LOAD)", "", "",
                "Docker (IDLE)",
                "GC-Verhalten", "", "", "", "", "",
                "Messprofil", "", "", "",
                "Meta", "", ""
        });
        mergeIfValid(sheet, 0, 0, 0, 3);    // Konfiguration  (4 cols)
        mergeIfValid(sheet, 0, 0, 4, 5);    // Startup         (2 cols)
        mergeIfValid(sheet, 0, 0, 6, 10);   // Latenzen        (5 cols)
        mergeIfValid(sheet, 0, 0, 11, 12);  // Durchsatz       (2 cols)
        mergeIfValid(sheet, 0, 0, 13, 15);  // Docker (LOAD)   (3 cols)
        // col 16 = Docker (IDLE) – single col, no merge
        mergeIfValid(sheet, 0, 0, 17, 22);  // GC-Verhalten    (6 cols)
        mergeIfValid(sheet, 0, 0, 23, 26);  // Messprofil      (4 cols)
        mergeIfValid(sheet, 0, 0, 27, 29);  // Meta            (3 cols)

        writeHeaderRow(sheet, 1, headers, s);

        // Datenzeilen
        for (int i = 0; i < results.size(); i++) {
            RunResult r = results.get(i);
            XSSFRow row = sheet.createRow(i + 2);

            List<Double> lats = sorted(r.latenciesSeconds());
            double mean = lats.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
            DockerPhaseAvg load = phaseAvg(r.dockerLoadSamples());
            DockerPhaseAvg idle = phaseAvg(r.dockerIdleSamples());
            MeasurementProfile p = r.measurementProfile();

            int c = 0;
            setCell(row, c++, r.configName(),                                pick(s, i, SK.TEXT));
            setCell(row, c++, r.scenario() == null ? "" : r.scenario().name(), pick(s, i, SK.TEXT));
            setCell(row, c++, normalizeFlags(r.effectiveJavaToolOptions()),   pick(s, i, SK.TEXT));
            setCell(row, c++, r.dockerImage(),                               pick(s, i, SK.TEXT));

            setNum(row, c++, r.readinessMs(),          pick(s, i, SK.INT));
            setNum(row, c++, r.firstSeconds(),         pick(s, i, SK.DEC4));

            setNum(row, c++, percentile(lats, 0.50),   pick(s, i, SK.DEC4));
            setNum(row, c++, percentile(lats, 0.95),   pick(s, i, SK.DEC4));
            setNum(row, c++, percentile(lats, 0.99),   pick(s, i, SK.DEC4));
            setNum(row, c++, mean,                     pick(s, i, SK.DEC4));
            setNum(row, c++, lats.size(),              pick(s, i, SK.INT));

            setNum(row, c++, r.totalMeasureTimeSeconds(), pick(s, i, SK.DEC4));
            setNum(row, c++, r.throughputReqPerSec(),     pick(s, i, SK.INT));

            setNum(row, c++, dval(load, a -> a.cpuAvg), pick(s, i, SK.PCT));
            setNum(row, c++, dval(load, a -> a.memAvg), pick(s, i, SK.PCT));
            setNum(row, c++, dval(load, a -> a.memMax), pick(s, i, SK.PCT));
            setNum(row, c++, dval(idle, a -> a.memAvg), pick(s, i, SK.PCT));

            // GC-Verhalten
            GcSummary gc = r.gcSummary();
            if (gc != null) {
                setNum(row, c++, gc.gcCount(),              pick(s, i, SK.INT));
                setNum(row, c++, gc.fullGcCount(),          pick(s, i, SK.INT));
                setNum(row, c++, gc.totalPauseMs(),         pick(s, i, SK.DEC4));
                setNum(row, c++, gc.maxPauseMs(),           pick(s, i, SK.DEC4));
                setNum(row, c++, gc.gcOverheadPercent(),    pick(s, i, SK.PCT));
                setNum(row, c++, gc.peakHeapAfterGcKb() / 1024.0, pick(s, i, SK.INT));
            } else {
                setNum(row, c++, Double.NaN, pick(s, i, SK.INT));
                setNum(row, c++, Double.NaN, pick(s, i, SK.INT));
                setNum(row, c++, Double.NaN, pick(s, i, SK.DEC4));
                setNum(row, c++, Double.NaN, pick(s, i, SK.DEC4));
                setNum(row, c++, Double.NaN, pick(s, i, SK.PCT));
                setNum(row, c++, Double.NaN, pick(s, i, SK.INT));
            }

            setNum(row, c++, p.warmupRequests(),          pick(s, i, SK.INT));
            setNum(row, c++, p.measureRequests(),         pick(s, i, SK.INT));
            setNum(row, c++, p.concurrency(),             pick(s, i, SK.INT));
            setNum(row, c++, p.sleepBetweenRequestsMs(),  pick(s, i, SK.INT));

            setCell(row, c++, r.workloadPath(),        pick(s, i, SK.TEXT));
            setNum(row, c++, r.workloadN(),            pick(s, i, SK.INT));
            setNum(row, c++, r.repetition(),           pick(s, i, SK.INT));
        }

        sheet.setAutoFilter(new CellRangeAddress(1, 1 + results.size(), 0, headers.length - 1));
        sheet.createFreezePane(1, 2);

        // Bedingte Farbskala: Latenzen (p50=6, p95=7, p99=8)
        addColorScale(sheet, 2, 1 + results.size(), 6, 8);
        // Bedingte Farbskala: Ressourcen (CPU% LOAD=13 bis Mem% IDLE=16)
        addColorScale(sheet, 2, 1 + results.size(), 13, 16);
        // Bedingte Farbskala: GC Max Pause=20, GC Overhead=21
        addColorScale(sheet, 2, 1 + results.size(), 20, 21);

        autoSizeColumns(sheet, headers.length);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Sheet 2: Latenzen
    // ═══════════════════════════════════════════════════════════════════

    private static void writeLatenzen(XSSFWorkbook wb, Styles s, List<RunResult> results) {
        XSSFSheet sheet = wb.createSheet("Latenzen");
        String[] cols = {"Config", "JVM-Flags", "p50 (s)", "p95 (s)", "p99 (s)", "Mean (s)"};
        writeHeaderRow(sheet, 0, cols, s);

        for (int i = 0; i < results.size(); i++) {
            RunResult r = results.get(i);
            List<Double> lats = sorted(r.latenciesSeconds());
            double mean = lats.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);

            XSSFRow row = sheet.createRow(i + 1);
            setCell(row, 0, r.configName(),                               pick(s, i, SK.TEXT));
            setCell(row, 1, normalizeFlags(r.effectiveJavaToolOptions()),  pick(s, i, SK.TEXT));
            setNum(row, 2, percentile(lats, 0.50),                        pick(s, i, SK.DEC4));
            setNum(row, 3, percentile(lats, 0.95),                        pick(s, i, SK.DEC4));
            setNum(row, 4, percentile(lats, 0.99),                        pick(s, i, SK.DEC4));
            setNum(row, 5, mean,                                          pick(s, i, SK.DEC4));
        }

        if (results.size() >= 2) {
            addBarChart(sheet, results.size(), "Latenz-Vergleich (s)",
                    "Konfiguration", "Latenz (s)", 0,
                    new ChartSeries("p50", 2, CLR_GREEN),
                    new ChartSeries("p95", 3, CLR_ORANGE),
                    new ChartSeries("p99", 4, CLR_RED));
        }
        autoSizeColumns(sheet, cols.length);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Sheet 3: Startup & Throughput
    // ═══════════════════════════════════════════════════════════════════

    private static void writeStartupThroughput(XSSFWorkbook wb, Styles s, List<RunResult> results) {
        XSSFSheet sheet = wb.createSheet("Startup & Throughput");
        String[] cols = {"Config", "JVM-Flags", "Readiness (ms)", "First Req (s)", "Throughput (req/s)"};
        writeHeaderRow(sheet, 0, cols, s);

        for (int i = 0; i < results.size(); i++) {
            RunResult r = results.get(i);
            XSSFRow row = sheet.createRow(i + 1);
            setCell(row, 0, r.configName(),                               pick(s, i, SK.TEXT));
            setCell(row, 1, normalizeFlags(r.effectiveJavaToolOptions()),  pick(s, i, SK.TEXT));
            setNum(row, 2, r.readinessMs(),                               pick(s, i, SK.INT));
            setNum(row, 3, r.firstSeconds(),                              pick(s, i, SK.DEC4));
            setNum(row, 4, r.throughputReqPerSec(),                       pick(s, i, SK.INT));
        }

        if (results.size() >= 2) {
            addBarChart(sheet, results.size(), "Startup-Zeit (ms)",
                    "Konfiguration", "Readiness (ms)", 0,
                    new ChartSeries("Readiness (ms)", 2, CLR_DARK_BLUE));

            addBarChart(sheet, results.size(), "Durchsatz (req/s)",
                    "Konfiguration", "Throughput (req/s)", 0, 14,
                    new ChartSeries("Throughput (req/s)", 4, CLR_GREEN));
        }
        autoSizeColumns(sheet, cols.length);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Sheet 4: Ressourcen
    // ═══════════════════════════════════════════════════════════════════

    private static void writeRessourcen(XSSFWorkbook wb, Styles s, List<RunResult> results) {
        XSSFSheet sheet = wb.createSheet("Ressourcen");
        String[] cols = {
                "Config", "JVM-Flags",
                "CPU% IDLE", "CPU% LOAD", "CPU% POST",
                "Mem% IDLE", "Mem% LOAD", "Mem% POST",
                "Mem% LOAD max"
        };
        writeHeaderRow(sheet, 0, cols, s);

        for (int i = 0; i < results.size(); i++) {
            RunResult r = results.get(i);
            XSSFRow row = sheet.createRow(i + 1);
            DockerPhaseAvg idle = phaseAvg(r.dockerIdleSamples());
            DockerPhaseAvg load = phaseAvg(r.dockerLoadSamples());
            DockerPhaseAvg post = phaseAvg(r.dockerPostSamples());

            setCell(row, 0, r.configName(),                               pick(s, i, SK.TEXT));
            setCell(row, 1, normalizeFlags(r.effectiveJavaToolOptions()),  pick(s, i, SK.TEXT));

            setNum(row, 2, dval(idle, a -> a.cpuAvg), pick(s, i, SK.PCT));
            setNum(row, 3, dval(load, a -> a.cpuAvg), pick(s, i, SK.PCT));
            setNum(row, 4, dval(post, a -> a.cpuAvg), pick(s, i, SK.PCT));
            setNum(row, 5, dval(idle, a -> a.memAvg), pick(s, i, SK.PCT));
            setNum(row, 6, dval(load, a -> a.memAvg), pick(s, i, SK.PCT));
            setNum(row, 7, dval(post, a -> a.memAvg), pick(s, i, SK.PCT));
            setNum(row, 8, dval(load, a -> a.memMax), pick(s, i, SK.PCT));
        }

        if (results.size() >= 2) {
            addBarChart(sheet, results.size(), "Ressourcenverbrauch unter Last (LOAD)",
                    "Konfiguration", "%", 0,
                    new ChartSeries("CPU% LOAD", 3, CLR_ORANGE),
                    new ChartSeries("Mem% LOAD", 6, CLR_DARK_BLUE));
        }
        autoSizeColumns(sheet, cols.length);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Sheet 5: Rohdaten
    // ═══════════════════════════════════════════════════════════════════

    private static void writeRohdaten(XSSFWorkbook wb, Styles s, List<RunResult> results) {
        XSSFSheet sheet = wb.createSheet("Rohdaten");
        String[] cols = {"Config", "JVM-Flags", "Repetition", "Request #", "Latenz (s)"};
        writeHeaderRow(sheet, 0, cols, s);

        int rowIdx = 1;
        for (RunResult r : results) {
            String flags = normalizeFlags(r.effectiveJavaToolOptions());
            List<Double> lats = r.latenciesSeconds();
            for (int j = 0; j < lats.size(); j++) {
                XSSFRow row = sheet.createRow(rowIdx);
                int stripe = rowIdx - 1;
                setCell(row, 0, r.configName(), pick(s, stripe, SK.TEXT));
                setCell(row, 1, flags,          pick(s, stripe, SK.TEXT));
                setNum(row, 2, r.repetition(),  pick(s, stripe, SK.TEXT));
                setNum(row, 3, j + 1,           pick(s, stripe, SK.TEXT));
                setNum(row, 4, lats.get(j),     pick(s, stripe, SK.DEC4));
                rowIdx++;
            }
        }

        sheet.createFreezePane(0, 1);
        autoSizeColumns(sheet, cols.length);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Sheet 6: GC-Verhalten (Level 2 – aggregierte GC-Kennzahlen)
    // ═══════════════════════════════════════════════════════════════════

    private static void writeGcVerhalten(XSSFWorkbook wb, Styles s, List<RunResult> results) {
        XSSFSheet sheet = wb.createSheet("GC-Verhalten");
        String[] cols = {
                "Config", "JVM-Flags",
                "GC Count", "Full GC",
                "Total Pause (ms)", "Max Pause (ms)",
                "Overhead (%)", "Peak Heap (MB)"
        };
        writeHeaderRow(sheet, 0, cols, s);

        for (int i = 0; i < results.size(); i++) {
            RunResult r = results.get(i);
            GcSummary gc = r.gcSummary();
            XSSFRow row = sheet.createRow(i + 1);

            setCell(row, 0, r.configName(),                              pick(s, i, SK.TEXT));
            setCell(row, 1, normalizeFlags(r.effectiveJavaToolOptions()), pick(s, i, SK.TEXT));

            if (gc != null) {
                setNum(row, 2, gc.gcCount(),           pick(s, i, SK.INT));
                setNum(row, 3, gc.fullGcCount(),       pick(s, i, SK.INT));
                setNum(row, 4, gc.totalPauseMs(),      pick(s, i, SK.DEC4));
                setNum(row, 5, gc.maxPauseMs(),        pick(s, i, SK.DEC4));
                setNum(row, 6, gc.gcOverheadPercent(), pick(s, i, SK.PCT));
                setNum(row, 7, gc.peakHeapAfterGcKb() / 1024.0, pick(s, i, SK.INT));
            } else {
                for (int c = 2; c < cols.length; c++) {
                    setNum(row, c, Double.NaN, pick(s, i, SK.INT));
                }
            }
        }

        if (results.size() >= 2) {
            // Chart 1: GC-Pausen-Vergleich (Total Pause + Max Pause)
            addBarChart(sheet, results.size(), "GC-Pausen-Vergleich",
                    "Konfiguration", "Pause (ms)", 0,
                    new ChartSeries("Total Pause (ms)", 4, CLR_DARK_BLUE),
                    new ChartSeries("Max Pause (ms)", 5, CLR_RED));

            // Chart 2: GC Overhead (%) – unterhalb des ersten Charts
            int chartHeight = Math.max(12, results.size() * 2);
            addBarChart(sheet, results.size(), "GC Overhead (%)",
                    "Konfiguration", "Overhead (%)", 0, chartHeight + 4,
                    new ChartSeries("Overhead (%)", 6, CLR_ORANGE));
        }
        autoSizeColumns(sheet, cols.length);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Sheet 7: GC-Timeline (Level 3 – einzelne GC-Events + Scatter)
    // ═══════════════════════════════════════════════════════════════════

    private static void writeGcTimeline(XSSFWorkbook wb, Styles s, List<RunResult> results) {
        XSSFSheet sheet = wb.createSheet("GC-Timeline");
        String[] cols = {
                "Config", "GC#", "Timestamp (s)", "Type", "Cause",
                "Heap Before (MB)", "Heap After (MB)", "Heap Max (MB)", "Pause (ms)"
        };
        writeHeaderRow(sheet, 0, cols, s);

        // Daten schreiben + Bereichsgrenzen pro Config merken (fuer Scatter-Serien)
        List<String> configNames = new ArrayList<>();
        List<int[]> configRanges = new ArrayList<>();   // [firstDataRow, lastDataRow] (0-indexed sheet rows)

        int rowIdx = 1;
        for (RunResult r : results) {
            GcSummary gc = r.gcSummary();
            if (gc == null || gc.events().isEmpty()) continue;

            int firstRow = rowIdx;
            List<GcEvent> events = gc.events();
            for (int j = 0; j < events.size(); j++) {
                GcEvent ev = events.get(j);
                XSSFRow row = sheet.createRow(rowIdx);
                int stripe = rowIdx - 1;
                setCell(row, 0, r.configName(),          pick(s, stripe, SK.TEXT));
                setNum(row, 1, j + 1,                   pick(s, stripe, SK.INT));
                setNum(row, 2, ev.timestampSeconds(),    pick(s, stripe, SK.DEC4));
                setCell(row, 3, ev.gcType(),             pick(s, stripe, SK.TEXT));
                setCell(row, 4, ev.gcCause(),            pick(s, stripe, SK.TEXT));
                setNum(row, 5, ev.heapBeforeKb() >= 0 ? ev.heapBeforeKb() / 1024.0 : Double.NaN,
                        pick(s, stripe, SK.INT));
                setNum(row, 6, ev.heapAfterKb() >= 0 ? ev.heapAfterKb() / 1024.0 : Double.NaN,
                        pick(s, stripe, SK.INT));
                setNum(row, 7, ev.heapMaxKb() >= 0 ? ev.heapMaxKb() / 1024.0 : Double.NaN,
                        pick(s, stripe, SK.INT));
                setNum(row, 8, ev.pauseMs(),             pick(s, stripe, SK.DEC4));
                rowIdx++;
            }
            configNames.add(r.configName());
            configRanges.add(new int[]{firstRow, rowIdx - 1});
        }

        sheet.createFreezePane(0, 1);
        autoSizeColumns(sheet, cols.length);

        // Scatter-Chart: X = Timestamp (s), Y = Pause (ms), eine Serie pro Config
        if (configNames.size() >= 1 && rowIdx > 1) {
            int chartStartRow = rowIdx + 2;
            int chartHeight = Math.max(15, configNames.size() * 3);

            XSSFDrawing drawing = sheet.createDrawingPatriarch();
            XSSFClientAnchor anchor = drawing.createAnchor(
                    0, 0, 0, 0, 0, chartStartRow, 12, chartStartRow + chartHeight);

            XSSFChart chart = drawing.createChart(anchor);
            chart.setTitleText("GC-Pausen Timeline");
            chart.setTitleOverlay(false);

            XDDFValueAxis xAxis = chart.createValueAxis(AxisPosition.BOTTOM);
            xAxis.setTitle("Timestamp (s)");
            XDDFValueAxis yAxis = chart.createValueAxis(AxisPosition.LEFT);
            yAxis.setTitle("Pause (ms)");
            yAxis.setCrossBetween(AxisCrossBetween.BETWEEN);

            XDDFScatterChartData scatterData = (XDDFScatterChartData)
                    chart.createData(ChartTypes.SCATTER, xAxis, yAxis);
            // MARKER_ONLY: Punkte ohne Verbindungslinien (ideal fuer Timeline)
            // Hinweis: setMarkerStyle/setMarkerSize benoetigt poi-ooxml-full statt
            // poi-ooxml-lite (fehlende xsb-Schema-Ressourcen), deshalb verwenden
            // wir den Default-Marker und setzen nur die Farbe per Series-Fill.
            scatterData.setStyle(ScatterStyle.MARKER);

            // Farbpalette fuer Serien (rotiert bei >6 Configs)
            byte[][] seriesColors = {
                    CLR_DARK_BLUE, CLR_RED, CLR_GREEN, CLR_ORANGE,
                    rgb(0x8E, 0x44, 0xAD), rgb(0x16, 0xA0, 0x85)
            };

            for (int i = 0; i < configNames.size(); i++) {
                int[] range = configRanges.get(i);
                // X = Timestamp (col 2), Y = Pause (col 8)
                XDDFNumericalDataSource<Double> xData = XDDFDataSourcesFactory.fromNumericCellRange(
                        sheet, new CellRangeAddress(range[0], range[1], 2, 2));
                XDDFNumericalDataSource<Double> yData = XDDFDataSourcesFactory.fromNumericCellRange(
                        sheet, new CellRangeAddress(range[0], range[1], 8, 8));

                XDDFScatterChartData.Series series =
                        (XDDFScatterChartData.Series) scatterData.addSeries(xData, yData);
                series.setTitle(configNames.get(i), null);

                byte[] color = seriesColors[i % seriesColors.length];
                series.setFillProperties(new XDDFSolidFillProperties(XDDFColor.from(color)));
            }

            chart.plot(scatterData);

            XDDFChartLegend legend = chart.getOrAddLegend();
            legend.setPosition(LegendPosition.BOTTOM);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Merged-CSV Sheets
    // ═══════════════════════════════════════════════════════════════════

    private static void writeMergedOverview(XSSFWorkbook wb, Styles s, List<CsvRow> rows) {
        XSSFSheet sheet = wb.createSheet("Übersicht (alle Runs)");
        sheet.setDefaultColumnWidth(14);

        String[] headers = {
                "Timestamp", "Config", "Szenario", "JVM-Flags",
                "Readiness (ms)", "First Req (s)",
                "p50 (s)", "p95 (s)", "p99 (s)", "Mean (s)",
                "Messzeit (s)", "Throughput (req/s)",
                "Warmup", "Mess-Req", "Concurrency", "Sleep (ms)",
                "Workload-Pfad", "Workload-N"
        };
        writeHeaderRow(sheet, 0, headers, s);

        for (int i = 0; i < rows.size(); i++) {
            CsvRow r = rows.get(i);
            XSSFRow row = sheet.createRow(i + 1);
            int c = 0;
            setCell(row, c++, r.timestamp(),       pick(s, i, SK.TEXT));
            setCell(row, c++, r.configName(),      pick(s, i, SK.TEXT));
            setCell(row, c++, r.scenario(),        pick(s, i, SK.TEXT));
            setCell(row, c++, r.jvmFlags(),        pick(s, i, SK.TEXT));
            setNum(row, c++, r.readinessMs(),      pick(s, i, SK.INT));
            setNum(row, c++, r.firstSeconds(),     pick(s, i, SK.DEC4));
            setNum(row, c++, r.p50(),              pick(s, i, SK.DEC4));
            setNum(row, c++, r.p95(),              pick(s, i, SK.DEC4));
            setNum(row, c++, r.p99(),              pick(s, i, SK.DEC4));
            setNum(row, c++, r.mean(),             pick(s, i, SK.DEC4));
            setNum(row, c++, r.totalMeasureTime(), pick(s, i, SK.DEC4));
            setNum(row, c++, r.throughput(),       pick(s, i, SK.INT));
            setNum(row, c++, r.warmup(),           pick(s, i, SK.INT));
            setNum(row, c++, r.measureReqs(),      pick(s, i, SK.INT));
            setNum(row, c++, r.concurrency(),      pick(s, i, SK.INT));
            setNum(row, c++, r.sleepMs(),          pick(s, i, SK.INT));
            setCell(row, c++, r.workloadPath(),    pick(s, i, SK.TEXT));
            setNum(row, c++, r.workloadN(),        pick(s, i, SK.INT));
        }

        sheet.setAutoFilter(new CellRangeAddress(0, rows.size(), 0, headers.length - 1));
        sheet.createFreezePane(2, 1);
        autoSizeColumns(sheet, headers.length);
    }

    private static void writeMergedLatencyChart(XSSFWorkbook wb, Styles s, List<CsvRow> rows) {
        XSSFSheet sheet = wb.createSheet("Latenzen (alle Runs)");
        String[] cols = {"Config", "Timestamp", "JVM-Flags", "p50 (s)", "p95 (s)", "p99 (s)"};
        writeHeaderRow(sheet, 0, cols, s);

        for (int i = 0; i < rows.size(); i++) {
            CsvRow r = rows.get(i);
            XSSFRow row = sheet.createRow(i + 1);
            setCell(row, 0, r.configName(), pick(s, i, SK.TEXT));
            setCell(row, 1, r.timestamp(),  pick(s, i, SK.TEXT));
            setCell(row, 2, r.jvmFlags(),   pick(s, i, SK.TEXT));
            setNum(row, 3, r.p50(),         pick(s, i, SK.DEC4));
            setNum(row, 4, r.p95(),         pick(s, i, SK.DEC4));
            setNum(row, 5, r.p99(),         pick(s, i, SK.DEC4));
        }

        if (rows.size() >= 2) {
            addBarChart(sheet, rows.size(), "Latenz-Vergleich alle Runs (s)",
                    "Konfiguration", "Latenz (s)", 0,
                    new ChartSeries("p50", 3, CLR_GREEN),
                    new ChartSeries("p95", 4, CLR_ORANGE),
                    new ChartSeries("p99", 5, CLR_RED));
        }
        autoSizeColumns(sheet, cols.length);
    }

    private static void writeMergedStartupChart(XSSFWorkbook wb, Styles s, List<CsvRow> rows) {
        XSSFSheet sheet = wb.createSheet("Startup (alle Runs)");
        String[] cols = {"Config", "Timestamp", "JVM-Flags", "Readiness (ms)", "First Req (s)", "Throughput (req/s)"};
        writeHeaderRow(sheet, 0, cols, s);

        for (int i = 0; i < rows.size(); i++) {
            CsvRow r = rows.get(i);
            XSSFRow row = sheet.createRow(i + 1);
            setCell(row, 0, r.configName(),   pick(s, i, SK.TEXT));
            setCell(row, 1, r.timestamp(),    pick(s, i, SK.TEXT));
            setCell(row, 2, r.jvmFlags(),     pick(s, i, SK.TEXT));
            setNum(row, 3, r.readinessMs(),   pick(s, i, SK.INT));
            setNum(row, 4, r.firstSeconds(),  pick(s, i, SK.DEC4));
            setNum(row, 5, r.throughput(),    pick(s, i, SK.INT));
        }

        if (rows.size() >= 2) {
            addBarChart(sheet, rows.size(), "Startup & Throughput alle Runs",
                    "Konfiguration", null, 0,
                    new ChartSeries("Readiness (ms)", 3, CLR_DARK_BLUE),
                    new ChartSeries("Throughput (req/s)", 5, CLR_GREEN));
        }
        autoSizeColumns(sheet, cols.length);
    }

    private static void writeMergedRessourcenNote(XSSFWorkbook wb, Styles s) {
        XSSFSheet sheet = wb.createSheet("Hinweis Ressourcen");
        setCell(sheet.createRow(0), 0,
                "Docker-Ressourcendaten (CPU%/Mem%) sind nur im Einzelrun-Excel enthalten,", s.text);
        setCell(sheet.createRow(1), 0,
                "da die CSV-Dateien diese Daten nicht exportieren.", s.text);
        setCell(sheet.createRow(2), 0,
                "Fuer Ressourcenvergleiche die Einzelrun-Excels nutzen.", s.text);
        sheet.autoSizeColumn(0);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  CSV Parsing
    // ═══════════════════════════════════════════════════════════════════

    /** Repraesentiert eine Zeile aus einer exportierten CSV-Datei. */
    record CsvRow(
            String timestamp, String scenario, int workloadN, String workloadPath,
            String configName, String dockerImage, String jvmFlags,
            double readinessMs, double firstSeconds,
            double p50, double p95, double p99, double mean,
            double totalMeasureTime, double throughput,
            int warmup, int measureReqs, int concurrency, long sleepMs
    ) {}

    static List<CsvRow> parseCsv(Path csvFile, String timestamp) throws IOException {
        List<CsvRow> rows = new ArrayList<>();
        try (BufferedReader br = Files.newBufferedReader(csvFile, StandardCharsets.UTF_8)) {
            String headerLine = br.readLine();
            if (headerLine == null) return rows;

            String[] headers = headerLine.split(",");
            Map<String, Integer> idx = new HashMap<>();
            for (int i = 0; i < headers.length; i++) idx.put(headers[i].trim(), i);

            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] vals = parseCsvLine(line);
                rows.add(new CsvRow(
                        timestamp,
                        getVal(vals, idx, "scenario"),
                        getIntVal(vals, idx, "workloadN"),
                        getVal(vals, idx, "workloadPath"),
                        getVal(vals, idx, "configName"),
                        getVal(vals, idx, "dockerImage"),
                        getVal(vals, idx, "effectiveJavaToolOptions"),
                        getDoubleVal(vals, idx, "readinessMs"),
                        getDoubleVal(vals, idx, "firstSeconds"),
                        getDoubleVal(vals, idx, "latencyP50"),
                        getDoubleVal(vals, idx, "latencyP95"),
                        getDoubleVal(vals, idx, "latencyP99"),
                        getDoubleVal(vals, idx, "latencyMean"),
                        getDoubleVal(vals, idx, "totalMeasureTimeSeconds"),
                        getDoubleVal(vals, idx, "throughputReqPerSec"),
                        getIntVal(vals, idx, "warmupRequests"),
                        getIntVal(vals, idx, "measureRequests"),
                        getIntVal(vals, idx, "concurrency"),
                        (long) getDoubleVal(vals, idx, "sleepBetweenRequestsMs")
                ));
            }
        }
        return rows;
    }

    static String extractTimestamp(String filename) {
        // results-2026-03-05T11-49-59.609588200Z.csv  →  2026-03-05 11:49:59
        String ts = filename.replace("results-", "").replace(".csv", "");
        int tIdx = ts.indexOf('T');
        if (tIdx >= 0) {
            String datePart = ts.substring(0, tIdx);
            String timePart = ts.substring(tIdx + 1);
            String hms = timePart.split("\\.")[0].replace("-", ":");
            ts = datePart + " " + hms;
        }
        return ts;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Styles  (modernes, borderless Design)
    // ═══════════════════════════════════════════════════════════════════

    /** Kurzname fuer StyleKind – vermeidet lange Qualifizierungen in Datenzeilen. */
    private enum SK { TEXT, INT, DEC4, PCT }

    private record Styles(
            CellStyle header, CellStyle sectionHeader,
            CellStyle text, CellStyle textStripe,
            CellStyle integer, CellStyle integerStripe,
            CellStyle dec4, CellStyle dec4Stripe,
            CellStyle pct, CellStyle pctStripe
    ) {}

    /** Waehlt anhand von Zeilenindex und Typ den passenden Style (normal / Zebra). */
    private static CellStyle pick(Styles s, int rowIndex, SK kind) {
        boolean stripe = rowIndex % 2 != 0;
        return switch (kind) {
            case TEXT -> stripe ? s.textStripe : s.text;
            case INT  -> stripe ? s.integerStripe : s.integer;
            case DEC4 -> stripe ? s.dec4Stripe : s.dec4;
            case PCT  -> stripe ? s.pctStripe : s.pct;
        };
    }

    private static Styles createStyles(XSSFWorkbook wb) {
        XSSFFont headerFont = wb.createFont();
        headerFont.setBold(true);
        headerFont.setFontHeightInPoints((short) 11);
        headerFont.setFontName("Calibri");
        headerFont.setColor(new XSSFColor(CLR_WHITE, null));

        XSSFFont sectionFont = wb.createFont();
        sectionFont.setBold(true);
        sectionFont.setFontHeightInPoints((short) 10);
        sectionFont.setFontName("Calibri");

        XSSFFont dataFont = wb.createFont();
        dataFont.setFontHeightInPoints((short) 10);
        dataFont.setFontName("Calibri");

        DataFormat df = wb.createDataFormat();
        short fmtInt  = df.getFormat("#,##0");
        short fmtDec4 = df.getFormat("0.0000");
        short fmtPct  = df.getFormat("0.00\"%\"");

        // Header: dunkelblau, weisse Schrift, Akzent-Rand unten
        XSSFCellStyle hdr = wb.createCellStyle();
        hdr.setFont(headerFont);
        hdr.setFillForegroundColor(new XSSFColor(CLR_PRIMARY, null));
        hdr.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        hdr.setAlignment(HorizontalAlignment.CENTER);
        hdr.setVerticalAlignment(VerticalAlignment.CENTER);
        hdr.setWrapText(true);
        hdr.setBorderBottom(BorderStyle.MEDIUM);

        // Section-Header: helles Blau
        XSSFCellStyle sec = wb.createCellStyle();
        sec.setFont(sectionFont);
        sec.setFillForegroundColor(new XSSFColor(CLR_PRIMARY_LT, null));
        sec.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        sec.setAlignment(HorizontalAlignment.CENTER);
        sec.setVerticalAlignment(VerticalAlignment.CENTER);

        // Datenzellen (borderless, feiner Haarlinie-Separator unten)
        CellStyle txt     = cellStyle(wb, dataFont, null, null);
        CellStyle txtS    = cellStyle(wb, dataFont, CLR_STRIPE, null);
        CellStyle intN    = cellStyle(wb, dataFont, null, fmtInt);
        CellStyle intS    = cellStyle(wb, dataFont, CLR_STRIPE, fmtInt);
        CellStyle dec4N   = cellStyle(wb, dataFont, null, fmtDec4);
        CellStyle dec4S   = cellStyle(wb, dataFont, CLR_STRIPE, fmtDec4);
        CellStyle pctN    = cellStyle(wb, dataFont, null, fmtPct);
        CellStyle pctS    = cellStyle(wb, dataFont, CLR_STRIPE, fmtPct);

        return new Styles(hdr, sec, txt, txtS, intN, intS, dec4N, dec4S, pctN, pctS);
    }

    /** Erzeugt einen Datenstyle: optionaler Hintergrund, optionales Zahlenformat, rechtsbuendig bei Zahlen. */
    private static XSSFCellStyle cellStyle(XSSFWorkbook wb, XSSFFont font, byte[] bg, Short numFmt) {
        XSSFCellStyle cs = wb.createCellStyle();
        cs.setFont(font);
        cs.setVerticalAlignment(VerticalAlignment.CENTER);
        if (bg != null) {
            cs.setFillForegroundColor(new XSSFColor(bg, null));
            cs.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        }
        if (numFmt != null) {
            cs.setDataFormat(numFmt);
            cs.setAlignment(HorizontalAlignment.RIGHT);
        }
        // Dezenter Haarlinie-Rand unten fuer visuelle Zeilentrennung
        cs.setBorderBottom(BorderStyle.HAIR);
        cs.setBottomBorderColor(new XSSFColor(rgb(0xE0, 0xE0, 0xE0), null));
        return cs;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Diagramm-Helfer (eliminiert fuenffache Duplikation)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Erzeugt ein gruppiertes Balkendiagramm unterhalb der Datentabelle.
     * Chart wird ab {@code dataRows + 3} platziert (Standard-Offset 0).
     */
    private static void addBarChart(XSSFSheet sheet, int dataRows,
                                    String title, String catLabel, String valLabel,
                                    int catCol, ChartSeries... series) {
        addBarChart(sheet, dataRows, title, catLabel, valLabel, catCol, 0, series);
    }

    /**
     * Erzeugt ein gruppiertes Balkendiagramm mit konfigurierbarem vertikalen Offset.
     *
     * @param sheet       Ziel-Sheet
     * @param dataRows    Anzahl Datenzeilen (ohne Header)
     * @param title       Diagramm-Titel
     * @param catLabel    Achsenbeschriftung Kategorie (oder null)
     * @param valLabel    Achsenbeschriftung Wert (oder null)
     * @param catCol      Spalte mit Kategorie-Labels (Config-Namen)
     * @param extraOffset Zusaetzlicher vertikaler Offset (Zeilen) unter der Standard-Position
     * @param series      Datenreihen (Titel, Spalte, Farbe)
     */
    private static void addBarChart(XSSFSheet sheet, int dataRows,
                                    String title, String catLabel, String valLabel,
                                    int catCol, int extraOffset, ChartSeries... series) {
        int chartHeight = Math.max(12, dataRows * 2);
        int startRow = dataRows + 3 + extraOffset;

        XSSFDrawing drawing = sheet.createDrawingPatriarch();
        XSSFClientAnchor anchor = drawing.createAnchor(
                0, 0, 0, 0, 0, startRow, 10, startRow + chartHeight);

        XSSFChart chart = drawing.createChart(anchor);
        chart.setTitleText(title);
        chart.setTitleOverlay(false);

        XDDFChartAxis catAxis = chart.createCategoryAxis(AxisPosition.BOTTOM);
        if (catLabel != null) catAxis.setTitle(catLabel);

        XDDFValueAxis valAxis = chart.createValueAxis(AxisPosition.LEFT);
        if (valLabel != null) valAxis.setTitle(valLabel);
        valAxis.setCrossBetween(AxisCrossBetween.BETWEEN);

        XDDFDataSource<String> cats = XDDFDataSourcesFactory.fromStringCellRange(
                sheet, new CellRangeAddress(1, dataRows, catCol, catCol));

        XDDFBarChartData barData = (XDDFBarChartData)
                chart.createData(ChartTypes.BAR, catAxis, valAxis);
        barData.setBarDirection(BarDirection.COL);
        if (series.length > 1) barData.setBarGrouping(BarGrouping.CLUSTERED);
        barData.setGapWidth(150);

        for (ChartSeries sd : series) {
            XDDFNumericalDataSource<Double> data = XDDFDataSourcesFactory.fromNumericCellRange(
                    sheet, new CellRangeAddress(1, dataRows, sd.column(), sd.column()));
            XDDFBarChartData.Series bs = (XDDFBarChartData.Series) barData.addSeries(cats, data);
            bs.setTitle(sd.title(), null);
            bs.setFillProperties(new XDDFSolidFillProperties(XDDFColor.from(sd.color())));
        }

        chart.plot(barData);

        // Legende unten platzieren
        XDDFChartLegend legend = chart.getOrAddLegend();
        legend.setPosition(LegendPosition.BOTTOM);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Bedingte Formatierung (Farbskala gruen → gelb → rot)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Fuegt eine 3-Farb-Skala (gruen → orange → rot) fuer die angegebenen Spalten hinzu.
     * Niedrige Werte = gruen, mittlere = orange, hohe = rot.
     */
    private static void addColorScale(XSSFSheet sheet, int firstRow, int lastRow,
                                      int firstCol, int lastCol) {
        SheetConditionalFormatting scf = sheet.getSheetConditionalFormatting();
        for (int col = firstCol; col <= lastCol; col++) {
            CellRangeAddress[] range = {new CellRangeAddress(firstRow, lastRow, col, col)};
            ConditionalFormattingRule rule = scf.createConditionalFormattingColorScaleRule();
            ColorScaleFormatting csf = rule.getColorScaleFormatting();

            csf.getThresholds()[0].setRangeType(ConditionalFormattingThreshold.RangeType.MIN);
            csf.getThresholds()[1].setRangeType(ConditionalFormattingThreshold.RangeType.PERCENTILE);
            csf.getThresholds()[1].setValue(50d);
            csf.getThresholds()[2].setRangeType(ConditionalFormattingThreshold.RangeType.MAX);

            Color[] colors = csf.getColors();
            ((ExtendedColor) colors[0]).setARGBHex("FF27AE60");  // gruen
            ((ExtendedColor) colors[1]).setARGBHex("FFF39C12");  // orange
            ((ExtendedColor) colors[2]).setARGBHex("FFE74C3C");  // rot

            scf.addConditionalFormatting(range, rule);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Zell- und Sheet-Helfer
    // ═══════════════════════════════════════════════════════════════════

    private static void setCell(XSSFRow row, int col, String value, CellStyle style) {
        XSSFCell cell = row.createCell(col);
        cell.setCellValue(value != null ? value : "");
        cell.setCellStyle(style);
    }

    private static void setNum(XSSFRow row, int col, double value, CellStyle style) {
        XSSFCell cell = row.createCell(col);
        if (Double.isNaN(value)) cell.setCellValue("");
        else                     cell.setCellValue(value);
        cell.setCellStyle(style);
    }

    private static void writeHeaderRow(XSSFSheet sheet, int rowNum, String[] headers, Styles s) {
        XSSFRow row = sheet.createRow(rowNum);
        row.setHeightInPoints(32);
        for (int c = 0; c < headers.length; c++) {
            XSSFCell cell = row.createCell(c);
            cell.setCellValue(headers[c]);
            cell.setCellStyle(s.header);
        }
    }

    private static void writeSectionHeaders(XSSFRow row, Styles s, String[] sections) {
        for (int i = 0; i < sections.length; i++) {
            XSSFCell cell = row.createCell(i);
            cell.setCellValue(sections[i]);
            cell.setCellStyle(s.sectionHeader);
        }
    }

    private static void mergeIfValid(XSSFSheet sheet, int firstRow, int lastRow, int firstCol, int lastCol) {
        if (firstCol < lastCol) {
            sheet.addMergedRegion(new CellRangeAddress(firstRow, lastRow, firstCol, lastCol));
        }
    }

    /** Auto-sizes alle Spalten mit Min 12 / Max 35 Zeichen Breite. */
    private static void autoSizeColumns(XSSFSheet sheet, int colCount) {
        for (int c = 0; c < colCount; c++) {
            sheet.autoSizeColumn(c);
            int w = sheet.getColumnWidth(c);
            sheet.setColumnWidth(c, Math.max(w, 12 * 256));
            if (w > 35 * 256) sheet.setColumnWidth(c, 35 * 256);
        }
    }

    private static String normalizeFlags(String flags) {
        if (flags == null) return "(native)";
        if (flags.isBlank()) return "(keine)";
        return flags;
    }

    private static double percentile(List<Double> sorted, double p) {
        if (sorted == null || sorted.isEmpty()) return Double.NaN;
        int idx = (int) Math.ceil(p * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
    }

    /** Kopiert und sortiert eine Latenzliste. */
    private static List<Double> sorted(List<Double> lats) {
        List<Double> copy = new ArrayList<>(lats);
        copy.sort(Double::compareTo);
        return copy;
    }

    // ───────────────────── Docker-Statistik Aggregation ─────────────────────

    private record DockerPhaseAvg(double cpuAvg, double memAvg, double memMax) {}

    @FunctionalInterface
    private interface PhaseField { double get(DockerPhaseAvg a); }

    /** Gibt den Feldwert zurueck oder NaN wenn die Phase null ist. */
    private static double dval(DockerPhaseAvg phase, PhaseField fn) {
        return phase != null ? fn.get(phase) : Double.NaN;
    }

    private static DockerPhaseAvg phaseAvg(List<DockerStatSample> samples) {
        if (samples == null || samples.isEmpty()) return null;
        double cpuSum = 0, memSum = 0, memMax = -1;
        for (DockerStatSample ss : samples) {
            cpuSum += ss.cpuPercent();
            memSum += ss.memPercent();
            if (ss.memPercent() > memMax) memMax = ss.memPercent();
        }
        return new DockerPhaseAvg(cpuSum / samples.size(), memSum / samples.size(), memMax);
    }

    // ───────────────────── CSV-Zeilen-Parser ─────────────────────

    static String[] parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        boolean inQuotes = false;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        sb.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    sb.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                fields.add(sb.toString());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        fields.add(sb.toString());
        return fields.toArray(new String[0]);
    }

    private static String getVal(String[] vals, Map<String, Integer> idx, String key) {
        Integer i = idx.get(key);
        return (i == null || i >= vals.length) ? "" : vals[i];
    }

    private static double getDoubleVal(String[] vals, Map<String, Integer> idx, String key) {
        String v = getVal(vals, idx, key);
        if (v.isBlank()) return Double.NaN;
        try { return Double.parseDouble(v); } catch (NumberFormatException e) { return Double.NaN; }
    }

    private static int getIntVal(String[] vals, Map<String, Integer> idx, String key) {
        String v = getVal(vals, idx, key);
        if (v.isBlank()) return 0;
        try { return Integer.parseInt(v); } catch (NumberFormatException e) { return 0; }
    }
}
