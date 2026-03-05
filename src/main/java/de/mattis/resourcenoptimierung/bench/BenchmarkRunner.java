package de.mattis.resourcenoptimierung.bench;

import java.util.ArrayList;
import java.util.List;

/**
 * Fuehrt einen vollstaendigen Benchmark-Durchlauf aus.
 *
 * Der BenchmarkRunner verbindet:
 * - einen BenchmarkPlan (welche Konfigurationen),
 * - ein BenchmarkScenario (welcher Workload),
 * - eine Workload-Groesse n,
 * - ein MeasurementProfile (Warmup/Messung/Concurrency/Sleep).
 *
 * Fuer jede Konfiguration im Plan wird genau ein SingleRun
 * erzeugt und ausgefuehrt. Die Ergebnisse aller Runs werden
 * gesammelt und zurueckgegeben.
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
     * Erstellt einen neuen BenchmarkRunner.
     *
     * @param plan Benchmark-Plan
     * @param scenario Workload-Szenario
     * @param n Workload-Groesse
     * @param profile Messprofil
     */
    public BenchmarkRunner(BenchmarkPlan plan, BenchmarkScenario scenario, int n, MeasurementProfile profile) {
        this.plan = plan;
        this.scenario = scenario;
        this.n = n;
        this.profile = profile;
    }

    /**
     * Fuehrt alle Konfigurationen des Benchmark-Plans aus.
     *
     * Die Ausfuehrungsreihenfolge entspricht der Reihenfolge
     * der Konfigurationen im Plan.
     *
     * @return Ergebnisse aller Runs
     * @throws Exception wenn ein einzelner Run fehlschlaegt
     */
    public List<RunResult> runAll() throws Exception {
        List<RunResult> results = new ArrayList<>();
        for (BenchmarkConfig cfg : plan.configs) {
            SingleRun run = new SingleRun(cfg, scenario, n, profile);
            results.add(run.execute());
        }
        return results;
    }
}
