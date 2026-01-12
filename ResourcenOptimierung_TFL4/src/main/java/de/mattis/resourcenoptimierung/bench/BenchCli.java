package de.mattis.resourcenoptimierung.bench;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class BenchCli {

    public static void main(String[] args) throws Exception {
        BenchmarkScenario scenario = resolveScenario(args);
        int workloadN = resolveWorkloadN(args, scenario);

        BenchmarkPlan plan = BenchmarkPlan.defaultPlan();
        BenchmarkRunner runner = new BenchmarkRunner(plan, scenario, workloadN);

        List<RunResult> results = runner.runAll();

        ConsoleSummaryPrinter.print(results);

        Path outDir = Path.of("bench-results");
        Files.createDirectories(outDir);
        String stamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now()).replace(":", "-");

        ResultExporters.writeJson(results, outDir.resolve("results-" + stamp + ".json"));
        ResultExporters.writeCsv(results, outDir.resolve("results-" + stamp + ".csv"));
    }

    private static BenchmarkScenario resolveScenario(String[] args) throws Exception {
        String raw = findArgValue(args, "--scenario");
        if (raw != null) {
            return parseScenario(raw);
        }

        // Interaktiv fragen (nur wenn --scenario nicht gesetzt)
        return promptScenario();
    }

    private static BenchmarkScenario promptScenario() throws Exception {
        BufferedReader br = new BufferedReader(new InputStreamReader(System.in));

        System.out.println();
        System.out.println("Choose workload scenario:");
        System.out.println("  1) /json  (payload-heavy, default n=200000)");
        System.out.println("  2) /alloc (alloc-heavy,  default n=10000000)");
        System.out.print("Enter 1 or 2 (default: 1): ");

        String line = br.readLine();
        if (line == null || line.isBlank() || line.trim().equals("1")) {
            return BenchmarkScenario.PAYLOAD_HEAVY_JSON;
        }
        if (line.trim().equals("2")) {
            return BenchmarkScenario.ALLOC_HEAVY_OK;
        }

        System.out.println("Invalid input, using default: /json");
        return BenchmarkScenario.PAYLOAD_HEAVY_JSON;
    }

    private static int resolveWorkloadN(String[] args, BenchmarkScenario scenario) {
        String raw = findArgValue(args, "--n");
        if (raw != null) return Integer.parseInt(raw);

        // Defaults pro Scenario
        return (scenario == BenchmarkScenario.PAYLOAD_HEAVY_JSON) ? 200_000 : 10_000_000;
    }

    private static BenchmarkScenario parseScenario(String raw) {
        return switch (raw.toLowerCase()) {
            case "payload", "payload-heavy", "payload-heavy-json", "json", "/json" -> BenchmarkScenario.PAYLOAD_HEAVY_JSON;
            case "alloc", "alloc-heavy", "alloc-heavy-ok", "ok", "/alloc" -> BenchmarkScenario.ALLOC_HEAVY_OK;
            default -> throw new IllegalArgumentException("Unknown --scenario: " + raw + " (use: json|alloc)");
        };
    }

    private static String findArgValue(String[] args, String key) {
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals(key) && i + 1 < args.length) return args[i + 1];
            if (args[i].startsWith(key + "=")) return args[i].substring((key + "=").length());
        }
        return null;
    }
}
