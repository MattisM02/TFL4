package de.mattis.resourcenoptimierung.bench;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class SingleRun {

    private final BenchmarkConfig cfg;
    private final int port;
    private final Duration readinessTimeout;

    private String containerId;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    public SingleRun(BenchmarkConfig cfg) {
        this(cfg, 8080, Duration.ofSeconds(120));
    }

    public SingleRun(BenchmarkConfig cfg, int port, Duration readinessTimeout) {
        this.cfg = cfg;
        this.port = port;
        this.readinessTimeout = readinessTimeout;
    }

    public RunResult execute() throws Exception {
        try {
            containerId = dockerRun(cfg, port);

            long readinessMs = measureReadinessMs();
            double firstJsonSeconds = measureEndpointSeconds("/json");
            // Optional: warmup
            warmup("/json", 5);

            List<Double> jsonLatencies = measureManySeconds("/json", 20);

            List<DockerStatSample> stats = dockerStatsSamples(containerId, 5, 1);

            // Optional: nochmal stats am Ende (z.B. nach /json Runs)
            DockerStatSample end = dockerStatsNoStream(containerId);

            return new RunResult(
                    cfg.name(),
                    cfg.dockerImage(),
                    readinessMs,
                    firstJsonSeconds,
                    jsonLatencies,
                    stats,
                    end
            );
        } finally {
            // best-effort cleanup
            if (containerId != null && !containerId.isBlank()) {
                dockerStop(containerId);
                dockerRm(containerId);
            }
        }
    }

    private String dockerRun(BenchmarkConfig cfg, int port) throws IOException, InterruptedException {
        // Beispiel: docker run -d --rm -p 8080:8080 --name <unique> <image> ...
        // JVM Args kannst du entweder als ENV übergeben oder als CMD, je nach Image.
        // Ich nehme hier: Image startet schon korrekt, cfg.jvmArgs() nur als Platzhalter.

        List<String> cmd = new ArrayList<>();
        cmd.add("docker");
        cmd.add("run");
        cmd.add("-d");
        cmd.add("-p");
        cmd.add(port + ":8080");

        // Optional: Ressourcenlimits (falls du die brauchst)
        // cmd.add("--memory"); cmd.add("768m");
        // cmd.add("--cpus"); cmd.add("1");

        // falls du einen festen Container-Namen willst:
        // cmd.add("--name"); cmd.add("demo-" + cfg.name() + "-" + System.currentTimeMillis());

        cmd.add(cfg.dockerImage());

        // Wenn dein Image ein ENTRYPOINT hat, das JVM args akzeptiert, könntest du sie hier anhängen:
        // cmd.addAll(cfg.jvmArgs());

        ExecResult res = exec(cmd, Duration.ofSeconds(30));
        if (res.exitCode != 0) {
            throw new RuntimeException("docker run failed: " + res.stderr);
        }
        return res.stdout.trim(); // container id
    }

    private long measureReadinessMs() throws Exception {
        URI uri = URI.create("http://localhost:" + port + "/actuator/health/readiness");

        Instant start = Instant.now();
        Instant deadline = start.plus(readinessTimeout);

        while (Instant.now().isBefore(deadline)) {
            try {
                HttpRequest req = HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofSeconds(2))
                        .GET()
                        .build();

                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                // readiness = UP (typisch JSON: {"status":"UP",...})
                if (resp.statusCode() == 200 && resp.body() != null && resp.body().contains("\"UP\"")) {
                    return Duration.between(start, Instant.now()).toMillis();
                }
            } catch (Exception ignored) {
                // app ist evtl noch nicht ready / port noch nicht offen
            }
            Thread.sleep(200);
        }
        throw new RuntimeException("Readiness timeout after " + readinessTimeout.toSeconds() + "s");
    }

    private double measureEndpointSeconds(String path) throws Exception {
        URI uri = URI.create("http://localhost:" + port + path);

        Instant start = Instant.now();
        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("GET " + path + " failed: " + resp.statusCode() + " body=" + resp.body());
        }
        long nanos = Duration.between(start, Instant.now()).toNanos();
        return nanos / 1_000_000_000.0;
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

    private record ExecResult(int exitCode, String stdout, String stderr) {}
}
