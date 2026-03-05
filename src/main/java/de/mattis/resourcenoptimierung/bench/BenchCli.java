package de.mattis.resourcenoptimierung.bench;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * CLI-Einstiegspunkt fuer das Benchmarking.
 *
 * Ablauf:
 * - Szenario waehlen (json, alloc, ebics-upload, ebics-download) ueber CLI oder interaktiv
 * - Workload-Groesse n bestimmen
 * - Messprofil konfigurieren (Warmup, Messung, Concurrency, Sleep)
 * - BenchmarkPlan laden
 * - Alle Konfigurationen ausfuehren
 * - Ergebnisse auf der Konsole ausgeben und als CSV/JSON exportieren
 *
 * CLI-Argumente:
 * - --scenario:                json|alloc|ebics-upload|ebics-download (optional, sonst interaktiv)
 * - --n:                       Workload-Groesse (optional)
 * - --warmupRequests:          Anzahl Warmup-Requests (default: 20)
 * - --measureRequests:         Anzahl Mess-Requests (default: 100)
 * - --concurrency:             Parallele Requests (default: 1)
 * - --sleepBetweenRequestsMs:  Pause zwischen Requests in ms (default: 0)
 *
 * Fuer automatisierte Runs sollte --scenario gesetzt werden.
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
     * - args: Kommandozeilenargumente (--scenario, --n, --warmupRequests, etc.)
     *
     * @param args CLI-Argumente
     * @throws Exception wenn Docker-Aufrufe, Requests oder Exporte fehlschlagen
     */
    public static void main(String[] args) throws Exception {
        BenchmarkScenario scenario = resolveScenario(args);
        int workloadN = resolveWorkloadN(args, scenario);
        MeasurementProfile profile = resolveProfile(args);

        System.out.println("Benchmark configuration:");
        System.out.println("  Scenario:  " + scenario);
        System.out.println("  Workload:  n=" + workloadN);
        System.out.println("  Profile:   " + profile);
        System.out.println();

        BenchmarkPlan plan = BenchmarkPlan.defaultPlan();
        BenchmarkRunner runner = new BenchmarkRunner(plan, scenario, workloadN, profile);

        List<RunResult> results = runner.runAll();

        ConsoleSummaryPrinter.print(results);

        Path outDir = Path.of("bench-results");
        Files.createDirectories(outDir);
        String stamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now()).replace(":", "-");

        ResultExporters.writeJson(results, outDir.resolve("results-" + stamp + ".json"));
        ResultExporters.writeCsv(results, outDir.resolve("results-" + stamp + ".csv"));
    }

    /**
     * Baut das Messprofil aus CLI-Argumenten oder Defaults.
     *
     * @param args CLI-Argumente
     * @return konfiguriertes MeasurementProfile
     */
    static MeasurementProfile resolveProfile(String[] args) {
        int warmup = resolveIntArg(args, "--warmupRequests", 20);
        int measure = resolveIntArg(args, "--measureRequests", 100);
        int concurrency = resolveIntArg(args, "--concurrency", 1);
        long sleepMs = resolveLongArg(args, "--sleepBetweenRequestsMs", 0);

        return new MeasurementProfile(warmup, measure, concurrency, sleepMs);
    }

    /**
     * Bestimmt das Benchmark-Szenario.
     * Wenn --scenario gesetzt ist, wird es daraus gelesen, sonst per Dialog abgefragt.
     *
     * @param args CLI-Argumente
     * @return Szenario
     * @throws Exception wenn die interaktive Eingabe fehlschlaegt
     */
    static BenchmarkScenario resolveScenario(String[] args) throws Exception {
        String raw = findArgValue(args, "--scenario");
        if (raw != null) {
            return parseScenario(raw);
        }

        // Interaktiv fragen (nur wenn --scenario nicht gesetzt)
        return promptScenario();
    }


    /**
     * Interaktiver Dialog zur Szenario-Auswahl.
     * Default ist /json, wenn Enter gedrueckt wird oder die Eingabe ungueltig ist.
     *
     * @return Szenario
     * @throws Exception wenn stdin nicht gelesen werden kann
     */
    private static BenchmarkScenario promptScenario() throws Exception {
        BufferedReader br = new BufferedReader(new InputStreamReader(System.in));

        System.out.println();
        System.out.println("Choose workload scenario:");
        System.out.println("  1) /json       (payload-heavy, default n=200000)");
        System.out.println("  2) /alloc      (alloc-heavy,  default n=10000000)");
        System.out.println("  3) /ebics/upload  (EBICS upload, default n=10)");
        System.out.println("  4) /ebics/download (EBICS download, default n=10)");
        System.out.print("Enter 1-4 (default: 1): ");

        String line = br.readLine();
        if (line == null || line.isBlank() || line.trim().equals("1")) {
            return BenchmarkScenario.PAYLOAD_HEAVY_JSON;
        }
        if (line.trim().equals("2")) {
            return BenchmarkScenario.ALLOC_HEAVY_OK;
        }
        if (line.trim().equals("3")) {
            return BenchmarkScenario.EBICS_UPLOAD;
        }
        if (line.trim().equals("4")) {
            return BenchmarkScenario.EBICS_DOWNLOAD;
        }

        System.out.println("Invalid input, using default: /json");
        return BenchmarkScenario.PAYLOAD_HEAVY_JSON;
    }

    /**
     * Bestimmt den Workload-Parameter n.
     * Wenn --n gesetzt ist, wird der Wert verwendet, sonst ein Default pro Szenario.
     *
     * @param args CLI-Argumente
     * @param scenario Szenario fuer die Default-Wahl
     * @return Workload-Groesse n
     */
    static int resolveWorkloadN(String[] args, BenchmarkScenario scenario) {
        String raw = findArgValue(args, "--n");
        if (raw != null) return Integer.parseInt(raw);

        // Defaults pro Scenario
        return switch (scenario) {
            case PAYLOAD_HEAVY_JSON -> 200_000;
            case ALLOC_HEAVY_OK -> 10_000_000;
            case EBICS_UPLOAD, EBICS_DOWNLOAD -> 10;
        };
    }

    /**
     * Parst das Szenario aus dem CLI-Wert.
     *
     * @param raw CLI-Wert (z.B. "json" oder "alloc")
     * @return Szenario
     */
    static BenchmarkScenario parseScenario(String raw) {
        return switch (raw.toLowerCase()) {
            case "payload", "payload-heavy", "payload-heavy-json", "json", "/json" -> BenchmarkScenario.PAYLOAD_HEAVY_JSON;
            case "alloc", "alloc-heavy", "alloc-heavy-ok", "ok", "/alloc" -> BenchmarkScenario.ALLOC_HEAVY_OK;
            case "ebics", "ebics-upload", "upload", "/ebics/upload" -> BenchmarkScenario.EBICS_UPLOAD;
            case "ebics-download", "download", "/ebics/download" -> BenchmarkScenario.EBICS_DOWNLOAD;
            default -> throw new IllegalArgumentException("Unknown --scenario: " + raw + " (use: json|alloc|ebics-upload|ebics-download)");
        };
    }

    /**
     * Liest den Wert eines CLI-Arguments.
     * Unterstuetzt beide Formen:
     * - --key value
     * - --key=value
     *
     * @param args CLI-Argumente
     * @param key Argumentname (z.B. "--scenario" oder "--n")
     * @return Wert oder null, wenn nicht vorhanden
     */
    static String findArgValue(String[] args, String key) {
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals(key) && i + 1 < args.length) return args[i + 1];
            if (args[i].startsWith(key + "=")) return args[i].substring((key + "=").length());
        }
        return null;
    }

    /**
     * Liest einen int-Wert aus CLI-Argumenten mit Fallback auf Default.
     *
     * @param args CLI-Argumente
     * @param key Argumentname
     * @param defaultValue Standardwert
     * @return geparster Wert oder Default
     */
    static int resolveIntArg(String[] args, String key, int defaultValue) {
        String raw = findArgValue(args, key);
        if (raw == null) return defaultValue;
        return Integer.parseInt(raw);
    }

    /**
     * Liest einen long-Wert aus CLI-Argumenten mit Fallback auf Default.
     *
     * @param args CLI-Argumente
     * @param key Argumentname
     * @param defaultValue Standardwert
     * @return geparster Wert oder Default
     */
    static long resolveLongArg(String[] args, String key, long defaultValue) {
        String raw = findArgValue(args, key);
        if (raw == null) return defaultValue;
        return Long.parseLong(raw);
    }
}
