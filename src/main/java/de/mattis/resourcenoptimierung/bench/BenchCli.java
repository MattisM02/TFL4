package de.mattis.resourcenoptimierung.bench;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;

/**
 * CLI-Einstiegspunkt fuer das Benchmarking.
 *
 * Ablauf:
 * - Szenario waehlen (json, alloc, ebics-upload) ueber CLI oder interaktiv
 * - Workload-Groesse n bestimmen
 * - Messprofil konfigurieren (Warmup, Messung, Concurrency, Sleep)
 * - BenchmarkPlan laden
 * - Alle Konfigurationen ausfuehren
 * - Ergebnisse auf der Konsole ausgeben und als CSV/JSON exportieren
 *
 * CLI-Argumente:
 * - --scenario:                json|alloc|ebics-upload (optional, sonst interaktiv)
 * - --n:                       Workload-Groesse (optional)
 * - --warmupRequests:          Anzahl Warmup-Requests (default: 200)
 * - --measureRequests:         Anzahl Mess-Requests (default: 500)
 * - --concurrency:             Parallele Requests (default: 1)
 * - --sleepBetweenRequestsMs:  Pause zwischen Requests in ms (default: 0)
 * - --repetitions:             Anzahl Wiederholungen pro Konfiguration (default: 3)
 * - --jvmArgs:                 JVM-Flags fuer einen einzelnen Run (z.B. "--jvmArgs \"-XX:+UseZGC -Xmx1g\"")
 * - --configName:              Name fuer die CLI-Konfiguration (default: "cli-custom")
 * - --dockerImage:             Docker-Image fuer den CLI-Run (default: "tfl4-ek-bench:jvm")
 * - --skipTravicLink:          TravicLink docker-compose NICHT starten (externer Server erwartet)
     * - --profiles:                Nur Laufzeitprofile statt vollstaendigem Plan (11 Profile P01-P04+P06-P12 statt 31 Configs)
 * - --rebuild:                 Erzwingt Neuaufbau von Maven-JAR und Docker-Images (auch wenn vorhanden)
 * - --merge-excel:             Alle CSVs aus bench-results/ in ein Excel zusammenfuehren (kein Benchmark)
 * - --quick:                   Schnelldurchlauf (10 Warmup, 30 Mess-Requests, 1 Wiederholung).
 *                              Explizite CLI-Werte (z.B. --measureRequests 50) ueberschreiben die Quick-Defaults.
 * - --smoke:                   Ultra-leichter Smoke-Test (3 Warmup, 5 Mess-Requests, 1 Wiederholung).
 *                              Fuer EBICS: n=3. Dient nur zur Validierung der Pipeline (Docker, Endpunkte, Export).
 *                              --smoke hat Vorrang vor --quick. Explizite CLI-Werte ueberschreiben Smoke-Defaults.
 *
 * Standalone Excel-Merge:
 *   java -cp ... BenchCli --merge-excel
 *   Liest alle CSVs aus bench-results/ und erzeugt bench-results/benchmark-vergleich.xlsx
 *
 * Fuer automatisierte Runs sollte --scenario gesetzt werden.
 *
 * Output:
 * - Konsolen-Zusammenfassung
 * - Dateien unter bench-results/ (CSV und JSON)
 */
public class BenchCli {

    /** Standard-Anzahl Wiederholungen pro Konfiguration, wenn weder --smoke noch --quick gesetzt. */
    private static final int DEFAULT_REPETITIONS = 3;

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
        // Standalone: --merge-excel erzeugt ein Excel aus allen vorhandenen CSVs
        if (hasFlag(args, "--merge-excel")) {
            Path outDir = Path.of(BenchDefaults.OUTPUT_DIR);
            Path excelOut = outDir.resolve(BenchDefaults.EXCEL_FILENAME);
            ExcelExporter.mergeFromCsvDirectory(outDir, excelOut);
            return;
        }

        BenchmarkScenario scenario = resolveScenario(args);
        int workloadN = resolveWorkloadN(args, scenario);
        MeasurementProfile profile = resolveProfile(args);
        BenchmarkPlan plan = resolvePlan(args, scenario);
        int defaultReps = (hasFlag(args, "--smoke") || hasFlag(args, "--quick")) ? 1 : DEFAULT_REPETITIONS;
        int repetitions = resolveIntArg(args, "--repetitions", defaultReps);

