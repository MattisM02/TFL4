package de.mattis.resourcenoptimierung.bench;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class ResultExporters {

    private ResultExporters() {}

    public static void writeCsv(List<RunResult> results, Path path) throws IOException {
        try (BufferedWriter w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {

            // scenario + workloadN + workloadPath + flags + readiness check
            w.write("""
                    scenario,workloadN,workloadPath,configName,dockerImage,effectiveJavaToolOptions,readinessCheckUsed,readinessMs,firstSeconds,latencyCount,latencyMean,latencyP50,latencyP95,latencyP99
                    """.trim());
            w.newLine();

            for (RunResult r : results) {
                List<Double> lats = new ArrayList<>(r.jsonLatenciesSeconds());
                lats.sort(Double::compareTo);

                double mean = lats.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);

                w.write(csv(r.scenario() == null ? "" : r.scenario().name())); w.write(",");
                w.write(Integer.toString(r.workloadN())); w.write(",");
                w.write(csv(r.workloadPath())); w.write(",");

                w.write(csv(r.configName())); w.write(",");
                w.write(csv(r.dockerImage())); w.write(",");
                w.write(csv(normalizeFlags(r.effectiveJavaToolOptions()))); w.write(",");
                w.write(csv(r.readinessCheckUsed() == null ? "" : r.readinessCheckUsed().name())); w.write(",");

                w.write(Long.toString(r.readinessMs())); w.write(",");
                w.write(Double.toString(r.firstSeconds())); w.write(",");

                w.write(Integer.toString(lats.size())); w.write(",");
                w.write(Double.toString(mean)); w.write(",");
                w.write(Double.toString(percentile(lats, 0.50))); w.write(",");
                w.write(Double.toString(percentile(lats, 0.95))); w.write(",");
                w.write(Double.toString(percentile(lats, 0.99)));
                w.newLine();
            }
        }
    }

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
            sb.append("\"jsonLatenciesSeconds\":").append(array(r.jsonLatenciesSeconds()));

            sb.append("}");
        }
        sb.append("]}");
        Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
    }

    // ---- helpers ----

    private static double percentile(List<Double> sorted, double p) {
        if (sorted == null || sorted.isEmpty()) return Double.NaN;
        int idx = (int) Math.ceil(p * sorted.size()) - 1;
        idx = Math.max(0, Math.min(idx, sorted.size() - 1));
        return sorted.get(idx);
    }

    private static String csv(String s) {
        if (s == null) return "";
        boolean needsQuotes = s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r");
        String esc = s.replace("\"", "\"\"");
        return needsQuotes ? "\"" + esc + "\"" : esc;
    }

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

    private static String js(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String normalizeFlags(String flags) {
        if (flags == null) return null;     // native
        if (flags.isBlank()) return "";     // baseline "(none)" lieber im Printer darstellen
        return flags;
    }
}
