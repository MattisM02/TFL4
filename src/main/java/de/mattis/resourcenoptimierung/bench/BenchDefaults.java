package de.mattis.resourcenoptimierung.bench;

import java.net.InetAddress;
import java.time.Duration;

/**
 * Zentrale Benchmark-Defaults: Alle umgebungs- und maschinenspezifischen Konstanten,
 * die bisher ueber mehrere Klassen verstreut waren.
 *
 * <p>Aenderungen an Host-Port, Docker-Limits oder Verzeichnissen muessen nur noch
 * hier vorgenommen werden.
 */
public final class BenchDefaults {

    private BenchDefaults() { /* utility */ }

    // ======================== Netzwerk ========================

    /** Port, auf dem die Spring-Boot-Anwendung im Container lauscht. */
    public static final int CONTAINER_PORT = 8080;

    /** Host-Port, ueber den der Container erreichbar ist (default). */
    public static final int DEFAULT_HOST_PORT = 8080;

    /** TravicLink-Bankserver-Port (nativ auf Windows). */
    public static final int TRAVICLINK_PORT = 7070;

    /**
     * Hostname des Host-Rechners fuer {@code --add-host} in Docker.
     *
     * <p>Wird automatisch aus {@link InetAddress#getLocalHost()} ermittelt,
     * sodass kein maschinenspezifischer Name im Quellcode steht.
     * Fallback: {@code "localhost"}.
     */
    public static final String HOST_NAME = resolveHostName();

    private static String resolveHostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            System.err.println("[WARN] Could not resolve hostname, falling back to 'localhost': " + e.getMessage());
            return "localhost";
        }
    }

    // ======================== Docker-Limits ========================

    /** CPU-Limit fuer Benchmark-Container (--cpus). */
    public static final String DOCKER_CPUS = "1";

    /** Memory-Limit fuer Benchmark-Container (--memory). */
    public static final String DOCKER_MEMORY = "768m";

    /** Memory-Swap-Limit fuer Benchmark-Container (= DOCKER_MEMORY, Swap deaktiviert). */
    public static final String DOCKER_MEMORY_SWAP = DOCKER_MEMORY;

    // ======================== Timeouts ========================

    /** Maximale Wartezeit auf Container-Readiness. */
    public static final Duration READINESS_TIMEOUT = Duration.ofSeconds(120);

    // ======================== Verzeichnisse und Dateien ========================

    /** Ausgabeverzeichnis fuer Benchmark-Ergebnisse. */
    public static final String OUTPUT_DIR = "bench-results";

    /** Dateiname der zusammengefuehrten Excel-Datei. */
    public static final String EXCEL_FILENAME = "benchmark-vergleich.xlsx";

    /** Unterverzeichnis fuer GC-Logs innerhalb von {@link #OUTPUT_DIR}. */
    public static final String GC_LOGS_SUBDIR = "gc-logs";

    // ======================== Docker-Images ========================

    /** Praefix fuer alle Benchmark-Docker-Images. */
    public static final String IMAGE_PREFIX = "tfl4-ek-bench";

    /** Standard-Image fuer JVM-basierte Benchmarks (ohne EBICS-Kernel). */
    public static final String IMAGE_JVM = IMAGE_PREFIX + ":jvm";

    /** Standard-Image fuer JVM-basierte Benchmarks mit EBICS-Kernel. */
    public static final String IMAGE_JVM_EK = IMAGE_PREFIX + ":jvm-ek";
}
