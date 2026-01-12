package de.mattis.resourcenoptimierung.bench;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Exportiert Benchmark-Ergebnisse ({@link RunResult}) in maschinenlesbare Dateien.
 *
 * <p>Warum gibt es diese Klasse?</p>
 * <ul>
 *   <li>Die Konsole ist gut für schnelle Sichtprüfung.</li>
 *   <li>Für spätere Auswertung (Excel etc.)
 *       braucht man strukturierte Daten.</li>
 * </ul>
 *
 * <p>Aktuell unterstützte Formate:</p>
 * <ul>
 *   <li><b>CSV</b>: kompakte Kennzahlen pro Run (gut für Excel)</li>
 *   <li><b>JSON</b>: enthält zusätzlich Rohdaten (z. B. alle Latenzen)</li>
 * </ul>
 *
 * <p>Hinweis: Das JSON wird bewusst ohne externe Libraries gebaut, damit der Bench
 * keine zusätzlichen Dependencies benötigt. Das bedeutet aber auch:
 * Die JSON-Erzeugung ist simpel gehalten und erwartet „normale“ Strings.</p>
 *
 * <p>Diese Klasse ist eine reine Utility-Klasse (nur statische Methoden).</p>
 */
public final class ResultExporters {

    /**
     * Private Konstruktor, damit keine Instanzen erzeugt werden.
     */
    private ResultExporters() {}

