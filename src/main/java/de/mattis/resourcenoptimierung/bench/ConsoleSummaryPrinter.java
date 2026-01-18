package de.mattis.resourcenoptimierung.bench;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.DoubleSummaryStatistics;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gibt eine kompakte, menschenlesbare Zusammenfassung der Benchmark-Ergebnisse
 * auf der Konsole aus.
 *
 * <p>Der {@code ConsoleSummaryPrinter} ist ausschließlich für die Darstellung
 * zuständig. Er:</p>
 * <ul>
 *   <li>gruppiert Ergebnisse nach {@link BenchmarkScenario},</li>
 *   <li>berechnet aggregierte Kennzahlen (min/avg/max, p50/p95/p99),</li>
 *   <li>druckt pro Szenario eine Übersicht sowie Details pro Run.</li>
 * </ul>
 *
 * <p>Die Klasse verändert keine Daten und führt keine Messungen durch.
 * Sie arbeitet ausschließlich mit den bereits berechneten {@link RunResult}-Objekten.</p>
 *
 * <p>Alle Methoden sind statisch, da kein interner Zustand benötigt wird.</p>
 */
public final class ConsoleSummaryPrinter {

    /**
     * Private Konstruktor, um Instanziierung zu verhindern.
     *
     * <p>Diese Klasse ist als reine Utility-Klasse gedacht.</p>
     */
    private ConsoleSummaryPrinter() {}

    /**
     * Gibt die vollständige Benchmark-Zusammenfassung auf der Konsole aus.
     *
     * <p>Ablauf:</p>
     * <ol>
     *   <li>Prüft, ob Ergebnisse vorhanden sind.</li>
     *   <li>Gruppiert alle {@link RunResult}-Objekte nach {@link BenchmarkScenario}.</li>
     *   <li>
     *     Gibt für jedes Szenario aus:
     *     <ul>
     *       <li>eine aggregierte Zusammenfassung (Readiness, First Request, Latenzen)</li>
     *       <li>eine Detailansicht pro Konfiguration</li>
     *     </ul>
     *   </li>
     * </ol>
     *
     * @param results Liste aller Benchmark-Ergebnisse (ein Eintrag pro Run)
     */
    public static void print(List<RunResult> results) {
        System.out.println("=== Benchmark Summary ===");

        if (results == null || results.isEmpty()) {
            System.out.println("No results.");
            return;
        }

        // Gruppieren nach Scenario (stabile Reihenfolge)
        Map<BenchmarkScenario, List<RunResult>> byScenario = new LinkedHashMap<>();
        for (RunResult r : results) {
            BenchmarkScenario sc = r.scenario();
            byScenario.computeIfAbsent(sc, k -> new ArrayList<>()).add(r);
        }

        for (Map.Entry<BenchmarkScenario, List<RunResult>> entry : byScenario.entrySet()) {
            BenchmarkScenario scenario = entry.getKey();
            List<RunResult> group = entry.getValue();

            System.out.println();
            System.out.println("=== Scenario: " + scenario + " ===");

            printScenarioSummary(group);
            printPerRun(group);
        }
    }

    /**
     * Gibt eine aggregierte Zusammenfassung für ein einzelnes Benchmark-Szenario aus.
     *
     * <p>Berechnete Kennzahlen:</p>
     * <ul>
     *   <li>Readiness-Zeit (min / avg / max)</li>
     *   <li>First-Request-Zeit (min / avg / max)</li>
     *   <li>Latenz-Perzentile über alle Runs (p50 / p95 / p99)</li>
     * </ul>
     *
     * <p>Die Latenzstatistik wird über <b>alle Requests aller Runs</b>
     * dieses Szenarios gebildet, um einen stabileren Gesamteindruck zu geben.</p>
     *
     * @param group Liste aller {@link RunResult}-Objekte für ein Szenario
     */
    private static void printScenarioSummary(List<RunResult> group) {
        // Readiness
        DoubleSummaryStatistics readinessStats = group.stream()
                .mapToDouble(RunResult::readinessMs)
                .summaryStatistics();

        // First request
        DoubleSummaryStatistics firstStats = group.stream()
                .mapToDouble(RunResult::firstSeconds)
                .summaryStatistics();

        // All latencies combined
        List<Double> allLatencies = new ArrayList<>();
        for (RunResult r : group) {
            allLatencies.addAll(r.latenciesSeconds());
        }
        allLatencies.sort(Double::compareTo);

        System.out.println("Runs: " + group.size());

        System.out.printf("Readiness (ms)   min/avg/max: %.0f / %.1f / %.0f%n",
                readinessStats.getMin(),
                readinessStats.getAverage(),
                readinessStats.getMax());

        System.out.printf("First (s)        min/avg/max: %.3f / %.3f / %.3f%n",
                firstStats.getMin(),
                firstStats.getAverage(),
                firstStats.getMax());

        if (!allLatencies.isEmpty()) {
            System.out.printf("Latency (s)      p50/p95/p99: %.3f / %.3f / %.3f  (n=%d)%n",
                    percentile(allLatencies, 0.50),
                    percentile(allLatencies, 0.95),
                    percentile(allLatencies, 0.99),
                    allLatencies.size()
            );
        }
    }

