package de.mattis.resourcenoptimierung.bench;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Führt einen einzelnen Benchmark-Run für genau eine BenchmarkConfig aus.
 *
 * Ablauf:
 * - Container starten (optional mit JAVA_TOOL_OPTIONS)
 * - Readiness abwarten (mit Fallbacks)
 * - Workload ausführen (First Request, Warmup, Messphase)
 * - Docker-Stats in Phasen sammeln (IDLE, LOAD, POST)
 * - Ergebnis als RunResult zurückgeben
 *
 * Diese Klasse enthält die Ausführungs- und Messlogik.
 */
public class SingleRun {

    /**
     * Benchmark-Konfiguration für diesen Run.
     */
    private final BenchmarkConfig cfg;

    /**
     * Host-Port für das Port-Mapping auf Container-Port 8080.
     */
    private final int port;

    /**
     * Maximale Wartezeit bis der Service als "ready" gilt.
     */
    private final Duration readinessTimeout;

    /**
     * Container-ID des gestarteten Containers.
     */
    private String containerId;

    /**
     * Workload-Szenario für diesen Run.
     */
    private final BenchmarkScenario scenario;

    /**
     * Workload-Größe n für den Endpoint.
     */
    private final int workloadN;

    /**
     * Erstellt einen SingleRun mit Default-Port 8080 und 120s Readiness-Timeout.
     *
     * @param cfg Benchmark-Konfiguration
     * @param scenario Workload-Szenario
     * @param workloadN Workload-Größe n
     */
    public SingleRun(BenchmarkConfig cfg, BenchmarkScenario scenario, int workloadN) {
        this(cfg, scenario, workloadN, 8080, Duration.ofSeconds(120));
    }

    /**
     * Erstellt einen SingleRun mit konfigurierbarem Port und Readiness-Timeout.
     *
     * @param cfg Benchmark-Konfiguration
     * @param scenario Workload-Szenario
     * @param workloadN Workload-Größe n
     * @param port Host-Port für das Port-Mapping
     * @param readinessTimeout maximale Wartezeit auf Readiness
     */
    public SingleRun(BenchmarkConfig cfg, BenchmarkScenario scenario, int workloadN, int port, Duration readinessTimeout) {
        this.cfg = cfg;
        this.scenario = scenario;
        this.workloadN = workloadN;
        this.port = port;
        this.readinessTimeout = readinessTimeout;
    }

    /**
     * Ermittelt den Workload-Pfad inkl. Query-Parameter.
     *
     * @return Pfad inkl. n-Parameter
     */
    private String workloadPath() {
        return switch (scenario) {
            case PAYLOAD_HEAVY_JSON -> "/json?n=" + workloadN;
            case ALLOC_HEAVY_OK -> "/alloc?n=" + workloadN;
        };
    }

    /**
     * HTTP-Client für Requests in diesem Run.
     */
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();


