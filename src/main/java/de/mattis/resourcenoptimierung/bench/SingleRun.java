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
 * Führt einen einzelnen Benchmark-Run für genau eine {@link BenchmarkConfig} aus.
 *
 * <p>Ein {@code SingleRun} kapselt die komplette Ablauf- und Messlogik für <b>einen</b> Run:</p>
 * <ol>
 *   <li>Docker-Container starten (inkl. JVM-Flags via {@code JAVA_TOOL_OPTIONS})</li>
 *   <li>Readiness abwarten (mit Fallback-Strategie)</li>
 *   <li>Workload ausführen (Warmup + Messphase)</li>
 *   <li>Docker-Stats in Phasen sammeln (IDLE / LOAD / POST)</li>
 *   <li>Alles in einem {@link RunResult} speichern</li>
 * </ol>
 *
 * <p>Wichtig: Diese Klasse „macht die Arbeit“ im Benchmarking:
 * sie erzeugt die HTTP-Last gegen die laufende Spring-App im Container.
 * Die eigentliche Workload-Implementierung liegt serverseitig im DemoController
 * (z. B. Endpoints {@code /json} und {@code /alloc}).</p>
 *
 * <p>Typische Erzeugung: {@link BenchmarkRunner} erstellt pro {@link BenchmarkConfig} einen {@code SingleRun}.</p>
 */
public class SingleRun {

    /**
     * Konfiguration, die in diesem Run getestet wird (Image + Flags).
     */
    private final BenchmarkConfig cfg;

    /**
     * Host-Port, auf den der Container-Port 8080 gemappt wird (z. B. {@code 8080:8080}).
     */
    private final int port;

    /**
     * Maximale Zeit, die wir auf „Service ist ready“ warten (inkl. Fallback-Checks).
     */
    private final Duration readinessTimeout;

    /**
     * Docker Container-ID (wird nach {@code docker run} gesetzt).
     *
     * <p>Wird u. a. für docker stats, docker logs und cleanup verwendet.</p>
     */
    private String containerId;

    /**
     * Workload-Szenario, das für diesen Run ausgeführt wird (z. B. JSON-heavy oder alloc-heavy).
     */
    private final BenchmarkScenario scenario;

    /**
     * Workload-Größe {@code n} (wird an den Endpoint als Query-Parameter übergeben).
     */
    private final int workloadN;

    /**
     * Convenience-Konstruktor mit Default-Port 8080 und 120s Readiness-Timeout.
     *
     * @param cfg       Benchmark-Konfiguration (Image + JVM-Args)
     * @param scenario  Workload-Szenario
     * @param workloadN Workload-Größe {@code n}
     */
    public SingleRun(BenchmarkConfig cfg, BenchmarkScenario scenario, int workloadN) {
        this(cfg, scenario, workloadN, 8080, Duration.ofSeconds(120));
    }


    /**
     * Voller Konstruktor, erlaubt Anpassung von Port und Readiness-Timeout.
     *
     * @param cfg             Benchmark-Konfiguration (Image + JVM-Args)
     * @param scenario        Workload-Szenario
     * @param workloadN       Workload-Größe {@code n}
     * @param port            Host-Port für Port-Mapping (hostPort:8080)
     * @param readinessTimeout maximales Zeitfenster bis der Service als „ready“ gilt
     */
    public SingleRun(BenchmarkConfig cfg, BenchmarkScenario scenario, int workloadN, int port, Duration readinessTimeout) {
        this.cfg = cfg;
        this.scenario = scenario;
        this.workloadN = workloadN;
        this.port = port;
        this.readinessTimeout = readinessTimeout;
    }

    /**
     * Ermittelt den konkreten Workload-Pfad für diesen Run (inkl. Query-Parameter).
     *
     * <p>Beispiele:</p>
     * <ul>
     *   <li>{@code PAYLOAD_HEAVY_JSON} → {@code /json?n=200000}</li>
     *   <li>{@code ALLOC_HEAVY_OK} → {@code /alloc?n=10000000}</li>
     * </ul>
     *
     * <p>Dieser String wird sowohl für die Messung genutzt als auch im {@link RunResult}
     * gespeichert (Reproduzierbarkeit).</p>
     *
     * @return Pfad inklusive {@code n}-Parameter
     */
    private String workloadPath() {
        return switch (scenario) {
            case PAYLOAD_HEAVY_JSON -> "/json?n=" + workloadN;
            case ALLOC_HEAVY_OK -> "/alloc?n=" + workloadN;
        };
    }

