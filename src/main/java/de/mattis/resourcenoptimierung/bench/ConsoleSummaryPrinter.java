package de.mattis.resourcenoptimierung.bench;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.DoubleSummaryStatistics;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Gibt eine kompakte Zusammenfassung der Benchmark-Ergebnisse auf der Konsole aus.
 *
 * Der ConsoleSummaryPrinter ist nur fuer die Darstellung zustaendig.
 * Er fuehrt keine Benchmarks aus und veraendert keine Daten.
 *
 * Was ausgegeben wird:
 * - Gruppierung nach BenchmarkScenario
 * - pro Szenario: Readiness und First-Request (min/avg/max)
 * - pro Szenario: Latenzen als p50/p95/p99 ueber alle Requests
 * - pro Szenario: Durchsatz (Throughput) in req/s
 * - pro Run: median/p95/mean, totalTime, throughput, Docker-Stats (IDLE/LOAD/POST), Flags und Workload-Pfad
 *
 * Die Runs werden nach p95-Latenz sortiert, damit langsame Konfigurationen sofort auffallen.
 */
public final class ConsoleSummaryPrinter {

    private ConsoleSummaryPrinter() {}

    /**
     * Gibt alle Ergebnisse auf der Konsole aus.
     *
     * @param results Liste der RunResult-Eintraege
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
            printRepetitionAggregation(group);
        }
    }

    /**
     * Gibt eine Zusammenfassung fuer ein Szenario aus.
     *
     * Kennzahlen:
     * - Readiness (ms): min/avg/max
     * - First request (s): min/avg/max
     * - Latenzen (s): p50/p95/p99 ueber alle Requests aller Runs
     * - Throughput (req/s): min/avg/max ueber alle Runs
     * - Messprofil: Warmup/Messung/Concurrency/Sleep
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

        // Throughput
        DoubleSummaryStatistics throughputStats = group.stream()
                .mapToDouble(RunResult::throughputReqPerSec)
                .summaryStatistics();

        // All latencies combined
        List<Double> allLatencies = new ArrayList<>();
        for (RunResult r : group) {
            allLatencies.addAll(r.latenciesSeconds());
        }
        allLatencies.sort(Double::compareTo);

        System.out.println("Runs: " + group.size());

        // Messprofil aus dem ersten Run (alle Runs im gleichen Szenario verwenden dasselbe Profil)
        MeasurementProfile profile = group.get(0).measurementProfile();
        System.out.printf("Profile: warmup=%d measure=%d concurrency=%d sleepMs=%d%n",
                profile.warmupRequests(), profile.measureRequests(),
                profile.concurrency(), profile.sleepBetweenRequestsMs());

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
                    BenchStats.percentile(allLatencies, 0.50),
                    BenchStats.percentile(allLatencies, 0.95),
                    BenchStats.percentile(allLatencies, 0.99),
                    allLatencies.size()
            );
        }

        System.out.printf("Throughput (req/s) min/avg/max: %.1f / %.1f / %.1f%n",
                throughputStats.getMin(),
                throughputStats.getAverage(),
                throughputStats.getMax());
    }

    /**
     * Gibt pro Run eine Detailzeile aus.
     *
     * Pro Run:
     * - readiness, first request
     * - median/p95/mean der Latenzen
     * - totalTime und throughput
     * - Docker-Stats fuer IDLE/LOAD/POST (falls vorhanden)
     * - Flags und Workload-Pfad
     *
     * @param group Runs eines Szenarios
     */
    private static void printPerRun(List<RunResult> group) {
        System.out.println();
        System.out.println("Per run (median/p95/mean + throughput + docker mem):");

        // nach p95 sortieren
        group.stream()
                .sorted(Comparator.comparingDouble(ConsoleSummaryPrinter::p95).reversed())
                .forEach(r -> {
                    List<Double> l = new ArrayList<>(r.latenciesSeconds());
                    l.sort(Double::compareTo);

                    double median = BenchStats.percentile(l, 0.50);
                    double p95 = BenchStats.percentile(l, 0.95);
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

                    System.out.printf("   totalTime=%.3fs  throughput=%.1f req/s%n",
                            r.totalMeasureTimeSeconds(),
                            r.throughputReqPerSec());

                    BenchStats.DockerPhaseAvg idle = BenchStats.dockerPhaseAvg(r.dockerIdleSamples());
                    BenchStats.DockerPhaseAvg load = BenchStats.dockerPhaseAvg(r.dockerLoadSamples());
                    BenchStats.DockerPhaseAvg post = BenchStats.dockerPhaseAvg(r.dockerPostSamples());

                    // Fokus: Werte unter Last
                    if (load != null) {
                        System.out.printf("   docker LOAD: cpu avg=%.2f%%  mem avg=%.2f%%  mem max=%.2f%%  memUsage(max)=%s%n",
                                load.cpuAvg(),
                                load.memAvg(),
                                load.memMax(),
                                load.memUsageAtMax()
                        );
                    }

                    // Optional: idle/post zum Vergleich
                    if (idle != null) {
                        System.out.printf("   docker IDLE: mem avg=%.2f%%  mem max=%.2f%%%n",
                                idle.memAvg(), idle.memMax());
                    }
                    if (post != null) {
                        System.out.printf("   docker POST: mem avg=%.2f%%  mem max=%.2f%%%n",
                                post.memAvg(), post.memMax());
                    }

                    System.out.println("   flags: " + flags);
                    System.out.println("   workload: " + r.workloadPath());
                });
    }

