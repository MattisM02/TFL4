package de.mattis.resourcenoptimierung.bench;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Command-Line Entry-Point für das Benchmarking.
 *
 * <p>Diese Klasse ist die "Hauptsteuerung" für einen Benchmark-Durchlauf:</p>
 * <ol>
 *   <li>Szenario auswählen (payload-heavy / alloc-heavy) über CLI-Argumente oder interaktiven Dialog</li>
 *   <li>Workload-Größe {@code n} bestimmen (CLI oder Default pro Szenario)</li>
 *   <li>Benchmark-Plan laden (welche JVM-/Docker-Konfigurationen getestet werden)</li>
 *   <li>Alle Runs ausführen lassen (über {@link BenchmarkRunner})</li>
 *   <li>Ergebnisse ausgeben (Konsole) und exportieren (JSON + CSV)</li>
 * </ol>
 *
 * <p><b>CLI-Argumente</b></p>
 * <ul>
 *   <li><b>--scenario</b> (optional): Workload-Auswahl.
 *       Erlaubte Werte: {@code json|payload|alloc}</li>
 *   <li><b>--n</b> (optional): Workload-Größe.
 *       Bedeutung: Parameter {@code n} des Endpoints (z. B. {@code /json?n=200000}).</li>
 * </ul>
 *
 * <p>Wenn {@code --scenario} nicht gesetzt ist, startet ein kleiner Konsolen-Dialog, der zwischen
 * {@code /json} und {@code /alloc} wählen lässt. Das macht lokale Tests komfortabel. Für automatisierte
 * Runs/CI setzt man {@code --scenario}, um den Dialog zu vermeiden.</p>
 *
 * <p><b>Output</b></p>
 * <ul>
 *   <li>Console summary über {@link ConsoleSummaryPrinter}</li>
 *   <li>Exports in das Verzeichnis {@code bench-results/}:
 *       <ul>
 *         <li>JSON: vollständige Ergebnisse inkl. Roh-Latenzen</li>
 *         <li>CSV: kompakte Kennzahlen pro Run</li>
 *       </ul>
 *   </li>
 * </ul>
 */
public class BenchCli {

    /**
     * Startet einen Benchmark-Durchlauf.
     *
     * <p>Ablauf:</p>
     * <ol>
     *   <li>Szenario ermitteln: {@link #resolveScenario(String[])}</li>
     *   <li>Workload-Größe ermitteln: {@link #resolveWorkloadN(String[], BenchmarkScenario)}</li>
     *   <li>Default-Plan laden: {@link BenchmarkPlan#defaultPlan()}</li>
     *   <li>Runs ausführen: {@link BenchmarkRunner#runAll()}</li>
     *   <li>Konsole drucken: {@link ConsoleSummaryPrinter#print(List)}</li>
     *   <li>Export schreiben: {@link ResultExporters#writeJson(List, Path)} und {@link ResultExporters#writeCsv(List, Path)}</li>
     * </ol>
     *
     * @param args Kommandozeilenargumente (siehe Klassendoku)
     * @throws Exception Wenn ein Run fehlschlägt oder Export/IO fehlschlägt
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
     * Ermittelt das Benchmark-Szenario.
     *
     * <p>Priorität:</p>
     * <ol>
     *   <li>Wenn {@code --scenario} gesetzt ist: parse und zurückgeben.</li>
     *   <li>Sonst: interaktiv per Dialog abfragen (lokaler Komfort).</li>
     * </ol>
     *
     * @param args CLI-Argumente
     * @return gewähltes Szenario
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
     * Öffnet einen einfachen Konsolen-Dialog, um das Szenario auszuwählen.
     *
     * <p>Default: Wenn der Nutzer Enter drückt oder ungültig eingibt, wird {@code /json} gewählt.</p>
     *
     * @return das ausgewählte {@link BenchmarkScenario}
     * @throws Exception bei Problemen beim Lesen von stdin
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
     * Ermittelt die Workload-Größe {@code n}.
     *
     * <p>Wenn {@code --n} gesetzt ist, wird dieser Wert verwendet. Sonst wird ein Default pro Szenario gesetzt:</p>
     * <ul>
     *   <li>{@code PAYLOAD_HEAVY_JSON}: 200000</li>
     *   <li>{@code ALLOC_HEAVY_OK}: 10000000</li>
     * </ul>
     *
     * <p>Hinweis: Dieser Wert wird später in der Benchmark-URL genutzt, z. B. {@code /json?n=200000}.</p>
     *
     * @param args CLI-Argumente
     * @param scenario zuvor ausgewähltes Szenario (entscheidet über den Default)
     * @return Workload-Parameter {@code n}
     */
    private static int resolveWorkloadN(String[] args, BenchmarkScenario scenario) {
        String raw = findArgValue(args, "--n");
        if (raw != null) return Integer.parseInt(raw);

        // Defaults pro Scenario
        return (scenario == BenchmarkScenario.PAYLOAD_HEAVY_JSON) ? 200_000 : 10_000_000;
    }

    /**
     * Parst das Szenario aus einem String.
     *
     * <p>Erlaubt mehrere Synonyme, damit CLI-Nutzung bequem ist:</p>
     * <ul>
     *   <li>{@code json}, {@code payload}, {@code payload-heavy}, ...</li>
     *   <li>{@code alloc}, {@code alloc-heavy}, ...</li>
     * </ul>
     *
     * @param raw roher CLI-Wert (z. B. "json" oder "alloc")
     * @return entsprechendes {@link BenchmarkScenario}
     * @throws IllegalArgumentException wenn der Wert unbekannt ist
     */
    private static BenchmarkScenario parseScenario(String raw) {
        return switch (raw.toLowerCase()) {
            case "payload", "payload-heavy", "payload-heavy-json", "json", "/json" -> BenchmarkScenario.PAYLOAD_HEAVY_JSON;
            case "alloc", "alloc-heavy", "alloc-heavy-ok", "ok", "/alloc" -> BenchmarkScenario.ALLOC_HEAVY_OK;
            default -> throw new IllegalArgumentException("Unknown --scenario: " + raw + " (use: json|alloc)");
        };
    }

    /**
     * Sucht den Wert eines CLI-Arguments.
     *
     * <p>Unterstützte Formen:</p>
     * <ul>
     *   <li>{@code --key value}</li>
     *   <li>{@code --key=value}</li>
     * </ul>
     *
     * @param args CLI-Argumente
     * @param key  Argument-Name, z. B. {@code "--scenario"} oder {@code "--n"}
     * @return Wert als String, oder {@code null} wenn nicht vorhanden
     */
    private static String findArgValue(String[] args, String key) {
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals(key) && i + 1 < args.length) return args[i + 1];
            if (args[i].startsWith(key + "=")) return args[i].substring((key + "=").length());
        }
        return null;
    }
}
