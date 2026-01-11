package de.mattis.resourcenoptimierung.bench;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.DoubleSummaryStatistics;
import java.util.List;

public final class ConsoleSummaryPrinter {

    private ConsoleSummaryPrinter() {}

    public static void print(List<RunResult> results) {
        System.out.println("=== Benchmark Summary ===");

        if (results == null || results.isEmpty()) {
            System.out.println("No results.");
            return;
        }

        // --- Readiness ---
        DoubleSummaryStatistics readinessStats = results.stream()
                .mapToDouble(RunResult::readinessMs)
                .summaryStatistics();

        // --- First JSON ---
        DoubleSummaryStatistics firstJsonStats = results.stream()
                .mapToDouble(RunResult::firstJsonSeconds)
                .summaryStatistics();

        // --- All JSON latencies combined ---
        List<Double> allLatencies = new ArrayList<>();
        for (RunResult r : results) {
            allLatencies.addAll(r.jsonLatenciesSeconds());
        }
        allLatencies.sort(Double::compareTo);

        System.out.println("Runs: " + results.size());
        System.out.printf("Readiness (ms)   min/avg/max: %.0f / %.1f / %.0f%n",
                readinessStats.getMin(),
                readinessStats.getAverage(),
                readinessStats.getMax());

        System.out.printf("First JSON (s)   min/avg/max: %.3f / %.3f / %.3f%n",
                firstJsonStats.getMin(),
                firstJsonStats.getAverage(),
                firstJsonStats.getMax());

        if (!allLatencies.isEmpty()) {
            System.out.printf("JSON latency (s) p50/p95/p99: %.3f / %.3f / %.3f%n",
                    percentile(allLatencies, 0.50),
                    percentile(allLatencies, 0.95),
                    percentile(allLatencies, 0.99));
        }

        System.out.println();
        System.out.println("Top 5 slowest configs (by p95 latency):");

        results.stream()
                .sorted(Comparator.comparingDouble(ConsoleSummaryPrinter::p95).reversed())
                .limit(5)
                .forEach(r ->
                        System.out.printf(" - %-30s  p95=%.3f s  image=%s%n",
                                r.configName(),
                                p95(r),
                                r.dockerImage())
                );

        System.out.println();
        System.out.println("Per run (latencies: median/p95/mean, plus docker end mem):");

        for (RunResult r : results) {
            List<Double> l = new ArrayList<>(r.jsonLatenciesSeconds());
            l.sort(Double::compareTo);

            double median = percentile(l, 0.50);
            double p95 = percentile(l, 0.95);
            double mean = l.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);

            String kind = (r.dockerImage() != null && r.dockerImage().contains("native")) ? "NATIVE" : "JVM";

            System.out.printf(
                    " - %-20s (%s) readiness=%dms first=%.3fs  median=%.3fs p95=%.3fs mean=%.3fs  n=%d  check=%s%n",
                    r.configName(),
                    kind,
                    r.readinessMs(),
                    r.firstJsonSeconds(),
                    median,
                    p95,
                    mean,
                    r.jsonLatenciesSeconds().size(),
                    r.readinessCheckUsed()
            );

            DockerStatSample end = r.dockerEndSample();
            if (end != null) {
                System.out.printf("   docker end mem: %s / %s (%.2f%%)%n",
                        end.memUsageRaw(), end.memLimitRaw(), end.memPercent());
            }

            String flags = r.effectiveJavaToolOptions();
            if (flags == null) flags = "(native)";
            if (flags.isBlank()) flags = "(none)";
            System.out.println("   flags: " + flags);

            if (r.startupLogSnippet() != null && !r.startupLogSnippet().isBlank()) {
                System.out.println("   startup log (trimmed):");
                String[] lines = r.startupLogSnippet().split("\n");
                for (int i = 0; i < Math.min(5, lines.length); i++) {
                    System.out.println("     " + lines[i]);
                }
            }
        }
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
}
