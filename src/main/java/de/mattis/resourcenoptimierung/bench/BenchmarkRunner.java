package de.mattis.resourcenoptimierung.bench;

import java.util.ArrayList;
import java.util.List;

/**
 * Führt einen vollständigen Benchmark-Durchlauf aus.
 *
 * Der BenchmarkRunner verbindet:
 * - einen BenchmarkPlan (welche Konfigurationen),
 * - ein BenchmarkScenario (welcher Workload),
 * - eine Workload-Größe n.
 *
 * Für jede Konfiguration im Plan wird genau ein SingleRun
 * erzeugt und ausgeführt. Die Ergebnisse aller Runs werden
 * gesammelt und zurückgegeben.
 *
 * Der Runner selbst startet keine Docker-Container
 * und führt keine Messungen durch.
 * Diese Aufgaben liegen vollständig bei SingleRun.
 */
public class BenchmarkRunner {

    /**
     * Benchmark-Plan mit allen auszuführenden Konfigurationen.
     */
    private final BenchmarkPlan plan;

    /**
     * Ausgewähltes Workload-Szenario.
     */
    private final BenchmarkScenario scenario;

    /**
     * Workload-Parameter n, der an den Endpoint übergeben wird.
     */
    private final int n;

    /**
     * Erstellt einen neuen BenchmarkRunner.
     *
     * @param plan Benchmark-Plan
     * @param scenario Workload-Szenario
     * @param n Workload-Größe
     */
    public BenchmarkRunner(BenchmarkPlan plan, BenchmarkScenario scenario, int n) {
        this.plan = plan;
        this.scenario = scenario;
        this.n = n;
    }

    /**
     * Führt alle Konfigurationen des Benchmark-Plans aus.
     *
     * Die Ausführungsreihenfolge entspricht der Reihenfolge
     * der Konfigurationen im Plan.
     *
     * @return Ergebnisse aller Runs
     * @throws Exception wenn ein einzelner Run fehlschlägt
     */
    public List<RunResult> runAll() throws Exception {
        List<RunResult> results = new ArrayList<>();
        for (BenchmarkConfig cfg : plan.configs) {
            SingleRun run = new SingleRun(cfg, scenario, n);
            results.add(run.execute());
        }
        return results;
    }
}
