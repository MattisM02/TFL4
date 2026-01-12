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

public class SingleRun {

    private final BenchmarkConfig cfg;
    private final int port;
    private final Duration readinessTimeout;
    private String containerId;
    private final BenchmarkScenario scenario;
    private final int workloadN;

    public SingleRun(BenchmarkConfig cfg, BenchmarkScenario scenario, int workloadN) {
        this(cfg, scenario, workloadN, 8080, Duration.ofSeconds(120));
    }

    public SingleRun(BenchmarkConfig cfg, BenchmarkScenario scenario, int workloadN, int port, Duration readinessTimeout) {
        this.cfg = cfg;
        this.scenario = scenario;
        this.workloadN = workloadN;
        this.port = port;
        this.readinessTimeout = readinessTimeout;
    }

    private String workloadPath() {
        return switch (scenario) {
            case PAYLOAD_HEAVY_JSON -> "/json?n=" + workloadN;
            case ALLOC_HEAVY_OK -> "/alloc?n=" + workloadN;
        };
    }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    public RunResult execute() throws Exception {
        boolean success = false;
        try {
            // 1) Flags berechnen (und im Result speichern)
            String effectiveJavaToolOptions = computeEffectiveJavaToolOptions(cfg);

            // 2) Container starten
            containerId = dockerRun(cfg, port, effectiveJavaToolOptions);

            // 3) Proof: kurze Start-Logs speichern (best-effort)
            String startupLogSnippet = null;
            if (!cfg.isNative()) {
                try {
                    String logs = dockerLogsTail(containerId, 200);
                    startupLogSnippet = trimSnippet(logs, 2000);
                } catch (Exception ignored) {}
            }

            // 4) Readiness (robust)
            ReadinessProber prober = new ReadinessProber();
            ReadinessProber.ReadinessResult rr =
                    prober.waitUntilReady("http://localhost:" + port, readinessTimeout);

            long readinessMs = rr.readinessMs();
            ReadinessCheckUsed readinessCheckUsed = rr.used();

            // 5) Workload bestimmen (/json?n=... oder /alloc?n=...)
            String path = workloadPath();

            // 6) Idle samples direkt nach readiness
            List<DockerStatSample> dockerIdleSamples = dockerStatsSamples(containerId, 3, 1);

            // 7) Während Load parallel samplen
            List<DockerStatSample> dockerLoadSamples = new ArrayList<>();
            Thread sampler = startDockerSampler(dockerLoadSamples, containerId, 10, 1);

            // 8) First request separat messen
            double firstSeconds = measureEndpointSeconds(path);

            // 9) Warmup + Messung
            warmup(path, 20);
            List<Double> jsonLatenciesSeconds = measureManySeconds(path, 100);

            // 10) Warten bis sampler fertig (best-effort)
            try {
                sampler.join(Duration.ofSeconds(15).toMillis());
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }

            // 11) Post samples
            List<DockerStatSample> dockerPostSamples = dockerStatsSamples(containerId, 3, 1);

            // 12) Ergebnis bauen

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
                try {
                    String logs = dockerLogsTail(containerId, 200);
                    System.err.println("=== docker logs (tail 200) ===");
                    System.err.println(logs);
                } catch (Exception ignored) {}
            }
            throw e;
        } finally {
            if (success && containerId != null && !containerId.isBlank()) {
                dockerStop(containerId);
                dockerRm(containerId);
            } else if (!success && containerId != null && !containerId.isBlank()) {
                System.err.println("Container kept for inspection: " + containerId);
            }
        }
    }

    private String dockerRun(BenchmarkConfig cfg, int port, String effectiveJavaToolOptions) throws IOException, InterruptedException {
        // docker run -d -p <port>:8080 <image>
        List<String> cmd = new ArrayList<>();
        cmd.add("docker");
        cmd.add("run");
        cmd.add("-d");
        cmd.add("-p");
        cmd.add(port + ":8080");

        // memory limit
        cmd.add("--cpus");
        cmd.add("1");
        cmd.add("--memory");
        cmd.add("768m");


        // JAVA_TOOL_OPTIONS setzen (nur wenn JVM-Args vorhanden UND nicht native)
        if (!cfg.isNative() && cfg.jvmArgs() != null && !cfg.jvmArgs().isEmpty()) {
            String joined = String.join(" ", cfg.jvmArgs());
            cmd.add("-e");
            cmd.add("JAVA_TOOL_OPTIONS=" + joined);
        }

        // JAVA_TOOL_OPTIONS setzen (nur wenn nicht native)
        if (!cfg.isNative() && effectiveJavaToolOptions != null && !effectiveJavaToolOptions.isBlank()) {
            cmd.add("-e");
            cmd.add("JAVA_TOOL_OPTIONS=" + effectiveJavaToolOptions);
        }

        cmd.add(cfg.dockerImage());

        ExecResult res = exec(cmd, Duration.ofSeconds(30));
        if (res.exitCode != 0) {
            throw new RuntimeException("docker run failed: " + res.stderr);
        }
        return res.stdout.trim(); // container id
    }

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

    private void warmup(String path, int times) throws Exception {
        for (int i = 0; i < times; i++) {
            measureEndpointSeconds(path);
        }
    }

    private List<Double> measureManySeconds(String path, int times) throws Exception {
        List<Double> res = new ArrayList<>(times);
        for (int i = 0; i < times; i++) {
            res.add(measureEndpointSeconds(path));
        }
        return res;
    }

    /**
     * Saubere Alternative zur ASCII-Table:
     * docker stats --no-stream --format "{{.CPUPerc}}|{{.MemUsage}}|{{.MemPerc}}|{{.NetIO}}|{{.BlockIO}}|{{.PIDs}}"
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

    private void dockerStop(String containerId) {
        try {
            exec(List.of("docker", "stop", containerId), Duration.ofSeconds(10));
        } catch (Exception ignored) {}
    }

    private void dockerRm(String containerId) {
        try {
            exec(List.of("docker", "rm", "-f", containerId), Duration.ofSeconds(10));
        } catch (Exception ignored) {}
    }

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
    private static String computeEffectiveJavaToolOptions(BenchmarkConfig cfg) {
        if (cfg.isNative()) return null;

        List<String> args = new ArrayList<>();
        if (cfg.jvmArgs() != null) args.addAll(cfg.jvmArgs());

        return String.join(" ", args);
    }

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

    private static String trimSnippet(String s, int maxChars) {
        if (s == null) return null;
        if (s.length() <= maxChars) return s;
        return s.substring(0, maxChars) + "\n... (truncated)";
    }

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


    private record ExecResult(int exitCode, String stdout, String stderr) {}
}
