package de.mattis.resourcenoptimierung.bench;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class BenchCli {

    public static void main(String[] args) throws Exception {
        BenchmarkPlan plan = BenchmarkPlan.defaultPlan();
        BenchmarkRunner runner = new BenchmarkRunner(plan);

        List<RunResult> results = runner.runAll();

        // 1) Console Summary
        ConsoleSummaryPrinter.print(results);

        // 2) Datei-Export
        Path outDir = Path.of("bench-results");
        Files.createDirectories(outDir);

        String stamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now()).replace(":", "-");
        Path jsonPath = outDir.resolve("results-" + stamp + ".json");
        Path csvPath  = outDir.resolve("results-" + stamp + ".csv");

        ResultExporters.writeJson(results, jsonPath);
        ResultExporters.writeCsv(results, csvPath);

        System.out.println();
        System.out.println("Export written:");
        System.out.println(" - JSON: " + jsonPath.toAbsolutePath());
        System.out.println(" - CSV:  " + csvPath.toAbsolutePath());

    }
}