        boolean rebuild = hasFlag(args, "--rebuild");

        System.out.println("Benchmark configuration:");
        System.out.println("  Scenario:     " + scenario);
        System.out.println("  Workload:     n=" + workloadN);
        System.out.println("  Profile:      " + profile);
        System.out.println("  Repetitions:  " + repetitions);
        System.out.println("  Configs:      " + plan.configs.size()
                + " (" + plan.configs.stream().map(BenchmarkConfig::name).toList() + ")");
        if (rebuild) {
            System.out.println("  Rebuild:      yes (force rebuild JAR + Docker images)");
        }
        System.out.println();

        // Docker-Images pruefen und bei Bedarf automatisch bauen
        DockerImageBuilder.ensureImagesExist(plan, rebuild);

        // EBICS: TravicLink-Bankserver automatisch starten (sofern nicht --skipTravicLink)
        TravicLinkManager travicLink = null;
        if (scenario.isEbics() && !hasFlag(args, "--skipTravicLink")) {
            travicLink = new TravicLinkManager();
            travicLink.start();
        }

        try {
            BenchmarkRunner runner = new BenchmarkRunner(plan, scenario, workloadN, profile, repetitions);

            // Inkrementelle CSV-Sicherung: nach jedem erfolgreichen Run wird das Ergebnis
            // sofort an eine Partial-CSV angehaengt, damit Teilergebnisse bei spaeteren
            // Fehlern (OOM-Kill, Verbindungsabbruch, etc.) nicht verloren gehen.
            Path outDir = Path.of(BenchDefaults.OUTPUT_DIR);
            Files.createDirectories(outDir);
            String stamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now()).replace(":", "-");
            Path partialCsv = outDir.resolve("results-" + stamp + "-partial.csv");

            List<RunResult> results = runner.runAll(result -> {
                try {
                    ResultExporters.appendCsvRow(partialCsv, result);
                } catch (Exception e) {
                    System.err.println("[WARN] Incremental CSV save failed: " + e.getMessage());
                }
            });

            ConsoleSummaryPrinter.print(results);

            ResultExporters.writeJson(results, outDir.resolve("results-" + stamp + ".json"));
            ResultExporters.writeCsv(results, outDir.resolve("results-" + stamp + ".csv"));
            ExcelExporter.writeExcel(results, outDir.resolve("results-" + stamp + ".xlsx"));

            // Automatisch das Vergleichs-Excel aktualisieren (alle CSVs zusammen)
            ExcelExporter.mergeFromCsvDirectory(outDir, outDir.resolve(BenchDefaults.EXCEL_FILENAME));

