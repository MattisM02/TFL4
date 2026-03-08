package de.mattis.resourcenoptimierung.bench;

/**
 * Beschreibt das Benchmark-Szenario.
 *
 * Ein BenchmarkScenario legt fest,
 * welche Art von Workload während eines Runs ausgeführt wird.
 *
 * Das Szenario bestimmt:
 * - welcher HTTP-Endpunkt aufgerufen wird ({@link #path}),
 * - welche Art von Last erzeugt wird,
 * - wie viele Wiederholungen als Standard gelten ({@link #defaultN}, {@link #smokeN}),
 * - ob ein EBICS-Bankserver benoetigt wird ({@link #ebics}).
 *
 * Das Szenario wird beim Start des Benchmarks ausgewählt
 * und gilt für alle Konfigurationen eines Durchlaufs.
 */
public enum BenchmarkScenario {

    /** Payload-lastiger JSON-Endpunkt. */
    PAYLOAD_HEAVY_JSON("/json",         200_000,    200_000, false),

    /** Allokations-lastiger Endpunkt. */
    ALLOC_HEAVY_OK    ("/alloc",     10_000_000, 10_000_000, false),

    /** EBICS-Upload ueber den EK-Bankserver. */
    EBICS_UPLOAD      ("/ebics/upload",      10,          3, true);

    private final String path;
    private final int defaultN;
    private final int smokeN;
    private final boolean ebics;

    BenchmarkScenario(String path, int defaultN, int smokeN, boolean ebics) {
        this.path = path;
        this.defaultN = defaultN;
        this.smokeN = smokeN;
        this.ebics = ebics;
    }

    /** HTTP-Endpunkt-Pfad (ohne Query-Parameter), z.B. {@code "/json"}. */
    public String path()     { return path; }

    /** Standard-Workload-Groesse n fuer regulaere Benchmark-Laeufe. */
    public int defaultN()    { return defaultN; }

    /** Workload-Groesse n fuer Smoke-Tests (reduziert fuer teure Szenarien). */
    public int smokeN()      { return smokeN; }

    /** Ob dieses Szenario einen EBICS-Bankserver (TravicLink) benoetigt. */
    public boolean isEbics() { return ebics; }
}