    /**
     * Liefert die p95-Latenz eines Runs.
     * Wird fuer die Sortierung der Ausgabe genutzt.
     *
     * @param r Run-Ergebnis
     * @return p95 in Sekunden oder NaN, wenn keine Daten vorhanden sind
     */
    private static double p95(RunResult r) {
        List<Double> l = r.latenciesSeconds();
        if (l == null || l.isEmpty()) return Double.NaN;
        List<Double> sorted = new ArrayList<>(l);
        sorted.sort(Double::compareTo);
        return BenchStats.percentile(sorted, 0.95);
    }

    /**
     * Gibt eine Aggregation ueber Wiederholungen pro Konfiguration aus.
     *
     * Wird nur ausgegeben, wenn es mehrere Wiederholungen pro Config gibt.
     * Zeigt pro Konfiguration: Mittelwert ± Standardabweichung fuer
     * Readiness, First-Request, p50, p95, Mean-Latenz und Throughput.
     *
     * @param group Runs eines Szenarios
     */
    private static void printRepetitionAggregation(List<RunResult> group) {
        // Gruppieren nach configName
        Map<String, List<RunResult>> byConfig = group.stream()
                .collect(Collectors.groupingBy(RunResult::configName, LinkedHashMap::new, Collectors.toList()));

        // Nur ausgeben, wenn mindestens eine Config > 1 Wiederholung hat
        boolean hasMultiple = byConfig.values().stream().anyMatch(l -> l.size() > 1);
        if (!hasMultiple) return;

        System.out.println();
        System.out.println("Aggregation ueber Wiederholungen (mean +/- stddev):");

        for (Map.Entry<String, List<RunResult>> e : byConfig.entrySet()) {
            String name = e.getKey();
            List<RunResult> runs = e.getValue();

            if (runs.size() < 2) {
                System.out.printf(" - %-20s  (%d run, kein Aggregat)%n", name, runs.size());
                continue;
            }

            double[] readiness = runs.stream().mapToDouble(RunResult::readinessMs).toArray();
            double[] first = runs.stream().mapToDouble(RunResult::firstSeconds).toArray();
            double[] throughput = runs.stream().mapToDouble(RunResult::throughputReqPerSec).toArray();

            double[] p50s = runs.stream().mapToDouble(r -> {
                List<Double> l = new ArrayList<>(r.latenciesSeconds());
                l.sort(Double::compareTo);
                return BenchStats.percentile(l, 0.50);
            }).toArray();

            double[] p95s = runs.stream().mapToDouble(r -> {
                List<Double> l = new ArrayList<>(r.latenciesSeconds());
                l.sort(Double::compareTo);
                return BenchStats.percentile(l, 0.95);
            }).toArray();

            double[] means = runs.stream().mapToDouble(r ->
                    r.latenciesSeconds().stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN)
            ).toArray();

            System.out.printf(" - %-20s  (n=%d)%n", name, runs.size());
            System.out.printf("     readiness:  %s ms%n", fmtMeanStddev(readiness, "%.0f"));
            System.out.printf("     first:      %s s%n", fmtMeanStddev(first, "%.4f"));
            System.out.printf("     p50:        %s s%n", fmtMeanStddev(p50s, "%.4f"));
            System.out.printf("     p95:        %s s%n", fmtMeanStddev(p95s, "%.4f"));
            System.out.printf("     mean:       %s s%n", fmtMeanStddev(means, "%.4f"));
            System.out.printf("     throughput: %s req/s%n", fmtMeanStddev(throughput, "%.1f"));
        }
    }

    /**
     * Formatiert Mittelwert +/- Standardabweichung fuer ein Array von Werten.
     *
     * @param values Messwerte
     * @param fmt    printf-Format fuer die Zahlen (z.B. "%.0f" oder "%.4f")
     * @return formatierte Zeichenkette wie "1234 +/- 56"
     */
    private static String fmtMeanStddev(double[] values, String fmt) {
        double mean = BenchStats.mean(values);
        if (values.length < 2) {
            return String.format(fmt, mean);
        }
        double stddev = BenchStats.sampleStddev(values);
        return String.format(fmt + " +/- " + fmt, mean, stddev);
    }

    private static String normalizeFlagsForPrint(String flags) {
        if (flags == null) return "(native)";
        if (flags.isBlank()) return "(none)";
        return flags;
    }
}
