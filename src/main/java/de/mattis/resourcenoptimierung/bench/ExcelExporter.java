package de.mattis.resourcenoptimierung.bench;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
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
 * Erzeugt ein Workbook mit folgenden Sheets:
 * 1. Uebersicht      – Alle Kennzahlen tabellarisch, bedingte Formatierung, AutoFilter
 * 2. Latenzen         – p50/p95/p99-Vergleich als Balkendiagramm
 * 3. Startup          – Readiness + First-Request + Throughput als Diagramme
 * 4. Ressourcen       – Docker CPU/Mem IDLE vs LOAD vs POST
 * 5. Rohdaten         – Einzellatenzen fuer eigene Auswertungen
 *
 * Zusaetzlich: mergeFromCsvDirectory() liest alle CSVs aus bench-results/
 * und erzeugt ein zusammengefuehrtes Excel fuer Vergleiche ueber mehrere Runs.
 */
public final class ExcelExporter {

    private ExcelExporter() {}

    // ======================== Farben ========================

    private static final byte[] COLOR_HEADER_BG    = {(byte) 0x2B, (byte) 0x57, (byte) 0x9A};  // dunkles Blau
    private static final byte[] COLOR_HEADER_FG    = {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF};  // weiss
    private static final byte[] COLOR_SECTION_BG   = {(byte) 0xDC, (byte) 0xE6, (byte) 0xF1};  // helles Blau
    private static final byte[] COLOR_STRIPE_BG    = {(byte) 0xF2, (byte) 0xF2, (byte) 0xF2};  // hellgrau
    private static final byte[] COLOR_GREEN        = {(byte) 0x27, (byte) 0xAE, (byte) 0x60};
    private static final byte[] COLOR_RED          = {(byte) 0xE7, (byte) 0x4C, (byte) 0x3C};
    private static final byte[] COLOR_ORANGE       = {(byte) 0xF3, (byte) 0x9C, (byte) 0x12};
    private static final byte[] COLOR_DARK_BLUE    = {(byte) 0x2C, (byte) 0x3E, (byte) 0x50};

    // ======================== Public API ========================

