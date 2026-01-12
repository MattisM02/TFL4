package de.mattis.resourcenoptimierung.bench;

import java.util.ArrayList;
import java.util.List;

/**
 * Führt einen vollständigen Benchmark-Durchlauf aus.
 *
 * <p>Der {@code BenchmarkRunner} ist die zentrale Ablaufsteuerung
 * für das Benchmarking. Er kombiniert:</p>
 * <ul>
 *   <li>einen {@link BenchmarkPlan} (welche Konfigurationen getestet werden),</li>
 *   <li>ein {@link BenchmarkScenario} (welcher Workload genutzt wird),</li>
 *   <li>eine Workload-Größe {@code n} (z. B. Anzahl Objekte oder Allokationen).</li>
 * </ul>
 *
 * <p>Für jede {@link BenchmarkConfig} im Plan wird genau ein
 * {@link SingleRun} erzeugt und ausgeführt. Die Ergebnisse aller Runs
 * werden gesammelt und als Liste zurückgegeben.</p>
 *
 * <p>Der Runner selbst misst nichts und startet keine Docker-Container
 * direkt. Diese Aufgaben liegen vollständig beim {@link SingleRun}.</p>
 */
public class BenchmarkRunner {

    /**
     * Der Benchmark-Plan, der festlegt, welche Konfigurationen ausgeführt werden.
     */
    private final BenchmarkPlan plan;

    /**
     * Das ausgewählte Benchmark-Szenario (z. B. payload-heavy oder alloc-heavy).
     */
    private final BenchmarkScenario scenario;

    /**
     * Workload-Parameter {@code n}, der an den HTTP-Endpunkt übergeben wird
     * (z. B. {@code /json?n=200000} oder {@code /alloc?n=10000000}).
     */
    private final int n;

    /**
     * Erstellt einen neuen {@code BenchmarkRunner}.
     *
     * @param plan     Benchmark-Plan mit allen auszuführenden Konfigurationen
     * @param scenario ausgewähltes Workload-Szenario
     * @param n        Workload-Größe (Bedeutung abhängig vom Szenario)
     */
    public BenchmarkRunner(BenchmarkPlan plan, BenchmarkScenario scenario, int n) {
        this.plan = plan;
        this.scenario = scenario;
        this.n = n;
    }

    /**
     * Führt alle Benchmark-Konfigurationen des Plans aus.
     *
     * <p>Ablauf:</p>
     * <ol>
     *   <li>Iteriert über alle {@link BenchmarkConfig}-Einträge im {@link BenchmarkPlan}.</li>
     *   <li>Erzeugt für jede Konfiguration einen {@link SingleRun}.</li>
     *   <li>Führt den Run aus und sammelt das {@link RunResult}.</li>
     * </ol>
     *
     * <p>Die Reihenfolge der Ergebnisse entspricht der Reihenfolge
     * der Konfigurationen im Plan.</p>
     *
     * @return Liste aller {@link RunResult}-Objekte (ein Eintrag pro Konfiguration)
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