    /**
     * Schreibt die Benchmark-Ergebnisse als CSV-Datei.
     *
     * <p>CSV ist für schnelle Auswertung in Excel gedacht.
     * Deshalb werden hier nicht alle Rohdaten exportiert, sondern primär
     * aggregierte Kennzahlen pro Run.</p>
     *
     * <p>Spalten (Header):</p>
     * <ul>
     *   <li>{@code scenario}: Benchmark-Szenario (z. B. PAYLOAD_HEAVY_JSON)</li>
     *   <li>{@code workloadN}: Workload-Größe n</li>
     *   <li>{@code workloadPath}: tatsächlich aufgerufener Endpoint-Pfad (z. B. "/json?n=200000")</li>
     *   <li>{@code configName}: Name der Konfiguration (baseline, coops-off, ...)</li>
     *   <li>{@code dockerImage}: verwendetes Image</li>
     *   <li>{@code effectiveJavaToolOptions}: effektiv gesetzte Flags (oder leer)</li>
     *   <li>{@code readinessCheckUsed}: welcher Readiness-Mechanismus genutzt wurde</li>
     *   <li>{@code readinessMs}: Zeit bis „ready“</li>
     *   <li>{@code firstSeconds}: Dauer des ersten Requests nach Readiness</li>
     *   <li>{@code latencyCount}: Anzahl der gemessenen Requests</li>
     *   <li>{@code latencyMean}: Mittelwert der gemessenen Latenzen</li>
     *   <li>{@code latencyP50/P95/P99}: Perzentile der gemessenen Latenzen</li>
     * </ul>
     *
     * <p>Wichtig: Für die Perzentile werden die Latenzen vorher sortiert.
     * Die Implementierung nutzt einen einfachen Nearest-Rank-Ansatz.</p>
     *
     * @param results Liste von {@link RunResult}-Objekten
     * @param path Zielpfad der CSV-Datei
     * @throws IOException wenn Schreiben/Verzeichniszugriff fehlschlägt
     */
    public static void writeCsv(List<RunResult> results, Path path) throws IOException {
        try (BufferedWriter w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {

            // scenario + workloadN + workloadPath + flags + readiness check
            w.write("""
                    scenario,workloadN,workloadPath,configName,dockerImage,effectiveJavaToolOptions,readinessCheckUsed,readinessMs,firstSeconds,latencyCount,latencyMean,latencyP50,latencyP95,latencyP99
                    """.trim());
            w.newLine();

            for (RunResult r : results) {
                // Latenzen sortieren (Voraussetzung für Perzentile)
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
                w.write(Double.toString(percentile(lats, 0.50))); w.write(",");
                w.write(Double.toString(percentile(lats, 0.95))); w.write(",");
                w.write(Double.toString(percentile(lats, 0.99)));
                w.newLine();
            }
        }
    }

    /**
     * Schreibt die Benchmark-Ergebnisse als JSON-Datei.
     *
     * <p>JSON ist für automatisierte Auswertung gedacht (z. B. Python-Scripts),
     * daher enthält dieser Export zusätzliche Details:</p>
     * <ul>
     *   <li>Scenario + Workload-Parameter</li>
     *   <li>Config + effektiv gesetzte Flags</li>
     *   <li>Readiness-Mechanismus</li>
     *   <li>Readiness-/First-Timings</li>
     *   <li><b>Rohdaten:</b> alle gemessenen Latenzen ({@code jsonLatenciesSeconds})</li>
     * </ul>
     *
     * <p>Hinweis: Das JSON wird ohne externe Bibliothek gebaut.
     * Es wird nur minimal escaped (Backslashes und Quotes).</p>
     *
     * @param results Liste von {@link RunResult}-Objekten
     * @param path Zielpfad der JSON-Datei
     * @throws IOException wenn Schreiben/Verzeichniszugriff fehlschlägt
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
            sb.append("\"jsonLatenciesSeconds\":").append(array(r.latenciesSeconds()));

            sb.append("}");
        }
        sb.append("]}");
        Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
    }

    // ---- helpers ----

    /**
     * Berechnet ein Perzentil aus einer aufsteigend sortierten Liste.
     *
     * <p>Implementierung: Nearest-Rank mit {@code ceil}.</p>
     *
     * @param sorted aufsteigend sortierte Werte
     * @param p Perzentil (0..1), z. B. 0.50, 0.95
     * @return Perzentilwert oder {@link Double#NaN} bei leerer Liste
     */
    private static double percentile(List<Double> sorted, double p) {
        if (sorted == null || sorted.isEmpty()) return Double.NaN;
        int idx = (int) Math.ceil(p * sorted.size()) - 1;
        idx = Math.max(0, Math.min(idx, sorted.size() - 1));
        return sorted.get(idx);
    }

    /**
     * Escaped einen String für CSV (minimal, aber ausreichend).
     *
     * <p>Wenn der String Kommas, Quotes oder Zeilenumbrüche enthält,
     * wird er in Quotes gesetzt und Quotes werden verdoppelt.</p>
     *
     * @param s Roh-String
     * @return CSV-sicherer String (ohne führende/trailing Kommas)
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
     * @param l Liste der Latenzen
     * @return JSON-Array-String, z. B. {@code [0.1,0.2,0.3]}
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
     * Escaped einen String für JSON (minimal).
     *
     * <p>Es werden nur Backslash und Quotes escaped, da die exportierten Werte
     * im Benchmark typischerweise einfache Strings sind.</p>
     *
     * @param s Roh-String (kann null sein)
     * @return JSON-Stringliteral, z. B. {@code "abc"}, oder {@code null}
     */
    private static String js(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /**
     * Normalisiert JVM-Flags für den Export.
     *
     * <p>Unterscheidung:</p>
     * <ul>
     *   <li>{@code null} → native (für Native Images nicht anwendbar)</li>
     *   <li>blank → baseline/keine Flags (Export als leerer String)</li>
     *   <li>sonst → Flags unverändert</li>
     * </ul>
     *
     * <p>Wichtig: Die Darstellung "(none)" oder "(native)" passiert bewusst
     * im {@link ConsoleSummaryPrinter}. So bleibt der Export "roh/neutral".</p>
     *
     * @param flags effektive Flags (z. B. {@code JAVA_TOOL_OPTIONS})
     * @return normalisierter String oder {@code null}
     */
    private static String normalizeFlags(String flags) {
        if (flags == null) return null;     // native
        if (flags.isBlank()) return "";     // baseline "(none)" lieber im Printer darstellen
        return flags;
    }
}
