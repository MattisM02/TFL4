package de.mattis.resourcenoptimierung.bench;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;

/**
 * Fuehrt einen vollstaendigen Benchmark-Durchlauf aus.
 *
 * Der BenchmarkRunner verbindet:
 * - einen BenchmarkPlan (welche Konfigurationen),
 * - ein BenchmarkScenario (welcher Workload),
 * - eine Workload-Groesse n,
 * - ein MeasurementProfile (Warmup/Messung/Concurrency/Sleep),
 * - eine Anzahl Wiederholungen (repetitions).
 *
 * Fuer jede Wiederholung wird die Reihenfolge der Konfigurationen
 * zufaellig permutiert, um Reihenfolge-Effekte zu minimieren.
 *
 * Die Ergebnisse aller Runs (repetitions * configs) werden
 * gesammelt und als flache Liste zurueckgegeben.
 *
 * Der Runner selbst startet keine Docker-Container
 * und fuehrt keine Messungen durch.
 * Diese Aufgaben liegen vollstaendig bei SingleRun.
 */
public class BenchmarkRunner {

    /**
     * Benchmark-Plan mit allen auszufuehrenden Konfigurationen.
     */
    private final BenchmarkPlan plan;

    /**
     * Ausgewaehltes Workload-Szenario.
     */
    private final BenchmarkScenario scenario;

    /**
     * Workload-Parameter n, der an den Endpoint uebergeben wird.
     */
    private final int n;

    /**
     * Messprofil mit Warmup/Messung/Concurrency/Sleep-Konfiguration.
     */
    private final MeasurementProfile profile;

    /**
     * Anzahl der Wiederholungen pro Konfiguration.
     */
    private final int repetitions;

    /**
     * Erstellt einen neuen BenchmarkRunner mit einer Wiederholung (Kompatibilitaet).
     *
     * @param plan Benchmark-Plan
     * @param scenario Workload-Szenario
     * @param n Workload-Groesse
     * @param profile Messprofil
     */
    public BenchmarkRunner(BenchmarkPlan plan, BenchmarkScenario scenario, int n, MeasurementProfile profile) {
        this(plan, scenario, n, profile, 1);
    }

    /**
     * Erstellt einen neuen BenchmarkRunner mit konfigurierbarer Wiederholungszahl.
     *
     * @param plan Benchmark-Plan
     * @param scenario Workload-Szenario
     * @param n Workload-Groesse
     * @param profile Messprofil
     * @param repetitions Anzahl Wiederholungen (mindestens 1)
     */
    public BenchmarkRunner(BenchmarkPlan plan, BenchmarkScenario scenario, int n,
                           MeasurementProfile profile, int repetitions) {
        this.plan = plan;
        this.scenario = scenario;
        this.n = n;
        this.profile = profile;
        this.repetitions = Math.max(1, repetitions);
    }

    /**
     * Fuehrt alle Konfigurationen des Benchmark-Plans aus,
     * wiederholt ueber die konfigurierte Anzahl Runden.
     *
     * Pro Runde wird die Reihenfolge der Konfigurationen
     * zufaellig permutiert, um systematische Reihenfolge-Effekte
     * (z.B. Cache-Waerme, Thermal Throttling) zu reduzieren.
     *
     * Einzelne fehlgeschlagene Configs werden uebersprungen;
     * die restlichen Runs laufen weiter.
     *
     * @return Ergebnisse aller erfolgreichen Runs, flache Liste
     * @throws Exception wenn ein nicht-abfangbarer Fehler auftritt
     */
    public List<RunResult> runAll() throws Exception {
        return runAll(r -> {});
    }

    /**
     * Fuehrt alle Konfigurationen aus und ruft fuer jedes Ergebnis den Callback auf.
     *
     * Der Callback wird unmittelbar nach jedem erfolgreichen Run aufgerufen.
     * Typischer Einsatz: inkrementelle CSV-Sicherung, damit Teilergebnisse
     * auch bei spaeteren Fehlern nicht verloren gehen.
     *
     * Einzelne fehlgeschlagene Configs werden uebersprungen;
     * die restlichen Runs laufen weiter.
     *
     * @param onResult Callback fuer jedes erfolgreich abgeschlossene Ergebnis
     * @return Ergebnisse aller erfolgreichen Runs, flache Liste
     * @throws Exception wenn ein nicht-abfangbarer Fehler auftritt
     */
    public List<RunResult> runAll(Consumer<RunResult> onResult) throws Exception {
        List<RunResult> results = new ArrayList<>();
        Random rng = new Random(42);
        int totalRuns = repetitions * plan.configs.size();
        int failures = 0;
        long runAllStartNanos = System.nanoTime();

        for (int rep = 1; rep <= repetitions; rep++) {
            if (repetitions > 1) {
                System.out.println();
                System.out.println("=== Repetition " + rep + " / " + repetitions + " ===");
            }

            // Configs dieser Runde zufaellig permutieren
            List<BenchmarkConfig> shuffled = new ArrayList<>(plan.configs);
            Collections.shuffle(shuffled, rng);

            for (BenchmarkConfig cfg : shuffled) {
                try {
                    RunResult result = new SingleRun(cfg, scenario, n, profile,
                            BenchDefaults.DEFAULT_HOST_PORT, BenchDefaults.READINESS_TIMEOUT, rep).execute();
                    results.add(result);
                    onResult.accept(result);

                    // Live-ETA ausgeben
                    double elapsedSec = (System.nanoTime() - runAllStartNanos) / 1_000_000_000.0;
                    DurationEstimator.printLiveEta(results.size(), totalRuns, elapsedSec, result);
                } catch (Exception e) {
                    failures++;
                    System.err.println();
                    System.err.println("[ERROR] Config '" + cfg.name() + "' rep " + rep
                            + " failed: " + formatException(e));
                    System.err.println("[ERROR] Skipping this config, continuing with next.");
                    e.printStackTrace(System.err);
                    System.err.println();
                }
            }
        }

        if (failures > 0) {
            System.err.println();
            System.err.println("WARNING: " + failures + " of " + totalRuns + " runs failed and were skipped.");
        }

        return results;
    }

    /**
     * Gibt die konfigurierte Anzahl Wiederholungen zurueck.
     *
     * @return Anzahl Wiederholungen
     */
    public int repetitions() {
        return repetitions;
    }

    /**
     * Formatiert eine Exception mit Klassenname und Cause-Chain fuer lesbare Log-Ausgabe.
     * Vermeidet das Problem, dass {@code e.getMessage()} bei vielen IOExceptions {@code null} liefert.
     *
     * @param e Exception
     * @return z.B. "java.io.IOException: HTTP/1.1 header parser received no bytes"
     *         oder "java.io.IOException (caused by java.net.ConnectException: Connection refused)"
     */
    static String formatException(Exception e) {
        StringBuilder sb = new StringBuilder();
        sb.append(e.getClass().getName());
        if (e.getMessage() != null) {
            sb.append(": ").append(e.getMessage());
        }
        Throwable cause = e.getCause();
        if (cause != null) {
            sb.append(" (caused by ").append(cause.getClass().getName());
            if (cause.getMessage() != null) {
                sb.append(": ").append(cause.getMessage());
            }
            sb.append(")");
        }
        return sb.toString();
    }
}
