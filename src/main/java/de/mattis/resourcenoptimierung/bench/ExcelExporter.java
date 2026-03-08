package de.mattis.resourcenoptimierung.bench;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xddf.usermodel.XDDFColor;
import org.apache.poi.xddf.usermodel.XDDFSolidFillProperties;
import org.apache.poi.xddf.usermodel.chart.*;
import org.apache.poi.xssf.usermodel.*;
import org.openxmlformats.schemas.drawingml.x2006.chart.*;
import org.openxmlformats.schemas.drawingml.x2006.main.CTSRgbColor;
import org.openxmlformats.schemas.drawingml.x2006.main.CTShapeProperties;
import org.openxmlformats.schemas.drawingml.x2006.main.CTSolidColorFillProperties;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.ToDoubleFunction;
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

    // Laufzeittyp-Farben fuer Multi-Runtime-Diagramme
    private static final byte[] CLR_RT_HOTSPOT  = rgb(0x2B, 0x57, 0x9A);  // blau
    private static final byte[] CLR_RT_OPENJ9   = rgb(0x16, 0xA0, 0x85);  // tuerkis/teal
    private static final byte[] CLR_RT_NATIVE   = rgb(0xE6, 0x7E, 0x22);  // orange

    private static byte[] rgb(int r, int g, int b) {
        return new byte[]{(byte) r, (byte) g, (byte) b};
    }

    // ───────────────────── Runtime-Type-Erkennung ─────────────────────

    /**
     * Leitet den RuntimeType aus dem Docker-Image-Namen ab.
     * Erkennt "openj9" und "native" als Schluesselwoerter; alles andere ist HOTSPOT.
     */
    static RuntimeType inferRuntimeType(String dockerImage) {
        if (dockerImage == null) return RuntimeType.HOTSPOT;
        String lower = dockerImage.toLowerCase();
        if (lower.contains("openj9") || lower.contains("semeru")) return RuntimeType.OPENJ9;
        if (lower.contains("native") || lower.contains("graalvm-native")) return RuntimeType.NATIVE;
        return RuntimeType.HOTSPOT;
    }

    /** Gibt die Diagrammfarbe fuer einen Laufzeittyp zurueck. */
    private static byte[] runtimeColor(RuntimeType rt) {
        return switch (rt) {
            case OPENJ9 -> CLR_RT_OPENJ9;
            case NATIVE -> CLR_RT_NATIVE;
            default     -> CLR_RT_HOTSPOT;
        };
    }

    // ───────────────────── Chart-Serien-Definition ─────────────────────

    /** Beschreibt eine Datenreihe innerhalb eines Balkendiagramms. */
    private record ChartSeries(String title, int column, byte[] color) {}

    /** Beschreibt eine Chart-Serie mit zugehoerigem CI-Spaltenindex fuer Error Bars. */
    private record ChartSeriesCI(String title, int column, int ciColumn, byte[] color) {}

    // ───────────────────── Aggregation (Repetitionen → eine Zeile pro Config) ─────────────────────

    /**
     * Gruppiert RunResults nach configName (Reihenfolge des ersten Auftretens).
     */
    private static Map<String, List<RunResult>> groupByConfig(List<RunResult> results) {
        Map<String, List<RunResult>> groups = new LinkedHashMap<>();
        for (RunResult r : results)
            groups.computeIfAbsent(r.configName(), k -> new ArrayList<>()).add(r);
        return groups;
    }

    /**
     * Gruppiert CsvRows nach configName (Reihenfolge des ersten Auftretens).
     */
    private static Map<String, List<CsvRow>> groupCsvByConfig(List<CsvRow> rows) {
        Map<String, List<CsvRow>> groups = new LinkedHashMap<>();
        for (CsvRow r : rows)
            groups.computeIfAbsent(r.configName(), k -> new ArrayList<>()).add(r);
        return groups;
    }

    /**
     * Berechnet Mean und 95%-CI-Halbbreite fuer eine Liste von RunResults und einen extractor.
     *
     * @return double[2]: {mean, ci95} — ci95 ist NaN bei n&lt;2
     */
    private static double[] meanAndCi(List<RunResult> group, ToDoubleFunction<RunResult> extractor) {
        double[] vals = group.stream().mapToDouble(extractor).toArray();
        double mean = BenchStats.mean(vals);
        double ci = vals.length >= 2 ? BenchStats.confidenceInterval95(vals) : Double.NaN;
        return new double[]{mean, ci};
    }

    /**
     * Berechnet Mean und 95%-CI-Halbbreite fuer eine Liste von CsvRows und einen extractor.
     *
     * @return double[2]: {mean, ci95} — ci95 ist NaN bei n&lt;2
     */
    private static double[] meanAndCiCsv(List<CsvRow> group, ToDoubleFunction<CsvRow> extractor) {
        double[] vals = group.stream().mapToDouble(extractor).toArray();
        double mean = BenchStats.mean(vals);
        double ci = vals.length >= 2 ? BenchStats.confidenceInterval95(vals) : Double.NaN;
        return new double[]{mean, ci};
    }

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
            writeMergedRessourcen(wb, styles, allRows);
            writeMergedAggregation(wb, styles, allRows);
            writeMergedRanking(wb, styles, allRows);

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
                "Config", "Szenario", "JVM-Flags", "Docker-Image", "Kategorie", "Laufzeitmodell",
                "Readiness (ms)", "First Req (s)",
                "p50 (s)", "p95 (s)", "p99 (s)", "Mean (s)", "Latenz-Count",
                "Messzeit (min)", "Throughput (req/s)",
                "CPU% (LOAD avg)", "Mem% (LOAD avg)", "Mem% (LOAD max)",
                "Mem% (IDLE avg)",
                "GC-Pausen (Anzahl)", "Full GC (Anzahl)", "GC Pause Gesamt (s)", "GC Max Pause (s)", "GC Overhead (%)", "Peak Heap nach GC (MB)",
                "Warmup", "Mess-Req", "Concurrency", "Sleep (ms)",
                "Workload-Pfad", "Workload-N", "Repetition"
        };

        // Section-Header-Zeile (Kategorien ueber den Spalten)
        XSSFRow sectionRow = sheet.createRow(0);
        sectionRow.setHeightInPoints(22);
        writeSectionHeaders(sectionRow, s, new String[]{
                "Konfiguration", "", "", "", "", "",
                "Startup", "",
                "Latenzen", "", "", "", "",
                "Durchsatz", "",
                "Docker (LOAD)", "", "",
                "Docker (IDLE)",
                "GC-Verhalten", "", "", "", "", "",
                "Messprofil", "", "", "",
                "Meta", "", ""
        });
        mergeIfValid(sheet, 0, 0, 0, 5);    // Konfiguration  (6 cols)
        mergeIfValid(sheet, 0, 0, 6, 7);    // Startup         (2 cols)
        mergeIfValid(sheet, 0, 0, 8, 12);   // Latenzen        (5 cols)
        mergeIfValid(sheet, 0, 0, 13, 14);  // Durchsatz       (2 cols)
        mergeIfValid(sheet, 0, 0, 15, 17);  // Docker (LOAD)   (3 cols)
        // col 18 = Docker (IDLE) – single col, no merge
        mergeIfValid(sheet, 0, 0, 19, 24);  // GC-Verhalten    (6 cols)
        mergeIfValid(sheet, 0, 0, 25, 28);  // Messprofil      (4 cols)
        mergeIfValid(sheet, 0, 0, 29, 31);  // Meta            (3 cols)

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
            setCell(row, c++, r.category() == null ? "" : r.category(),  pick(s, i, SK.TEXT));
            setCell(row, c++, r.runtimeModel() == null ? "" : r.runtimeModel(), pick(s, i, SK.TEXT));

            setNum(row, c++, r.readinessMs(),          pick(s, i, SK.INT));
            setNum(row, c++, r.firstSeconds(),         pick(s, i, SK.DEC4));

            setNum(row, c++, percentile(lats, 0.50),   pick(s, i, SK.DEC4));
            setNum(row, c++, percentile(lats, 0.95),   pick(s, i, SK.DEC4));
            setNum(row, c++, percentile(lats, 0.99),   pick(s, i, SK.DEC4));
            setNum(row, c++, mean,                     pick(s, i, SK.DEC4));
            setNum(row, c++, lats.size(),              pick(s, i, SK.INT));

            setNum(row, c++, r.totalMeasureTimeSeconds() / 60.0, pick(s, i, SK.DEC2));
            setNum(row, c++, r.throughputReqPerSec(),     pick(s, i, SK.DEC2));

            setNum(row, c++, dval(load, a -> a.cpuAvg), pick(s, i, SK.PCT));
            setNum(row, c++, dval(load, a -> a.memAvg), pick(s, i, SK.PCT));
            setNum(row, c++, dval(load, a -> a.memMax), pick(s, i, SK.PCT));
            setNum(row, c++, dval(idle, a -> a.memAvg), pick(s, i, SK.PCT));

            // GC-Verhalten
            GcSummary gc = r.gcSummary();
            if (gc != null) {
                setNum(row, c++, gc.gcCount(),              pick(s, i, SK.INT));
                setNum(row, c++, gc.fullGcCount(),          pick(s, i, SK.INT));
                setNum(row, c++, gc.totalPauseMs() / 1000.0, pick(s, i, SK.DEC4));
                setNum(row, c++, gc.maxPauseMs() / 1000.0,   pick(s, i, SK.DEC4));
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

        // CPU%-Header-Kommentar (Erklaerung fuer Docker >100% Artefakt)
        addCellComment(sheet, 1, 15, CPU_COMMENT);

        // Bedingte Farbskala: Latenzen (p50=8, p95=9, p99=10)
        addColorScale(sheet, 2, 1 + results.size(), 8, 10);
        // Bedingte Farbskala: Ressourcen (CPU% LOAD=15 bis Mem% IDLE=18)
        addColorScale(sheet, 2, 1 + results.size(), 15, 18);
        // Bedingte Farbskala: GC Max Pause=22, GC Overhead=23
        addColorScale(sheet, 2, 1 + results.size(), 22, 23);

        autoSizeColumns(sheet, headers.length);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Sheet 2: Latenzen
    // ═══════════════════════════════════════════════════════════════════

    private static void writeLatenzen(XSSFWorkbook wb, Styles s, List<RunResult> results) {
        XSSFSheet sheet = wb.createSheet("Latenzen");
        // Sichtbare Spalten + versteckte CI-Spalten fuer Error Bars
        String[] visibleCols = {"Config", "JVM-Flags", "p50 (s)", "p95 (s)", "p99 (s)", "Mean (s)"};
        // CI-Spalten: 6=CI p50, 7=CI p95, 8=CI p99, 9=CI mean
        String[] allCols = {"Config", "JVM-Flags", "p50 (s)", "p95 (s)", "p99 (s)", "Mean (s)",
                "CI p50", "CI p95", "CI p99", "CI Mean"};
        writeHeaderRow(sheet, 0, allCols, s);

        Map<String, List<RunResult>> groups = groupByConfig(results);
        int i = 0;
        for (var entry : groups.entrySet()) {
            List<RunResult> group = entry.getValue();
            // Latenz-Aggregate: fuer jede Rep die percentile berechnen, dann mean/CI ueber Reps
            double[] p50s = group.stream().mapToDouble(r -> percentile(sorted(r.latenciesSeconds()), 0.50)).toArray();
            double[] p95s = group.stream().mapToDouble(r -> percentile(sorted(r.latenciesSeconds()), 0.95)).toArray();
            double[] p99s = group.stream().mapToDouble(r -> percentile(sorted(r.latenciesSeconds()), 0.99)).toArray();
            double[] means = group.stream().mapToDouble(r -> sorted(r.latenciesSeconds()).stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN)).toArray();

            XSSFRow row = sheet.createRow(i + 1);
            setCell(row, 0, entry.getKey(),                                                    pick(s, i, SK.TEXT));
            setCell(row, 1, normalizeFlags(group.get(0).effectiveJavaToolOptions()),           pick(s, i, SK.TEXT));
            setNum(row, 2, BenchStats.mean(p50s),                                              pick(s, i, SK.DEC4));
            setNum(row, 3, BenchStats.mean(p95s),                                              pick(s, i, SK.DEC4));
            setNum(row, 4, BenchStats.mean(p99s),                                              pick(s, i, SK.DEC4));
            setNum(row, 5, BenchStats.mean(means),                                             pick(s, i, SK.DEC4));
            // CI-Spalten (versteckt)
            setNum(row, 6, p50s.length >= 2 ? BenchStats.confidenceInterval95(p50s) : Double.NaN, pick(s, i, SK.DEC4));
            setNum(row, 7, p95s.length >= 2 ? BenchStats.confidenceInterval95(p95s) : Double.NaN, pick(s, i, SK.DEC4));
            setNum(row, 8, p99s.length >= 2 ? BenchStats.confidenceInterval95(p99s) : Double.NaN, pick(s, i, SK.DEC4));
            setNum(row, 9, means.length >= 2 ? BenchStats.confidenceInterval95(means) : Double.NaN, pick(s, i, SK.DEC4));
            i++;
        }
        int dataRows = groups.size();

        // CI-Spalten verstecken
        for (int c = 6; c <= 9; c++) sheet.setColumnHidden(c, true);

        if (dataRows >= 2) {
            List<RuntimeType> runtimes = groups.values().stream()
                    .map(g -> inferRuntimeType(g.get(0).dockerImage()))
                    .toList();
            addBarChartWithCI(sheet, dataRows, "Latenz-Vergleich (s)",
                    "Konfiguration", "Latenz (s)", 0, 0, true, runtimes,
                    new ChartSeriesCI("p50", 2, 6, CLR_GREEN),
                    new ChartSeriesCI("p95", 3, 7, CLR_ORANGE),
                    new ChartSeriesCI("p99", 4, 8, CLR_RED));
            addColorScale(sheet, 1, dataRows, 2, 5);
        }
        sheet.setAutoFilter(new CellRangeAddress(0, dataRows, 0, visibleCols.length - 1));
        sheet.createFreezePane(1, 1);
        autoSizeColumns(sheet, visibleCols.length);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Sheet 3: Startup & Throughput
    // ═══════════════════════════════════════════════════════════════════

    private static void writeStartupThroughput(XSSFWorkbook wb, Styles s, List<RunResult> results) {
        XSSFSheet sheet = wb.createSheet("Startup & Throughput");
        String[] visibleCols = {"Config", "JVM-Flags", "Readiness (ms)", "First Req (s)", "Throughput (req/s)"};
        // CI-Spalten: 5=CI Readiness, 6=CI First, 7=CI Throughput
        String[] allCols = {"Config", "JVM-Flags", "Readiness (ms)", "First Req (s)", "Throughput (req/s)",
                "CI Readiness", "CI First", "CI Throughput"};
        writeHeaderRow(sheet, 0, allCols, s);

        Map<String, List<RunResult>> groups = groupByConfig(results);
        int i = 0;
        for (var entry : groups.entrySet()) {
            List<RunResult> group = entry.getValue();
            double[] mc;
            XSSFRow row = sheet.createRow(i + 1);
            setCell(row, 0, entry.getKey(),                                              pick(s, i, SK.TEXT));
            setCell(row, 1, normalizeFlags(group.get(0).effectiveJavaToolOptions()),     pick(s, i, SK.TEXT));

            mc = meanAndCi(group, r -> (double) r.readinessMs());
            setNum(row, 2, mc[0], pick(s, i, SK.INT));
            setNum(row, 5, mc[1], pick(s, i, SK.INT));

            mc = meanAndCi(group, RunResult::firstSeconds);
            setNum(row, 3, mc[0], pick(s, i, SK.DEC4));
            setNum(row, 6, mc[1], pick(s, i, SK.DEC4));

            mc = meanAndCi(group, RunResult::throughputReqPerSec);
            setNum(row, 4, mc[0], pick(s, i, SK.DEC2));
            setNum(row, 7, mc[1], pick(s, i, SK.DEC2));
            i++;
        }
        int dataRows = groups.size();

        for (int c = 5; c <= 7; c++) sheet.setColumnHidden(c, true);

        if (dataRows >= 2) {
            List<RuntimeType> runtimes = groups.values().stream()
                    .map(g -> inferRuntimeType(g.get(0).dockerImage()))
                    .toList();
            addBarChartWithCI(sheet, dataRows, "Startup-Zeit (ms)",
                    "Konfiguration", "Readiness (ms)", 0, 0, false, runtimes,
                    new ChartSeriesCI("Readiness (ms)", 2, 5, CLR_DARK_BLUE));

            int chartHeight = Math.max(12, dataRows * 2);
            addBarChartWithCI(sheet, dataRows, "Durchsatz (req/s)",
                    "Konfiguration", "Throughput (req/s)", 0, chartHeight + 4, false, runtimes,
                    new ChartSeriesCI("Throughput (req/s)", 4, 7, CLR_GREEN));
        }
        sheet.setAutoFilter(new CellRangeAddress(0, dataRows, 0, visibleCols.length - 1));
        sheet.createFreezePane(1, 1);
        autoSizeColumns(sheet, visibleCols.length);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Sheet 4: Ressourcen
    // ═══════════════════════════════════════════════════════════════════

    private static void writeRessourcen(XSSFWorkbook wb, Styles s, List<RunResult> results) {
        XSSFSheet sheet = wb.createSheet("Ressourcen");
        String[] visibleCols = {
                "Config", "JVM-Flags",
                "CPU% IDLE", "CPU% LOAD", "CPU% POST",
                "Mem% IDLE", "Mem% LOAD", "Mem% POST",
                "Mem% LOAD max"
        };
        // CI-Spalten: 9=CI CPU IDLE, 10=CI CPU LOAD, 11=CI CPU POST,
        //             12=CI Mem IDLE, 13=CI Mem LOAD, 14=CI Mem POST, 15=CI Mem max
        String[] allCols = {
                "Config", "JVM-Flags",
                "CPU% IDLE", "CPU% LOAD", "CPU% POST",
                "Mem% IDLE", "Mem% LOAD", "Mem% POST",
                "Mem% LOAD max",
                "CI CPU IDLE", "CI CPU LOAD", "CI CPU POST",
                "CI Mem IDLE", "CI Mem LOAD", "CI Mem POST", "CI Mem max"
        };
        writeHeaderRow(sheet, 0, allCols, s);

        Map<String, List<RunResult>> groups = groupByConfig(results);
        int i = 0;
        for (var entry : groups.entrySet()) {
            List<RunResult> group = entry.getValue();
            XSSFRow row = sheet.createRow(i + 1);
            setCell(row, 0, entry.getKey(),                                           pick(s, i, SK.TEXT));
            setCell(row, 1, normalizeFlags(group.get(0).effectiveJavaToolOptions()),  pick(s, i, SK.TEXT));

            // Berechne pro RunResult die Phase-Averages, dann mean/CI ueber Reps
            double[] cpuIdle = group.stream().mapToDouble(r -> dval(phaseAvg(r.dockerIdleSamples()), a -> a.cpuAvg)).toArray();
            double[] cpuLoad = group.stream().mapToDouble(r -> dval(phaseAvg(r.dockerLoadSamples()), a -> a.cpuAvg)).toArray();
            double[] cpuPost = group.stream().mapToDouble(r -> dval(phaseAvg(r.dockerPostSamples()), a -> a.cpuAvg)).toArray();
            double[] memIdle = group.stream().mapToDouble(r -> dval(phaseAvg(r.dockerIdleSamples()), a -> a.memAvg)).toArray();
            double[] memLoad = group.stream().mapToDouble(r -> dval(phaseAvg(r.dockerLoadSamples()), a -> a.memAvg)).toArray();
            double[] memPost = group.stream().mapToDouble(r -> dval(phaseAvg(r.dockerPostSamples()), a -> a.memAvg)).toArray();
            double[] memMax  = group.stream().mapToDouble(r -> dval(phaseAvg(r.dockerLoadSamples()), a -> a.memMax)).toArray();

            setNum(row, 2, BenchStats.mean(cpuIdle), pick(s, i, SK.PCT));
            setNum(row, 3, BenchStats.mean(cpuLoad), pick(s, i, SK.PCT));
            setNum(row, 4, BenchStats.mean(cpuPost), pick(s, i, SK.PCT));
            setNum(row, 5, BenchStats.mean(memIdle), pick(s, i, SK.PCT));
            setNum(row, 6, BenchStats.mean(memLoad), pick(s, i, SK.PCT));
            setNum(row, 7, BenchStats.mean(memPost), pick(s, i, SK.PCT));
            setNum(row, 8, BenchStats.mean(memMax),  pick(s, i, SK.PCT));

            // CI columns
            setNum(row, 9,  cpuIdle.length >= 2 ? BenchStats.confidenceInterval95(cpuIdle) : Double.NaN, pick(s, i, SK.PCT));
            setNum(row, 10, cpuLoad.length >= 2 ? BenchStats.confidenceInterval95(cpuLoad) : Double.NaN, pick(s, i, SK.PCT));
            setNum(row, 11, cpuPost.length >= 2 ? BenchStats.confidenceInterval95(cpuPost) : Double.NaN, pick(s, i, SK.PCT));
            setNum(row, 12, memIdle.length >= 2 ? BenchStats.confidenceInterval95(memIdle) : Double.NaN, pick(s, i, SK.PCT));
            setNum(row, 13, memLoad.length >= 2 ? BenchStats.confidenceInterval95(memLoad) : Double.NaN, pick(s, i, SK.PCT));
            setNum(row, 14, memPost.length >= 2 ? BenchStats.confidenceInterval95(memPost) : Double.NaN, pick(s, i, SK.PCT));
            setNum(row, 15, memMax.length >= 2  ? BenchStats.confidenceInterval95(memMax)  : Double.NaN, pick(s, i, SK.PCT));
            i++;
        }
        int dataRows = groups.size();

        for (int c = 9; c <= 15; c++) sheet.setColumnHidden(c, true);

        if (dataRows >= 2) {
            List<RuntimeType> runtimes = groups.values().stream()
                    .map(g -> inferRuntimeType(g.get(0).dockerImage()))
                    .toList();
            addBarChartWithCI(sheet, dataRows, "Ressourcenverbrauch unter Last (LOAD)",
                    "Konfiguration", "%", 0, 0, true, runtimes,
                    new ChartSeriesCI("CPU% LOAD", 3, 10, CLR_ORANGE),
                    new ChartSeriesCI("Mem% LOAD", 6, 13, CLR_DARK_BLUE));
            addColorScale(sheet, 1, dataRows, 2, 8);
        }

        // CPU%-Header-Kommentare
        addCellComment(sheet, 0, 2, CPU_COMMENT);
        addCellComment(sheet, 0, 3, CPU_COMMENT);
        addCellComment(sheet, 0, 4, CPU_COMMENT);

        sheet.setAutoFilter(new CellRangeAddress(0, dataRows, 0, visibleCols.length - 1));
        sheet.createFreezePane(1, 1);
        autoSizeColumns(sheet, visibleCols.length);
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

        sheet.setAutoFilter(new CellRangeAddress(0, Math.max(1, rowIdx - 1), 0, cols.length - 1));
        sheet.createFreezePane(0, 1);
        autoSizeColumns(sheet, cols.length);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Sheet 6: GC-Verhalten (Level 2 – aggregierte GC-Kennzahlen)
    // ═══════════════════════════════════════════════════════════════════

    private static void writeGcVerhalten(XSSFWorkbook wb, Styles s, List<RunResult> results) {
        XSSFSheet sheet = wb.createSheet("GC-Verhalten");
        String[] visibleCols = {
                "Config", "JVM-Flags",
                "GC-Pausen (Anzahl)", "Full GC (Anzahl)",
                "Pause Gesamt (s)", "Max Pause (s)",
                "Overhead (%)", "Peak Heap (MB)"
        };
        // CI-Spalten: 8=CI gcCount, 9=CI fullGc, 10=CI totalPause, 11=CI maxPause, 12=CI overhead, 13=CI peakHeap
        String[] allCols = {
                "Config", "JVM-Flags",
                "GC-Pausen (Anzahl)", "Full GC (Anzahl)",
                "Pause Gesamt (s)", "Max Pause (s)",
                "Overhead (%)", "Peak Heap (MB)",
                "CI GC-Pausen", "CI Full GC",
                "CI Pause Gesamt", "CI Max Pause",
                "CI Overhead", "CI Peak Heap"
        };
        writeHeaderRow(sheet, 0, allCols, s);

        Map<String, List<RunResult>> groups = groupByConfig(results);
        int i = 0;
        for (var entry : groups.entrySet()) {
            List<RunResult> group = entry.getValue();
            XSSFRow row = sheet.createRow(i + 1);
            setCell(row, 0, entry.getKey(),                                           pick(s, i, SK.TEXT));
            setCell(row, 1, normalizeFlags(group.get(0).effectiveJavaToolOptions()),  pick(s, i, SK.TEXT));

            // Extraktoren fuer GC-Metriken (NaN wenn gcSummary null)
            double[] gcCounts    = group.stream().mapToDouble(r -> r.gcSummary() != null ? r.gcSummary().gcCount() : Double.NaN).toArray();
            double[] fullGcs     = group.stream().mapToDouble(r -> r.gcSummary() != null ? r.gcSummary().fullGcCount() : Double.NaN).toArray();
            double[] totalPauses = group.stream().mapToDouble(r -> r.gcSummary() != null ? r.gcSummary().totalPauseMs() / 1000.0 : Double.NaN).toArray();
            double[] maxPauses   = group.stream().mapToDouble(r -> r.gcSummary() != null ? r.gcSummary().maxPauseMs() / 1000.0 : Double.NaN).toArray();
            double[] overheads   = group.stream().mapToDouble(r -> r.gcSummary() != null ? r.gcSummary().gcOverheadPercent() : Double.NaN).toArray();
            double[] peakHeaps   = group.stream().mapToDouble(r -> r.gcSummary() != null ? r.gcSummary().peakHeapAfterGcKb() / 1024.0 : Double.NaN).toArray();

            setNum(row, 2, BenchStats.mean(gcCounts),    pick(s, i, SK.INT));
            setNum(row, 3, BenchStats.mean(fullGcs),     pick(s, i, SK.INT));
            setNum(row, 4, BenchStats.mean(totalPauses), pick(s, i, SK.DEC4));
            setNum(row, 5, BenchStats.mean(maxPauses),   pick(s, i, SK.DEC4));
            setNum(row, 6, BenchStats.mean(overheads),   pick(s, i, SK.PCT));
            setNum(row, 7, BenchStats.mean(peakHeaps),   pick(s, i, SK.INT));

            // CI columns
            setNum(row, 8,  gcCounts.length >= 2    ? BenchStats.confidenceInterval95(gcCounts)    : Double.NaN, pick(s, i, SK.INT));
            setNum(row, 9,  fullGcs.length >= 2     ? BenchStats.confidenceInterval95(fullGcs)     : Double.NaN, pick(s, i, SK.INT));
            setNum(row, 10, totalPauses.length >= 2 ? BenchStats.confidenceInterval95(totalPauses) : Double.NaN, pick(s, i, SK.DEC4));
            setNum(row, 11, maxPauses.length >= 2   ? BenchStats.confidenceInterval95(maxPauses)   : Double.NaN, pick(s, i, SK.DEC4));
            setNum(row, 12, overheads.length >= 2   ? BenchStats.confidenceInterval95(overheads)   : Double.NaN, pick(s, i, SK.PCT));
            setNum(row, 13, peakHeaps.length >= 2   ? BenchStats.confidenceInterval95(peakHeaps)   : Double.NaN, pick(s, i, SK.INT));
            i++;
        }
        int dataRows = groups.size();

        for (int c = 8; c <= 13; c++) sheet.setColumnHidden(c, true);

        if (dataRows >= 2) {
            List<RuntimeType> runtimes = groups.values().stream()
                    .map(g -> inferRuntimeType(g.get(0).dockerImage()))
                    .toList();
            // Chart 1: GC-Pausen-Vergleich (Total Pause + Max Pause) – logarithmische Y-Achse
            addBarChartLogYWithCI(sheet, dataRows, "GC-Pausen-Vergleich (logarithmisch)",
                    "Konfiguration", "Pause (s)", 0, 0, runtimes,
                    new ChartSeriesCI("Pause Gesamt (s)", 4, 10, CLR_DARK_BLUE),
                    new ChartSeriesCI("Max Pause (s)", 5, 11, CLR_RED));

            // Chart 2: GC Overhead (%) – unterhalb des ersten Charts
            int chartHeight = Math.max(12, dataRows * 2);
            addBarChartWithCI(sheet, dataRows, "GC Overhead (%)",
                    "Konfiguration", "Overhead (%)", 0, chartHeight + 4, false, runtimes,
                    new ChartSeriesCI("Overhead (%)", 6, 12, CLR_ORANGE));
        }
        sheet.setAutoFilter(new CellRangeAddress(0, dataRows, 0, visibleCols.length - 1));
        sheet.createFreezePane(1, 1);
        autoSizeColumns(sheet, visibleCols.length);
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

        sheet.setAutoFilter(new CellRangeAddress(0, Math.max(1, rowIdx - 1), 0, cols.length - 1));
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
            yAxis.setMinimum(0.0);

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

            // Smooth-Interpolation deaktivieren: gerade Linien statt Kurven
            for (CTScatterChart sc : chart.getCTChart().getPlotArea().getScatterChartList()) {
                for (CTScatterSer ser : sc.getSerArray()) {
                    ser.addNewSmooth().setVal(false);
                }
            }

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
                "Timestamp", "Config", "Szenario", "JVM-Flags", "Kategorie", "Laufzeitmodell",
                "Readiness (ms)", "First Req (s)",
                "p50 (s)", "p95 (s)", "p99 (s)", "Mean (s)",
                "Messzeit (min)", "Throughput (req/s)",
                "CPU% (LOAD avg)", "Mem% (LOAD avg)", "Mem% (LOAD max)",
                "GC-Pausen (Anzahl)", "Full GC (Anzahl)",
                "GC Pause Gesamt (s)", "GC Max Pause (s)",
                "GC Overhead (%)", "Peak Heap nach GC (MB)",
                "Warmup", "Mess-Req", "Concurrency", "Sleep (ms)",
                "Workload-Pfad", "Workload-N", "Repetition"
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
            setCell(row, c++, r.category() == null ? "" : r.category(),  pick(s, i, SK.TEXT));
            setCell(row, c++, r.runtimeModel() == null ? "" : r.runtimeModel(), pick(s, i, SK.TEXT));
            setNum(row, c++, r.readinessMs(),      pick(s, i, SK.INT));
            setNum(row, c++, r.firstSeconds(),     pick(s, i, SK.DEC4));
            setNum(row, c++, r.p50(),              pick(s, i, SK.DEC4));
            setNum(row, c++, r.p95(),              pick(s, i, SK.DEC4));
            setNum(row, c++, r.p99(),              pick(s, i, SK.DEC4));
            setNum(row, c++, r.mean(),             pick(s, i, SK.DEC4));
            setNum(row, c++, r.totalMeasureTime() / 60.0, pick(s, i, SK.DEC2));
            setNum(row, c++, r.throughput(),       pick(s, i, SK.DEC2));
            setNum(row, c++, r.cpuLoadAvg(),       pick(s, i, SK.PCT));
            setNum(row, c++, r.memLoadAvg(),       pick(s, i, SK.PCT));
            setNum(row, c++, r.memLoadMax(),       pick(s, i, SK.PCT));
            setNum(row, c++, r.gcCount(),          pick(s, i, SK.INT));
            setNum(row, c++, r.gcFullCount(),      pick(s, i, SK.INT));
            setNum(row, c++, r.gcTotalPauseMs() / 1000.0, pick(s, i, SK.DEC4));
            setNum(row, c++, r.gcMaxPauseMs() / 1000.0,   pick(s, i, SK.DEC4));
            setNum(row, c++, r.gcOverheadPercent(), pick(s, i, SK.PCT));
            setNum(row, c++, r.gcPeakHeapAfterMb(), pick(s, i, SK.INT));
            setNum(row, c++, r.warmup(),           pick(s, i, SK.INT));
            setNum(row, c++, r.measureReqs(),      pick(s, i, SK.INT));
            setNum(row, c++, r.concurrency(),      pick(s, i, SK.INT));
            setNum(row, c++, r.sleepMs(),          pick(s, i, SK.INT));
            setCell(row, c++, r.workloadPath(),    pick(s, i, SK.TEXT));
            setNum(row, c++, r.workloadN(),        pick(s, i, SK.INT));
            setNum(row, c++, r.repetition(),       pick(s, i, SK.INT));
        }

        sheet.setAutoFilter(new CellRangeAddress(0, rows.size(), 0, headers.length - 1));
        sheet.createFreezePane(2, 1);

        // CPU%-Header-Kommentar
        addCellComment(sheet, 0, 14, CPU_COMMENT);

        // Farbskala: Latenzen (p50=8 bis Mean=11)
        if (rows.size() >= 2) {
            addColorScale(sheet, 1, rows.size(), 8, 11);
            // Farbskala: Ressourcen (CPU%=14 bis Mem% max=16)
            addColorScale(sheet, 1, rows.size(), 14, 16);
            // Farbskala: GC Max Pause=20, GC Overhead=21
            addColorScale(sheet, 1, rows.size(), 20, 21);
        }
        autoSizeColumns(sheet, headers.length);
    }

    private static void writeMergedLatencyChart(XSSFWorkbook wb, Styles s, List<CsvRow> rows) {
        XSSFSheet sheet = wb.createSheet("Latenzen (alle Runs)");
        String[] visibleCols = {"Config", "JVM-Flags", "p50 (s)", "p95 (s)", "p99 (s)"};
        // CI-Spalten: 5=CI p50, 6=CI p95, 7=CI p99
        String[] allCols = {"Config", "JVM-Flags", "p50 (s)", "p95 (s)", "p99 (s)",
                "CI p50", "CI p95", "CI p99"};
        writeHeaderRow(sheet, 0, allCols, s);

        Map<String, List<CsvRow>> groups = groupCsvByConfig(rows);
        int i = 0;
        for (var entry : groups.entrySet()) {
            List<CsvRow> group = entry.getValue();
            double[] p50s  = group.stream().mapToDouble(CsvRow::p50).toArray();
            double[] p95s  = group.stream().mapToDouble(CsvRow::p95).toArray();
            double[] p99s  = group.stream().mapToDouble(CsvRow::p99).toArray();

            XSSFRow row = sheet.createRow(i + 1);
            setCell(row, 0, entry.getKey(),                       pick(s, i, SK.TEXT));
            setCell(row, 1, group.get(0).jvmFlags(),              pick(s, i, SK.TEXT));
            setNum(row, 2, BenchStats.mean(p50s),                 pick(s, i, SK.DEC4));
            setNum(row, 3, BenchStats.mean(p95s),                 pick(s, i, SK.DEC4));
            setNum(row, 4, BenchStats.mean(p99s),                 pick(s, i, SK.DEC4));
            setNum(row, 5, p50s.length >= 2 ? BenchStats.confidenceInterval95(p50s) : Double.NaN, pick(s, i, SK.DEC4));
            setNum(row, 6, p95s.length >= 2 ? BenchStats.confidenceInterval95(p95s) : Double.NaN, pick(s, i, SK.DEC4));
            setNum(row, 7, p99s.length >= 2 ? BenchStats.confidenceInterval95(p99s) : Double.NaN, pick(s, i, SK.DEC4));
            i++;
        }
        int dataRows = groups.size();

        for (int c = 5; c <= 7; c++) sheet.setColumnHidden(c, true);

        if (dataRows >= 2) {
            List<RuntimeType> runtimes = groups.values().stream()
                    .map(g -> inferRuntimeType(g.get(0).dockerImage()))
                    .toList();
            addBarChartWithCI(sheet, dataRows, "Latenz-Vergleich alle Runs (s)",
                    "Konfiguration", "Latenz (s)", 0, 0, true, runtimes,
                    new ChartSeriesCI("p50", 2, 5, CLR_GREEN),
                    new ChartSeriesCI("p95", 3, 6, CLR_ORANGE),
                    new ChartSeriesCI("p99", 4, 7, CLR_RED));
        }
        sheet.setAutoFilter(new CellRangeAddress(0, dataRows, 0, visibleCols.length - 1));
        sheet.createFreezePane(1, 1);
        autoSizeColumns(sheet, visibleCols.length);
    }

    private static void writeMergedStartupChart(XSSFWorkbook wb, Styles s, List<CsvRow> rows) {
        XSSFSheet sheet = wb.createSheet("Startup (alle Runs)");
        String[] visibleCols = {"Config", "JVM-Flags", "Readiness (ms)", "First Req (s)", "Throughput (req/s)"};
        // CI-Spalten: 5=CI Readiness, 6=CI First, 7=CI Throughput
        String[] allCols = {"Config", "JVM-Flags", "Readiness (ms)", "First Req (s)", "Throughput (req/s)",
                "CI Readiness", "CI First", "CI Throughput"};
        writeHeaderRow(sheet, 0, allCols, s);

        Map<String, List<CsvRow>> groups = groupCsvByConfig(rows);
        int i = 0;
        for (var entry : groups.entrySet()) {
            List<CsvRow> group = entry.getValue();
            double[] mc;
            XSSFRow row = sheet.createRow(i + 1);
            setCell(row, 0, entry.getKey(),              pick(s, i, SK.TEXT));
            setCell(row, 1, group.get(0).jvmFlags(),     pick(s, i, SK.TEXT));

            mc = meanAndCiCsv(group, CsvRow::readinessMs);
            setNum(row, 2, mc[0], pick(s, i, SK.INT));
            setNum(row, 5, mc[1], pick(s, i, SK.INT));

            mc = meanAndCiCsv(group, CsvRow::firstSeconds);
            setNum(row, 3, mc[0], pick(s, i, SK.DEC4));
            setNum(row, 6, mc[1], pick(s, i, SK.DEC4));

            mc = meanAndCiCsv(group, CsvRow::throughput);
            setNum(row, 4, mc[0], pick(s, i, SK.DEC2));
            setNum(row, 7, mc[1], pick(s, i, SK.DEC2));
            i++;
        }
        int dataRows = groups.size();

        for (int c = 5; c <= 7; c++) sheet.setColumnHidden(c, true);

        if (dataRows >= 2) {
            List<RuntimeType> runtimes = groups.values().stream()
                    .map(g -> inferRuntimeType(g.get(0).dockerImage()))
                    .toList();
            addBarChartWithCI(sheet, dataRows, "Startup & Throughput alle Runs",
                    "Konfiguration", null, 0, 0, true, runtimes,
                    new ChartSeriesCI("Readiness (ms)", 2, 5, CLR_DARK_BLUE),
                    new ChartSeriesCI("Throughput (req/s)", 4, 7, CLR_GREEN));
        }
        sheet.setAutoFilter(new CellRangeAddress(0, dataRows, 0, visibleCols.length - 1));
        sheet.createFreezePane(1, 1);
        autoSizeColumns(sheet, visibleCols.length);
    }

    private static void writeMergedRessourcen(XSSFWorkbook wb, Styles s, List<CsvRow> rows) {
        XSSFSheet sheet = wb.createSheet("Ressourcen (alle Runs)");
        String[] visibleCols = {
                "Config", "JVM-Flags",
                "CPU% LOAD", "Mem% LOAD", "Mem% LOAD max",
                "GC-Pausen", "Full GC", "Pause Gesamt (s)", "Max Pause (s)",
                "Overhead (%)", "Peak Heap (MB)"
        };
        // CI-Spalten: 11=CI CPU, 12=CI Mem, 13=CI Mem max, 14=CI GC, 15=CI FullGC,
        //             16=CI Pause Ges, 17=CI Max Pause, 18=CI Overhead, 19=CI Peak
        String[] allCols = {
                "Config", "JVM-Flags",
                "CPU% LOAD", "Mem% LOAD", "Mem% LOAD max",
                "GC-Pausen", "Full GC", "Pause Gesamt (s)", "Max Pause (s)",
                "Overhead (%)", "Peak Heap (MB)",
                "CI CPU", "CI Mem", "CI Mem max",
                "CI GC", "CI FullGC", "CI Pause Ges", "CI Max Pause",
                "CI Overhead", "CI Peak"
        };
        writeHeaderRow(sheet, 0, allCols, s);

        Map<String, List<CsvRow>> groups = groupCsvByConfig(rows);
        int i = 0;
        for (var entry : groups.entrySet()) {
            List<CsvRow> group = entry.getValue();
            XSSFRow row = sheet.createRow(i + 1);
            setCell(row, 0,  entry.getKey(),              pick(s, i, SK.TEXT));
            setCell(row, 1,  group.get(0).jvmFlags(),     pick(s, i, SK.TEXT));

            double[] cpuLoad    = group.stream().mapToDouble(CsvRow::cpuLoadAvg).toArray();
            double[] memLoad    = group.stream().mapToDouble(CsvRow::memLoadAvg).toArray();
            double[] memMax     = group.stream().mapToDouble(CsvRow::memLoadMax).toArray();
            double[] gcCounts   = group.stream().mapToDouble(CsvRow::gcCount).toArray();
            double[] fullGcs    = group.stream().mapToDouble(CsvRow::gcFullCount).toArray();
            double[] totalPause = group.stream().mapToDouble(r -> r.gcTotalPauseMs() / 1000.0).toArray();
            double[] maxPause   = group.stream().mapToDouble(r -> r.gcMaxPauseMs() / 1000.0).toArray();
            double[] overhead   = group.stream().mapToDouble(CsvRow::gcOverheadPercent).toArray();
            double[] peakHeap   = group.stream().mapToDouble(CsvRow::gcPeakHeapAfterMb).toArray();

            setNum(row, 2,  BenchStats.mean(cpuLoad),    pick(s, i, SK.PCT));
            setNum(row, 3,  BenchStats.mean(memLoad),    pick(s, i, SK.PCT));
            setNum(row, 4,  BenchStats.mean(memMax),     pick(s, i, SK.PCT));
            setNum(row, 5,  BenchStats.mean(gcCounts),   pick(s, i, SK.INT));
            setNum(row, 6,  BenchStats.mean(fullGcs),    pick(s, i, SK.INT));
            setNum(row, 7,  BenchStats.mean(totalPause), pick(s, i, SK.DEC4));
            setNum(row, 8,  BenchStats.mean(maxPause),   pick(s, i, SK.DEC4));
            setNum(row, 9,  BenchStats.mean(overhead),   pick(s, i, SK.PCT));
            setNum(row, 10, BenchStats.mean(peakHeap),   pick(s, i, SK.INT));

            // CI columns
            setNum(row, 11, cpuLoad.length >= 2    ? BenchStats.confidenceInterval95(cpuLoad)    : Double.NaN, pick(s, i, SK.PCT));
            setNum(row, 12, memLoad.length >= 2    ? BenchStats.confidenceInterval95(memLoad)    : Double.NaN, pick(s, i, SK.PCT));
            setNum(row, 13, memMax.length >= 2     ? BenchStats.confidenceInterval95(memMax)     : Double.NaN, pick(s, i, SK.PCT));
            setNum(row, 14, gcCounts.length >= 2   ? BenchStats.confidenceInterval95(gcCounts)   : Double.NaN, pick(s, i, SK.INT));
            setNum(row, 15, fullGcs.length >= 2    ? BenchStats.confidenceInterval95(fullGcs)    : Double.NaN, pick(s, i, SK.INT));
            setNum(row, 16, totalPause.length >= 2 ? BenchStats.confidenceInterval95(totalPause) : Double.NaN, pick(s, i, SK.DEC4));
            setNum(row, 17, maxPause.length >= 2   ? BenchStats.confidenceInterval95(maxPause)   : Double.NaN, pick(s, i, SK.DEC4));
            setNum(row, 18, overhead.length >= 2   ? BenchStats.confidenceInterval95(overhead)   : Double.NaN, pick(s, i, SK.PCT));
            setNum(row, 19, peakHeap.length >= 2   ? BenchStats.confidenceInterval95(peakHeap)   : Double.NaN, pick(s, i, SK.INT));
            i++;
        }
        int dataRows = groups.size();

        for (int c = 11; c <= 19; c++) sheet.setColumnHidden(c, true);

        if (dataRows >= 2) {
            List<RuntimeType> runtimes = groups.values().stream()
                    .map(g -> inferRuntimeType(g.get(0).dockerImage()))
                    .toList();
            addBarChartWithCI(sheet, dataRows, "Ressourcen & GC alle Runs",
                    "Konfiguration", null, 0, 0, true, runtimes,
                    new ChartSeriesCI("CPU% LOAD", 2, 11, CLR_ORANGE),
                    new ChartSeriesCI("Mem% LOAD", 3, 12, CLR_DARK_BLUE),
                    new ChartSeriesCI("Overhead (%)", 9, 18, CLR_RED));
            // Farbskala: CPU%=2 bis Mem% max=4
            addColorScale(sheet, 1, dataRows, 2, 4);
            // Farbskala: GC Max Pause=8, Overhead=9
            addColorScale(sheet, 1, dataRows, 8, 9);
        }

        // CPU%-Header-Kommentar
        addCellComment(sheet, 0, 2, CPU_COMMENT);

        sheet.setAutoFilter(new CellRangeAddress(0, dataRows, 0, visibleCols.length - 1));
        sheet.createFreezePane(1, 1);
        autoSizeColumns(sheet, visibleCols.length);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Zusammenfassung (Aggregation ueber Repetitionen)
    // ═══════════════════════════════════════════════════════════════════

    /** Metrik-Descriptor fuer die Aggregationstabelle. */
    private record AggMetric(String label, SK kind, java.util.function.ToDoubleFunction<CsvRow> extractor) {}

    /** Zentrale Liste der aggregierten Metriken (Zusammenfassung + Ranking). */
    private static final AggMetric[] AGG_METRICS = {
            new AggMetric("Readiness (ms)",     SK.INT,  CsvRow::readinessMs),
            new AggMetric("First Req (s)",      SK.DEC4, CsvRow::firstSeconds),
            new AggMetric("p50 (s)",            SK.DEC4, CsvRow::p50),
            new AggMetric("p95 (s)",            SK.DEC4, CsvRow::p95),
            new AggMetric("p99 (s)",            SK.DEC4, CsvRow::p99),
            new AggMetric("Mean Latenz (s)",    SK.DEC4, CsvRow::mean),
            new AggMetric("Throughput (req/s)", SK.DEC2, CsvRow::throughput),
            new AggMetric("CPU% (LOAD)",        SK.PCT,  CsvRow::cpuLoadAvg),
            new AggMetric("Mem% (LOAD)",        SK.PCT,  CsvRow::memLoadAvg),
            new AggMetric("GC Overhead (%)",    SK.PCT,  CsvRow::gcOverheadPercent),
    };

    /**
     * Erzeugt das Sheet "Zusammenfassung" mit Aggregation ueber Repetitionen.
     *
     * <p>Gruppiert nach configName, berechnet pro Metrik:
     * <ul>
     *   <li>n (Anzahl Repetitionen)</li>
     *   <li>Mean</li>
     *   <li>Stddev (Bessel-korrigiert)</li>
     *   <li>95%-Konfidenzintervall (Halbbreite)</li>
     * </ul>
     */
    private static void writeMergedAggregation(XSSFWorkbook wb, Styles s, List<CsvRow> rows) {
        XSSFSheet sheet = wb.createSheet("Zusammenfassung");
        sheet.setDefaultColumnWidth(14);

        // Gruppen bilden: configName → List<CsvRow>, Reihenfolge des ersten Auftretens beibehalten
        Map<String, List<CsvRow>> groups = new LinkedHashMap<>();
        for (CsvRow r : rows) groups.computeIfAbsent(r.configName(), k -> new ArrayList<>()).add(r);

        // Header aufbauen: Config | n | pro Metrik: Mean | Stddev | 95%-KI
        List<String> headerList = new ArrayList<>();
        headerList.add("Config");
        headerList.add("n");
        for (AggMetric m : AGG_METRICS) {
            headerList.add(m.label() + " Mean");
            headerList.add(m.label() + " Stddev");
            headerList.add(m.label() + " 95%-KI");
        }
        String[] headers = headerList.toArray(new String[0]);
        writeHeaderRow(sheet, 0, headers, s);

        int rowIdx = 0;
        for (var entry : groups.entrySet()) {
            String config = entry.getKey();
            List<CsvRow> group = entry.getValue();
            XSSFRow row = sheet.createRow(rowIdx + 1);

            setCell(row, 0, config, pick(s, rowIdx, SK.TEXT));
            setNum(row, 1, group.size(), pick(s, rowIdx, SK.INT));

            int col = 2;
            for (AggMetric m : AGG_METRICS) {
                double[] vals = group.stream().mapToDouble(m.extractor()::applyAsDouble).toArray();
                double mean = BenchStats.mean(vals);
                double sd   = vals.length >= 2 ? BenchStats.sampleStddev(vals) : Double.NaN;
                double ci   = vals.length >= 2 ? BenchStats.confidenceInterval95(vals) : Double.NaN;

                setNum(row, col++, mean, pick(s, rowIdx, m.kind()));
                setNum(row, col++, sd,   pick(s, rowIdx, m.kind()));
                setNum(row, col++, ci,   pick(s, rowIdx, m.kind()));
            }
            rowIdx++;
        }

        sheet.setAutoFilter(new CellRangeAddress(0, rowIdx, 0, headers.length - 1));
        sheet.createFreezePane(2, 1);

        // Farbskala auf Mean-Spalten (jede 3. Spalte ab col 2)
        if (rowIdx >= 2) {
            for (int m = 0; m < AGG_METRICS.length; m++) {
                int meanCol = 2 + m * 3;
                addColorScale(sheet, 1, rowIdx, meanCol, meanCol);
            }
        }

        autoSizeColumns(sheet, headers.length);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Ranking (normalisiert auf Baseline = 100%)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Erzeugt das Sheet "Ranking" mit Normalisierung auf die Baseline-Konfiguration.
     *
     * <p>Die erste Konfiguration (alphabetisch bzw. erste in der CSV) wird als Baseline (=100%) verwendet.
     * Niedrigere Werte = besser bei Latenz/Ressourcen, hoehere Werte = besser bei Throughput.
     */
    private static void writeMergedRanking(XSSFWorkbook wb, Styles s, List<CsvRow> rows) {
        XSSFSheet sheet = wb.createSheet("Ranking");
        sheet.setDefaultColumnWidth(14);

        // Gruppen bilden
        Map<String, List<CsvRow>> groups = new LinkedHashMap<>();
        for (CsvRow r : rows) groups.computeIfAbsent(r.configName(), k -> new ArrayList<>()).add(r);

        if (groups.isEmpty()) return;

        // Header aufbauen: Config | n | pro Metrik: Mean | Relativ (%)
        List<String> headerList = new ArrayList<>();
        headerList.add("Config");
        headerList.add("n");
        for (AggMetric m : AGG_METRICS) {
            headerList.add(m.label() + " Mean");
            headerList.add(m.label() + " rel. (%)");
        }
        String[] headers = headerList.toArray(new String[0]);
        writeHeaderRow(sheet, 0, headers, s);

        // Baseline = erste Gruppe
        String baselineConfig = groups.keySet().iterator().next();
        List<CsvRow> baselineGroup = groups.get(baselineConfig);
        double[] baselineMeans = new double[AGG_METRICS.length];
        for (int m = 0; m < AGG_METRICS.length; m++) {
            double[] vals = baselineGroup.stream().mapToDouble(AGG_METRICS[m].extractor()::applyAsDouble).toArray();
            baselineMeans[m] = BenchStats.mean(vals);
        }

        int rowIdx = 0;
        for (var entry : groups.entrySet()) {
            String config = entry.getKey();
            List<CsvRow> group = entry.getValue();
            XSSFRow row = sheet.createRow(rowIdx + 1);

            setCell(row, 0, config, pick(s, rowIdx, SK.TEXT));
            setNum(row, 1, group.size(), pick(s, rowIdx, SK.INT));

            int col = 2;
            for (int m = 0; m < AGG_METRICS.length; m++) {
                double[] vals = group.stream().mapToDouble(AGG_METRICS[m].extractor()::applyAsDouble).toArray();
                double mean = BenchStats.mean(vals);
                double rel = BenchStats.relativeToBaseline(mean, baselineMeans[m]);

                setNum(row, col++, mean, pick(s, rowIdx, AGG_METRICS[m].kind()));
                setNum(row, col++, rel,  pick(s, rowIdx, SK.DEC2));
            }
            rowIdx++;
        }

        sheet.setAutoFilter(new CellRangeAddress(0, rowIdx, 0, headers.length - 1));
        sheet.createFreezePane(2, 1);

        // Farbskala auf relative Spalten
        if (rowIdx >= 2) {
            for (int m = 0; m < AGG_METRICS.length; m++) {
                int relCol = 2 + m * 2 + 1;
                boolean lowerIsBetter = m != 6; // Throughput (index 6): hoeher = besser
                if (lowerIsBetter) {
                    addColorScale(sheet, 1, rowIdx, relCol, relCol);
                } else {
                    addColorScaleReverse(sheet, 1, rowIdx, relCol, relCol);
                }
            }
        }

        // Bar chart: relative Werte fuer die wichtigsten Metriken
        if (rowIdx >= 2) {
            int chartOffset = rowIdx + 3;
            int chartHeight = Math.max(12, rowIdx * 2);

            XSSFDrawing drawing = sheet.createDrawingPatriarch();
            XSSFClientAnchor anchor = drawing.createAnchor(
                    0, 0, 0, 0, 0, chartOffset, 14, chartOffset + chartHeight);

            XSSFChart chart = drawing.createChart(anchor);
            chart.setTitleText("Relative Performance (Baseline = 100%)");
            chart.setTitleOverlay(false);

            XDDFChartAxis catAxis = chart.createCategoryAxis(AxisPosition.BOTTOM);
            catAxis.setTitle("Konfiguration");
            XDDFValueAxis valAxis = chart.createValueAxis(AxisPosition.LEFT);
            valAxis.setTitle("Relativ (%)");
            valAxis.setCrossBetween(AxisCrossBetween.BETWEEN);

            XDDFDataSource<String> cats = XDDFDataSourcesFactory.fromStringCellRange(
                    sheet, new CellRangeAddress(1, rowIdx, 0, 0));

            XDDFBarChartData barData = (XDDFBarChartData)
                    chart.createData(ChartTypes.BAR, catAxis, valAxis);
            barData.setBarDirection(BarDirection.COL);
            barData.setBarGrouping(BarGrouping.CLUSTERED);
            barData.setGapWidth(150);

            // Ausgewaehlte Metriken im Chart: Readiness, p50, Throughput, CPU%, GC Overhead
            int[] chartMetricIndices = {0, 2, 6, 7, 9}; // Readiness, p50, Throughput, CPU%, GC Overhead
            byte[][] chartColors = {CLR_DARK_BLUE, CLR_GREEN, CLR_ORANGE, CLR_RED, rgb(0x8E, 0x44, 0xAD)};

            for (int i = 0; i < chartMetricIndices.length; i++) {
                int mIdx = chartMetricIndices[i];
                int relCol = 2 + mIdx * 2 + 1;
                XDDFNumericalDataSource<Double> data = XDDFDataSourcesFactory.fromNumericCellRange(
                        sheet, new CellRangeAddress(1, rowIdx, relCol, relCol));
                XDDFBarChartData.Series bs = (XDDFBarChartData.Series) barData.addSeries(cats, data);
                bs.setTitle(AGG_METRICS[mIdx].label() + " (%)", null);
                bs.setFillProperties(new XDDFSolidFillProperties(XDDFColor.from(chartColors[i])));
            }

            chart.plot(barData);
            XDDFChartLegend legend = chart.getOrAddLegend();
            legend.setPosition(LegendPosition.BOTTOM);
        }

        autoSizeColumns(sheet, headers.length);
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
            int warmup, int measureReqs, int concurrency, long sleepMs,
            double cpuLoadAvg, double memLoadAvg, double memLoadMax,
            int gcCount, int gcFullCount, double gcTotalPauseMs,
            double gcMaxPauseMs, double gcOverheadPercent, double gcPeakHeapAfterMb,
            int repetition,
            String category, String runtimeModel
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
                        (long) getDoubleVal(vals, idx, "sleepBetweenRequestsMs"),
                        getDoubleVal(vals, idx, "cpuLoadAvg"),
                        getDoubleVal(vals, idx, "memLoadAvg"),
                        getDoubleVal(vals, idx, "memLoadMax"),
                        getIntVal(vals, idx, "gcCount"),
                        getIntVal(vals, idx, "gcFullCount"),
                        getDoubleVal(vals, idx, "gcTotalPauseMs"),
                        getDoubleVal(vals, idx, "gcMaxPauseMs"),
                        getDoubleVal(vals, idx, "gcOverheadPercent"),
                        getDoubleVal(vals, idx, "gcPeakHeapAfterMb"),
                        getIntVal(vals, idx, "repetition"),
                        getVal(vals, idx, "category"),
                        getVal(vals, idx, "runtimeModel")
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
    private enum SK { TEXT, INT, DEC2, DEC4, PCT }

    private record Styles(
            CellStyle header, CellStyle sectionHeader,
            CellStyle text, CellStyle textStripe,
            CellStyle integer, CellStyle integerStripe,
            CellStyle dec2, CellStyle dec2Stripe,
            CellStyle dec4, CellStyle dec4Stripe,
            CellStyle pct, CellStyle pctStripe
    ) {}

    /** Waehlt anhand von Zeilenindex und Typ den passenden Style (normal / Zebra). */
    private static CellStyle pick(Styles s, int rowIndex, SK kind) {
        boolean stripe = rowIndex % 2 != 0;
        return switch (kind) {
            case TEXT -> stripe ? s.textStripe : s.text;
            case INT  -> stripe ? s.integerStripe : s.integer;
            case DEC2 -> stripe ? s.dec2Stripe : s.dec2;
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
        short fmtDec2 = df.getFormat("0.00");
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
        CellStyle dec2N   = cellStyle(wb, dataFont, null, fmtDec2);
        CellStyle dec2S   = cellStyle(wb, dataFont, CLR_STRIPE, fmtDec2);
        CellStyle dec4N   = cellStyle(wb, dataFont, null, fmtDec4);
        CellStyle dec4S   = cellStyle(wb, dataFont, CLR_STRIPE, fmtDec4);
        CellStyle pctN    = cellStyle(wb, dataFont, null, fmtPct);
        CellStyle pctS    = cellStyle(wb, dataFont, CLR_STRIPE, fmtPct);

        return new Styles(hdr, sec, txt, txtS, intN, intS, dec2N, dec2S, dec4N, dec4S, pctN, pctS);
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
     * Legende wird nur angezeigt, wenn mehr als eine Serie vorhanden ist.
     */
    private static void addBarChart(XSSFSheet sheet, int dataRows,
                                    String title, String catLabel, String valLabel,
                                    int catCol, ChartSeries... series) {
        addBarChart(sheet, dataRows, title, catLabel, valLabel, catCol, 0, series.length > 1, series);
    }

    /**
     * Erzeugt ein gruppiertes Balkendiagramm mit konfigurierbarem vertikalen Offset.
     * Legende wird nur angezeigt, wenn mehr als eine Serie vorhanden ist.
     */
    private static void addBarChart(XSSFSheet sheet, int dataRows,
                                    String title, String catLabel, String valLabel,
                                    int catCol, int extraOffset, ChartSeries... series) {
        addBarChart(sheet, dataRows, title, catLabel, valLabel, catCol, extraOffset, series.length > 1, series);
    }

    /**
     * Erzeugt ein gruppiertes Balkendiagramm mit konfigurierbarem vertikalen Offset
     * und optionaler Legende.
     */
    private static void addBarChart(XSSFSheet sheet, int dataRows,
                                    String title, String catLabel, String valLabel,
                                    int catCol, boolean showLegend, ChartSeries... series) {
        addBarChart(sheet, dataRows, title, catLabel, valLabel, catCol, 0, showLegend, series);
    }

    /**
     * Erzeugt ein gruppiertes Balkendiagramm mit konfigurierbarem vertikalen Offset
     * und optionaler Legende.
     *
     * @param sheet       Ziel-Sheet
     * @param dataRows    Anzahl Datenzeilen (ohne Header)
     * @param title       Diagramm-Titel
     * @param catLabel    Achsenbeschriftung Kategorie (oder null)
     * @param valLabel    Achsenbeschriftung Wert (oder null)
     * @param catCol      Spalte mit Kategorie-Labels (Config-Namen)
     * @param extraOffset Zusaetzlicher vertikaler Offset (Zeilen) unter der Standard-Position
     * @param showLegend  Legende anzeigen (false bei Einzelserien)
     * @param series      Datenreihen (Titel, Spalte, Farbe)
     */
    private static void addBarChart(XSSFSheet sheet, int dataRows,
                                    String title, String catLabel, String valLabel,
                                    int catCol, int extraOffset, boolean showLegend,
                                    ChartSeries... series) {
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

        // Legende nur anzeigen, wenn explizit gewuenscht (bei Mehrfachserien)
        if (showLegend) {
            XDDFChartLegend legend = chart.getOrAddLegend();
            legend.setPosition(LegendPosition.BOTTOM);
        }
    }

    /**
     * Erzeugt ein gruppiertes Balkendiagramm mit logarithmischer Y-Achse (Basis 10).
     * Ideal fuer Wertebereiche mit extremen Unterschieden (z.B. GC-Pausen: 0.001s bis 2s).
     */
    private static void addBarChartLogY(XSSFSheet sheet, int dataRows,
                                        String title, String catLabel, String valLabel,
                                        int catCol, int extraOffset,
                                        ChartSeries... series) {
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

        // Logarithmische Skala (Basis 10) fuer extreme Wertbereiche
        CTPlotArea plotArea = chart.getCTChart().getPlotArea();
        CTValAx ctValAx = plotArea.getValAxList().get(plotArea.getValAxList().size() - 1);
        if (!ctValAx.getScaling().isSetLogBase()) ctValAx.getScaling().addNewLogBase();
        ctValAx.getScaling().getLogBase().setVal(10.0);

        // Fix: Kategorie-Achse kreuzt am Minimum statt bei 1.0 (10^0),
        // damit alle Balken nach oben wachsen
        CTCatAx ctCatAx = plotArea.getCatAxList().get(plotArea.getCatAxList().size() - 1);
        if (ctCatAx.isSetCrosses()) ctCatAx.unsetCrosses();
        ctCatAx.addNewCrosses().setVal(STCrosses.MIN);

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

        if (series.length > 1) {
            XDDFChartLegend legend = chart.getOrAddLegend();
            legend.setPosition(LegendPosition.BOTTOM);
        }
    }

    /**
     * Erzeugt ein gruppiertes Balkendiagramm mit 95%-CI-Fehlerbalken.
     *
     * <p>Jede {@link ChartSeriesCI} referenziert sowohl die Mittelwert-Spalte als auch
     * die CI-Halbbreiten-Spalte. Die Fehlerbalken werden symmetrisch (±CI) dargestellt.
     *
     * @param sheet       Ziel-Sheet
     * @param dataRows    Anzahl Datenzeilen (ohne Header)
     * @param title       Diagramm-Titel
     * @param catLabel    Achsenbeschriftung Kategorie (oder null)
     * @param valLabel    Achsenbeschriftung Wert (oder null)
     * @param catCol      Spalte mit Kategorie-Labels (Config-Namen)
     * @param extraOffset zusaetzlicher vertikaler Offset (fuer mehrere Charts)
     * @param showLegend  true = Legende anzeigen
     * @param series      Datenreihen mit CI-Spaltenreferenz
     */
    private static void addBarChartWithCI(XSSFSheet sheet, int dataRows,
                                          String title, String catLabel, String valLabel,
                                          int catCol, int extraOffset, boolean showLegend,
                                          ChartSeriesCI... series) {
        addBarChartWithCI(sheet, dataRows, title, catLabel, valLabel,
                catCol, extraOffset, showLegend, null, series);
    }

    /**
     * Erzeugt ein gruppiertes Balkendiagramm mit 95%-CI-Fehlerbalken und optionaler Runtime-Einfaerbung.
     *
     * <p>Wenn {@code runtimes} mehr als einen Typ enthaelt, werden die Balken nach RuntimeType eingefaerbt.
     */
    private static void addBarChartWithCI(XSSFSheet sheet, int dataRows,
                                          String title, String catLabel, String valLabel,
                                          int catCol, int extraOffset, boolean showLegend,
                                          List<RuntimeType> runtimes,
                                          ChartSeriesCI... series) {
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

        for (ChartSeriesCI sd : series) {
            XDDFNumericalDataSource<Double> data = XDDFDataSourcesFactory.fromNumericCellRange(
                    sheet, new CellRangeAddress(1, dataRows, sd.column(), sd.column()));
            XDDFBarChartData.Series bs = (XDDFBarChartData.Series) barData.addSeries(cats, data);
            bs.setTitle(sd.title(), null);
            bs.setFillProperties(new XDDFSolidFillProperties(XDDFColor.from(sd.color())));
        }

        chart.plot(barData);

        // Error Bars via CT-API: eine CTErrBars pro Serie
        CTPlotArea plotArea = chart.getCTChart().getPlotArea();
        CTBarChart ctBar = plotArea.getBarChartList().get(plotArea.getBarChartList().size() - 1);
        for (int i = 0; i < series.length; i++) {
            ChartSeriesCI sd = series[i];
            if (sd.ciColumn() < 0) continue; // kein CI vorhanden
            CTBarSer ctSer = ctBar.getSerList().get(i);
            addCTErrorBars(ctSer.addNewErrBars(), sheet, dataRows, sd.ciColumn());
        }

        if (showLegend) {
            XDDFChartLegend legend = chart.getOrAddLegend();
            legend.setPosition(LegendPosition.BOTTOM);
        }
    }

    /**
     * Erzeugt ein Balkendiagramm mit logarithmischer Y-Achse und 95%-CI-Fehlerbalken.
     */
    private static void addBarChartLogYWithCI(XSSFSheet sheet, int dataRows,
                                              String title, String catLabel, String valLabel,
                                              int catCol, int extraOffset,
                                              ChartSeriesCI... series) {
        addBarChartLogYWithCI(sheet, dataRows, title, catLabel, valLabel,
                catCol, extraOffset, null, series);
    }

    /**
     * Erzeugt ein Balkendiagramm mit logarithmischer Y-Achse, CI-Fehlerbalken und optionaler Runtime-Einfaerbung.
     */
    private static void addBarChartLogYWithCI(XSSFSheet sheet, int dataRows,
                                              String title, String catLabel, String valLabel,
                                              int catCol, int extraOffset,
                                              List<RuntimeType> runtimes,
                                              ChartSeriesCI... series) {
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

        // Logarithmische Skala
        CTPlotArea plotArea = chart.getCTChart().getPlotArea();
        CTValAx ctValAx = plotArea.getValAxList().get(plotArea.getValAxList().size() - 1);
        if (!ctValAx.getScaling().isSetLogBase()) ctValAx.getScaling().addNewLogBase();
        ctValAx.getScaling().getLogBase().setVal(10.0);

        // Kategorie-Achse kreuzt am Minimum
        CTCatAx ctCatAx = plotArea.getCatAxList().get(plotArea.getCatAxList().size() - 1);
        if (ctCatAx.isSetCrosses()) ctCatAx.unsetCrosses();
        ctCatAx.addNewCrosses().setVal(STCrosses.MIN);

        XDDFDataSource<String> cats = XDDFDataSourcesFactory.fromStringCellRange(
                sheet, new CellRangeAddress(1, dataRows, catCol, catCol));

        XDDFBarChartData barData = (XDDFBarChartData)
                chart.createData(ChartTypes.BAR, catAxis, valAxis);
        barData.setBarDirection(BarDirection.COL);
        if (series.length > 1) barData.setBarGrouping(BarGrouping.CLUSTERED);
        barData.setGapWidth(150);

        for (ChartSeriesCI sd : series) {
            XDDFNumericalDataSource<Double> data = XDDFDataSourcesFactory.fromNumericCellRange(
                    sheet, new CellRangeAddress(1, dataRows, sd.column(), sd.column()));
            XDDFBarChartData.Series bs = (XDDFBarChartData.Series) barData.addSeries(cats, data);
            bs.setTitle(sd.title(), null);
            bs.setFillProperties(new XDDFSolidFillProperties(XDDFColor.from(sd.color())));
        }

        chart.plot(barData);

        // Error Bars
        CTBarChart ctBar = plotArea.getBarChartList().get(plotArea.getBarChartList().size() - 1);
        for (int i = 0; i < series.length; i++) {
            ChartSeriesCI sd = series[i];
            if (sd.ciColumn() < 0) continue;
            CTBarSer ctSer = ctBar.getSerList().get(i);
            addCTErrorBars(ctSer.addNewErrBars(), sheet, dataRows, sd.ciColumn());
        }

        if (series.length > 1) {
            XDDFChartLegend legend = chart.getOrAddLegend();
            legend.setPosition(LegendPosition.BOTTOM);
        }
    }

    /**
     * Konfiguriert ein CTErrBars-Element fuer symmetrische 95%-CI-Fehlerbalken.
     * Die CI-Halbbreiten stehen in der angegebenen Spalte (ciCol), Zeilen 1..dataRows.
     */
    private static void addCTErrorBars(CTErrBars errBars, XSSFSheet sheet,
                                       int dataRows, int ciCol) {
        errBars.addNewErrBarType().setVal(STErrBarType.BOTH);
        errBars.addNewErrDir().setVal(STErrDir.Y);
        errBars.addNewErrValType().setVal(STErrValType.CUST);
        errBars.addNewNoEndCap().setVal(false);

        // Plus-Werte = CI-Halbbreite (symmetrisch, also Plus == Minus)
        String sheetName = sheet.getSheetName();
        String colRef = colLetter(ciCol);
        String formula = "'" + sheetName + "'!$" + colRef + "$2:$" + colRef + "$" + (dataRows + 1);

        CTNumDataSource plus = errBars.addNewPlus();
        CTNumRef plusRef = plus.addNewNumRef();
        plusRef.setF(formula);

        CTNumDataSource minus = errBars.addNewMinus();
        CTNumRef minusRef = minus.addNewNumRef();
        minusRef.setF(formula);
    }

    /**
     * Faerbt die Balken eines Diagramms nach Laufzeittyp (HotSpot=blau, OpenJ9=tuerkis, Native=orange),
     * sofern mehr als ein Laufzeittyp in den Daten vorkommt.
     *
     * <p>Setzt fuer jeden Datenpunkt (CTDPt) einen eigenen Solid-Fill auf Basis des RuntimeType.
     * Wenn nur ein RuntimeType vorhanden ist, wird nichts geaendert (die Serien-Farbe bleibt).
     *
     * @param ctBar    das CTBarChart, dessen Serien eingefaerbt werden
     * @param runtimes Laufzeittyp pro Datenzeile (Index 0 = erste Datenzeile)
     */
    private static void applyRuntimeColors(CTBarChart ctBar, List<RuntimeType> runtimes) {
        if (runtimes == null || runtimes.isEmpty()) return;
        long distinct = runtimes.stream().distinct().count();
        if (distinct <= 1) return; // nur ein Typ → keine Einfaerbung noetig

        for (CTBarSer ctSer : ctBar.getSerList()) {
            for (int pt = 0; pt < runtimes.size(); pt++) {
                CTDPt dPt = ctSer.addNewDPt();
                dPt.addNewIdx().setVal(pt);
                byte[] color = runtimeColor(runtimes.get(pt));
                CTShapeProperties spPr = dPt.addNewSpPr();
                CTSolidColorFillProperties fill = spPr.addNewSolidFill();
                CTSRgbColor srgb = fill.addNewSrgbClr();
                srgb.setVal(color);
            }
        }
    }

    /** Wandelt einen 0-basierten Spaltenindex in einen Excel-Spaltenbuchstaben um (A, B, ..., Z, AA, ...). */
    private static String colLetter(int col) {
        StringBuilder sb = new StringBuilder();
        col++;
        while (col > 0) {
            col--;
            sb.insert(0, (char) ('A' + col % 26));
            col /= 26;
        }
        return sb.toString();
    }

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

    /**
     * Umgekehrte 3-Farb-Skala (rot → orange → gruen): hohe Werte = gruen (besser).
     * Verwendet fuer Metriken wie Throughput, bei denen hohe Werte wuenschenswert sind.
     */
    private static void addColorScaleReverse(XSSFSheet sheet, int firstRow, int lastRow,
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
            ((ExtendedColor) colors[0]).setARGBHex("FFE74C3C");  // rot   (niedrig = schlecht)
            ((ExtendedColor) colors[1]).setARGBHex("FFF39C12");  // orange
            ((ExtendedColor) colors[2]).setARGBHex("FF27AE60");  // gruen (hoch = gut)

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

    /**
     * Fuegt einen Zellkommentar hinzu (z.B. Erklaerung fuer CPU% > 100%).
     */
    private static void addCellComment(XSSFSheet sheet, int rowNum, int colNum, String text) {
        XSSFDrawing drawing = sheet.createDrawingPatriarch();
        CreationHelper factory = sheet.getWorkbook().getCreationHelper();
        ClientAnchor anchor = factory.createClientAnchor();
        anchor.setCol1(colNum);
        anchor.setCol2(colNum + 3);
        anchor.setRow1(rowNum);
        anchor.setRow2(rowNum + 4);
        Comment comment = drawing.createCellComment(anchor);
        comment.setString(factory.createRichTextString(text));
        comment.setAuthor("TFL4 Benchmark");
        sheet.getRow(rowNum).getCell(colNum).setCellComment(comment);
    }

    private static final String CPU_COMMENT =
            "Docker-Stats CPU% kann kurzzeitig >100% anzeigen, obwohl --cpus=1 gesetzt ist. "
            + "Dies ist ein Messartefakt durch Zeitfenster-Unterschiede zwischen Host-Kernel "
            + "und Container-Cgroups. Die Rohdaten werden nicht geklammert.";

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