    /**
     * Schreibt Benchmark-Ergebnisse als Excel-Datei.
     * Wird automatisch nach jedem Benchmark-Durchlauf aufgerufen.
     *
     * @param results Ergebnisse eines Benchmark-Durchlaufs
     * @param path    Zielpfad (.xlsx)
     * @throws IOException wenn Schreiben fehlschlaegt
     */
    public static void writeExcel(List<RunResult> results, Path path) throws IOException {
        if (results == null || results.isEmpty()) return;

        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Styles styles = createStyles(wb);

            writeUebersicht(wb, styles, results);
            writeLatenzen(wb, styles, results);
            writeStartupThroughput(wb, styles, results);
            writeRessourcen(wb, styles, results);
            writeRohdaten(wb, styles, results);

            try (OutputStream os = Files.newOutputStream(path)) {
                wb.write(os);
            }
        }
    }

    /**
     * Liest alle CSV-Dateien aus einem Verzeichnis und erzeugt ein
     * zusammengefuehrtes Excel-Workbook. Ermoeglicht den Vergleich
     * ueber mehrere Benchmark-Durchlaeufe hinweg.
     *
     * @param csvDir   Verzeichnis mit CSV-Dateien (z.B. bench-results/)
     * @param excelOut Zielpfad fuer die Excel-Datei
     * @throws IOException wenn Lesen oder Schreiben fehlschlaegt
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

            try (OutputStream os = Files.newOutputStream(excelOut)) {
                wb.write(os);
            }
        }

        System.out.println("Merged Excel: " + excelOut + " (" + allRows.size() + " rows from CSV)");
    }

    // ======================== Sheet 1: Uebersicht ========================

    private static void writeUebersicht(XSSFWorkbook wb, Styles s, List<RunResult> results) {
        XSSFSheet sheet = wb.createSheet("Übersicht");
        sheet.setDefaultColumnWidth(14);

        // Spalten-Definition
        String[] headers = {
                "Config",
                "Szenario",
                "JVM-Flags",
                "Docker-Image",
                // Startup
                "Readiness (ms)",
                "First Req (s)",
                // Latenzen
                "p50 (s)",
                "p95 (s)",
                "p99 (s)",
                "Mean (s)",
                "Latenz-Count",
                // Durchsatz
                "Messzeit (s)",
                "Throughput (req/s)",
                // Docker LOAD
                "CPU% (LOAD avg)",
                "Mem% (LOAD avg)",
                "Mem% (LOAD max)",
                // Docker IDLE
                "Mem% (IDLE avg)",
                // Messprofil
                "Warmup",
                "Mess-Req",
                "Concurrency",
                "Sleep (ms)",
                // Meta
                "Workload-Pfad",
                "Workload-N"
        };

        // --- Section Header Row ---
        XSSFRow sectionRow = sheet.createRow(0);
        sectionRow.setHeightInPoints(20);
        writeSectionHeaders(sectionRow, s, new String[]{
                "Konfiguration", "", "", "",
                "Startup", "",
                "Latenzen", "", "", "", "",
                "Durchsatz", "",
                "Docker (LOAD)", "", "",
                "Docker (IDLE)",
                "Messprofil", "", "", "",
                "Meta", ""
        });

        // Merge section headers
        mergeIfValid(sheet, 0, 0, 0, 3);   // Konfiguration
        mergeIfValid(sheet, 0, 0, 4, 5);   // Startup
        mergeIfValid(sheet, 0, 0, 6, 10);  // Latenzen
        mergeIfValid(sheet, 0, 0, 11, 12); // Durchsatz
        mergeIfValid(sheet, 0, 0, 13, 15); // Docker LOAD
        mergeIfValid(sheet, 0, 0, 17, 20); // Messprofil
        mergeIfValid(sheet, 0, 0, 21, 22); // Meta

        // --- Column Header Row ---
        XSSFRow headerRow = sheet.createRow(1);
        headerRow.setHeightInPoints(30);
        for (int c = 0; c < headers.length; c++) {
            XSSFCell cell = headerRow.createCell(c);
            cell.setCellValue(headers[c]);
            cell.setCellStyle(s.header);
        }

        // --- Data Rows ---
        for (int i = 0; i < results.size(); i++) {
            RunResult r = results.get(i);
            XSSFRow row = sheet.createRow(i + 2);
            CellStyle dataStyle = (i % 2 == 0) ? s.data : s.dataStripe;
            CellStyle numStyle = (i % 2 == 0) ? s.number : s.numberStripe;
            CellStyle num4Style = (i % 2 == 0) ? s.number4 : s.number4Stripe;
            CellStyle pctStyle = (i % 2 == 0) ? s.percent : s.percentStripe;

            List<Double> lats = new ArrayList<>(r.latenciesSeconds());
            lats.sort(Double::compareTo);
            double mean = lats.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);

            DockerPhaseAvg load = phaseAvg(r.dockerLoadSamples());
            DockerPhaseAvg idle = phaseAvg(r.dockerIdleSamples());

            int c = 0;
            setCell(row, c++, r.configName(), dataStyle);
            setCell(row, c++, r.scenario() == null ? "" : r.scenario().name(), dataStyle);
            setCell(row, c++, normalizeFlags(r.effectiveJavaToolOptions()), dataStyle);
            setCell(row, c++, r.dockerImage(), dataStyle);

            setCellNum(row, c++, r.readinessMs(), numStyle);
            setCellNum(row, c++, r.firstSeconds(), num4Style);

            setCellNum(row, c++, percentile(lats, 0.50), num4Style);
            setCellNum(row, c++, percentile(lats, 0.95), num4Style);
            setCellNum(row, c++, percentile(lats, 0.99), num4Style);
            setCellNum(row, c++, mean, num4Style);
            setCellNum(row, c++, lats.size(), numStyle);

            setCellNum(row, c++, r.totalMeasureTimeSeconds(), num4Style);
            setCellNum(row, c++, r.throughputReqPerSec(), numStyle);

            setCellNum(row, c++, load != null ? load.cpuAvg : Double.NaN, pctStyle);
            setCellNum(row, c++, load != null ? load.memAvg : Double.NaN, pctStyle);
            setCellNum(row, c++, load != null ? load.memMax : Double.NaN, pctStyle);
            setCellNum(row, c++, idle != null ? idle.memAvg : Double.NaN, pctStyle);

            MeasurementProfile p = r.measurementProfile();
            setCellNum(row, c++, p.warmupRequests(), numStyle);
            setCellNum(row, c++, p.measureRequests(), numStyle);
            setCellNum(row, c++, p.concurrency(), numStyle);
            setCellNum(row, c++, p.sleepBetweenRequestsMs(), numStyle);

            setCell(row, c++, r.workloadPath(), dataStyle);
            setCellNum(row, c++, r.workloadN(), numStyle);
        }

        // AutoFilter
        sheet.setAutoFilter(new CellRangeAddress(1, 1 + results.size(), 0, headers.length - 1));
        sheet.createFreezePane(1, 2);

        // Auto-size key columns
        for (int c = 0; c < Math.min(headers.length, 23); c++) {
            sheet.autoSizeColumn(c);
            // Minimum 12 chars, max 35
            int w = sheet.getColumnWidth(c);
            sheet.setColumnWidth(c, Math.max(w, 12 * 256));
            if (w > 35 * 256) sheet.setColumnWidth(c, 35 * 256);
        }
    }

    // ======================== Sheet 2: Latenzen ========================

    private static void writeLatenzen(XSSFWorkbook wb, Styles s, List<RunResult> results) {
        XSSFSheet sheet = wb.createSheet("Latenzen");

        // Table header
        XSSFRow header = sheet.createRow(0);
        header.setHeightInPoints(25);
        String[] cols = {"Config", "JVM-Flags", "p50 (s)", "p95 (s)", "p99 (s)", "Mean (s)"};
        for (int c = 0; c < cols.length; c++) {
            XSSFCell cell = header.createCell(c);
            cell.setCellValue(cols[c]);
            cell.setCellStyle(s.header);
        }

        for (int i = 0; i < results.size(); i++) {
            RunResult r = results.get(i);
            List<Double> lats = new ArrayList<>(r.latenciesSeconds());
            lats.sort(Double::compareTo);
            double mean = lats.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);

            XSSFRow row = sheet.createRow(i + 1);
            CellStyle ds = (i % 2 == 0) ? s.data : s.dataStripe;
            CellStyle ns = (i % 2 == 0) ? s.number4 : s.number4Stripe;

            setCell(row, 0, r.configName(), ds);
            setCell(row, 1, normalizeFlags(r.effectiveJavaToolOptions()), ds);
            setCellNum(row, 2, percentile(lats, 0.50), ns);
            setCellNum(row, 3, percentile(lats, 0.95), ns);
            setCellNum(row, 4, percentile(lats, 0.99), ns);
            setCellNum(row, 5, mean, ns);
        }

        // --- Balkendiagramm: Latenz-Vergleich ---
        if (results.size() >= 2) {
            XSSFDrawing drawing = sheet.createDrawingPatriarch();
            XSSFClientAnchor anchor = drawing.createAnchor(0, 0, 0, 0,
                    0, results.size() + 3, 10, results.size() + 3 + Math.max(12, results.size() * 2));

            XSSFChart chart = drawing.createChart(anchor);
            chart.setTitleText("Latenz-Vergleich (s)");

            // POI XDDFChart for bar chart
            org.apache.poi.xddf.usermodel.chart.XDDFChartAxis catAxis =
                    chart.createCategoryAxis(org.apache.poi.xddf.usermodel.chart.AxisPosition.BOTTOM);
            catAxis.setTitle("Konfiguration");

            org.apache.poi.xddf.usermodel.chart.XDDFValueAxis valAxis =
                    chart.createValueAxis(org.apache.poi.xddf.usermodel.chart.AxisPosition.LEFT);
            valAxis.setTitle("Latenz (s)");

            // Category (config names): column 0, rows 1..n
            org.apache.poi.xddf.usermodel.chart.XDDFDataSource<String> categories =
                    org.apache.poi.xddf.usermodel.chart.XDDFDataSourcesFactory.fromStringCellRange(
                            sheet, new CellRangeAddress(1, results.size(), 0, 0));

            // Data series: p50 (col 2), p95 (col 3), p99 (col 4)
            String[] seriesNames = {"p50", "p95", "p99"};
            int[] seriesCols = {2, 3, 4};
            byte[][] seriesColors = {COLOR_GREEN, COLOR_ORANGE, COLOR_RED};

            org.apache.poi.xddf.usermodel.chart.XDDFBarChartData barData =
                    (org.apache.poi.xddf.usermodel.chart.XDDFBarChartData)
                            chart.createData(org.apache.poi.xddf.usermodel.chart.ChartTypes.BAR, catAxis, valAxis);
            barData.setBarDirection(org.apache.poi.xddf.usermodel.chart.BarDirection.COL);
            barData.setBarGrouping(org.apache.poi.xddf.usermodel.chart.BarGrouping.CLUSTERED);

            for (int si = 0; si < seriesNames.length; si++) {
                org.apache.poi.xddf.usermodel.chart.XDDFNumericalDataSource<Double> data =
                        org.apache.poi.xddf.usermodel.chart.XDDFDataSourcesFactory.fromNumericCellRange(
                                sheet, new CellRangeAddress(1, results.size(), seriesCols[si], seriesCols[si]));
                org.apache.poi.xddf.usermodel.chart.XDDFBarChartData.Series series =
                        (org.apache.poi.xddf.usermodel.chart.XDDFBarChartData.Series) barData.addSeries(categories, data);
                series.setTitle(seriesNames[si], null);

                // Color
                org.apache.poi.xddf.usermodel.XDDFSolidFillProperties fill =
                        new org.apache.poi.xddf.usermodel.XDDFSolidFillProperties(
                                xssfColor(seriesColors[si]));
                series.setFillProperties(fill);
            }

            chart.plot(barData);
        }

        for (int c = 0; c < cols.length; c++) sheet.autoSizeColumn(c);
    }

    // ======================== Sheet 3: Startup & Throughput ========================

    private static void writeStartupThroughput(XSSFWorkbook wb, Styles s, List<RunResult> results) {
        XSSFSheet sheet = wb.createSheet("Startup & Throughput");

        String[] cols = {"Config", "JVM-Flags", "Readiness (ms)", "First Req (s)", "Throughput (req/s)"};
        XSSFRow header = sheet.createRow(0);
        header.setHeightInPoints(25);
        for (int c = 0; c < cols.length; c++) {
            XSSFCell cell = header.createCell(c);
            cell.setCellValue(cols[c]);
            cell.setCellStyle(s.header);
        }

        for (int i = 0; i < results.size(); i++) {
            RunResult r = results.get(i);
            XSSFRow row = sheet.createRow(i + 1);
            CellStyle ds = (i % 2 == 0) ? s.data : s.dataStripe;
            CellStyle ns = (i % 2 == 0) ? s.number : s.numberStripe;
            CellStyle n4s = (i % 2 == 0) ? s.number4 : s.number4Stripe;

            setCell(row, 0, r.configName(), ds);
            setCell(row, 1, normalizeFlags(r.effectiveJavaToolOptions()), ds);
            setCellNum(row, 2, r.readinessMs(), ns);
            setCellNum(row, 3, r.firstSeconds(), n4s);
            setCellNum(row, 4, r.throughputReqPerSec(), ns);
        }

        // --- Readiness-Chart ---
        if (results.size() >= 2) {
            XSSFDrawing drawing = sheet.createDrawingPatriarch();

            // Chart 1: Readiness
            XSSFClientAnchor anchor1 = drawing.createAnchor(0, 0, 0, 0,
                    0, results.size() + 3, 7, results.size() + 15);
            XSSFChart chart1 = drawing.createChart(anchor1);
            chart1.setTitleText("Startup-Zeit (ms)");

            var catAxis1 = chart1.createCategoryAxis(org.apache.poi.xddf.usermodel.chart.AxisPosition.BOTTOM);
            var valAxis1 = chart1.createValueAxis(org.apache.poi.xddf.usermodel.chart.AxisPosition.LEFT);
            valAxis1.setTitle("Readiness (ms)");

            var cats1 = org.apache.poi.xddf.usermodel.chart.XDDFDataSourcesFactory.fromStringCellRange(
                    sheet, new CellRangeAddress(1, results.size(), 0, 0));
            var data1 = org.apache.poi.xddf.usermodel.chart.XDDFDataSourcesFactory.fromNumericCellRange(
                    sheet, new CellRangeAddress(1, results.size(), 2, 2));

            var barData1 = (org.apache.poi.xddf.usermodel.chart.XDDFBarChartData)
                    chart1.createData(org.apache.poi.xddf.usermodel.chart.ChartTypes.BAR, catAxis1, valAxis1);
            barData1.setBarDirection(org.apache.poi.xddf.usermodel.chart.BarDirection.COL);

            var series1 = (org.apache.poi.xddf.usermodel.chart.XDDFBarChartData.Series) barData1.addSeries(cats1, data1);
            series1.setTitle("Readiness (ms)", null);
            series1.setFillProperties(new org.apache.poi.xddf.usermodel.XDDFSolidFillProperties(
                    xssfColor(COLOR_DARK_BLUE)));
            chart1.plot(barData1);

            // Chart 2: Throughput
            XSSFClientAnchor anchor2 = drawing.createAnchor(0, 0, 0, 0,
                    0, results.size() + 17, 7, results.size() + 29);
            XSSFChart chart2 = drawing.createChart(anchor2);
            chart2.setTitleText("Durchsatz (req/s)");

            var catAxis2 = chart2.createCategoryAxis(org.apache.poi.xddf.usermodel.chart.AxisPosition.BOTTOM);
            var valAxis2 = chart2.createValueAxis(org.apache.poi.xddf.usermodel.chart.AxisPosition.LEFT);
            valAxis2.setTitle("Throughput (req/s)");

            var cats2 = org.apache.poi.xddf.usermodel.chart.XDDFDataSourcesFactory.fromStringCellRange(
                    sheet, new CellRangeAddress(1, results.size(), 0, 0));
            var data2 = org.apache.poi.xddf.usermodel.chart.XDDFDataSourcesFactory.fromNumericCellRange(
                    sheet, new CellRangeAddress(1, results.size(), 4, 4));

            var barData2 = (org.apache.poi.xddf.usermodel.chart.XDDFBarChartData)
                    chart2.createData(org.apache.poi.xddf.usermodel.chart.ChartTypes.BAR, catAxis2, valAxis2);
            barData2.setBarDirection(org.apache.poi.xddf.usermodel.chart.BarDirection.COL);

            var series2 = (org.apache.poi.xddf.usermodel.chart.XDDFBarChartData.Series) barData2.addSeries(cats2, data2);
            series2.setTitle("Throughput (req/s)", null);
            series2.setFillProperties(new org.apache.poi.xddf.usermodel.XDDFSolidFillProperties(
                    xssfColor(COLOR_GREEN)));
            chart2.plot(barData2);
        }

        for (int c = 0; c < cols.length; c++) sheet.autoSizeColumn(c);
    }

    // ======================== Sheet 4: Ressourcen ========================

    private static void writeRessourcen(XSSFWorkbook wb, Styles s, List<RunResult> results) {
        XSSFSheet sheet = wb.createSheet("Ressourcen");

        String[] cols = {
                "Config", "JVM-Flags",
                "CPU% IDLE", "CPU% LOAD", "CPU% POST",
                "Mem% IDLE", "Mem% LOAD", "Mem% POST",
                "Mem% LOAD max"
        };

        XSSFRow header = sheet.createRow(0);
        header.setHeightInPoints(25);
        for (int c = 0; c < cols.length; c++) {
            XSSFCell cell = header.createCell(c);
            cell.setCellValue(cols[c]);
            cell.setCellStyle(s.header);
        }

        for (int i = 0; i < results.size(); i++) {
            RunResult r = results.get(i);
            XSSFRow row = sheet.createRow(i + 1);
            CellStyle ds = (i % 2 == 0) ? s.data : s.dataStripe;
            CellStyle ps = (i % 2 == 0) ? s.percent : s.percentStripe;

            DockerPhaseAvg idle = phaseAvg(r.dockerIdleSamples());
            DockerPhaseAvg load = phaseAvg(r.dockerLoadSamples());
            DockerPhaseAvg post = phaseAvg(r.dockerPostSamples());

            setCell(row, 0, r.configName(), ds);
            setCell(row, 1, normalizeFlags(r.effectiveJavaToolOptions()), ds);

            setCellNum(row, 2, idle != null ? idle.cpuAvg : Double.NaN, ps);
            setCellNum(row, 3, load != null ? load.cpuAvg : Double.NaN, ps);
            setCellNum(row, 4, post != null ? post.cpuAvg : Double.NaN, ps);

            setCellNum(row, 5, idle != null ? idle.memAvg : Double.NaN, ps);
            setCellNum(row, 6, load != null ? load.memAvg : Double.NaN, ps);
            setCellNum(row, 7, post != null ? post.memAvg : Double.NaN, ps);

            setCellNum(row, 8, load != null ? load.memMax : Double.NaN, ps);
        }

        // --- Grouped Bar Chart: CPU + Mem under Load ---
        if (results.size() >= 2) {
            XSSFDrawing drawing = sheet.createDrawingPatriarch();
            XSSFClientAnchor anchor = drawing.createAnchor(0, 0, 0, 0,
                    0, results.size() + 3, 10, results.size() + 3 + Math.max(12, results.size() * 2));
            XSSFChart chart = drawing.createChart(anchor);
            chart.setTitleText("Ressourcenverbrauch unter Last (LOAD)");

            var catAxis = chart.createCategoryAxis(org.apache.poi.xddf.usermodel.chart.AxisPosition.BOTTOM);
            var valAxis = chart.createValueAxis(org.apache.poi.xddf.usermodel.chart.AxisPosition.LEFT);
            valAxis.setTitle("%");

            var cats = org.apache.poi.xddf.usermodel.chart.XDDFDataSourcesFactory.fromStringCellRange(
                    sheet, new CellRangeAddress(1, results.size(), 0, 0));

            var barData = (org.apache.poi.xddf.usermodel.chart.XDDFBarChartData)
                    chart.createData(org.apache.poi.xddf.usermodel.chart.ChartTypes.BAR, catAxis, valAxis);
            barData.setBarDirection(org.apache.poi.xddf.usermodel.chart.BarDirection.COL);
            barData.setBarGrouping(org.apache.poi.xddf.usermodel.chart.BarGrouping.CLUSTERED);

            // CPU% LOAD (col 3)
            var cpuData = org.apache.poi.xddf.usermodel.chart.XDDFDataSourcesFactory.fromNumericCellRange(
                    sheet, new CellRangeAddress(1, results.size(), 3, 3));
            var cpuSeries = (org.apache.poi.xddf.usermodel.chart.XDDFBarChartData.Series) barData.addSeries(cats, cpuData);
            cpuSeries.setTitle("CPU% LOAD", null);
            cpuSeries.setFillProperties(new org.apache.poi.xddf.usermodel.XDDFSolidFillProperties(
                    xssfColor(COLOR_ORANGE)));

            // Mem% LOAD (col 6)
            var memData = org.apache.poi.xddf.usermodel.chart.XDDFDataSourcesFactory.fromNumericCellRange(
                    sheet, new CellRangeAddress(1, results.size(), 6, 6));
            var memSeries = (org.apache.poi.xddf.usermodel.chart.XDDFBarChartData.Series) barData.addSeries(cats, memData);
            memSeries.setTitle("Mem% LOAD", null);
            memSeries.setFillProperties(new org.apache.poi.xddf.usermodel.XDDFSolidFillProperties(
                    xssfColor(COLOR_DARK_BLUE)));

            chart.plot(barData);
        }

        for (int c = 0; c < cols.length; c++) sheet.autoSizeColumn(c);
    }

    // ======================== Sheet 5: Rohdaten ========================

    private static void writeRohdaten(XSSFWorkbook wb, Styles s, List<RunResult> results) {
        XSSFSheet sheet = wb.createSheet("Rohdaten");

        // Header: Config | Request# | Latenz (s)
        XSSFRow header = sheet.createRow(0);
        header.setHeightInPoints(25);
        String[] cols = {"Config", "JVM-Flags", "Request #", "Latenz (s)"};
        for (int c = 0; c < cols.length; c++) {
            XSSFCell cell = header.createCell(c);
            cell.setCellValue(cols[c]);
            cell.setCellStyle(s.header);
        }

        int rowIdx = 1;
        for (RunResult r : results) {
            List<Double> lats = r.latenciesSeconds();
            String flags = normalizeFlags(r.effectiveJavaToolOptions());
            for (int j = 0; j < lats.size(); j++) {
                XSSFRow row = sheet.createRow(rowIdx++);
                CellStyle ds = ((rowIdx - 1) % 2 == 0) ? s.data : s.dataStripe;
                CellStyle ns = ((rowIdx - 1) % 2 == 0) ? s.number4 : s.number4Stripe;

                setCell(row, 0, r.configName(), ds);
                setCell(row, 1, flags, ds);
                setCellNum(row, 2, j + 1, ds);
                setCellNum(row, 3, lats.get(j), ns);
            }
        }

        sheet.createFreezePane(0, 1);
        for (int c = 0; c < cols.length; c++) sheet.autoSizeColumn(c);
    }

    // ======================== Merged CSV Sheets ========================

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

        XSSFRow headerRow = sheet.createRow(0);
        headerRow.setHeightInPoints(25);
        for (int c = 0; c < headers.length; c++) {
            XSSFCell cell = headerRow.createCell(c);
            cell.setCellValue(headers[c]);
            cell.setCellStyle(s.header);
        }

        for (int i = 0; i < rows.size(); i++) {
            CsvRow r = rows.get(i);
            XSSFRow row = sheet.createRow(i + 1);
            CellStyle ds = (i % 2 == 0) ? s.data : s.dataStripe;
            CellStyle ns = (i % 2 == 0) ? s.number : s.numberStripe;
            CellStyle n4s = (i % 2 == 0) ? s.number4 : s.number4Stripe;

            int c = 0;
            setCell(row, c++, r.timestamp, ds);
            setCell(row, c++, r.configName, ds);
            setCell(row, c++, r.scenario, ds);
            setCell(row, c++, r.jvmFlags, ds);
            setCellNum(row, c++, r.readinessMs, ns);
            setCellNum(row, c++, r.firstSeconds, n4s);
            setCellNum(row, c++, r.p50, n4s);
            setCellNum(row, c++, r.p95, n4s);
            setCellNum(row, c++, r.p99, n4s);
            setCellNum(row, c++, r.mean, n4s);
            setCellNum(row, c++, r.totalMeasureTime, n4s);
            setCellNum(row, c++, r.throughput, ns);
            setCellNum(row, c++, r.warmup, ns);
            setCellNum(row, c++, r.measureReqs, ns);
            setCellNum(row, c++, r.concurrency, ns);
            setCellNum(row, c++, r.sleepMs, ns);
            setCell(row, c++, r.workloadPath, ds);
            setCellNum(row, c++, r.workloadN, ns);
        }

        sheet.setAutoFilter(new CellRangeAddress(0, rows.size(), 0, headers.length - 1));
        sheet.createFreezePane(2, 1);

        for (int c = 0; c < headers.length; c++) {
            sheet.autoSizeColumn(c);
            int w = sheet.getColumnWidth(c);
            sheet.setColumnWidth(c, Math.max(w, 12 * 256));
            if (w > 35 * 256) sheet.setColumnWidth(c, 35 * 256);
        }
    }

    private static void writeMergedLatencyChart(XSSFWorkbook wb, Styles s, List<CsvRow> rows) {
        XSSFSheet sheet = wb.createSheet("Latenzen (alle Runs)");

        String[] cols = {"Config", "Timestamp", "JVM-Flags", "p50 (s)", "p95 (s)", "p99 (s)"};
        XSSFRow header = sheet.createRow(0);
        header.setHeightInPoints(25);
        for (int c = 0; c < cols.length; c++) {
            XSSFCell cell = header.createCell(c);
            cell.setCellValue(cols[c]);
            cell.setCellStyle(s.header);
        }

        for (int i = 0; i < rows.size(); i++) {
            CsvRow r = rows.get(i);
            XSSFRow row = sheet.createRow(i + 1);
            CellStyle ds = (i % 2 == 0) ? s.data : s.dataStripe;
            CellStyle ns = (i % 2 == 0) ? s.number4 : s.number4Stripe;

            setCell(row, 0, r.configName, ds);
            setCell(row, 1, r.timestamp, ds);
            setCell(row, 2, r.jvmFlags, ds);
            setCellNum(row, 3, r.p50, ns);
            setCellNum(row, 4, r.p95, ns);
            setCellNum(row, 5, r.p99, ns);
        }

        if (rows.size() >= 2) {
            XSSFDrawing drawing = sheet.createDrawingPatriarch();
            XSSFClientAnchor anchor = drawing.createAnchor(0, 0, 0, 0,
                    0, rows.size() + 3, 12, rows.size() + 3 + Math.max(14, rows.size() * 2));
            XSSFChart chart = drawing.createChart(anchor);
            chart.setTitleText("Latenz-Vergleich alle Runs (s)");

            var catAxis = chart.createCategoryAxis(org.apache.poi.xddf.usermodel.chart.AxisPosition.BOTTOM);
            var valAxis = chart.createValueAxis(org.apache.poi.xddf.usermodel.chart.AxisPosition.LEFT);
            valAxis.setTitle("Latenz (s)");

            var cats = org.apache.poi.xddf.usermodel.chart.XDDFDataSourcesFactory.fromStringCellRange(
                    sheet, new CellRangeAddress(1, rows.size(), 0, 0));

            var barData = (org.apache.poi.xddf.usermodel.chart.XDDFBarChartData)
                    chart.createData(org.apache.poi.xddf.usermodel.chart.ChartTypes.BAR, catAxis, valAxis);
            barData.setBarDirection(org.apache.poi.xddf.usermodel.chart.BarDirection.COL);
            barData.setBarGrouping(org.apache.poi.xddf.usermodel.chart.BarGrouping.CLUSTERED);

            String[] names = {"p50", "p95", "p99"};
            int[] seriesCols = {3, 4, 5};
            byte[][] colors = {COLOR_GREEN, COLOR_ORANGE, COLOR_RED};

            for (int si = 0; si < names.length; si++) {
                var data = org.apache.poi.xddf.usermodel.chart.XDDFDataSourcesFactory.fromNumericCellRange(
                        sheet, new CellRangeAddress(1, rows.size(), seriesCols[si], seriesCols[si]));
                var series = (org.apache.poi.xddf.usermodel.chart.XDDFBarChartData.Series) barData.addSeries(cats, data);
                series.setTitle(names[si], null);
                series.setFillProperties(new org.apache.poi.xddf.usermodel.XDDFSolidFillProperties(
                        xssfColor(colors[si])));
            }

            chart.plot(barData);
        }

        for (int c = 0; c < cols.length; c++) sheet.autoSizeColumn(c);
    }

    private static void writeMergedStartupChart(XSSFWorkbook wb, Styles s, List<CsvRow> rows) {
        XSSFSheet sheet = wb.createSheet("Startup (alle Runs)");

        String[] cols = {"Config", "Timestamp", "JVM-Flags", "Readiness (ms)", "First Req (s)", "Throughput (req/s)"};
        XSSFRow header = sheet.createRow(0);
        header.setHeightInPoints(25);
        for (int c = 0; c < cols.length; c++) {
            XSSFCell cell = header.createCell(c);
            cell.setCellValue(cols[c]);
            cell.setCellStyle(s.header);
        }

        for (int i = 0; i < rows.size(); i++) {
            CsvRow r = rows.get(i);
            XSSFRow row = sheet.createRow(i + 1);
            CellStyle ds = (i % 2 == 0) ? s.data : s.dataStripe;
            CellStyle ns = (i % 2 == 0) ? s.number : s.numberStripe;
            CellStyle n4s = (i % 2 == 0) ? s.number4 : s.number4Stripe;

            setCell(row, 0, r.configName, ds);
            setCell(row, 1, r.timestamp, ds);
            setCell(row, 2, r.jvmFlags, ds);
            setCellNum(row, 3, r.readinessMs, ns);
            setCellNum(row, 4, r.firstSeconds, n4s);
            setCellNum(row, 5, r.throughput, ns);
        }

        if (rows.size() >= 2) {
            XSSFDrawing drawing = sheet.createDrawingPatriarch();

            XSSFClientAnchor anchor = drawing.createAnchor(0, 0, 0, 0,
                    0, rows.size() + 3, 10, rows.size() + 17);
            XSSFChart chart = drawing.createChart(anchor);
            chart.setTitleText("Startup & Throughput alle Runs");

            var catAxis = chart.createCategoryAxis(org.apache.poi.xddf.usermodel.chart.AxisPosition.BOTTOM);
            var valAxis = chart.createValueAxis(org.apache.poi.xddf.usermodel.chart.AxisPosition.LEFT);

            var cats = org.apache.poi.xddf.usermodel.chart.XDDFDataSourcesFactory.fromStringCellRange(
                    sheet, new CellRangeAddress(1, rows.size(), 0, 0));

            var barData = (org.apache.poi.xddf.usermodel.chart.XDDFBarChartData)
                    chart.createData(org.apache.poi.xddf.usermodel.chart.ChartTypes.BAR, catAxis, valAxis);
            barData.setBarDirection(org.apache.poi.xddf.usermodel.chart.BarDirection.COL);
            barData.setBarGrouping(org.apache.poi.xddf.usermodel.chart.BarGrouping.CLUSTERED);

            var readinessData = org.apache.poi.xddf.usermodel.chart.XDDFDataSourcesFactory.fromNumericCellRange(
                    sheet, new CellRangeAddress(1, rows.size(), 3, 3));
            var readSeries = (org.apache.poi.xddf.usermodel.chart.XDDFBarChartData.Series) barData.addSeries(cats, readinessData);
            readSeries.setTitle("Readiness (ms)", null);
            readSeries.setFillProperties(new org.apache.poi.xddf.usermodel.XDDFSolidFillProperties(
                    xssfColor(COLOR_DARK_BLUE)));

            var throughputData = org.apache.poi.xddf.usermodel.chart.XDDFDataSourcesFactory.fromNumericCellRange(
                    sheet, new CellRangeAddress(1, rows.size(), 5, 5));
            var tpSeries = (org.apache.poi.xddf.usermodel.chart.XDDFBarChartData.Series) barData.addSeries(cats, throughputData);
            tpSeries.setTitle("Throughput (req/s)", null);
            tpSeries.setFillProperties(new org.apache.poi.xddf.usermodel.XDDFSolidFillProperties(
                    xssfColor(COLOR_GREEN)));

            chart.plot(barData);
        }

        for (int c = 0; c < cols.length; c++) sheet.autoSizeColumn(c);
    }

    private static void writeMergedRessourcenNote(XSSFWorkbook wb, Styles s) {
        XSSFSheet sheet = wb.createSheet("Hinweis Ressourcen");
        XSSFRow row = sheet.createRow(0);
        setCell(row, 0, "Docker-Ressourcendaten (CPU%/Mem%) sind nur im Einzelrun-Excel enthalten,", s.data);
        XSSFRow row2 = sheet.createRow(1);
        setCell(row2, 0, "da die CSV-Dateien diese Daten nicht exportieren.", s.data);
        XSSFRow row3 = sheet.createRow(2);
        setCell(row3, 0, "Fuer Ressourcenvergleiche die Einzelrun-Excels nutzen.", s.data);
        sheet.autoSizeColumn(0);
    }

    // ======================== CSV Parsing ========================

    /**
     * Zeile aus einer CSV-Datei mit Timestamp.
     */
    static class CsvRow {
        String timestamp;
        String scenario;
        int workloadN;
        String workloadPath;
        String configName;
        String dockerImage;
        String jvmFlags;
        double readinessMs;
        double firstSeconds;
        double p50;
        double p95;
        double p99;
        double mean;
        double totalMeasureTime;
        double throughput;
        int warmup;
        int measureReqs;
        int concurrency;
        long sleepMs;
    }

    static List<CsvRow> parseCsv(Path csvFile, String timestamp) throws IOException {
        List<CsvRow> rows = new ArrayList<>();
        try (BufferedReader br = Files.newBufferedReader(csvFile, StandardCharsets.UTF_8)) {
            String headerLine = br.readLine();
            if (headerLine == null) return rows;

            String[] headers = headerLine.split(",");
            Map<String, Integer> idx = new HashMap<>();
            for (int i = 0; i < headers.length; i++) {
                idx.put(headers[i].trim(), i);
            }

            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] vals = parseCsvLine(line);
                CsvRow r = new CsvRow();
                r.timestamp = timestamp;
                r.scenario = getVal(vals, idx, "scenario");
                r.workloadN = getIntVal(vals, idx, "workloadN");
                r.workloadPath = getVal(vals, idx, "workloadPath");
                r.configName = getVal(vals, idx, "configName");
                r.dockerImage = getVal(vals, idx, "dockerImage");
                r.jvmFlags = getVal(vals, idx, "effectiveJavaToolOptions");
                r.readinessMs = getDoubleVal(vals, idx, "readinessMs");
                r.firstSeconds = getDoubleVal(vals, idx, "firstSeconds");
                r.p50 = getDoubleVal(vals, idx, "latencyP50");
                r.p95 = getDoubleVal(vals, idx, "latencyP95");
                r.p99 = getDoubleVal(vals, idx, "latencyP99");
                r.mean = getDoubleVal(vals, idx, "latencyMean");
                r.totalMeasureTime = getDoubleVal(vals, idx, "totalMeasureTimeSeconds");
                r.throughput = getDoubleVal(vals, idx, "throughputReqPerSec");
                r.warmup = getIntVal(vals, idx, "warmupRequests");
                r.measureReqs = getIntVal(vals, idx, "measureRequests");
                r.concurrency = getIntVal(vals, idx, "concurrency");
                r.sleepMs = (long) getDoubleVal(vals, idx, "sleepBetweenRequestsMs");
                rows.add(r);
            }
        }
        return rows;
    }

    static String extractTimestamp(String filename) {
        // results-2026-03-05T11-49-59.609588200Z.csv → 2026-03-05T11:49:59
        String ts = filename.replace("results-", "").replace(".csv", "");
        // Restore colons in time portion
        int tIdx = ts.indexOf('T');
        if (tIdx >= 0) {
            String datePart = ts.substring(0, tIdx);
            String timePart = ts.substring(tIdx + 1);
            // Time: 11-49-59.xxx → 11:49:59
            String[] timeParts = timePart.split("\\.");
            String hms = timeParts[0].replace("-", ":");
            ts = datePart + " " + hms;
        }
        return ts;
    }

    // ======================== Styles ========================

    private record Styles(
            CellStyle header,
            CellStyle sectionHeader,
            CellStyle data,
            CellStyle dataStripe,
            CellStyle number,
            CellStyle numberStripe,
            CellStyle number4,
            CellStyle number4Stripe,
            CellStyle percent,
            CellStyle percentStripe
    ) {}

    private static Styles createStyles(XSSFWorkbook wb) {
        // --- Fonts ---
        XSSFFont headerFont = wb.createFont();
        headerFont.setBold(true);
        headerFont.setFontHeightInPoints((short) 10);
        headerFont.setColor(new XSSFColor(COLOR_HEADER_FG, null));

        XSSFFont sectionFont = wb.createFont();
        sectionFont.setBold(true);
        sectionFont.setFontHeightInPoints((short) 10);

        XSSFFont dataFont = wb.createFont();
        dataFont.setFontHeightInPoints((short) 10);

        // --- DataFormats ---
        DataFormat df = wb.createDataFormat();
        short fmtInt = df.getFormat("#,##0");
        short fmt4 = df.getFormat("0.0000");
        short fmtPct = df.getFormat("0.00\"%\"");

        // --- Header ---
        XSSFCellStyle headerStyle = wb.createCellStyle();
        headerStyle.setFont(headerFont);
        headerStyle.setFillForegroundColor(new XSSFColor(COLOR_HEADER_BG, null));
        headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        headerStyle.setAlignment(HorizontalAlignment.CENTER);
        headerStyle.setVerticalAlignment(VerticalAlignment.CENTER);
        headerStyle.setWrapText(true);
        setBorders(headerStyle);

        // --- Section Header ---
        XSSFCellStyle sectionStyle = wb.createCellStyle();
        sectionStyle.setFont(sectionFont);
        sectionStyle.setFillForegroundColor(new XSSFColor(COLOR_SECTION_BG, null));
        sectionStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        sectionStyle.setAlignment(HorizontalAlignment.CENTER);
        setBorders(sectionStyle);

        // --- Data ---
        XSSFCellStyle dataStyle = wb.createCellStyle();
        dataStyle.setFont(dataFont);
        setBorders(dataStyle);

        XSSFCellStyle dataStripeStyle = wb.createCellStyle();
        dataStripeStyle.setFont(dataFont);
        dataStripeStyle.setFillForegroundColor(new XSSFColor(COLOR_STRIPE_BG, null));
        dataStripeStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        setBorders(dataStripeStyle);

        // --- Number (int) ---
        XSSFCellStyle numStyle = wb.createCellStyle();
        numStyle.setFont(dataFont);
        numStyle.setDataFormat(fmtInt);
        numStyle.setAlignment(HorizontalAlignment.RIGHT);
        setBorders(numStyle);

        XSSFCellStyle numStripeStyle = wb.createCellStyle();
        numStripeStyle.setFont(dataFont);
        numStripeStyle.setDataFormat(fmtInt);
        numStripeStyle.setAlignment(HorizontalAlignment.RIGHT);
        numStripeStyle.setFillForegroundColor(new XSSFColor(COLOR_STRIPE_BG, null));
        numStripeStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        setBorders(numStripeStyle);

        // --- Number (4 decimal) ---
        XSSFCellStyle num4Style = wb.createCellStyle();
        num4Style.setFont(dataFont);
        num4Style.setDataFormat(fmt4);
        num4Style.setAlignment(HorizontalAlignment.RIGHT);
        setBorders(num4Style);

        XSSFCellStyle num4StripeStyle = wb.createCellStyle();
        num4StripeStyle.setFont(dataFont);
        num4StripeStyle.setDataFormat(fmt4);
        num4StripeStyle.setAlignment(HorizontalAlignment.RIGHT);
        num4StripeStyle.setFillForegroundColor(new XSSFColor(COLOR_STRIPE_BG, null));
        num4StripeStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        setBorders(num4StripeStyle);

        // --- Percent ---
        XSSFCellStyle pctStyle = wb.createCellStyle();
        pctStyle.setFont(dataFont);
        pctStyle.setDataFormat(fmtPct);
        pctStyle.setAlignment(HorizontalAlignment.RIGHT);
        setBorders(pctStyle);

        XSSFCellStyle pctStripeStyle = wb.createCellStyle();
        pctStripeStyle.setFont(dataFont);
        pctStripeStyle.setDataFormat(fmtPct);
        pctStripeStyle.setAlignment(HorizontalAlignment.RIGHT);
        pctStripeStyle.setFillForegroundColor(new XSSFColor(COLOR_STRIPE_BG, null));
        pctStripeStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        setBorders(pctStripeStyle);

        return new Styles(
                headerStyle, sectionStyle,
                dataStyle, dataStripeStyle,
                numStyle, numStripeStyle,
                num4Style, num4StripeStyle,
                pctStyle, pctStripeStyle
        );
    }

    private static void setBorders(CellStyle style) {
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
    }

    // ======================== Helpers ========================

    private static void setCell(XSSFRow row, int col, String value, CellStyle style) {
        XSSFCell cell = row.createCell(col);
        cell.setCellValue(value != null ? value : "");
        cell.setCellStyle(style);
    }

    private static void setCellNum(XSSFRow row, int col, double value, CellStyle style) {
        XSSFCell cell = row.createCell(col);
        if (Double.isNaN(value)) {
            cell.setCellValue("");
        } else {
            cell.setCellValue(value);
        }
        cell.setCellStyle(style);
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

    private static String normalizeFlags(String flags) {
        if (flags == null) return "(native)";
        if (flags.isBlank()) return "(keine)";
        return flags;
    }

    /**
     * Erzeugt eine XDDF-kompatible Farbe aus einem RGB-Byte-Array.
     */
    private static org.apache.poi.xddf.usermodel.XDDFColor xssfColor(byte[] rgb) {
        return org.apache.poi.xddf.usermodel.XDDFColor.from(rgb);
    }

    private static double percentile(List<Double> sorted, double p) {
        if (sorted == null || sorted.isEmpty()) return Double.NaN;
        int idx = (int) Math.ceil(p * sorted.size()) - 1;
        idx = Math.max(0, Math.min(idx, sorted.size() - 1));
        return sorted.get(idx);
    }

    private record DockerPhaseAvg(double cpuAvg, double memAvg, double memMax) {}

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

    private static String[] parseCsvLine(String line) {
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
            } else {
                if (c == '"') {
                    inQuotes = true;
                } else if (c == ',') {
                    fields.add(sb.toString());
                    sb.setLength(0);
                } else {
                    sb.append(c);
                }
            }
        }
        fields.add(sb.toString());
        return fields.toArray(new String[0]);
    }

    private static String getVal(String[] vals, Map<String, Integer> idx, String key) {
        Integer i = idx.get(key);
        if (i == null || i >= vals.length) return "";
        return vals[i];
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
