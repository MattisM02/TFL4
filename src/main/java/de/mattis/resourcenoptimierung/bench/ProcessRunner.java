package de.mattis.resourcenoptimierung.bench;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Gemeinsame Utility-Klasse fuer die Ausfuehrung externer Prozesse.
 *
 * Liest stdout und stderr parallel in separaten Threads, BEVOR
 * {@code waitFor()} aufgerufen wird. Dadurch wird ein Pipe-Deadlock
 * verhindert: Wenn der OS-Pipe-Buffer (~64KB) volllaeuft, blockiert
 * der Kindprozess beim Schreiben — und {@code waitFor()} haengt ewig,
 * wenn die Streams nicht gleichzeitig gelesen werden.
 *
 * Wird von {@link SingleRun} und {@link TravicLinkManager} verwendet.
 */
public final class ProcessRunner {

    private ProcessRunner() {}

    /**
     * Ergebnis einer Prozess-Ausfuehrung.
     *
     * @param exitCode Exit-Code des Prozesses
     * @param stdout   Standardausgabe
     * @param stderr   Fehlerausgabe
     */
    public record ExecResult(int exitCode, String stdout, String stderr) {}

    /**
     * Fuehrt ein Kommando aus und sammelt stdout/stderr.
     *
     * Stdout und stderr werden parallel in separaten Threads gelesen,
     * BEVOR {@code waitFor()} aufgerufen wird, um Pipe-Deadlocks zu verhindern.
     *
     * @param cmd     Kommando als Liste
     * @param timeout maximale Laufzeit
     * @return ExecResult mit Exit-Code, stdout und stderr
     * @throws IOException          wenn der Prozess nicht gestartet werden kann
     * @throws InterruptedException wenn der Aufruf unterbrochen wird
     */
    public static ExecResult exec(List<String> cmd, Duration timeout) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        Process p = pb.start();

        // Stdout und stderr parallel lesen, BEVOR waitFor() aufgerufen wird.
        // Verhindert Pipe-Deadlock: Wenn der OS-Pipe-Buffer (~64KB) volllaeuft,
        // blockiert der Kindprozess beim Schreiben und waitFor() haengt ewig.
        CompletableFuture<String> stdoutFuture = CompletableFuture.supplyAsync(() -> {
            try { return readAll(p.getInputStream()); } catch (IOException e) { return ""; }
        });
        CompletableFuture<String> stderrFuture = CompletableFuture.supplyAsync(() -> {
            try { return readAll(p.getErrorStream()); } catch (IOException e) { return ""; }
        });

        boolean ok = p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!ok) {
            p.destroyForcibly();
            stdoutFuture.cancel(true);
            stderrFuture.cancel(true);
            throw new RuntimeException("Command timed out: " + String.join(" ", cmd));
        }

        String stdout = stdoutFuture.join();
        String stderr = stderrFuture.join();
        return new ExecResult(p.exitValue(), stdout, stderr);
    }

    /**
     * Liest einen Stream komplett ein und gibt ihn als String zurueck.
     *
     * @param in InputStream
     * @return Inhalt als String
     * @throws IOException wenn Lesen fehlschlaegt
     */
    static String readAll(InputStream in) throws IOException {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        }
    }
}