    /**
     * Gibt pro {@link RunResult} (also pro Konfiguration innerhalb eines Szenarios) eine Detailzeile aus.
     *
     * <p>Was hier ausgegeben wird:</p>
     * <ul>
     *   <li>Run-Identität: Config-Name und ob JVM oder Native</li>
     *   <li>Zeitwerte: Readiness, First Request, median/p95/mean der Request-Latenzen</li>
     *   <li>Anzahl gemessener Requests (req)</li>
     *   <li>Welcher Readiness-Check benutzt wurde</li>
     *   <li>Docker-Stats in 3 Phasen (IDLE / LOAD / POST), v. a. Memory/CPU unter Last</li>
     *   <li>Aktive JVM-Flags (effective JAVA_TOOL_OPTIONS)</li>
     *   <li>Workload-URL (z. B. /json?n=... oder /alloc?n=...)</li>
     * </ul>
     *
     * <p>Die Runs werden absichtlich nach p95-Latenz sortiert (langsamste zuerst),
     * damit problematische Konfigurationen sofort auffallen.</p>
     *
     * @param group alle Runs eines einzelnen Szenarios
     */
    private static void printPerRun(List<RunResult> group) {
        System.out.println();
        System.out.println("Per run (median/p95/mean + docker mem end + flags):");

        // nach p95 sortieren
        group.stream()
                .sorted(Comparator.comparingDouble(ConsoleSummaryPrinter::p95).reversed())
                .forEach(r -> {
                    List<Double> l = new ArrayList<>(r.latenciesSeconds());
                    l.sort(Double::compareTo);

                    double median = percentile(l, 0.50);
                    double p95 = percentile(l, 0.95);
                    double mean = l.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);

                    String flags = normalizeFlagsForPrint(r.effectiveJavaToolOptions());
                    String kind = (r.dockerImage() != null && r.dockerImage().contains("native")) ? "NATIVE" : "JVM";

                    System.out.printf(
                            " - %-20s (%s) readiness=%dms first=%.3fs  median=%.3fs p95=%.3fs mean=%.3fs  req=%d  check=%s%n",
                            r.configName(),
                            kind,
                            r.readinessMs(),
                            r.firstSeconds(),
                            median,
                            p95,
                            mean,
                            r.latenciesSeconds().size(),
                            r.readinessCheckUsed()
                    );

                    DockerPhaseStats idle = phaseStats(r.dockerIdleSamples());
                    DockerPhaseStats load = phaseStats(r.dockerLoadSamples());
                    DockerPhaseStats post = phaseStats(r.dockerPostSamples());

                    // Fokus: Werte unter Last
                    if (load != null) {
                        System.out.printf("   docker LOAD: cpu avg=%.2f%%  mem avg=%.2f%%  mem max=%.2f%%  memUsage(max)=%s%n",
                                load.cpuAvg(),
                                load.memPercAvg(),
                                load.memPercMax(),
                                load.memUsageAtMax()
                        );
                    }

                    // Optional: idle/post zum Vergleich
                    if (idle != null) {
                        System.out.printf("   docker IDLE: mem avg=%.2f%%  mem max=%.2f%%%n",
                                idle.memPercAvg(), idle.memPercMax());
                    }
                    if (post != null) {
                        System.out.printf("   docker POST: mem avg=%.2f%%  mem max=%.2f%%%n",
                                post.memPercAvg(), post.memPercMax());
                    }

                    System.out.println("   flags: " + flags);
                    System.out.println("   workload: " + r.workloadPath());
                });
    }

    /**
     * Hilfsmethode: p95 (95. Perzentil) für die Request-Latenzen eines Runs.
     *
     * <p>Wird primär für Sortierung ("langsamste zuerst") genutzt.</p>
     *
     * @param r ein einzelnes Run-Ergebnis
     * @return p95-Latenz in Sekunden oder {@link Double#NaN}, wenn keine Latenzen vorhanden sind
     */
    private static double p95(RunResult r) {
        List<Double> l = r.latenciesSeconds();
        if (l == null || l.isEmpty()) return Double.NaN;
        List<Double> sorted = new ArrayList<>(l);
        sorted.sort(Double::compareTo);
        return percentile(sorted, 0.95);
    }

    /**
     * Berechnet ein Perzentil aus einer aufsteigend sortierten Liste von Messwerten.
     *
     * <p>Wichtig:</p>
     * <ul>
     *   <li>Die Liste muss bereits sortiert sein (aufsteigend).</li>
     *   <li>Diese Implementierung nutzt einen einfachen Index-Ansatz (Nearest-Rank, "ceil").</li>
     *   <li>Für Benchmark-Zwecke reicht das aus und ist reproduzierbar.</li>
     * </ul>
     *
     * @param sorted aufsteigend sortierte Messwerte
     * @param p      Perzentil als Wert zwischen 0 und 1 (z. B. 0.50, 0.95, 0.99)
     * @return Wert an der Perzentil-Position oder {@link Double#NaN}, wenn keine Daten vorhanden sind
     */
    private static double percentile(List<Double> sorted, double p) {
        if (sorted == null || sorted.isEmpty()) return Double.NaN;
        int idx = (int) Math.ceil(p * sorted.size()) - 1;
        idx = Math.max(0, Math.min(idx, sorted.size() - 1));
        return sorted.get(idx);
    }

    /**
     * Normalisiert JVM-Flags für die Konsole.
     *
     * <p>Hintergrund: Für JVM-Runs speichern wir effektiv gesetzte Flags
     * (meist als {@code JAVA_TOOL_OPTIONS}). Für Native Images ist das nicht anwendbar.</p>
     *
     * @param flags effektive Flags / JAVA_TOOL_OPTIONS (kann null oder leer sein)
     * @return lesbarer String für Konsole ("(native)" / "(none)" / Flags)
     */
    private static String normalizeFlagsForPrint(String flags) {
        if (flags == null) return "(native)";
        if (flags.isBlank()) return "(none)";
        return flags;
    }

    /**
     * Verdichtete Kennzahlen für Docker-Stats einer Phase (IDLE/LOAD/POST).
     *
     * <p>Speichert absichtlich nur wenige, aussagekräftige Werte, damit die
     * Konsolenausgabe kompakt bleibt.</p>
     *
     * @param cpuAvg        durchschnittliche CPU-Auslastung in Prozent
     * @param memPercAvg    durchschnittliche Speicherauslastung in Prozent
     * @param memPercMax    maximale Speicherauslastung in Prozent
     * @param memUsageAtMax Speicher-String ("usage / limit") zum Zeitpunkt des Maximums
     */
    private record DockerPhaseStats(
            double cpuAvg,
            double memPercAvg,
            double memPercMax,
            String memUsageAtMax
    ) {}

    /**
     * Berechnet aus mehreren Docker-Stat-Samples einer Phase kompakte Kennzahlen.
     *
     * <p>Die Samples kommen aus {@code docker stats --no-stream} und wurden in {@link SingleRun}
     * in festen Intervallen gesammelt.</p>
     *
     * <p>Berechnungen:</p>
     * <ul>
     *   <li>CPU avg: Mittelwert von {@link DockerStatSample#cpuPercent()}</li>
     *   <li>Mem avg: Mittelwert von {@link DockerStatSample#memPercent()}</li>
     *   <li>Mem max: Maximum von {@link DockerStatSample#memPercent()}</li>
     *   <li>memUsageAtMax: Roh-Strings ("usage / limit") beim Maximum, hilfreich fürs Debugging</li>
     * </ul>
     *
     * @param samples Liste von Docker-Stat-Samples einer Phase
     * @return verdichtete Stats oder {@code null}, wenn keine Samples vorhanden sind
     */
    private static DockerPhaseStats phaseStats(List<DockerStatSample> samples) {
        if (samples == null || samples.isEmpty()) return null;

        double cpuSum = 0.0;
        double memSum = 0.0;

        double memMax = -1.0;
        String memUsageAtMax = null;

        for (DockerStatSample s : samples) {
            cpuSum += s.cpuPercent();
            memSum += s.memPercent();

            if (s.memPercent() > memMax) {
                memMax = s.memPercent();
                memUsageAtMax = s.memUsageRaw() + " / " + s.memLimitRaw();
            }
        }

        double cpuAvg = cpuSum / samples.size();
        double memAvg = memSum / samples.size();

        return new DockerPhaseStats(cpuAvg, memAvg, memMax, memUsageAtMax);
    }

}
