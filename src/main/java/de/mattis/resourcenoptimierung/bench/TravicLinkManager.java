package de.mattis.resourcenoptimierung.bench;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Steuert den Lebenszyklus des TravicLink-EBICS-Servers fuer Benchmark-Runs.
 *
 * Beim Start eines EBICS-Szenarios wird der TravicLink-Server (inkl. PostgreSQL)
 * per docker-compose hochgefahren. Nach Abschluss aller Runs wird er gestoppt.
 *
 * Der Manager:
 * - startet docker-compose up -d
 * - wartet bis TravicLink auf Port 7070 erreichbar ist (TCP-Probe)
 * - stoppt docker-compose down nach den Runs
 *
 * Erwartet, dass bench-docker/docker-compose.yml relativ zum Arbeitsverzeichnis existiert.
 */
public class TravicLinkManager {

    /**
     * Pfad zur docker-compose.yml relativ zum Arbeitsverzeichnis.
     */
    private final Path composeFile;

    /**
     * Host auf dem TravicLink erreichbar ist (fuer TCP-Readiness-Check).
     */
    private final String host;

    /**
     * Port auf dem TravicLink erreichbar ist.
     */
    private final int port;

    /**
     * Maximale Wartezeit bis TravicLink bereit ist.
     */
    private final Duration readinessTimeout;

    /**
     * Erstellt einen TravicLinkManager mit Standard-Konfiguration.
     * Compose-Datei: bench-docker/docker-compose.yml
     * Host: localhost, Port: 7070, Timeout: 120s
     */
    public TravicLinkManager() {
        this(Path.of("bench-docker", "docker-compose.yml"), "localhost", 7070, Duration.ofSeconds(120));
    }

    /**
     * Erstellt einen TravicLinkManager mit konfigurierbaren Parametern.
     *
     * @param composeFile Pfad zur docker-compose.yml
     * @param host Host fuer den TCP-Readiness-Check
     * @param port Port fuer den TCP-Readiness-Check
     * @param readinessTimeout maximale Wartezeit auf Readiness
     */
    public TravicLinkManager(Path composeFile, String host, int port, Duration readinessTimeout) {
        this.composeFile = composeFile;
        this.host = host;
        this.port = port;
        this.readinessTimeout = readinessTimeout;
    }

    /**
     * Ob docker-compose von uns gestartet wurde (steuert ob stop() etwas tut).
     */
    private boolean composeManagedByUs = false;

    /**
     * Startet den TravicLink-Stack (docker-compose up -d) und wartet auf Readiness.
     * Falls TravicLink bereits auf host:port erreichbar ist (z.B. nativ auf Windows),
     * wird docker-compose NICHT gestartet — der externe Server wird direkt genutzt.
     *
     * @throws IOException wenn docker-compose nicht gestartet werden kann
     * @throws InterruptedException wenn der Aufruf unterbrochen wird
     * @throws RuntimeException wenn TravicLink nicht innerhalb des Timeouts erreichbar ist
     */
    public void start() throws IOException, InterruptedException {
        // Pruefen ob TravicLink bereits laeuft (z.B. nativ auf Windows)
        if (isTcpReachable()) {
            System.out.println("TravicLink already reachable on " + host + ":" + port + " — skipping docker-compose.");
            return;
        }

        System.out.println("Starting TravicLink EBICS server (docker-compose up) ...");

        ExecResult res = exec(List.of(
                "docker", "compose",
                "-f", composeFile.toString(),
                "up", "-d"
        ), Duration.ofMinutes(3));

        if (res.exitCode != 0) {
            throw new RuntimeException(
                    "docker compose up failed (exit " + res.exitCode + ")\n"
                            + "stderr: " + res.stderr + "\n"
                            + "stdout: " + res.stdout
            );
        }

        composeManagedByUs = true;
        System.out.println("docker compose up OK. Waiting for TravicLink readiness on " + host + ":" + port + " ...");
        waitForTcpReady();
        System.out.println("TravicLink is ready.");
    }

    /**
     * Stoppt den TravicLink-Stack (docker-compose down).
     * Wird nur ausgefuehrt, wenn docker-compose von uns gestartet wurde.
     * Fehler werden auf stderr ausgegeben, aber nicht geworfen.
     */
    public void stop() {
        if (!composeManagedByUs) {
            System.out.println("TravicLink was external — nothing to stop.");
            return;
        }
        System.out.println("Stopping TravicLink EBICS server (docker-compose down) ...");
        try {
            ExecResult res = exec(List.of(
                    "docker", "compose",
                    "-f", composeFile.toString(),
                    "down"
            ), Duration.ofMinutes(2));

            if (res.exitCode != 0) {
                System.err.println("docker compose down returned exit " + res.exitCode + ": " + res.stderr);
            } else {
                System.out.println("TravicLink stopped.");
            }
        } catch (Exception e) {
            System.err.println("Failed to stop TravicLink: " + e.getMessage());
        }
    }

    /**
     * Prueft einmalig ob TravicLink per TCP erreichbar ist (Timeout 2s).
     *
     * @return true wenn eine TCP-Verbindung zu host:port moeglich ist
     */
    private boolean isTcpReachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new java.net.InetSocketAddress(host, port), 2000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Wartet bis TravicLink per TCP auf host:port erreichbar ist.
     * Pollt alle 2 Sekunden.
     *
     * @throws RuntimeException wenn das Timeout ueberschritten wird
     */
    private void waitForTcpReady() {
        long deadline = System.nanoTime() + readinessTimeout.toNanos();
        int attempts = 0;

        while (System.nanoTime() < deadline) {
            attempts++;
            try (Socket socket = new Socket(host, port)) {
                // Verbindung erfolgreich -> TravicLink ist bereit
                return;
            } catch (IOException e) {
                // Noch nicht bereit
                if (attempts % 10 == 0) {
                    System.out.println("  Still waiting for TravicLink (" + attempts + " attempts) ...");
                }
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted while waiting for TravicLink", ie);
                }
            }
        }

        throw new RuntimeException(
                "TravicLink did not become ready within " + readinessTimeout.getSeconds() + "s on " + host + ":" + port
        );
    }

    /**
     * Prueft, ob ein EBICS-Szenario vorliegt.
     *
     * @param scenario Benchmark-Szenario
     * @return true wenn EBICS
     */
    public static boolean isEbicsScenario(BenchmarkScenario scenario) {
        return scenario == BenchmarkScenario.EBICS_UPLOAD;
    }

    // ==================== Process Utilities ====================

    private static ExecResult exec(List<String> cmd, Duration timeout) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);
        Process p = pb.start();

        boolean ok = p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!ok) {
            p.destroyForcibly();
            throw new RuntimeException("Command timed out: " + String.join(" ", cmd));
        }

        String stdout = readAll(p.getInputStream());
        String stderr = readAll(p.getErrorStream());
        return new ExecResult(p.exitValue(), stdout, stderr);
    }

    private static String readAll(java.io.InputStream in) throws IOException {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        }
    }

    private record ExecResult(int exitCode, String stdout, String stderr) {}
}
