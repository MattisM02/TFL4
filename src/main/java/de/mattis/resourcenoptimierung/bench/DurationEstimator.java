package de.mattis.resourcenoptimierung.bench;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Schaetzt die Laufzeit eines geplanten Benchmark-Durchlaufs basierend auf historischen Daten.
 *
 * <p>Liest alle CSV-Ergebnisse aus {@code bench-results/} und baut daraus eine Lookup-Tabelle
 * mit durchschnittlichen Wall-Clock-Zeiten pro Kombination aus (configName, scenario).
 *
 * <h3>Lookup-Strategie (Prioritaet absteigend)</h3>
 * <ol>
 *   <li><b>Exakter Match:</b> gleicher configName + gleiches Szenario, skaliert linear
 *       mit dem Verhaeltnis der Request-Anzahl (warmup+measure).</li>
 *   <li><b>Image-Match:</b> anderer configName, aber gleiches dockerImage + Szenario.
 *       Durchschnitt aller passenden Runs, skaliert mit Request-Verhaeltnis.</li>
 *   <li><b>Szenario-Match:</b> Durchschnitt aller Runs fuer dieses Szenario,
 *       skaliert mit Request-Verhaeltnis.</li>
 *   <li><b>Fallback:</b> Konstante {@value #FALLBACK_SECONDS}s pro Run.</li>
 * </ol>
 *
 * <h3>Skalierung</h3>
 * <p>Da die Wall-Clock-Zeit stark von der Request-Anzahl abhaengt, wird die historische
 * Dauer proportional skaliert: {@code estimated = historical * (plannedRequests / historicalRequests)}.
 * Dabei wird ein Basis-Overhead ({@link #FIXED_OVERHEAD_SECONDS}) fuer Container-Start,
 * Idle/Post-Stats und Cleanup abgezogen, da dieser nicht mit der Request-Anzahl skaliert.</p>
 */
public final class DurationEstimator {

    private DurationEstimator() { /* utility */ }

    /** Fester Overhead pro Run in Sekunden (Container-Start-Anteil, Idle/Post-Stats, Cleanup). */
    static final double FIXED_OVERHEAD_SECONDS = 10.0;

    /** Fallback-Schaetzung pro Run, wenn keine historischen Daten vorliegen. */
    static final double FALLBACK_SECONDS = 120.0;

    // ======================== Historische Daten ========================

    /**
     * Ein einzelner historischer Datenpunkt aus einer CSV-Zeile.
     *
     * @param configName Name der Konfiguration
     * @param dockerImage Docker-Image
     * @param scenario Szenario-Name (z.B. "PAYLOAD_HEAVY_JSON")
     * @param warmupRequests Anzahl Warmup-Requests
     * @param measureRequests Anzahl Mess-Requests
     * @param wallClockSeconds gemessene Wall-Clock-Dauer in Sekunden (0 falls nicht vorhanden)
     * @param readinessMs Readiness-Zeit in Millisekunden
     * @param totalMeasureTimeSeconds Gesamtdauer der Messphase in Sekunden
     */
    record HistoricalRun(
            String configName,
            String dockerImage,
            String scenario,
            int warmupRequests,
            int measureRequests,
            double wallClockSeconds,
            long readinessMs,
            double totalMeasureTimeSeconds) {

        /** Gesamtzahl Requests (Warmup + Measure). */
        int totalRequests() {
            return warmupRequests + measureRequests;
        }

        /**
         * Effektive Wall-Clock-Dauer: entweder die explizit gemessene wallClockSeconds
         * oder eine konservative Schaetzung aus readinessMs + totalMeasureTimeSeconds + Overhead.
         *
         * <p>Die Schaetzung wird fuer aeltere CSVs verwendet, die noch kein wallClockSeconds-Feld haben.
         */
        double effectiveWallClock() {
            if (wallClockSeconds > 0) return wallClockSeconds;
            // Fallback: readiness + measure + geschaetzter Warmup + Overhead
            double readinessSec = readinessMs / 1000.0;
            // Warmup-Dauer schaetzen: proportional zur Messdauer
            double avgLatency = measureRequests > 0
                    ? totalMeasureTimeSeconds / measureRequests : 0;
            double estimatedWarmup = warmupRequests * avgLatency;
            return readinessSec + estimatedWarmup + totalMeasureTimeSeconds + FIXED_OVERHEAD_SECONDS;
        }
    }

    // ======================== CSV-Parsing ========================

    /**
     * Liest alle CSV-Dateien aus dem angegebenen Verzeichnis und extrahiert historische Runs.
     *
     * <p>Akzeptiert sowohl neue CSVs (mit wallClockSeconds-Spalte) als auch aeltere
     * (ohne diese Spalte — Fallback-Berechnung in {@link HistoricalRun#effectiveWallClock()}).
     *
     * @param directory Verzeichnis mit CSV-Dateien (typisch: bench-results/)
     * @return Liste aller historischen Runs
     */
    static List<HistoricalRun> loadHistory(Path directory) {
        List<HistoricalRun> runs = new ArrayList<>();
        if (!Files.isDirectory(directory)) return runs;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "results-*.csv")) {
            for (Path csv : stream) {
                runs.addAll(parseCsv(csv));
            }
        } catch (IOException e) {
            System.err.println("[WARN] Could not read historical CSVs: " + e.getMessage());
        }
        return runs;
    }

    /**
     * Parst eine einzelne CSV-Datei und extrahiert die relevanten Felder.
     *
     * @param csv Pfad zur CSV-Datei
     * @return Liste der geparsten Runs
     */
    static List<HistoricalRun> parseCsv(Path csv) {
        List<HistoricalRun> runs = new ArrayList<>();
        try (BufferedReader br = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
            String headerLine = br.readLine();
            if (headerLine == null) return runs;

            String[] headers = headerLine.split(",", -1);
            Map<String, Integer> idx = new HashMap<>();
            for (int i = 0; i < headers.length; i++) {
                idx.put(headers[i].trim(), i);
            }

            // Pflichtfelder pruefen
            if (!idx.containsKey("configName") || !idx.containsKey("scenario")) return runs;

            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    String[] cols = splitCsvLine(line);
                    runs.add(new HistoricalRun(
                            col(cols, idx, "configName", ""),
                            col(cols, idx, "dockerImage", ""),
                            col(cols, idx, "scenario", ""),
                            intCol(cols, idx, "warmupRequests", 0),
                            intCol(cols, idx, "measureRequests", 0),
                            doubleCol(cols, idx, "wallClockSeconds", 0.0),
                            longCol(cols, idx, "readinessMs", 0),
                            doubleCol(cols, idx, "totalMeasureTimeSeconds", 0.0)
                    ));
                } catch (Exception e) {
                    // Einzelne fehlerhafte Zeilen ueberspringen
                }
            }
        } catch (IOException e) {
            System.err.println("[WARN] Could not parse CSV " + csv + ": " + e.getMessage());
        }
        return runs;
    }

    // ======================== Schaetzung ========================

    /**
     * Ergebnis einer Laufzeitschaetzung fuer einen geplanten Benchmark.
     *
     * @param perConfigEstimates geschaetzte Dauer pro Config-Name in Sekunden (ein Run)
     * @param totalSeconds geschaetzte Gesamtdauer in Sekunden
     * @param totalRuns Gesamtzahl der Runs (configs * repetitions)
     * @param historicalRunCount Anzahl genutzter historischer Datenpunkte
     */
    public record Estimate(
            Map<String, Double> perConfigEstimates,
            double totalSeconds,
            int totalRuns,
            int historicalRunCount) { }

    /**
     * Berechnet die geschaetzte Gesamtdauer fuer einen geplanten Benchmark-Durchlauf.
     *
     * @param plan Benchmark-Plan mit Configs
     * @param scenario Szenario
     * @param profile Messprofil (Warmup/Measure-Anzahl)
     * @param repetitions Wiederholungen
     * @param historyDir Verzeichnis mit historischen CSVs
     * @return Schaetzung
     */
    public static Estimate estimate(BenchmarkPlan plan, BenchmarkScenario scenario,
                                    MeasurementProfile profile, int repetitions, Path historyDir) {
        List<HistoricalRun> history = loadHistory(historyDir);
        return estimate(plan, scenario, profile, repetitions, history);
    }

    /**
     * Berechnet die Schaetzung mit bereits geladenen historischen Daten.
     * (Testbar ohne Dateisystem-Zugriff.)
     */
    static Estimate estimate(BenchmarkPlan plan, BenchmarkScenario scenario,
                             MeasurementProfile profile, int repetitions,
                             List<HistoricalRun> history) {
        String scenarioName = scenario.name();
        int plannedTotal = profile.warmupRequests() + profile.measureRequests();

        Map<String, Double> perConfig = new LinkedHashMap<>();
        double totalSec = 0;

        for (BenchmarkConfig cfg : plan.configs) {
            double est = estimateSingleRun(cfg, scenarioName, plannedTotal, history);
            perConfig.put(cfg.name(), est);
            totalSec += est * repetitions;
        }

        return new Estimate(perConfig, totalSec, plan.configs.size() * repetitions,
                history.size());
    }

    /**
     * Schaetzt die Dauer eines einzelnen Runs fuer eine bestimmte Config.
     *
     * @param cfg Benchmark-Konfiguration
     * @param scenarioName Szenario-Name
     * @param plannedTotalRequests geplante Gesamtzahl Requests (warmup+measure)
     * @param history historische Datenpunkte
     * @return geschaetzte Dauer in Sekunden
     */
    static double estimateSingleRun(BenchmarkConfig cfg, String scenarioName,
                                    int plannedTotalRequests, List<HistoricalRun> history) {
        // Prio 1: Exakter Match (configName + scenario)
        List<HistoricalRun> exact = history.stream()
                .filter(h -> h.configName().equals(cfg.name()) && h.scenario().equals(scenarioName))
                .toList();
        if (!exact.isEmpty()) {
            return scaleEstimate(exact, plannedTotalRequests);
        }

        // Prio 2: Image-Match (dockerImage + scenario)
        List<HistoricalRun> imageMatch = history.stream()
                .filter(h -> h.dockerImage().equals(cfg.dockerImage()) && h.scenario().equals(scenarioName))
                .toList();
        if (!imageMatch.isEmpty()) {
            return scaleEstimate(imageMatch, plannedTotalRequests);
        }

        // Prio 3: Szenario-Match (beliebiger Config + gleiches Szenario)
        List<HistoricalRun> scenarioMatch = history.stream()
                .filter(h -> h.scenario().equals(scenarioName))
                .toList();
        if (!scenarioMatch.isEmpty()) {
            return scaleEstimate(scenarioMatch, plannedTotalRequests);
        }

        // Prio 4: Fallback
        return FALLBACK_SECONDS;
    }

    /**
     * Skaliert die historische Dauer auf die geplante Request-Anzahl.
     *
     * <p>Formel: {@code fixedOverhead + (wallClock - fixedOverhead) * (planned / historical)}.
     * Der fixe Overhead (Container-Start, Stats, Cleanup) skaliert nicht mit der Request-Anzahl.
     *
     * @param runs historische Runs (mindestens 1)
     * @param plannedTotalRequests geplante Gesamtzahl Requests
     * @return geschaetzte Dauer in Sekunden
     */
    static double scaleEstimate(List<HistoricalRun> runs, int plannedTotalRequests) {
        double avgWallClock = runs.stream()
                .mapToDouble(HistoricalRun::effectiveWallClock)
                .average()
                .orElse(FALLBACK_SECONDS);

        double avgHistoricalRequests = runs.stream()
                .mapToInt(HistoricalRun::totalRequests)
                .average()
                .orElse(1);

        if (avgHistoricalRequests <= 0) return FALLBACK_SECONDS;

        // Skalierung: fester Overhead bleibt gleich, variabler Teil skaliert
        double variablePart = Math.max(0, avgWallClock - FIXED_OVERHEAD_SECONDS);
        double ratio = plannedTotalRequests / avgHistoricalRequests;
        return FIXED_OVERHEAD_SECONDS + variablePart * ratio;
    }

    // ======================== Ausgabe ========================

    /**
     * Gibt die geschaetzte Laufzeit auf der Konsole aus.
     *
     * @param plan Benchmark-Plan
     * @param scenario Szenario
     * @param profile Messprofil
     * @param repetitions Wiederholungen
     */
    public static void printEstimate(BenchmarkPlan plan, BenchmarkScenario scenario,
                                     MeasurementProfile profile, int repetitions) {
        Path historyDir = Path.of(BenchDefaults.OUTPUT_DIR);
        Estimate est = estimate(plan, scenario, profile, repetitions, historyDir);

        System.out.println();
        System.out.println("Geschaetzte Laufzeit basierend auf " + est.historicalRunCount() + " historischen Runs:");
        System.out.println();
        System.out.printf("  %-30s | %12s | %5s | %10s%n", "Config", "Geschaetzt/Run", "Runs", "Gesamt");
        System.out.println("  " + "-".repeat(30) + "-+-" + "-".repeat(12) + "-+-"
                + "-".repeat(5) + "-+-" + "-".repeat(10));

        for (var entry : est.perConfigEstimates().entrySet()) {
            double perRun = entry.getValue();
            double total = perRun * repetitions;
            System.out.printf("  %-30s | %12s | %5d | %10s%n",
                    entry.getKey(),
                    formatDuration(perRun),
                    repetitions,
                    formatDuration(total));
        }

        System.out.println("  " + "-".repeat(30) + "-+-" + "-".repeat(12) + "-+-"
                + "-".repeat(5) + "-+-" + "-".repeat(10));
        System.out.printf("  %-30s   %12s   %5d   %10s%n",
                "GESAMT (" + plan.configs.size() + " Configs x " + repetitions + " Reps)",
                "", est.totalRuns(), formatDuration(est.totalSeconds()));
        System.out.println();

        if (est.historicalRunCount() == 0) {
            System.out.println("  HINWEIS: Keine historischen Daten gefunden.");
            System.out.println("  Die Schaetzung basiert auf dem Fallback von " + (int) FALLBACK_SECONDS + "s pro Run.");
            System.out.println("  Nach dem ersten Durchlauf werden die Schaetzungen praeziser.");
            System.out.println();
        }
    }

    /**
     * Gibt eine Live-ETA-Zeile auf stderr aus (waehrend des Benchmarks).
     *
     * @param completedRuns bisherige Runs
     * @param totalRuns geplante Runs
     * @param elapsedSeconds bereits verstrichene Zeit in Sekunden
     * @param lastResult letztes Ergebnis (fuer Config-Name und Dauer)
     */
    public static void printLiveEta(int completedRuns, int totalRuns,
                                    double elapsedSeconds, RunResult lastResult) {
        int remaining = totalRuns - completedRuns;
        double avgPerRun = elapsedSeconds / completedRuns;
        double etaSeconds = remaining * avgPerRun;

        System.err.printf("[Run %d/%d] %s rep %d — %s%n",
                completedRuns, totalRuns,
                lastResult.configName(), lastResult.repetition(),
                formatDuration(lastResult.wallClockSeconds()));
        System.err.printf("           ETA: ~%s verbleibend (basierend auf %d Runs, Oe %s/Run)%n",
                formatDuration(etaSeconds), completedRuns, formatDuration(avgPerRun));
    }

    // ======================== Hilfsmethoden ========================

    /**
     * Formatiert eine Dauer in Sekunden als menschenlesbaren String.
     *
     * @param seconds Dauer in Sekunden
     * @return z.B. "2m 15s", "1h 30m", "8h 15m"
     */
    static String formatDuration(double seconds) {
        if (seconds < 0) return "?";
        long total = Math.round(seconds);
        long h = total / 3600;
        long m = (total % 3600) / 60;
        long s = total % 60;

        if (h > 0) return h + "h " + m + "m";
        if (m > 0) return m + "m " + s + "s";
        return s + "s";
    }

    /** Liest einen String-Wert aus einer CSV-Spalte. */
    private static String col(String[] cols, Map<String, Integer> idx, String key, String fallback) {
        Integer i = idx.get(key);
        if (i == null || i >= cols.length) return fallback;
        String val = cols[i].trim();
        // Entferne Anfuehrungszeichen
        if (val.startsWith("\"") && val.endsWith("\"") && val.length() >= 2) {
            val = val.substring(1, val.length() - 1).replace("\"\"", "\"");
        }
        return val.isEmpty() ? fallback : val;
    }

    /** Liest einen int-Wert aus einer CSV-Spalte. */
    private static int intCol(String[] cols, Map<String, Integer> idx, String key, int fallback) {
        String val = col(cols, idx, key, "");
        if (val.isEmpty()) return fallback;
        try { return Integer.parseInt(val); } catch (NumberFormatException e) { return fallback; }
    }

    /** Liest einen long-Wert aus einer CSV-Spalte. */
    private static long longCol(String[] cols, Map<String, Integer> idx, String key, long fallback) {
        String val = col(cols, idx, key, "");
        if (val.isEmpty()) return fallback;
        try { return Long.parseLong(val); } catch (NumberFormatException e) { return fallback; }
    }

    /** Liest einen double-Wert aus einer CSV-Spalte. */
    private static double doubleCol(String[] cols, Map<String, Integer> idx, String key, double fallback) {
        String val = col(cols, idx, key, "");
        if (val.isEmpty()) return fallback;
        try { return Double.parseDouble(val); } catch (NumberFormatException e) { return fallback; }
    }

    /**
     * Splittet eine CSV-Zeile unter Beruecksichtigung von Quoted-Fields.
     *
     * <p>Einfache CSV-Felder werden bei Komma getrennt. Felder in Anfuehrungszeichen
     * koennen Kommas und escaped Quotes ("") enthalten.
     */
    static String[] splitCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++; // skip escaped quote
                    } else {
                        inQuotes = false;
                    }
                } else {
                    current.append(c);
                }
            } else {
                if (c == '"') {
                    inQuotes = true;
                } else if (c == ',') {
                    fields.add(current.toString());
                    current.setLength(0);
                } else {
                    current.append(c);
                }
            }
        }
        fields.add(current.toString());
        return fields.toArray(new String[0]);
    }
}
