package de.mattis.resourcenoptimierung.bench;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;


/**
 * Exportiert Benchmark-Ergebnisse (RunResult) in Dateien.
 *
 * Die Konsole ist gut fuer einen schnellen Ueberblick.
 * Fuer spaetere Auswertung (z.B. Excel oder Skripte) werden strukturierte Exporte benoetigt.
 *
 * Unterstuetzte Formate:
 * - CSV: kompakte Kennzahlen pro Run
 * - JSON: enthaelt zusaetzlich Rohdaten wie die einzelnen Latenzen
 *
 * Das JSON wird bewusst ohne externe Bibliotheken erzeugt, damit der Bench keine
 * zusaetzlichen Dependencies benoetigt.
 */
public final class ResultExporters {

    private ResultExporters() {}

    /**
     * Schreibt die Benchmark-Ergebnisse als CSV-Datei.
     *
     * Exportiert pro Run eine Zeile mit Kennzahlen und Metadaten.
     * Perzentile werden aus sortierten Latenzen berechnet.
     *
     * @param results Run-Ergebnisse
     * @param path Zielpfad der CSV-Datei
     * @throws IOException wenn Schreiben fehlschlaegt
     */
    public static void writeCsv(List<RunResult> results, Path path) throws IOException {
        try (BufferedWriter w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writeCsvHeader(w);
            for (RunResult r : results) {
                writeCsvRow(w, r);
            }
        }
    }

    /**
     * Haengt ein einzelnes Ergebnis an eine CSV-Datei an.
     *
     * Wenn die Datei noch nicht existiert oder leer ist, wird zuerst der Header geschrieben.
     * Diese Methode ermoeglicht inkrementelle Sicherung waehrend des Benchmarks,
     * damit Teilergebnisse bei spaeteren Fehlern nicht verloren gehen.
     *
     * @param path Zielpfad der CSV-Datei
     * @param result einzelnes Run-Ergebnis
     * @throws IOException wenn Schreiben fehlschlaegt
     */
    public static void appendCsvRow(Path path, RunResult result) throws IOException {
        boolean needsHeader = !Files.exists(path) || Files.size(path) == 0;
        try (BufferedWriter w = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            if (needsHeader) {
                writeCsvHeader(w);
            }
            writeCsvRow(w, result);
        }
    }

    /**
     * Schreibt die CSV-Headerzeile.
     *
     * @param w Writer
     * @throws IOException wenn Schreiben fehlschlaegt
     */
    static void writeCsvHeader(BufferedWriter w) throws IOException {
        w.write("scenario,workloadN,workloadPath,configName,dockerImage,effectiveJavaToolOptions," +
                "readinessCheckUsed,readinessMs,firstSeconds," +
                "latencyCount,latencyMean,latencyP50,latencyP95,latencyP99," +
                "totalMeasureTimeSeconds,throughputReqPerSec," +
                "warmupRequests,measureRequests,concurrency,sleepBetweenRequestsMs," +
                "cpuLoadAvg,memLoadAvg,memLoadMax," +
                "gcCount,gcFullCount,gcTotalPauseMs,gcMaxPauseMs,gcOverheadPercent,gcPeakHeapAfterMb," +
                "repetition,category,runtimeModel,wallClockSeconds");
        w.newLine();
    }

    /**
     * Schreibt eine einzelne CSV-Datenzeile fuer ein RunResult.
     *
     * @param w Writer
     * @param r Run-Ergebnis
     * @throws IOException wenn Schreiben fehlschlaegt
     */
    static void writeCsvRow(BufferedWriter w, RunResult r) throws IOException {
        // Latenzen sortieren (Voraussetzung fuer Perzentile)
        List<Double> lats = new ArrayList<>(r.latenciesSeconds());
        lats.sort(Double::compareTo);

        // Mittelwert der Latenzen
        double mean = lats.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);

        // --- Metadaten: Szenario + Workload ---
        w.write(csv(r.scenario() == null ? "" : r.scenario().name())); w.write(",");
        w.write(Integer.toString(r.workloadN())); w.write(",");
        w.write(csv(r.workloadPath())); w.write(",");

