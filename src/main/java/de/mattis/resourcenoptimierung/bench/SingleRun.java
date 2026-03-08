package de.mattis.resourcenoptimierung.bench;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fuehrt einen einzelnen Benchmark-Run fuer genau eine BenchmarkConfig aus.
 *
 * Ablauf:
 * - Container starten (optional mit JAVA_TOOL_OPTIONS)
 * - Readiness abwarten (mit Fallbacks)
 * - Workload ausfuehren (First Request, Warmup, Messphase)
 * - Docker-Stats in Phasen sammeln (IDLE, LOAD, POST)
 * - Ergebnis als RunResult zurueckgeben
 *
 * Das Messprofil (Warmup/Messung/Concurrency/Sleep) ist parametrisierbar
 * ueber MeasurementProfile.
 *
 * Diese Klasse enthaelt die Ausfuehrungs- und Messlogik.
 */
public class SingleRun {

    /**
     * Benchmark-Konfiguration fuer diesen Run.
     */
    private final BenchmarkConfig cfg;

    /**
     * Host-Port fuer das Port-Mapping auf Container-Port 8080.
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
     * Workload-Szenario fuer diesen Run.
     */
    private final BenchmarkScenario scenario;

    /**
     * Workload-Groesse n fuer den Endpoint.
     */
    private final int workloadN;

    /**
     * Messprofil: steuert Warmup, Messung, Concurrency und Sleep.
     */
    private final MeasurementProfile profile;

    /**
     * 1-basierte Wiederholungsnummer.
     */
    private final int repetition;

    /**
     * Erstellt einen SingleRun mit Default-Port 8080 und 120s Readiness-Timeout.
     *
     * @param cfg Benchmark-Konfiguration
     * @param scenario Workload-Szenario
     * @param workloadN Workload-Groesse n
     * @param profile Messprofil
     */
    public SingleRun(BenchmarkConfig cfg, BenchmarkScenario scenario, int workloadN, MeasurementProfile profile) {
        this(cfg, scenario, workloadN, profile, BenchDefaults.DEFAULT_HOST_PORT, BenchDefaults.READINESS_TIMEOUT, 1);
    }

    /**
     * Erstellt einen SingleRun mit konfigurierbarem Port und Readiness-Timeout.
     *
     * @param cfg Benchmark-Konfiguration
     * @param scenario Workload-Szenario
     * @param workloadN Workload-Groesse n
     * @param profile Messprofil
     * @param port Host-Port fuer das Port-Mapping
     * @param readinessTimeout maximale Wartezeit auf Readiness
     * @param repetition 1-basierte Wiederholungsnummer
     */
    public SingleRun(BenchmarkConfig cfg, BenchmarkScenario scenario, int workloadN,
                     MeasurementProfile profile, int port, Duration readinessTimeout, int repetition) {
        this.cfg = cfg;
        this.scenario = scenario;
        this.workloadN = workloadN;
        this.profile = profile;
        this.port = port;
        this.readinessTimeout = readinessTimeout;
        this.repetition = repetition;
    }

    /**
     * Ermittelt den Workload-Pfad inkl. Query-Parameter.
     *
     * @return Pfad inkl. n-Parameter
     */
    private String workloadPath() {
        return scenario.path() + "?n=" + workloadN;
    }

    /**
     * HTTP-Client fuer Requests in diesem Run.
     */
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .proxy(java.net.ProxySelector.of(null))
            .build();


