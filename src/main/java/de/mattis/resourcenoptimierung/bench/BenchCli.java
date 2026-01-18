package de.mattis.resourcenoptimierung.bench;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * CLI-Einstiegspunkt für das Benchmarking.
 *
 * Ablauf:
 * - Szenario wählen (json oder alloc) über CLI oder interaktiv
 * - Workload-Größe n bestimmen
 * - BenchmarkPlan laden
 * - Alle Konfigurationen ausführen
 * - Ergebnisse auf der Konsole ausgeben und als CSV/JSON exportieren
 *
 * CLI-Argumente:
 * - --scenario: json oder alloc (optional)
 * - --n: Workload-Größe (optional)
 *
 * Wenn --scenario fehlt, wird ein einfacher Konsolen-Dialog geöffnet.
 * Für automatisierte Runs sollte --scenario gesetzt werden.
 *
 * Output:
 * - Konsolen-Zusammenfassung
 * - Dateien unter bench-results/ (CSV und JSON)
 */
public class BenchCli {

    /**
     * Startet den Benchmark-Durchlauf.
     *
     * Parameter:
     * - args: Kommandozeilenargumente (--scenario, --n)
     *
     * @param args CLI-Argumente
     * @throws Exception wenn Docker-Aufrufe, Requests oder Exporte fehlschlagen
     */
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

    /**
     * Bestimmt das Benchmark-Szenario.
     * Wenn --scenario gesetzt ist, wird es daraus gelesen, sonst per Dialog abgefragt.
     *
     * @param args CLI-Argumente
     * @return Szenario
     * @throws Exception wenn die interaktive Eingabe fehlschlägt
     */
    private static BenchmarkScenario resolveScenario(String[] args) throws Exception {
        String raw = findArgValue(args, "--scenario");
        if (raw != null) {
            return parseScenario(raw);
        }

        // Interaktiv fragen (nur wenn --scenario nicht gesetzt)
        return promptScenario();
    }


    /**
     * Interaktiver Dialog zur Szenario-Auswahl.
     * Default ist /json, wenn Enter gedrückt wird oder die Eingabe ungültig ist.
     *
     * @return Szenario
     * @throws Exception wenn stdin nicht gelesen werden kann
     */
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

    /**
     * Bestimmt den Workload-Parameter n.
     * Wenn --n gesetzt ist, wird der Wert verwendet, sonst ein Default pro Szenario.
     *
     * @param args CLI-Argumente
     * @param scenario Szenario für die Default-Wahl
     * @return Workload-Größe n
     */
    private static int resolveWorkloadN(String[] args, BenchmarkScenario scenario) {
        String raw = findArgValue(args, "--n");
        if (raw != null) return Integer.parseInt(raw);

        // Defaults pro Scenario
        return (scenario == BenchmarkScenario.PAYLOAD_HEAVY_JSON) ? 200_000 : 10_000_000;
    }

    /**
     * Parst das Szenario aus dem CLI-Wert.
     *
     * @param raw CLI-Wert (z.B. "json" oder "alloc")
     * @return Szenario
     */
    private static BenchmarkScenario parseScenario(String raw) {
        return switch (raw.toLowerCase()) {
            case "payload", "payload-heavy", "payload-heavy-json", "json", "/json" -> BenchmarkScenario.PAYLOAD_HEAVY_JSON;
            case "alloc", "alloc-heavy", "alloc-heavy-ok", "ok", "/alloc" -> BenchmarkScenario.ALLOC_HEAVY_OK;
            default -> throw new IllegalArgumentException("Unknown --scenario: " + raw + " (use: json|alloc)");
        };
    }

    /**
     * Liest den Wert eines CLI-Arguments.
     * Unterstützt beide Formen:
     * - --key value
     * - --key=value
     *
     * @param args CLI-Argumente
     * @param key Argumentname (z.B. "--scenario" oder "--n")
     * @return Wert oder null, wenn nicht vorhanden
     */
    private static String findArgValue(String[] args, String key) {
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals(key) && i + 1 < args.length) return args[i + 1];
            if (args[i].startsWith(key + "=")) return args[i].substring((key + "=").length());
        }
        return null;
    }
}