        // --- Metadaten: Konfiguration ---
        w.write(csv(r.configName())); w.write(",");
        w.write(csv(r.dockerImage())); w.write(",");
        w.write(csv(normalizeFlags(r.effectiveJavaToolOptions()))); w.write(",");
        w.write(csv(r.readinessCheckUsed() == null ? "" : r.readinessCheckUsed().name())); w.write(",");

        // --- Timings ---
        w.write(Long.toString(r.readinessMs())); w.write(",");
        w.write(Double.toString(r.firstSeconds())); w.write(",");

        // --- Latenz-Kennzahlen ---
        w.write(Integer.toString(lats.size())); w.write(",");
        w.write(Double.toString(mean)); w.write(",");
        w.write(Double.toString(BenchStats.percentile(lats, 0.50))); w.write(",");
        w.write(Double.toString(BenchStats.percentile(lats, 0.95))); w.write(",");
        w.write(Double.toString(BenchStats.percentile(lats, 0.99))); w.write(",");

        // --- Gesamtzeit + Durchsatz ---
        w.write(Double.toString(r.totalMeasureTimeSeconds())); w.write(",");
        w.write(Double.toString(r.throughputReqPerSec())); w.write(",");

        // --- Messprofil ---
        MeasurementProfile p = r.measurementProfile();
        w.write(Integer.toString(p.warmupRequests())); w.write(",");
        w.write(Integer.toString(p.measureRequests())); w.write(",");
        w.write(Integer.toString(p.concurrency())); w.write(",");
        w.write(Long.toString(p.sleepBetweenRequestsMs())); w.write(",");

        // --- Docker-Stats (LOAD-Phase) ---
        BenchStats.DockerPhaseAvg loadPhase = BenchStats.dockerPhaseAvg(r.dockerLoadSamples());
        double cpuLoadAvg = BenchStats.dval(loadPhase, a -> a.cpuAvg());
        double memLoadAvg = BenchStats.dval(loadPhase, a -> a.memAvg());
        double memLoadMax = BenchStats.dval(loadPhase, a -> a.memMax());
        w.write(Double.toString(cpuLoadAvg)); w.write(",");
        w.write(Double.toString(memLoadAvg)); w.write(",");
        w.write(Double.toString(memLoadMax)); w.write(",");

        // --- GC-Kennzahlen ---
        GcSummary gc = r.gcSummary();
        if (gc != null) {
            w.write(Integer.toString(gc.gcCount())); w.write(",");
            w.write(Integer.toString(gc.fullGcCount())); w.write(",");
            w.write(Double.toString(gc.totalPauseMs())); w.write(",");
            w.write(Double.toString(gc.maxPauseMs())); w.write(",");
            w.write(Double.toString(gc.gcOverheadPercent())); w.write(",");
            w.write(Double.toString(gc.peakHeapAfterGcKb() / 1024.0));
        } else {
            w.write(",,,,,");   // 6 leere Felder
        }
        w.write(",");