    /**
     * Fuehrt den kompletten Run aus und gibt ein RunResult zurueck.
     *
     * Fehlerverhalten:
     * - Bei Fehlern werden Logs best-effort ausgegeben.
     * - Container wird IMMER gestoppt und entfernt (auch bei Fehler),
     *   damit keine Zombie-Container Port 8080 blockieren.
     *
     * @return Run-Ergebnis
     * @throws Exception wenn Docker/HTTP/Messung fehlschlaegt oder Readiness nicht erreicht wird
     */
    public RunResult execute() throws Exception {
        try {
            // 1) Flags berechnen (und im Result speichern)
            //    Fuer JVM-Runs wird aus cfg.jvmArgs() ein String gebaut, der als JAVA_TOOL_OPTIONS gesetzt wird.
            //    Fuer Native-Runs ist das nicht anwendbar -> null.
            String effectiveJavaToolOptions = computeEffectiveJavaToolOptions(cfg);

            // 2) Container starten
            //    dockerRun uebernimmt das Port-Mapping und setzt ggf. -e JAVA_TOOL_OPTIONS=...
            //    Timer startet VOR docker run, damit die Container-Startzeit (Image-Pull, Overlay-FS,
            //    Namespace-Setup) in readinessMs enthalten ist — analog zu Kubernetes-Pod-Startup.
            long startNanos = System.nanoTime();
            containerId = dockerRun(cfg, port, effectiveJavaToolOptions);

            // 3) Proof: kurze Start-Logs speichern (best-effort)
            //    Zweck: Nachvollziehbarkeit, ob Flags "ankamen" oder ob Startprobleme sichtbar sind.
            //    Best-effort, weil Logs nicht immer verfuegbar/sofort da sind.
            //    Fuer alle Laufzeittypen erfasst (auch NATIVE), damit Crash-Logs sichtbar werden.
            String startupLogSnippet = null;
            try {
                String logs = dockerLogsTail(containerId, 200);
                startupLogSnippet = trimSnippet(logs, 2000);
            } catch (Exception ignored) {}

            // 4) Readiness (robust & workload-konsistent)
            //    Wartet, bis der Service "ready" ist.
            //    Fallback-Kette:
            //      1) /actuator/health/readiness  (bevorzugt, semantisch korrekt)
            //      2) /actuator/health            (Fallback, wenn Readiness-Probe fehlt)
            //      3) Workload-Endpoint           (letzter Fallback)
            //
            //    WICHTIG fuer EBICS-Szenarien:
            //    Wenn Fallback auf den Workload-Endpoint erfolgt, wird die EK-Initialisierung
            //    (inkl. HPB-Abruf der Bank-Keys) als Teil der Readiness-Zeit gemessen.
            //    Das bedeutet: readinessMs enthaelt dann EK-Init + HPB-Latenz.
            //    Fuer EBICS ist das beabsichtigt, da der Service erst nach erfolgreicher
            //    EK-Initialisierung wirklich "ready" ist.
            //
            //    Container-Alive-Check: Erkennt fruehzeitig, wenn der Container
            //    bereits beendet ist (z.B. Crash bei NATIVE ohne Reachability-Metadata),
            //    statt das volle 120s Timeout abzuwarten.
            // Container-Alive-Check: prueft via "docker inspect" ob der Container noch laeuft
            final String cid = containerId;
            ReadinessProber.ContainerAliveCheck aliveCheck = () -> isContainerRunning(cid);

            String path = workloadPath(); // z.B. "/json" oder "/alloc"
            ReadinessProber.ReadinessResult rr;
            try (ReadinessProber prober = new ReadinessProber()) {
                rr = prober.waitUntilReady("http://localhost:" + port, readinessTimeout, path, aliveCheck);
            }

            // readinessMs = Zeit von VOR docker run bis ready (inkl. Container-Startup)
            long readinessMs = (System.nanoTime() - startNanos) / 1_000_000;
            ReadinessCheckUsed readinessCheckUsed = rr.used();

            // 5) Idle samples direkt nach readiness
            //    Basiswerte, bevor Last erzeugt wird (Vergleich zu LOAD/POST).
            List<DockerStatSample> dockerIdleSamples = dockerStatsSamples(containerId, 3, 1);

            // 6) Waehrend Load parallel samplen
            //    Startet einen Thread, der waehrend der Lastphase docker stats sammelt.
            //    Die Samples landen in dockerLoadSamples (shared list).
            //    Der Sampler laeuft bis er via interrupt() gestoppt wird, damit die
            //    Sampling-Dauer exakt der Warmup+Messphase entspricht (M8 fix).
            List<DockerStatSample> dockerLoadSamples = Collections.synchronizedList(new ArrayList<>());
            Thread sampler = startDockerSampler(dockerLoadSamples, containerId, 1);

            // 7) First request separat messen
            //    Diese Metrik zeigt oft Cold-Path / JIT / Cache-Effekte nach Readiness.
            double firstSeconds = measureEndpointSeconds(path);

            // 8) Warmup + Messung (parametrisiert ueber MeasurementProfile)
            warmup(path, profile.warmupRequests());

            // 9) Messphase: Latenzen sammeln + Gesamtzeit messen
            long measureStart = System.nanoTime();
            List<Double> latenciesSeconds;
            if (profile.concurrency() <= 1) {
                latenciesSeconds = measureManySequential(path, profile.measureRequests(), profile.sleepBetweenRequestsMs());
            } else {
                latenciesSeconds = measureManyConcurrent(path, profile.measureRequests(), profile.concurrency(), profile.sleepBetweenRequestsMs());
            }
            long measureEnd = System.nanoTime();
            double totalMeasureTimeSeconds = (measureEnd - measureStart) / 1_000_000_000.0;
            double throughputReqPerSec = latenciesSeconds.size() / totalMeasureTimeSeconds;

            // 10) Sampler stoppen (laeuft seit Schritt 6 bis interrupt)
            //     Interrupt signalisiert dem Sampler, dass die Lastphase vorbei ist.
            sampler.interrupt();
            try {
                sampler.join(Duration.ofSeconds(15).toMillis());
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }

            // 11) Post samples
            //     Nachlaufwerte: interessant fuer "memory not returning" oder Nach-GC-Verhalten.
            List<DockerStatSample> dockerPostSamples = dockerStatsSamples(containerId, 3, 1);

            // 12) GC-Log erfassen und parsen
            //     Vollstaendige Container-Logs enthalten die GC-Ausgaben (-Xlog:gc*:stdout).
            //     Level 1: Rohlog als Datei speichern (fuer externe Tools wie GCViewer/GCEasy).
            //     Level 2+3: Parsen und als GcSummary + GcEvents in RunResult aufnehmen.
            GcSummary gcSummary = null;
            String gcLogPath = null;
            if (cfg.runtimeType().hasGcLogs() && containerId != null && !containerId.isBlank()) {
                try {
                    String fullLog = dockerLogsAll(containerId);

                    // Level 1: Rohlog speichern
                    Path logDir = Path.of(BenchDefaults.OUTPUT_DIR, BenchDefaults.GC_LOGS_SUBDIR);
                    Files.createDirectories(logDir);
                    String filename = cfg.name() + "-rep" + repetition + ".log";
                    Path logFile = logDir.resolve(filename);
                    Files.writeString(logFile, fullLog);
                    gcLogPath = logFile.toString();

                    // Level 2+3: GC-Events parsen (Parser abhaengig vom RuntimeType)
                    double totalRuntimeSeconds = readinessMs / 1000.0 + totalMeasureTimeSeconds;
                    gcSummary = switch (cfg.runtimeType()) {
                        case HOTSPOT -> GcLogParser.parse(fullLog, totalRuntimeSeconds);
                        case OPENJ9  -> OpenJ9GcLogParser.parse(fullLog, totalRuntimeSeconds);
                        case NATIVE  -> null; // sollte nicht erreicht werden (hasGcLogs() == false)
                    };

                    if (gcSummary != null) {
                        System.err.printf("[GC] %s rep%d: %d pauses (%.1f ms total, max=%.1f ms, overhead=%.2f%%)%n",
                                cfg.name(), repetition,
                                gcSummary.gcCount(), gcSummary.totalPauseMs(),
                                gcSummary.maxPauseMs(), gcSummary.gcOverheadPercent());
                    }
                } catch (Exception e) {
                    System.err.println("[WARN] GC log capture failed for " + cfg.name() + ": " + e.getMessage());
                }
            }

            // 13) Ergebnis bauen
            //     Speichert sowohl die Rohdaten (Latenzen + Docker-Samples) als auch Metadaten,
            //     damit spaetere Auswertung/Exports vollstaendig sind.
            RunResult result = new RunResult(
                    new RunResult.Metadata(cfg.name(), cfg.dockerImage(),
                            effectiveJavaToolOptions, readinessCheckUsed, startupLogSnippet,
                            scenario, workloadN, path, profile, repetition,
                            cfg.category(), cfg.runtimeModel()),
                    new RunResult.Timing(readinessMs, firstSeconds, latenciesSeconds,
                            totalMeasureTimeSeconds, throughputReqPerSec),
                    new RunResult.Docker(dockerIdleSamples, dockerLoadSamples,
                            dockerPostSamples, gcSummary, gcLogPath));

            return result;

        } catch (Exception e) {
            if (containerId != null && !containerId.isBlank()) {
                // Bei Fehlern: Log-Auszug ausgeben, BEVOR der Container entfernt wird.
                try {
                    String logs = dockerLogsTail(containerId, 200);
                    System.err.println("=== docker logs (tail 200) for " + containerId + " ===");
                    System.err.println(logs);
                } catch (Exception ignored) {}
            }
            throw e;
        } finally {
            // Cleanup-Strategie: Container IMMER stoppen + entfernen.
            // Zombie-Container blockieren sonst Port 8080 fuer nachfolgende Runs.
            // Log-Auszug wird im catch-Block VOR dem Cleanup ausgegeben.
            if (containerId != null && !containerId.isBlank()) {
                System.err.println("Cleaning up container: " + containerId);
                try {
                    exec(List.of("docker", "rm", "-f", containerId), Duration.ofSeconds(10));
                } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Entfernt alle Container, die den angegebenen Host-Port belegen.
     *
     * Wird VOR docker run aufgerufen, um Zombie-Container aus vorherigen
     * fehlgeschlagenen Runs zu bereinigen. Ohne diesen Schritt blockieren
     * "Created"-State-Container den Port dauerhaft.
     *
     * @param port Host-Port (z.B. 8080)
     */
    private void killContainerOnPort(int port) {
        try {
            // docker ps -a: findet ALLE Container (laufend, Created, Exited),
            // die mit diesem Port-Mapping erstellt wurden.
            // -a ist wichtig, weil "Created"-State-Container den Port blockieren,
            // aber von "docker ps" (ohne -a) nicht angezeigt werden.
            ProcessRunner.ExecResult all = exec(List.of(
                    "docker", "ps", "-aq", "--filter", "publish=" + port
            ), Duration.ofSeconds(10));

            if (all.exitCode() != 0 || all.stdout().trim().isEmpty()) return;

            for (String id : all.stdout().trim().split("\\s+")) {
                if (!id.isBlank()) {
                    System.err.println("WARNING: Removing stale container " + id + " on port " + port);
                    exec(List.of("docker", "rm", "-f", id), Duration.ofSeconds(10));
                }
            }
        } catch (Exception e) {
            System.err.println("WARNING: Pre-run port cleanup failed: " + e.getMessage());
        }
    }

    /**
     * Startet den Container fuer die gegebene Konfiguration.
     *
     * Setzt CPU- und Memory-Limits fuer bessere Vergleichbarkeit.
     * Fuer JVM-Images wird JAVA_TOOL_OPTIONS gesetzt, fuer native nicht.
     *
     * Vor dem Start wird geprueft, ob bereits ein Container den Ziel-Port
     * belegt, und dieser ggf. entfernt (Zombie-Schutz).
     *
     * @param cfg Benchmark-Konfiguration
     * @param port Host-Port fuer das Port-Mapping
     * @param effectiveJavaToolOptions Flags als String (oder null bei native)
     * @return Container-ID
     * @throws IOException wenn der Prozess nicht gestartet werden kann
     * @throws InterruptedException wenn der Aufruf unterbrochen wird
     */
    private String dockerRun(BenchmarkConfig cfg, int port, String effectiveJavaToolOptions)
            throws IOException, InterruptedException {

        // Zombie-Schutz: bestehende Container auf diesem Port entfernen
        killContainerOnPort(port);

        List<String> cmd = new ArrayList<>(List.of(
                "docker", "run",
                "-d",
                "-p", port + ":" + BenchDefaults.CONTAINER_PORT,
                "--cpus", BenchDefaults.DOCKER_CPUS,
                "--memory", BenchDefaults.DOCKER_MEMORY,
                "--memory-swap", BenchDefaults.DOCKER_MEMORY_SWAP   // gleich wie --memory => Swap deaktiviert (Kubernetes-Verhalten)
        ));

        // EBICS: Hostname des Host-Rechners im Container auflösbar machen,
        // damit der EK-Client den TravicLink-Server via https://<host>:7070/ erreichen kann.
        // host-gateway ist ein Docker-Feature (seit 20.10) das auf die Host-IP zeigt.
        if (scenario.isEbics()) {
            cmd.add("--add-host");
            cmd.add(BenchDefaults.HOST_NAME + ":host-gateway");
        }

        // JAVA_TOOL_OPTIONS setzen (nur fuer JVM, nicht fuer native)
        if (cfg.runtimeType().isJvm()) {
            String javaToolOptions = (effectiveJavaToolOptions == null) ? "" : effectiveJavaToolOptions.trim();
            if (!javaToolOptions.isBlank()) {
                cmd.add("-e");
                cmd.add("JAVA_TOOL_OPTIONS=" + javaToolOptions);
            }
        }

        cmd.add(cfg.dockerImage());

        ProcessRunner.ExecResult res = exec(cmd, Duration.ofSeconds(30));
        if (res.exitCode() != 0) {
            // Docker erzeugt manchmal einen Container im "Created"-State, auch wenn
            // docker run fehlschlaegt (z.B. bei Port-Konflikt, exit 125).
            // Diesen Container hier best-effort entfernen, damit er nicht als Zombie bleibt.
            String partialId = res.stdout().trim();
            if (!partialId.isBlank()) {
                System.err.println("docker run failed — removing partial container: " + partialId);
                try {
                    exec(List.of("docker", "rm", "-f", partialId), Duration.ofSeconds(10));
                } catch (Exception ignored) {}
            }

            throw new RuntimeException(
                    "docker run failed (exit " + res.exitCode() + ")\n" +
                            "cmd: " + formatCmd(cmd) + "\n" +
                            "stderr: " + res.stderr() + "\n" +
                            "stdout: " + res.stdout()
            );
        }

        String id = res.stdout().trim();
        if (id.isEmpty()) {
            throw new RuntimeException("docker run returned empty container id. stdout=" + res.stdout() + ", stderr=" + res.stderr());
        }
        return id;
    }

    /**
     * Maximale Anzahl Versuche fuer einen einzelnen HTTP-Request.
     *
     * Retries fangen "stale keep-alive connection"-Fehler ab, bei denen
     * der Server die Verbindung geschlossen hat, bevor der Client
     * seinen naechsten Request senden konnte (IOException:
     * "HTTP/1.1 header parser received no bytes").
     */
    private static final int MAX_REQUEST_ATTEMPTS = 3;

    /**
     * Misst die Latenz eines HTTP-GET Requests in Sekunden.
     *
     * Bei IOException (z.B. stale keep-alive connection) wird der Request
     * bis zu {@link #MAX_REQUEST_ATTEMPTS} Mal wiederholt, mit linearem
     * Backoff (500ms * Versuchsnummer).
     *
     * @param path Pfad inkl. Query (z.B. "/json?n=200000")
     * @return Latenz in Sekunden
     * @throws Exception wenn der Request nach allen Versuchen fehlschlaegt oder Status != 200 ist
     */
    private double measureEndpointSeconds(String path) throws Exception {
        URI uri = URI.create("http://localhost:" + port + path);

        // EBICS-Uploads sind langsam (~1-3s pro Upload), daher laengeres Timeout
        Duration requestTimeout = scenario.isEbics()
                ? Duration.ofSeconds(120) : Duration.ofSeconds(5);

        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .GET()
                .build();

        for (int attempt = 1; attempt <= MAX_REQUEST_ATTEMPTS; attempt++) {
            try {
                long t0 = System.nanoTime();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                long t1 = System.nanoTime();

                if (resp.statusCode() != 200) {
                    throw new RuntimeException("GET " + path + " failed: " + resp.statusCode() + " body=" + resp.body());
                }

                return (t1 - t0) / 1_000_000_000.0;
            } catch (IOException e) {
                if (attempt == MAX_REQUEST_ATTEMPTS) throw e;
                long backoffMs = 500L * attempt;
                System.err.printf("[RETRY] %s attempt %d/%d failed: %s — retrying in %dms%n",
                        cfg.name(), attempt, MAX_REQUEST_ATTEMPTS, e.getMessage(), backoffMs);
                Thread.sleep(backoffMs);
            }
        }
        throw new AssertionError("unreachable");
    }

    /**
     * Fuehrt Warmup-Requests aus, um Messungen zu stabilisieren.
     *
     * @param path Workload-Pfad
     * @param times Anzahl Warmup-Requests
     * @throws Exception wenn ein Request fehlschlaegt
     */
    private void warmup(String path, int times) throws Exception {
        for (int i = 0; i < times; i++) {
            measureEndpointSeconds(path);
        }
    }

    /**
     * Fuehrt Mess-Requests sequentiell aus und sammelt die Latenzen.
     * Optional mit Sleep zwischen den Requests fuer konstante Last.
     *
     * @param path Workload-Pfad
     * @param times Anzahl Requests
     * @param sleepMs Pause zwischen Requests in ms (0 = kein Sleep)
     * @return Latenzen in Sekunden
     * @throws Exception wenn ein Request fehlschlaegt
     */
    private List<Double> measureManySequential(String path, int times, long sleepMs) throws Exception {
        List<Double> res = new ArrayList<>(times);
        for (int i = 0; i < times; i++) {
            res.add(measureEndpointSeconds(path));
            if (sleepMs > 0 && i < times - 1) {
                Thread.sleep(sleepMs);
            }
        }
        return res;
    }

    /**
     * Fuehrt Mess-Requests mit Concurrency aus.
     * Verteilt die Requests auf einen Thread-Pool und sammelt alle Latenzen.
     *
     * @param path Workload-Pfad
     * @param totalRequests Gesamtzahl der Requests
     * @param concurrency Anzahl paralleler Threads
     * @param sleepMs Pause zwischen Requests pro Thread in ms (0 = kein Sleep)
     * @return Latenzen in Sekunden (unsortiert, in Reihenfolge der Fertigstellung)
     * @throws Exception wenn Requests fehlschlagen
     */
    private List<Double> measureManyConcurrent(String path, int totalRequests, int concurrency, long sleepMs) throws Exception {
        List<Double> results = Collections.synchronizedList(new ArrayList<>(totalRequests));
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        AtomicInteger errors = new AtomicInteger(0);

        try {
            List<Future<?>> futures = new ArrayList<>(totalRequests);
            for (int i = 0; i < totalRequests; i++) {
                final int idx = i;
                futures.add(pool.submit(() -> {
                    try {
                        double latency = measureEndpointSeconds(path);
                        results.add(latency);
                        if (sleepMs > 0) {
                            Thread.sleep(sleepMs);
                        }
                    } catch (Exception e) {
                        errors.incrementAndGet();
                        System.err.println("Concurrent request " + idx + " failed: " + e.getMessage());
                    }
                }));
            }

            // Auf alle Futures warten
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdown();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }

        if (errors.get() > 0) {
            System.err.println("WARNING: " + errors.get() + " of " + totalRequests + " concurrent requests failed");
        }

        return results;
    }

    /**
     * Liest einen einzelnen docker stats Snapshot.
     *
     * @param containerId Container-ID
     * @return DockerStatSample
     * @throws IOException wenn der Aufruf fehlschlaegt
     * @throws InterruptedException wenn der Aufruf unterbrochen wird
     */
    private DockerStatSample dockerStatsNoStream(String containerId) throws IOException, InterruptedException {
        String format = "{{.CPUPerc}}|{{.MemUsage}}|{{.MemPerc}}|{{.NetIO}}|{{.BlockIO}}|{{.PIDs}}";
        ProcessRunner.ExecResult r = exec(List.of(
                "docker", "stats", "--no-stream", "--format", format, containerId
        ), Duration.ofSeconds(10));
        if (r.exitCode() != 0) throw new RuntimeException("docker stats failed: " + r.stderr());

        String line = r.stdout().trim();
        return DockerStatSample.parse(line);
    }

    /**
     * Erfasst mehrere docker stats Snapshots in festen Intervallen.
     *
     * @param containerId Container-ID
     * @param samples Anzahl Snapshots
     * @param sleepSeconds Pause zwischen Snapshots in Sekunden
     * @return Liste der Samples
     * @throws IOException wenn der Aufruf fehlschlaegt
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
     * Prueft, ob ein Container noch im Status "running" ist.
     *
     * Nutzt "docker inspect --format={{.State.Running}}" fuer einen schnellen Check.
     * Wird waehrend des Readiness-Pollings aufgerufen, um fruehzeitig zu erkennen,
     * wenn der Container bereits beendet ist (z.B. Crash beim Start).
     *
     * @param containerId Container-ID
     * @return true wenn der Container laeuft, false wenn beendet oder Pruefung fehlschlaegt
     */
    private static boolean isContainerRunning(String containerId) {
        try {
            ProcessRunner.ExecResult res = exec(List.of(
                    "docker", "inspect", "--format", "{{.State.Running}}", containerId
            ), Duration.ofSeconds(5));
            return res.exitCode() == 0 && res.stdout().trim().equalsIgnoreCase("true");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Baut den JAVA_TOOL_OPTIONS-String fuer diesen Run.
     *
     * Injiziert automatisch GC-Logging abhaengig vom Laufzeittyp:
     * <ul>
     *   <li>HOTSPOT: {@code -Xlog:gc*:stdout} (Unified Logging, JEP 158)</li>
     *   <li>OPENJ9: {@code -verbose:gc} (XML-basiertes Format auf stderr)</li>
     *   <li>NATIVE: kein JAVA_TOOL_OPTIONS (kein JVM-Overhead)</li>
     * </ul>
     *
     * @param cfg Benchmark-Konfiguration
     * @return Flags als String oder null bei native
     */
    static String computeEffectiveJavaToolOptions(BenchmarkConfig cfg) {
        if (!cfg.runtimeType().isJvm()) return null;

        List<String> args = new ArrayList<>();

        // GC-Logging: Runtime-spezifisch
        switch (cfg.runtimeType()) {
            case HOTSPOT -> args.add("-Xlog:gc*:stdout");
            case OPENJ9  -> args.add("-verbose:gc");
            default -> { /* NATIVE — nicht erreichbar wegen isJvm()-Guard */ }
        }

        if (cfg.jvmArgs() != null) args.addAll(cfg.jvmArgs());

        return String.join(" ", args);
    }

    /**
     * Holt die letzten Zeilen aus docker logs.
     *
     * @param containerId Container-ID
     * @param tailLines Anzahl Zeilen
     * @return Log-Auszug oder Fehlermeldung
     * @throws IOException wenn der Aufruf fehlschlaegt
     * @throws InterruptedException wenn der Aufruf unterbrochen wird
     */
    private String dockerLogsTail(String containerId, int tailLines) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add("docker");
        cmd.add("logs");
        cmd.add("--tail");
        cmd.add(Integer.toString(tailLines));
        cmd.add(containerId);

        ProcessRunner.ExecResult res = exec(cmd, Duration.ofSeconds(10));
        if (res.exitCode() != 0) {
            return "docker logs failed: " + res.stderr();
        }
        return res.stdout();
    }

    /**
     * Holt die kompletten Container-Logs (stdout + stderr).
     * Wird fuer die GC-Log-Erfassung nach dem Benchmark-Run verwendet.
     *
     * @param containerId Container-ID
     * @return vollstaendiger Log-Output
     * @throws IOException wenn der Aufruf fehlschlaegt
     * @throws InterruptedException wenn der Aufruf unterbrochen wird
     */
    private String dockerLogsAll(String containerId) throws IOException, InterruptedException {
        List<String> cmd = List.of("docker", "logs", containerId);
        ProcessRunner.ExecResult res = exec(cmd, Duration.ofSeconds(60));
        if (res.exitCode() != 0) {
            return "docker logs failed: " + res.stderr();
        }
        // GC-Logs gehen teilweise nach stderr (docker mischt stdout/stderr)
        return res.stdout() + (res.stderr().isBlank() ? "" : "\n" + res.stderr());
    }

    /**
     * Kuerzt einen String auf maximal maxChars.
     *
     * @param s Eingabe
     * @param maxChars maximale Laenge
     * @return ggf. gekuerzter String
     */
    private static String trimSnippet(String s, int maxChars) {
        if (s == null) return null;
        if (s.length() <= maxChars) return s;
        return s.substring(0, maxChars) + "\n... (truncated)";
    }

    /**
     * Formatiert eine Kommandoliste fuer lesbare Log-Ausgabe.
     *
     * Argumente, die Leerzeichen oder Sonderzeichen enthalten, werden in
     * Anfuehrungszeichen eingeschlossen. Damit ist in der Fehlerausgabe
     * eindeutig erkennbar, welche Teile ein einzelnes OS-Argument sind
     * (z.B. JAVA_TOOL_OPTIONS=-Xlog:gc*:stdout -XX:+UseParallelGC).
     *
     * @param cmd Kommando als Liste
     * @return formatierter String
     */
    private static String formatCmd(List<String> cmd) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cmd.size(); i++) {
            if (i > 0) sb.append(' ');
            String arg = cmd.get(i);
            if (arg.contains(" ") || arg.contains("*") || arg.contains("\"")) {
                sb.append('"').append(arg.replace("\"", "\\\"")).append('"');
            } else {
                sb.append(arg);
            }
        }
        return sb.toString();
    }

    /**
     * Startet einen Thread, der waehrend der Lastphase docker stats sammelt.
     *
     * Der Thread arbeitet best-effort: Fehler werden ignoriert.
     * Der Thread ist daemon, damit er das Beenden des Programms nicht blockiert.
     *
     * @param target Liste fuer die Samples
     * @param containerId Container-ID
     * @param samples Anzahl Snapshots
     * @param sleepSeconds Pause zwischen Snapshots in Sekunden
     * @return gestarteter Thread
     */
    /**
     * Startet einen Daemon-Thread, der docker stats samplet bis er per interrupt() gestoppt wird.
     * Die Samples werden in die uebergebene (synchronisierte) Liste geschrieben.
     *
     * @param target Zielliste fuer die Samples (muss thread-safe sein)
     * @param containerId Docker-Container-ID
     * @param sleepSeconds Pause zwischen Samples in Sekunden
     * @return gestarteter Daemon-Thread (stoppen via interrupt + join)
     */
    private Thread startDockerSampler(List<DockerStatSample> target, String containerId, int sleepSeconds) {
        Thread t = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    target.add(dockerStatsNoStream(containerId));
                    Thread.sleep(sleepSeconds * 1000L);
                }
            } catch (InterruptedException e) {
                // Normaler Abbruch: Lastphase beendet
                Thread.currentThread().interrupt();
            } catch (Exception ignored) {
                // best-effort: docker stats kann bei Container-Stop fehlschlagen
            }
        }, "docker-stats-sampler");
        t.setDaemon(true);
        t.start();
        return t;
    }

    /**
     * Delegiert an {@link ProcessRunner#exec(List, Duration)}.
     * Haelt die interne API stabil (private static, kurzer Name).
     */
    private static ProcessRunner.ExecResult exec(List<String> cmd, Duration timeout)
            throws IOException, InterruptedException {
        return ProcessRunner.exec(cmd, timeout);
    }
}
