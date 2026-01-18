package de.mattis.resourcenoptimierung.bench;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.DoubleSummaryStatistics;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gibt eine kompakte Zusammenfassung der Benchmark-Ergebnisse auf der Konsole aus.
 *
 * Der ConsoleSummaryPrinter ist nur für die Darstellung zuständig.
 * Er führt keine Benchmarks aus und verändert keine Daten.
 *
 * Was ausgegeben wird:
 * - Gruppierung nach BenchmarkScenario
 * - pro Szenario: Readiness und First-Request (min/avg/max)
 * - pro Szenario: Latenzen als p50/p95/p99 über alle Requests
 * - pro Run: median/p95/mean, Docker-Stats (IDLE/LOAD/POST), Flags und Workload-Pfad
 *
 * Die Runs werden nach p95-Latenz sortiert, damit langsame Konfigurationen sofort auffallen.
 */
public final class ConsoleSummaryPrinter {

    private ConsoleSummaryPrinter() {}

    /**
     * Gibt alle Ergebnisse auf der Konsole aus.
     *
     * @param results Liste der RunResult-Einträge
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
     * Gibt eine Zusammenfassung für ein Szenario aus.
     *
     * Kennzahlen:
     * - Readiness (ms): min/avg/max
     * - First request (s): min/avg/max
     * - Latenzen (s): p50/p95/p99 über alle Requests aller Runs
     *
     * @param group Runs eines Szenarios
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
     * Gibt pro Run eine Detailzeile aus.
     *
     * Pro Run:
     * - readiness, first request
     * - median/p95/mean der Latenzen
     * - Docker-Stats für IDLE/LOAD/POST (falls vorhanden)
     * - Flags und Workload-Pfad
     *
     * @param group Runs eines Szenarios
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
     * Liefert die p95-Latenz eines Runs.
     * Wird für die Sortierung der Ausgabe genutzt.
     *
     * @param r Run-Ergebnis
     * @return p95 in Sekunden oder NaN, wenn keine Daten vorhanden sind
     */
    private static double p95(RunResult r) {
        List<Double> l = r.latenciesSeconds();
        if (l == null || l.isEmpty()) return Double.NaN;
        List<Double> sorted = new ArrayList<>(l);
        sorted.sort(Double::compareTo);
        return percentile(sorted, 0.95);
    }

    /**
     * Berechnet ein Perzentil aus einer sortierten Liste.
     *
     * Hinweis:
     * - Die Liste muss aufsteigend sortiert sein.
     * - Verwendet einen einfachen Nearest-Rank-Ansatz.
     *
     * @param sorted aufsteigend sortierte Werte
     * @param p Perzentil zwischen 0 und 1 (z.B. 0.50, 0.95)
     * @return Perzentilwert oder NaN, wenn keine Daten vorhanden sind
     */
    private static double percentile(List<Double> sorted, double p) {
        if (sorted == null || sorted.isEmpty()) return Double.NaN;
        int idx = (int) Math.ceil(p * sorted.size()) - 1;
        idx = Math.max(0, Math.min(idx, sorted.size() - 1));
        return sorted.get(idx);
    }

    /**
     * Formatiert die Flags für die Konsolenausgabe.
     *
     * @param flags effektive JAVA_TOOL_OPTIONS (null bei Native)
     * @return "(native)", "(none)" oder der Flag-String
     */
    private static String normalizeFlagsForPrint(String flags) {
        if (flags == null) return "(native)";
        if (flags.isBlank()) return "(none)";
        return flags;
    }

    /**
     * Verdichtete Kennzahlen für Docker-Stats einer Phase (IDLE, LOAD, POST).
     *
     * Enthält nur die wichtigsten Werte, damit die Konsolenausgabe kompakt bleibt.
     *
     * @param cpuAvg durchschnittliche CPU-Auslastung in Prozent
     * @param memPercAvg durchschnittliche Speicherauslastung in Prozent
     * @param memPercMax maximale Speicherauslastung in Prozent
     * @param memUsageAtMax Speicherbelegung als "usage / limit" zum Zeitpunkt des Maximums
     */
    private record DockerPhaseStats(
            double cpuAvg,
            double memPercAvg,
            double memPercMax,
            String memUsageAtMax
    ) {}

    /**
     * Verdichtet mehrere DockerStatSample zu einer kompakten Zusammenfassung.
     *
     * Es werden Mittelwerte für CPU und Memory sowie das Memory-Maximum berechnet.
     * Zusätzlich wird der Rohwert "usage / limit" für das Memory-Maximum gespeichert.
     *
     * @param samples Docker-Stat-Samples einer Phase
     * @return Zusammenfassung oder null, wenn keine Samples vorhanden sind
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