    /**
     * HTTP-Client für alle Requests innerhalb dieses Runs (Readiness und Messung).
     *
     * <p>Kurze Connect-Timeouts sind wichtig, da während des Container-Starts
     * viele Verbindungsversuche fehlschlagen können (Port noch nicht offen).</p>
     */
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    /**
     * Führt den kompletten Benchmark-Run aus und gibt ein {@link RunResult} zurück.
     *
     * <p>Diese Methode ist der zentrale Ablauf eines Runs. Sie übernimmt:</p>
     * <ol>
     *   <li>Berechnen/Protokollieren der effektiven JVM-Flags (JAVA_TOOL_OPTIONS)</li>
     *   <li>Starten des Docker-Containers</li>
     *   <li>Optionales Einsammeln eines Log-Snippets als „Proof“/Debug-Hilfe</li>
     *   <li>Readiness abwarten (mit Fallback-Strategie)</li>
     *   <li>Workload ausführen (First Request separat, dann Warmup, dann Messphase)</li>
     *   <li>Docker-Stats in Phasen sammeln (IDLE, LOAD, POST)</li>
     *   <li>Alles in einem {@link RunResult} bündeln</li>
     * </ol>
     *
     * <p><b>Fehlerverhalten</b></p>
     * <ul>
     *   <li>Wenn ein Fehler auftritt, werden (best-effort) die letzten Container-Logs ausgegeben.</li>
     *   <li>Bei Erfolg wird der Container gestoppt und gelöscht (Cleanup).</li>
     *   <li>Bei Fehler wird der Container absichtlich behalten, damit man ihn inspizieren kann.</li>
     * </ul>
     *
     * @return Run-Ergebnis mit Timings, Latenzserie, Flags, Readiness-Info und Docker-Samples
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
     * Startet einen Docker-Container für die gegebene {@link BenchmarkConfig} und gibt die Container-ID zurück.
     *
     * <p>Der Container wird im Hintergrund gestartet ({@code -d}) und Port {@code 8080} im Container
     * wird auf den angegebenen Host-Port gemappt ({@code -p hostPort:8080}).</p>
     *
     * <p>Reproduzierbarkeit/Isolation:</p>
     * <ul>
     *   <li>{@code --cpus 1} begrenzt den Container auf 1 CPU-Core (reduziert Scheduling-Varianz).</li>
     *   <li>{@code --memory 768m} begrenzt den Container-Speicher (macht Memory-Metriken vergleichbarer).</li>
     * </ul>
     *
     * <p>JVM-Flags:</p>
     * <ul>
     *   <li>Für JVM-Images werden Flags über die Umgebungsvariable {@code JAVA_TOOL_OPTIONS} gesetzt.</li>
     *   <li>Für Native Images ist das nicht anwendbar (daher keine Flags).</li>
     * </ul>
     *
     * @param cfg Benchmark-Konfiguration (Image + evtl. JVM-Args)
     * @param port Host-Port, auf den {@code 8080} im Container gemappt wird
     * @param effectiveJavaToolOptions finaler, zusammengebauter Flags-String (z. B. "-XX:-UseCompressedOops")
     * @return Container-ID (stdout von {@code docker run})
     * @throws IOException wenn das Starten des docker-Prozesses fehlschlägt
     * @throws InterruptedException wenn der Thread während des docker-Aufrufs unterbrochen wird
     * @throws RuntimeException wenn {@code docker run} mit Exit-Code != 0 endet
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
     * Misst die End-to-End-Latenz eines HTTP-GET Requests in Sekunden.
     *
     * <p>Gemessen wird mit {@link System#nanoTime()} (präzise, monotone Uhr).</p>
     *
     * <p>Diese Messung ist bewusst "E2E":</p>
     * <ul>
     *   <li>Request-Aufbau + Netzwerk-Overhead</li>
     *   <li>Server-Bearbeitung</li>
     *   <li>Antwort lesen (Body wird komplett als String eingelesen)</li>
     * </ul>
     *
     * <p>Damit misst es im JSON-Szenario tatsächlich „Payload komplett angekommen“,
     * was für payload-heavy Benchmarks sinnvoll ist.</p>
     *
     * @param path Pfad relativ zur Base-URL, z. B. {@code "/json?n=200000"}
     * @return Latenz in Sekunden
     * @throws Exception wenn HTTP-Request fehlschlägt oder Status != 200 ist
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
     * <p>Warmup reduziert typischerweise Varianz durch:</p>
     * <ul>
     *   <li>JIT-Compilation / Hot paths (bei JVM)</li>
     *   <li>Caches (z. B. JSON-Serializer, HTTP keep-alive)</li>
     * </ul>
     *
     * @param path Workload-Endpoint, z. B. {@code "/json?n=..."}
     * @param times Anzahl Warmup-Requests
     * @throws Exception wenn ein Request fehlschlägt
     */
    private void warmup(String path, int times) throws Exception {
        for (int i = 0; i < times; i++) {
            measureEndpointSeconds(path);
        }
    }