        w.write(Integer.toString(r.repetition()));
        w.write(",");
        w.write(csv(r.category()));
        w.write(",");
        w.write(csv(r.runtimeModel()));
        w.write(",");
        w.write(Double.toString(r.wallClockSeconds()));
        w.newLine();
    }

    /**
     * Schreibt die Benchmark-Ergebnisse als JSON-Datei.
     *
     * Der Export enthaelt zusaetzlich zu Metadaten und Timings auch die Rohdaten
     * der gemessenen Latenzen sowie Gesamtzeit und Durchsatz.
     *
     * @param results Run-Ergebnisse
     * @param path Zielpfad der JSON-Datei
     * @throws IOException wenn Schreiben fehlschlaegt
     */
    public static void writeJson(List<RunResult> results, Path path) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"results\":[");
        for (int i = 0; i < results.size(); i++) {
            RunResult r = results.get(i);
            if (i > 0) sb.append(",");
            sb.append("{");

            // scenario + workloadN + workloadPath
            sb.append("\"scenario\":").append(js(r.scenario() == null ? null : r.scenario().name())).append(",");
            sb.append("\"workloadN\":").append(r.workloadN()).append(",");
            sb.append("\"workloadPath\":").append(js(r.workloadPath())).append(",");

            // config + env
            sb.append("\"configName\":").append(js(r.configName())).append(",");
            sb.append("\"dockerImage\":").append(js(r.dockerImage())).append(",");
            sb.append("\"effectiveJavaToolOptions\":").append(js(normalizeFlags(r.effectiveJavaToolOptions()))).append(",");
            sb.append("\"readinessCheckUsed\":").append(js(r.readinessCheckUsed() == null ? null : r.readinessCheckUsed().name())).append(",");

            // timings + raw latencies
            sb.append("\"readinessMs\":").append(r.readinessMs()).append(",");
            sb.append("\"firstSeconds\":").append(r.firstSeconds()).append(",");
            sb.append("\"latenciesSeconds\":").append(array(r.latenciesSeconds())).append(",");

            // Gesamtzeit + Durchsatz
            sb.append("\"totalMeasureTimeSeconds\":").append(r.totalMeasureTimeSeconds()).append(",");
            sb.append("\"throughputReqPerSec\":").append(r.throughputReqPerSec()).append(",");

            // Messprofil
            MeasurementProfile p = r.measurementProfile();
            sb.append("\"measurementProfile\":{");
            sb.append("\"warmupRequests\":").append(p.warmupRequests()).append(",");
            sb.append("\"measureRequests\":").append(p.measureRequests()).append(",");
            sb.append("\"concurrency\":").append(p.concurrency()).append(",");
            sb.append("\"sleepBetweenRequestsMs\":").append(p.sleepBetweenRequestsMs());
            sb.append("},");

            // GC-Kennzahlen
            GcSummary gc = r.gcSummary();
            if (gc != null) {
                sb.append("\"gcSummary\":{");
                sb.append("\"gcCount\":").append(gc.gcCount()).append(",");
                sb.append("\"fullGcCount\":").append(gc.fullGcCount()).append(",");
                sb.append("\"totalPauseMs\":").append(gc.totalPauseMs()).append(",");
                sb.append("\"maxPauseMs\":").append(gc.maxPauseMs()).append(",");
                sb.append("\"avgPauseMs\":").append(gc.avgPauseMs()).append(",");
                sb.append("\"gcOverheadPercent\":").append(gc.gcOverheadPercent()).append(",");
                sb.append("\"peakHeapAfterGcKb\":").append(gc.peakHeapAfterGcKb());
                sb.append("},");
            } else {
                sb.append("\"gcSummary\":null,");
            }

            // Repetition + wallClockSeconds
            sb.append("\"repetition\":").append(r.repetition()).append(",");
            sb.append("\"wallClockSeconds\":").append(r.wallClockSeconds());

            sb.append("}");
        }
        sb.append("]}");
        Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
    }

    // ---- helpers ----

    /**
     * Escaped einen String fuer CSV.
     *
     * @param s Roh-String
     * @return CSV-sicherer String
     */
    private static String csv(String s) {
        if (s == null) return "";
        boolean needsQuotes = s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r");
        String esc = s.replace("\"", "\"\"");
        return needsQuotes ? "\"" + esc + "\"" : esc;
    }

    /**
     * Baut ein JSON-Array aus einer Liste von Doubles.
     *
     * @param l Liste von Werten
     * @return JSON-Array als String
     */
    private static String array(List<Double> l) {
        if (l == null) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < l.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(l.get(i));
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * Escaped einen String fuer JSON (minimal).
     *
     * @param s Roh-String
     * @return JSON-Stringliteral oder null
     */
    private static String js(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\")
                       .replace("\"", "\\\"")
                       .replace("\n", "\\n")
                       .replace("\r", "\\r")
                       .replace("\t", "\\t") + "\"";
    }

    /**
     * Normalisiert JVM-Flags fuer den Export.
     *
     * @param flags effektive Flags (z.B. JAVA_TOOL_OPTIONS), kann null sein
     * @return normalisierte Darstellung (null fuer native, leer fuer keine Flags)
     */
    private static String normalizeFlags(String flags) {
        if (flags == null) return null;     // native
        if (flags.isBlank()) return "";     // baseline "(none)" lieber im Printer darstellen
        return flags;
    }

}
