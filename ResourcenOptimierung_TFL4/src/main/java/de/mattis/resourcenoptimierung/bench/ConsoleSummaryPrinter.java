package de.mattis.resourcenoptimierung.bench;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.DoubleSummaryStatistics;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ConsoleSummaryPrinter {

    private ConsoleSummaryPrinter() {}

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
            allLatencies.addAll(r.jsonLatenciesSeconds());
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

    private static void printPerRun(List<RunResult> group) {
        System.out.println();
        System.out.println("Per run (median/p95/mean + docker mem end + flags):");

        // nach p95 sortieren
        group.stream()
                .sorted(Comparator.comparingDouble(ConsoleSummaryPrinter::p95).reversed())
                .forEach(r -> {
                    List<Double> l = new ArrayList<>(r.jsonLatenciesSeconds());
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
                            r.jsonLatenciesSeconds().size(),
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

    private static double p95(RunResult r) {
        List<Double> l = r.jsonLatenciesSeconds();
        if (l == null || l.isEmpty()) return Double.NaN;
        List<Double> sorted = new ArrayList<>(l);
        sorted.sort(Double::compareTo);
        return percentile(sorted, 0.95);
    }

    private static double percentile(List<Double> sorted, double p) {
        if (sorted == null || sorted.isEmpty()) return Double.NaN;
        int idx = (int) Math.ceil(p * sorted.size()) - 1;
        idx = Math.max(0, Math.min(idx, sorted.size() - 1));
        return sorted.get(idx);
    }

    private static String normalizeFlagsForPrint(String flags) {
        if (flags == null) return "(native)";
        if (flags.isBlank()) return "(none)";
        return flags;
    }
    private record DockerPhaseStats(
            double cpuAvg,
            double memPercAvg,
            double memPercMax,
            String memUsageAtMax
    ) {}

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
