package de.mattis.resourcenoptimierung.bench;

/**
 * Beschreibt das Benchmark-Szenario.
 *
 * Ein BenchmarkScenario legt fest,
 * welche Art von Workload während eines Runs ausgeführt wird.
 *
 * Das Szenario bestimmt:
 * - welcher HTTP-Endpunkt aufgerufen wird,
 * - welche Art von Last erzeugt wird,
 * - wie die Messergebnisse zu interpretieren sind.
 *
 * Das Szenario wird beim Start des Benchmarks ausgewählt
 * und gilt für alle Konfigurationen eines Durchlaufs.
 */
public enum BenchmarkScenario {
    PAYLOAD_HEAVY_JSON,
    ALLOC_HEAVY_OK,
    EBICS_UPLOAD
}
