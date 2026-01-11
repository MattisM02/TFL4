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

            w.write("""
                    configName,dockerImage,readinessMs,firstJsonSeconds,latencyCount,latencyAvg,latencyP50,latencyP95,latencyP99
                    """.trim());
            w.newLine();

            for (RunResult r : results) {
                List<Double> lats = new ArrayList<>(r.jsonLatenciesSeconds());
                lats.sort(Double::compareTo);

                double avg = lats.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);

                w.write(csv(r.configName())); w.write(",");
                w.write(csv(r.dockerImage())); w.write(",");
                w.write(Long.toString(r.readinessMs())); w.write(",");
                w.write(Double.toString(r.firstJsonSeconds())); w.write(",");
                w.write(Integer.toString(lats.size())); w.write(",");
                w.write(Double.toString(avg)); w.write(",");
                w.write(Double.toString(percentile(lats, 0.50))); w.write(",");
                w.write(Double.toString(percentile(lats, 0.95))); w.write(",");
                w.write(Double.toString(percentile(lats, 0.99)));
                w.newLine();
            }
        }
    }

    private static double percentile(List<Double> sorted, double p) {
        if (sorted.isEmpty()) return Double.NaN;
        int idx = (int) Math.ceil(p * sorted.size()) - 1;
        idx = Math.max(0, Math.min(idx, sorted.size() - 1));
        return sorted.get(idx);
    }

    private static String csv(String s) {
        if (s == null) return "";
        boolean needsQuotes = s.contains(",") || s.contains("\"") || s.contains("\n");
        String esc = s.replace("\"", "\"\"");
        return needsQuotes ? "\"" + esc + "\"" : esc;
    }

    public static void writeJson(List<RunResult> results, Path path) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"results\":[");
        for (int i = 0; i < results.size(); i++) {
            RunResult r = results.get(i);
            if (i > 0) sb.append(",");
            sb.append("{");
            sb.append("\"configName\":").append(js(r.configName())).append(",");
            sb.append("\"dockerImage\":").append(js(r.dockerImage())).append(",");
            sb.append("\"readinessMs\":").append(r.readinessMs()).append(",");
            sb.append("\"firstJsonSeconds\":").append(r.firstJsonSeconds()).append(",");
            sb.append("\"jsonLatenciesSeconds\":").append(array(r.jsonLatenciesSeconds()));
            sb.append("}");
        }
        sb.append("]}");
        Files.writeString(path, sb.toString());
    }

    private static String array(List<Double> l) {
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

}