    /**
     * Führt {@code times} Mess-Requests aus und sammelt die Latenzen in Sekunden.
     *
     * <p>Die Rückgabe ist eine Rohdaten-Serie, aus der später Kennzahlen wie median/p95/p99
     * berechnet werden (siehe {@link ConsoleSummaryPrinter} und {@link ResultExporters}).</p>
     *
     * @param path Workload-Endpoint, z. B. {@code "/alloc?n=..."}
     * @param times Anzahl der Mess-Requests
     * @return Liste der Latenzen in Sekunden
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
     * Liest genau einen Docker-Stats-Snapshot ohne Streaming aus.
     *
     * <p>Genutzt wird:</p>
     * <pre>
     * docker stats --no-stream --format "{{.CPUPerc}}|{{.MemUsage}}|{{.MemPerc}}|{{.NetIO}}|{{.BlockIO}}|{{.PIDs}}"
     * </pre>
     *
     * <p>Die Zeile wird anschließend durch {@link DockerStatSample#parse(String)} geparst.</p>
     *
     * @param containerId Container-ID, für die Stats abgefragt werden
     * @return ein {@link DockerStatSample} als Snapshot
     * @throws IOException wenn der docker-Prozess nicht gestartet werden kann
     * @throws InterruptedException wenn der Thread während des docker-Aufrufs unterbrochen wird
     * @throws RuntimeException wenn {@code docker stats} fehlschlägt (Exit-Code != 0)
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
     * Erfasst mehrere Docker-Stats-Snapshots in festen Intervallen.
     *
     * <p>Beispiel: {@code samples=3, sleepSeconds=1} liefert 3 Messpunkte mit jeweils 1s Abstand.</p>
     *
     * @param containerId Container-ID
     * @param samples Anzahl Snapshots
     * @param sleepSeconds Pause zwischen Snapshots (Sekunden)
     * @return Liste der {@link DockerStatSample}-Snapshots
     * @throws IOException wenn docker-Calls fehlschlagen
     * @throws InterruptedException wenn Sleep/Prozessaufruf unterbrochen wird
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
     * Stoppt einen Docker-Container (best-effort).
     *
     * <p>Fehler werden bewusst ignoriert, da Cleanup nicht den gesamten Run
     * crashen soll (z. B. Container bereits gestoppt).</p>
     *
     * @param containerId Container-ID
     */
    private void dockerStop(String containerId) {
        try {
            exec(List.of("docker", "stop", containerId), Duration.ofSeconds(10));
        } catch (Exception ignored) {}
    }

    /**
     * Entfernt einen Docker-Container (best-effort).
     *
     * <p>Wird typischerweise nach einem erfolgreichen Run im Cleanup aufgerufen.
     * {@code -f} erzwingt die Entfernung auch dann, wenn der Container noch läuft.</p>
     *
     * <p>Fehler werden bewusst ignoriert, da Cleanup nicht den gesamten Benchmark
     * crashen soll (z. B. Container wurde bereits entfernt).</p>
     *
     * @param containerId Container-ID
     */
    private void dockerRm(String containerId) {
        try {
            exec(List.of("docker", "rm", "-f", containerId), Duration.ofSeconds(10));
        } catch (Exception ignored) {}
    }

    /**
     * Führt einen externen Prozess aus (z. B. docker CLI) und sammelt stdout/stderr.
     *
     * <p>Diese Methode ist der zentrale Wrapper für alle Shell-Calls im Bench
     * (docker run / logs / stats / stop / rm etc.).</p>
     *
     * <p>Timeout-Verhalten:</p>
     * <ul>
     *   <li>Wenn der Prozess nicht innerhalb von {@code timeout} beendet ist, wird er
     *       per {@link Process#destroyForcibly()} abgebrochen.</li>
     *   <li>Dann wird eine {@link RuntimeException} geworfen, damit der Run nicht „hängt“.</li>
     * </ul>
     *
     * <p>stdout/stderr werden vollständig eingelesen und als Strings zurückgegeben.</p>
     *
     * @param cmd Kommando als Liste (ProcessBuilder-Style), z. B. {@code List.of("docker","ps")}
     * @param timeout maximale Laufzeit des Prozesses
     * @return {@link ExecResult} mit Exit-Code, stdout, stderr
     * @throws IOException wenn der Prozess nicht gestartet werden kann
     * @throws InterruptedException wenn das Warten auf Prozessende unterbrochen wird
     * @throws RuntimeException wenn ein Timeout auftritt
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
     * Liest einen InputStream vollständig zeilenweise ein und gibt den Inhalt als String zurück.
     *
     * <p>Wird genutzt, um stdout/stderr von {@link #exec(List, Duration)} zu erfassen.</p>
     *
     * @param in Stream (stdout oder stderr eines Prozesses)
     * @return kompletter Stream-Inhalt als String
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
     * Baut den finalen {@code JAVA_TOOL_OPTIONS}-String für diesen Run.
     *
     * <p>Ziel: Im Benchmark soll klar sein, welche Flags wirklich gesetzt werden.
     * Deshalb wird hier der exakte String erzeugt, der später im {@link RunResult}
     * gespeichert wird und 1:1 an {@code docker run -e JAVA_TOOL_OPTIONS=...}
     * übergeben wird.</p>
     *
     * <p>Verhalten:</p>
     * <ul>
     *   <li>Für Native Images: {@code null} (JVM-Flags nicht anwendbar)</li>
     *   <li>Für JVM Images: Join aller {@link BenchmarkConfig#jvmArgs()} mit Leerzeichen</li>
     * </ul>
     *
     * @param cfg Benchmark-Konfiguration
     * @return Flags als ein String, oder {@code null} wenn native
     */
    private static String computeEffectiveJavaToolOptions(BenchmarkConfig cfg) {
        if (cfg.isNative()) return null;

        List<String> args = new ArrayList<>();
        if (cfg.jvmArgs() != null) args.addAll(cfg.jvmArgs());

        return String.join(" ", args);
    }