    /**
     * Führt den kompletten Run aus und gibt ein RunResult zurück.
     *
     * Fehlerverhalten:
     * - Bei Fehlern werden Logs best-effort ausgegeben.
     * - Bei Erfolg wird der Container gestoppt und entfernt.
     * - Bei Fehler wird der Container für manuelle Analyse behalten.
     *
     * @return Run-Ergebnis
     * @throws Exception wenn Docker/HTTP/Messung fehlschlägt oder Readiness nicht erreicht wird
     */
    public RunResult execute() throws Exception {
        boolean success = false;
        try {
            // 1) Flags berechnen (und im Result speichern)
            //    Für JVM-Runs wird aus cfg.jvmArgs() ein String gebaut, der als JAVA_TOOL_OPTIONS gesetzt wird.
            //    Für Native-Runs ist das nicht anwendbar -> null.
            String effectiveJavaToolOptions = computeEffectiveJavaToolOptions(cfg);

            // 2) Container starten
            //    dockerRun übernimmt das Port-Mapping und setzt ggf. -e JAVA_TOOL_OPTIONS=...
            containerId = dockerRun(cfg, port, effectiveJavaToolOptions);

            // 3) Proof: kurze Start-Logs speichern (best-effort)
            //    Zweck: Nachvollziehbarkeit, ob Flags "ankamen" oder ob Startprobleme sichtbar sind.
            //    Best-effort, weil Logs nicht immer verfügbar/sofort da sind.
            String startupLogSnippet = null;
            if (!cfg.isNative()) {
                try {
                    String logs = dockerLogsTail(containerId, 200);
                    startupLogSnippet = trimSnippet(logs, 2000);
                } catch (Exception ignored) {}
            }

            // 4) Readiness (robust & workload-konsistent)
            //    Wartet, bis der Service "ready" ist.
            //    Fallback-Kette:
            //      1) /actuator/health/readiness
            //      2) /actuator/health
            //      3) Workload-Endpoint (z. B. /json oder /alloc)
            //    → Der letzte Fallback nutzt bewusst den späteren Workload,
            //      um Readiness und Benchmark nicht zu entkoppeln.
            ReadinessProber prober = new ReadinessProber();

            String path = workloadPath(); // z.B. "/json" oder "/alloc"
            ReadinessProber.ReadinessResult rr =
                    prober.waitUntilReady("http://localhost:" + port, readinessTimeout, path);

            long readinessMs = rr.readinessMs();
            ReadinessCheckUsed readinessCheckUsed = rr.used();

            // 5) Idle samples direkt nach readiness
            //    Basiswerte, bevor Last erzeugt wird (Vergleich zu LOAD/POST).
            List<DockerStatSample> dockerIdleSamples = dockerStatsSamples(containerId, 3, 1);

            // 6) Während Load parallel samplen
            //    Startet einen Thread, der während der Lastphase docker stats sammelt.
            //    Die Samples landen in dockerLoadSamples (shared list).
            List<DockerStatSample> dockerLoadSamples = new ArrayList<>();
            Thread sampler = startDockerSampler(dockerLoadSamples, containerId, 10, 1);

            // 7) First request separat messen
            //    Diese Metrik zeigt oft Cold-Path / JIT / Cache-Effekte nach Readiness.
            double firstSeconds = measureEndpointSeconds(path);

            // 8) Warmup + Messung
            //    Warmup reduziert die Varianz (JIT/Cache). Danach wird die eigentliche Messreihe aufgenommen.
            warmup(path, 20);
            List<Double> jsonLatenciesSeconds = measureManySeconds(path, 100);

            // 9) Warten bis sampler fertig (best-effort)
            //     Wenn der Sampler nicht rechtzeitig fertig wird, brechen wir ab,
            //     um keinen Run dauerhaft zu blockieren.
            try {
                sampler.join(Duration.ofSeconds(15).toMillis());
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }

            // 10) Post samples
            //     Nachlaufwerte: interessant für "memory not returning" oder Nach-GC-Verhalten.
            List<DockerStatSample> dockerPostSamples = dockerStatsSamples(containerId, 3, 1);

            // 11) Ergebnis bauen
            //     Speichert sowohl die Rohdaten (Latenzen + Docker-Samples) als auch Metadaten,
            //     damit spätere Auswertung/Exports vollständig sind.
            RunResult result = new RunResult(
                    cfg.name(),
                    cfg.dockerImage(),
                    readinessMs,
                    firstSeconds,
                    jsonLatenciesSeconds,
                    effectiveJavaToolOptions,
                    readinessCheckUsed,
                    startupLogSnippet,
                    scenario,
                    workloadN,
                    path,
                    dockerIdleSamples,
                    dockerLoadSamples,
                    dockerPostSamples
            );

            success = true;
            return result;

        } catch (RuntimeException e) {
            if (containerId != null && !containerId.isBlank()) {
                // Bei Fehlern: Log-Auszug ausgeben, um die Ursache schneller zu sehen.
                try {
                    String logs = dockerLogsTail(containerId, 200);
                    System.err.println("=== docker logs (tail 200) ===");
                    System.err.println(logs);
                } catch (Exception ignored) {}
            }
            throw e;
        } finally {
            // Cleanup-Strategie:
            // - Erfolg: Container stoppen + entfernen, damit keine Ressourcen leaken.
            // - Fehler: Container behalten, damit man manuell inspecten kann (docker logs/exec).
            if (success && containerId != null && !containerId.isBlank()) {
                dockerStop(containerId);
                dockerRm(containerId);
            } else if (!success && containerId != null && !containerId.isBlank()) {
                System.err.println("Container kept for inspection: " + containerId);
            }
        }
    }

