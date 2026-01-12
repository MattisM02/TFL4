package de.mattis.resourcenoptimierung.bench;

/**
 * Beschreibt das Benchmark-Szenario, also <b>welche Art von Workload</b>
 * während eines Runs ausgeführt wird.
 *
 * <p>Ein {@code BenchmarkScenario} bestimmt:</p>
 * <ul>
 *   <li>welcher HTTP-Endpunkt aufgerufen wird,</li>
 *   <li>welche Art von Last erzeugt wird (Payload vs. Allocation),</li>
 *   <li>wie die gemessenen Zeiten interpretiert werden müssen.</li>
 * </ul>
 *
 * <p>Das Szenario wird typischerweise beim Start des Benchmarks
 * (z. B. über {@code BenchCli}) ausgewählt und gilt dann für alle
 * Konfigurationen innerhalb eines Durchlaufs.</p>
 */
public enum BenchmarkScenario {
    PAYLOAD_HEAVY_JSON,
    ALLOC_HEAVY_OK
}