    /**
     * Holt die letzten {@code tailLines} Zeilen aus den Docker-Logs eines Containers.
     *
     * <p>Wird verwendet für:</p>
     * <ul>
     *   <li>„Proof“/Debug: ein kurzes Start-Log-Snippet im {@link RunResult}</li>
     *   <li>Fehlerfall: schnelle Diagnose ohne manuelles {@code docker logs}</li>
     * </ul>
     *
     * <p>Wenn {@code docker logs} fehlschlägt, wird kein Exception geworfen,
     * sondern ein erklärender String zurückgegeben (damit der Benchmark nicht deswegen stoppt).</p>
     *
     * @param containerId Container-ID
     * @param tailLines Anzahl Zeilen vom Log-Ende
     * @return Log-Auszug oder Fehlermeldung als String
     * @throws IOException wenn der docker-Prozess nicht gestartet werden kann
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
     * Kürzt einen String auf maximal {@code maxChars} Zeichen.
     *
     * <p>Wird genutzt, um Log-Snippets klein zu halten, damit:</p>
     * <ul>
     *   <li>RunResult/JSON nicht explodiert</li>
     *   <li>Konsole/Export noch lesbar bleibt</li>
     * </ul>
     *
     * @param s Eingabe-String (kann {@code null} sein)
     * @param maxChars maximale Länge
     * @return ggf. gekürzter String inkl. Hinweis „truncated“
     */
    private static String trimSnippet(String s, int maxChars) {
        if (s == null) return null;
        if (s.length() <= maxChars) return s;
        return s.substring(0, maxChars) + "\n... (truncated)";
    }

    /**
     * Startet einen Hintergrund-Thread, der Docker-Stats-Snapshots sammelt.
     *
     * <p>Motivation: Es sollen Stats während der Lastphase erfasst werden, ohne die
     * HTTP-Messung durch blockierende Docker-Calls zu stören.</p>
     *
     * <p>Der Thread arbeitet „best-effort“:</p>
     * <ul>
     *   <li>Fehler werden geschluckt (z. B. docker kurz nicht verfügbar)</li>
     *   <li>Der Thread ist {@code daemon}, damit er den Prozess nicht am Beenden hindert</li>
     * </ul>
     *
     * <p>Thread-Safety Hinweis:</p>
     * <ul>
     *   <li>{@code target} ist eine normale {@link ArrayList}. In diesem Projekt ist das ok,
     *       weil der Main-Thread erst nach {@code join()} auf die Liste zugreift.</li>
     *   <li>Wenn du in Zukunft parallel lesen/auswerten willst, nutze eine threadsichere Liste
     *       (z. B. {@code Collections.synchronizedList}).</li>
     * </ul>
     *
     * @param target Liste, in die die Samples geschrieben werden
     * @param containerId Container-ID
     * @param samples Anzahl Snapshots
     * @param sleepSeconds Pause zwischen Snapshots (Sekunden)
     * @return der gestartete Thread (damit {@code join()} möglich ist)
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
     * Ergebniscontainer für {@link #exec(List, Duration)}.
     *
     * <p>Enthält:</p>
     * <ul>
     *   <li>{@code exitCode}: Exit-Code des Prozesses</li>
     *   <li>{@code stdout}: Standardausgabe</li>
     *   <li>{@code stderr}: Fehlerausgabe</li>
     * </ul>
     *
     * <p>Dieses Record ist bewusst minimal und nur intern in {@link SingleRun} sichtbar.</p>
     *
     * @param exitCode Exit-Code des Prozesses
     * @param stdout Standardausgabe
     * @param stderr Fehlerausgabe
     */
    private record ExecResult(int exitCode, String stdout, String stderr) {}
}