    /**
     * Startet den Container für die gegebene Konfiguration.
     *
     * Setzt CPU- und Memory-Limits für bessere Vergleichbarkeit.
     * Für JVM-Images wird JAVA_TOOL_OPTIONS gesetzt, für native nicht.
     *
     * @param cfg Benchmark-Konfiguration
     * @param port Host-Port für das Port-Mapping
     * @param effectiveJavaToolOptions Flags als String (oder null bei native)
     * @return Container-ID
     * @throws IOException wenn der Prozess nicht gestartet werden kann
     * @throws InterruptedException wenn der Aufruf unterbrochen wird
     */
    private String dockerRun(BenchmarkConfig cfg, int port, String effectiveJavaToolOptions)
            throws IOException, InterruptedException {

        List<String> cmd = new ArrayList<>(List.of(
                "docker", "run",
                "-d",
                "-p", port + ":8080",
                "--cpus", "1",
                "--memory", "768m"
        ));

        // JAVA_TOOL_OPTIONS setzen (nur für JVM, nicht für native)
        if (!cfg.isNative()) {
            String fromCfg = (cfg.jvmArgs() == null) ? "" : String.join(" ", cfg.jvmArgs()).trim();
            String fromEffective = (effectiveJavaToolOptions == null) ? "" : effectiveJavaToolOptions.trim();

            String javaToolOptions = String.join(" ", fromCfg, fromEffective).trim();
            if (!javaToolOptions.isBlank()) {
                cmd.add("-e");
                cmd.add("JAVA_TOOL_OPTIONS=" + javaToolOptions);
            }
        }

        cmd.add(cfg.dockerImage());

        ExecResult res = exec(cmd, Duration.ofSeconds(30));
        if (res.exitCode != 0) {
            throw new RuntimeException(
                    "docker run failed (exit " + res.exitCode + ")\n" +
                            "cmd: " + String.join(" ", cmd) + "\n" +
                            "stderr: " + res.stderr + "\n" +
                            "stdout: " + res.stdout
            );
        }

        String id = res.stdout.trim();
        if (id.isEmpty()) {
            throw new RuntimeException("docker run returned empty container id. stdout=" + res.stdout + ", stderr=" + res.stderr);
        }
        return id;
    }

    /**
     * Misst die Latenz eines HTTP-GET Requests in Sekunden.
     *
     * @param path Pfad inkl. Query (z.B. "/json?n=200000")
     * @return Latenz in Sekunden
     * @throws Exception wenn der Request fehlschlägt oder Status != 200 ist
     */
    private double measureEndpointSeconds(String path) throws Exception {
        URI uri = URI.create("http://localhost:" + port + path);

        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();

        long t0 = System.nanoTime();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        long t1 = System.nanoTime();

        if (resp.statusCode() != 200) {
            throw new RuntimeException("GET " + path + " failed: " + resp.statusCode() + " body=" + resp.body());
        }

        return (t1 - t0) / 1_000_000_000.0;
    }

    /**
     * Führt Warmup-Requests aus, um Messungen zu stabilisieren.
     *
     * @param path Workload-Pfad
     * @param times Anzahl Warmup-Requests
     * @throws Exception wenn ein Request fehlschlägt
     */
    private void warmup(String path, int times) throws Exception {
        for (int i = 0; i < times; i++) {
            measureEndpointSeconds(path);
        }
    }

    /**
     * Führt Mess-Requests aus und sammelt die Latenzen.
     *
     * @param path Workload-Pfad
     * @param times Anzahl Requests
     * @return Latenzen in Sekunden
     * @throws Exception wenn ein Request fehlschlägt
     */
    private List<Double> measureManySeconds(String path, int times) throws Exception {
        List<Double> res = new ArrayList<>(times);
        for (int i = 0; i < times; i++) {
            res.add(measureEndpointSeconds(path));
        }
        return res;
    }

    /**
     * Liest einen einzelnen docker stats Snapshot.
     *
     * @param containerId Container-ID
     * @return DockerStatSample
     * @throws IOException wenn der Aufruf fehlschlägt
     * @throws InterruptedException wenn der Aufruf unterbrochen wird
     */
    private DockerStatSample dockerStatsNoStream(String containerId) throws IOException, InterruptedException {
        String format = "{{.CPUPerc}}|{{.MemUsage}}|{{.MemPerc}}|{{.NetIO}}|{{.BlockIO}}|{{.PIDs}}";
        ExecResult r = exec(List.of(
                "docker", "stats", "--no-stream", "--format", format, containerId
        ), Duration.ofSeconds(10));
        if (r.exitCode != 0) throw new RuntimeException("docker stats failed: " + r.stderr);

        String line = r.stdout.trim();
        return DockerStatSample.parse(line);
    }

    /**
     * Erfasst mehrere docker stats Snapshots in festen Intervallen.
     *
     * @param containerId Container-ID
     * @param samples Anzahl Snapshots
     * @param sleepSeconds Pause zwischen Snapshots in Sekunden
     * @return Liste der Samples
     * @throws IOException wenn der Aufruf fehlschlägt
     * @throws InterruptedException wenn Sleep unterbrochen wird
     */
    private List<DockerStatSample> dockerStatsSamples(String containerId, int samples, int sleepSeconds)
            throws IOException, InterruptedException {

        List<DockerStatSample> list = new ArrayList<>();
        for (int i = 0; i < samples; i++) {
            list.add(dockerStatsNoStream(containerId));
            if (i < samples - 1) {
                Thread.sleep(sleepSeconds * 1000L);
            }
        }
        return list;
    }