            // Partial-CSV aufraemen — die vollstaendige CSV existiert jetzt
            try {
                Files.deleteIfExists(partialCsv);
            } catch (Exception ignored) {}
        } finally {
            // EBICS: TravicLink-Bankserver stoppen
            if (travicLink != null) {
                travicLink.stop();
            }
        }
    }

    /**
     * Baut das Messprofil aus CLI-Argumenten oder Defaults.
     * Bei --smoke werden minimale Defaults verwendet (3/5 statt 200/500) — reiner Pipeline-Test.
     * Bei --quick werden reduzierte Defaults verwendet (10/30 statt 200/500).
     * --smoke hat Vorrang vor --quick, falls beide angegeben sind.
     * Explizite CLI-Werte ueberschreiben die jeweiligen Defaults.
     *
     * @param args CLI-Argumente
     * @return konfiguriertes MeasurementProfile
     */
    static MeasurementProfile resolveProfile(String[] args) {
        boolean smoke = hasFlag(args, "--smoke");
        boolean quick = hasFlag(args, "--quick");
        MeasurementProfile base = smoke ? MeasurementProfile.smokeDefaults()
                : quick ? MeasurementProfile.quickDefaults()
                : MeasurementProfile.defaults();

        int warmup = resolveIntArg(args, "--warmupRequests", base.warmupRequests());
        int measure = resolveIntArg(args, "--measureRequests", base.measureRequests());
        int concurrency = resolveIntArg(args, "--concurrency", base.concurrency());
        long sleepMs = resolveLongArg(args, "--sleepBetweenRequestsMs", base.sleepBetweenRequestsMs());

        return new MeasurementProfile(warmup, measure, concurrency, sleepMs);
    }

    /**
     * Bestimmt den Benchmark-Plan.
     * Wenn --jvmArgs gesetzt ist, wird ein Plan mit einer einzelnen Konfiguration erzeugt.
     * Sonst wird der vollstaendige Plan (31 Konfigurationen) verwendet.
     * Mit --profiles werden nur die Laufzeitprofile (11 Profile P01-P04+P06-P12) verwendet.
     *
     * Fuer EBICS-Szenarien werden die Docker-Images automatisch auf die EK-Varianten
     * umgestellt (Suffix-Konvention: Tag + "-ek"), sofern nicht per --dockerImage
     * explizit ueberschrieben.
     *
     * @param args CLI-Argumente
     * @param scenario Szenario (fuer Image-Auswahl)
     * @return Benchmark-Plan
     */
    static BenchmarkPlan resolvePlan(String[] args, BenchmarkScenario scenario) {
        boolean ebics = scenario.isEbics();
        String defaultImage = ebics ? BenchDefaults.IMAGE_JVM_EK : BenchDefaults.IMAGE_JVM;

        String jvmArgsRaw = findArgValue(args, "--jvmArgs");
        if (jvmArgsRaw == null) {
            // Entscheidung: nur Profile (--profiles) oder vollstaendiger Plan (default)
            BenchmarkPlan plan = hasFlag(args, "--profiles")
                    ? BenchmarkPlan.profilePlan()
                    : BenchmarkPlan.defaultPlan();
            if (ebics) {
                plan = plan.withEbicsImages();
            }
            return plan;
        }

        String configName = findArgValue(args, "--configName");
        if (configName == null) configName = "cli-custom";

        String dockerImage = findArgValue(args, "--dockerImage");
        if (dockerImage == null) dockerImage = defaultImage;

        List<String> jvmArgs = parseJvmArgs(jvmArgsRaw);
        BenchmarkConfig config = new BenchmarkConfig(configName, dockerImage, jvmArgs, RuntimeType.HOTSPOT,
                "CLI", "HotSpot");
        return new BenchmarkPlan(List.of(config));
    }

    /**
     * Parst einen JVM-Args-String in eine Liste einzelner Argumente.
     * Leerer String ergibt eine leere Liste (Baseline-Verhalten).
     *
     * @param raw JVM-Args als String, Space-separated (z.B. "-XX:+UseZGC -Xmx1g")
     * @return Liste der einzelnen JVM-Argumente
     */
    static List<String> parseJvmArgs(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return Arrays.stream(raw.trim().split("\\s+")).toList();
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
        System.out.println("  1) /json         (payload-heavy, default n=200000)");
        System.out.println("  2) /alloc        (alloc-heavy,  default n=10000000)");
        System.out.println("  3) /ebics/upload (EBICS upload, default n=10)");
        System.out.print("Enter 1-3 (default: 1): ");

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

        System.out.println("Invalid input, using default: /json");
        return BenchmarkScenario.PAYLOAD_HEAVY_JSON;
    }

    /**
     * Bestimmt den Workload-Parameter n.
     * Wenn --n gesetzt ist, wird der Wert verwendet, sonst ein Default pro Szenario.
     * Bei --smoke werden die Defaults reduziert (EBICS: 3 statt 10).
     *
     * @param args CLI-Argumente
     * @param scenario Szenario fuer die Default-Wahl
     * @return Workload-Groesse n
     */
    static int resolveWorkloadN(String[] args, BenchmarkScenario scenario) {
        String raw = findArgValue(args, "--n");
        if (raw != null) {
            try {
                return Integer.parseInt(raw);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid integer for --n: " + raw, e);
            }
        }

        boolean smoke = hasFlag(args, "--smoke");
        return smoke ? scenario.smokeN() : scenario.defaultN();
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
            default -> throw new IllegalArgumentException("Unknown --scenario: " + raw + " (use: json|alloc|ebics-upload)");
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
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid integer for " + key + ": " + raw, e);
        }
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
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid long for " + key + ": " + raw, e);
        }
    }

    /**
     * Prueft, ob ein Flag (ohne Wert) in den CLI-Argumenten vorkommt.
     *
     * @param args CLI-Argumente
     * @param flag Flag-Name (z.B. "--merge-excel")
     * @return true, wenn das Flag vorhanden ist
     */
    static boolean hasFlag(String[] args, String flag) {
        for (String arg : args) {
            if (arg.equals(flag)) return true;
        }
        return false;
    }
}