    /**
     * Stoppt einen Container. Fehler werden ignoriert.
     *
     * @param containerId Container-ID
     */
    private void dockerStop(String containerId) {
        try {
            exec(List.of("docker", "stop", containerId), Duration.ofSeconds(10));
        } catch (Exception ignored) {}
    }

    /**
     * Entfernt einen Container. Fehler werden ignoriert.
     *
     * @param containerId Container-ID
     */
    private void dockerRm(String containerId) {
        try {
            exec(List.of("docker", "rm", "-f", containerId), Duration.ofSeconds(10));
        } catch (Exception ignored) {}
    }

    /**
     * Führt ein Kommando aus und sammelt stdout/stderr.
     *
     * @param cmd Kommando als Liste
     * @param timeout maximale Laufzeit
     * @return ExecResult
     * @throws IOException wenn der Prozess nicht gestartet werden kann
     * @throws InterruptedException wenn der Aufruf unterbrochen wird
     */
    private static ExecResult exec(List<String> cmd, Duration timeout) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
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

    /**
     * Liest einen Stream komplett ein und gibt ihn als String zurück.
     *
     * @param in InputStream
     * @return Inhalt als String
     * @throws IOException wenn Lesen fehlschlägt
     */
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


    /**
     * Baut den JAVA_TOOL_OPTIONS-String für diesen Run.
     *
     * @param cfg Benchmark-Konfiguration
     * @return Flags als String oder null bei native
     */
    private static String computeEffectiveJavaToolOptions(BenchmarkConfig cfg) {
        if (cfg.isNative()) return null;

        List<String> args = new ArrayList<>();
        if (cfg.jvmArgs() != null) args.addAll(cfg.jvmArgs());

        return String.join(" ", args);
    }

    /**
     * Holt die letzten Zeilen aus docker logs.
     *
     * @param containerId Container-ID
     * @param tailLines Anzahl Zeilen
     * @return Log-Auszug oder Fehlermeldung
     * @throws IOException wenn der Aufruf fehlschlägt
     * @throws InterruptedException wenn der Aufruf unterbrochen wird
     */
    private String dockerLogsTail(String containerId, int tailLines) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add("docker");
        cmd.add("logs");
        cmd.add("--tail");
        cmd.add(Integer.toString(tailLines));
        cmd.add(containerId);

        ExecResult res = exec(cmd, Duration.ofSeconds(10));
        if (res.exitCode != 0) {
            return "docker logs failed: " + res.stderr;
        }
        return res.stdout;
    }

    /**
     * Kürzt einen String auf maximal maxChars.
     *
     * @param s Eingabe
     * @param maxChars maximale Länge
     * @return ggf. gekürzter String
     */
    private static String trimSnippet(String s, int maxChars) {
        if (s == null) return null;
        if (s.length() <= maxChars) return s;
        return s.substring(0, maxChars) + "\n... (truncated)";
    }

    /**
     * Startet einen Thread, der während der Lastphase docker stats sammelt.
     *
     * Der Thread arbeitet best-effort: Fehler werden ignoriert.
     * Der Thread ist daemon, damit er das Beenden des Programms nicht blockiert.
     *
     * @param target Liste für die Samples
     * @param containerId Container-ID
     * @param samples Anzahl Snapshots
     * @param sleepSeconds Pause zwischen Snapshots in Sekunden
     * @return gestarteter Thread
     */
    private Thread startDockerSampler(List<DockerStatSample> target, String containerId, int samples, int sleepSeconds) {
        Thread t = new Thread(() -> {
            try {
                for (int i = 0; i < samples; i++) {
                    target.add(dockerStatsNoStream(containerId));
                    if (i < samples - 1) Thread.sleep(sleepSeconds * 1000L);
                }
            } catch (Exception ignored) {
                // best-effort
            }
        }, "docker-stats-sampler");
        t.setDaemon(true);
        t.start();
        return t;
    }

    /**
     * Rückgabecontainer für exec.
     *
     * @param exitCode Exit-Code des Prozesses
     * @param stdout Standardausgabe
     * @param stderr Fehlerausgabe
     */
    private record ExecResult(int exitCode, String stdout, String stderr) {}
}
