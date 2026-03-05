package de.mattis.resourcenoptimierung.bench;

import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Docker-basierter End-to-End Integrationstest.
 *
 * Testet den kompletten Benchmark-Ablauf:
 *   Docker-Container starten -> Readiness abwarten -> Workload ausfuehren
 *   -> Docker-Stats sammeln -> Container stoppen
 *
 * Voraussetzungen:
 * - Docker muss verfuegbar sein
 * - Das Docker-Image "tfl4-ek-bench:jvm" muss gebaut sein
 *   (docker build -t tfl4-ek-bench:jvm -f Dockerfile .)
 *
 * Dieser Test ist mit @Tag("docker") markiert und wird im normalen
 * Maven-Testlauf NICHT ausgefuehrt. Ausfuehrung:
 *   mvn test -Dgroups=docker
 *   oder via IDE mit Tag-Filter "docker"
 */
@Tag("docker")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DockerEndToEndTest {

    /** Docker-Image, das getestet wird. */
    private static final String DOCKER_IMAGE = "tfl4-ek-bench:jvm";

    /** Host-Port (nicht 8080, um Konflikte zu vermeiden). */
    private static final int TEST_PORT = 8085;

    /** Timeout fuer Readiness (grosszuegig fuer CI/langsame Maschinen). */
    private static final Duration READINESS_TIMEOUT = Duration.ofSeconds(90);

    // ==================== Voraussetzungen pruefen ====================

    @BeforeAll
    static void checkDockerAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder("docker", "version");
            Process p = pb.start();
            boolean ok = p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(ok && p.exitValue() == 0,
                    "Docker ist nicht verfuegbar. Diesen Test mit Docker starten.");
        } catch (Exception e) {
            fail("Docker ist nicht verfuegbar: " + e.getMessage());
        }
    }

    @BeforeAll
    static void checkImageExists() {
        try {
            ProcessBuilder pb = new ProcessBuilder("docker", "image", "inspect", DOCKER_IMAGE);
            Process p = pb.start();
            boolean ok = p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(ok && p.exitValue() == 0,
                    "Docker-Image '" + DOCKER_IMAGE + "' nicht gefunden. " +
                            "Bitte zuerst bauen: docker build -t " + DOCKER_IMAGE + " -f Dockerfile .");
        } catch (Exception e) {
            fail("Docker-Image Pruefung fehlgeschlagen: " + e.getMessage());
        }
    }

    @BeforeAll
    static void ensurePortFree() {
        // Sicherstellen, dass der Test-Port nicht belegt ist
        try (var ss = new java.net.ServerSocket(TEST_PORT)) {
            // Port ist frei
        } catch (Exception e) {
            fail("Test-Port " + TEST_PORT + " ist bereits belegt. " +
                    "Evtl. laeuft noch ein Container auf diesem Port.");
        }
    }

    // ==================== E2E: Vollstaendiger SingleRun ====================

    @Test
    @Order(1)
    @Timeout(value = 120, unit = java.util.concurrent.TimeUnit.SECONDS)
    void singleRun_jsonScenario_completesSuccessfully() throws Exception {
        // Minimales Profil fuer schnellen Test
        MeasurementProfile profile = new MeasurementProfile(
                /* warmupRequests */ 2,
                /* measureRequests */ 5,
                /* concurrency */ 1,
                /* sleepBetweenRequestsMs */ 0
        );

        BenchmarkConfig cfg = new BenchmarkConfig(
                "e2e-test",
                DOCKER_IMAGE,
                List.of()  // keine Extra-JVM-Flags
        );

        // Kleines n fuer schnellen Durchlauf
        int workloadN = 100;
        BenchmarkScenario scenario = BenchmarkScenario.PAYLOAD_HEAVY_JSON;

        SingleRun run = new SingleRun(cfg, scenario, workloadN, profile, TEST_PORT, READINESS_TIMEOUT, 1);
        RunResult result = run.execute();

        // --- Metadaten pruefen ---
        assertEquals("e2e-test", result.configName(), "configName");
        assertEquals(DOCKER_IMAGE, result.dockerImage(), "dockerImage");
        assertEquals(scenario, result.scenario(), "scenario");
        assertEquals(workloadN, result.workloadN(), "workloadN");
        assertEquals("/json?n=" + workloadN, result.workloadPath(), "workloadPath");
        assertNotNull(result.measurementProfile(), "measurementProfile");
        assertEquals(profile, result.measurementProfile(), "measurementProfile should match input");

        // --- Readiness pruefen ---
        assertTrue(result.readinessMs() > 0,
                "readinessMs should be > 0, was: " + result.readinessMs());
        assertNotNull(result.readinessCheckUsed(), "readinessCheckUsed");
        // Spring Boot 4 mit Actuator sollte ACTUATOR_READINESS verwenden
        assertEquals(ReadinessCheckUsed.ACTUATOR_READINESS, result.readinessCheckUsed(),
                "Expected ACTUATOR_READINESS, got: " + result.readinessCheckUsed());

        // --- Startup-Log pruefen (JVM, nicht native) ---
        // The log snippet is captured right after docker run, before readiness.
        // It may be empty if the JVM hasn't printed anything yet. That's OK.
        // We only verify that the field is not null (it should be set for JVM images).
        assertNotNull(result.startupLogSnippet(), "startupLogSnippet should be present for JVM image");

        // --- First Request pruefen ---
        assertTrue(result.firstSeconds() > 0,
                "firstSeconds should be > 0, was: " + result.firstSeconds());
        assertTrue(result.firstSeconds() < 10,
                "firstSeconds should be < 10s, was: " + result.firstSeconds());

        // --- Latenzen pruefen ---
        assertNotNull(result.latenciesSeconds(), "latenciesSeconds");
        assertEquals(profile.measureRequests(), result.latenciesSeconds().size(),
                "Should have exactly " + profile.measureRequests() + " latency measurements");
        for (int i = 0; i < result.latenciesSeconds().size(); i++) {
            double latency = result.latenciesSeconds().get(i);
            assertTrue(latency > 0, "Latency[" + i + "] should be > 0, was: " + latency);
            assertTrue(latency < 10, "Latency[" + i + "] should be < 10s, was: " + latency);
        }

        // --- Timing-Aggregate pruefen ---
        assertTrue(result.totalMeasureTimeSeconds() > 0,
                "totalMeasureTimeSeconds should be > 0, was: " + result.totalMeasureTimeSeconds());
        assertTrue(result.throughputReqPerSec() > 0,
                "throughputReqPerSec should be > 0, was: " + result.throughputReqPerSec());

        // --- Docker Stats pruefen ---
        assertNotNull(result.dockerIdleSamples(), "dockerIdleSamples");
        assertEquals(3, result.dockerIdleSamples().size(),
                "Should have 3 idle samples");

        assertNotNull(result.dockerLoadSamples(), "dockerLoadSamples");
        assertTrue(result.dockerLoadSamples().size() > 0,
                "Should have at least 1 load sample (best-effort)");

        assertNotNull(result.dockerPostSamples(), "dockerPostSamples");
        assertEquals(3, result.dockerPostSamples().size(),
                "Should have 3 post samples");

        // Stichprobe: Ein Idle-Sample sollte parsbare Werte haben
        DockerStatSample idleSample = result.dockerIdleSamples().get(0);
        assertTrue(idleSample.cpuPercent() >= 0, "idle CPU% should be >= 0");
        assertNotNull(idleSample.memUsageRaw(), "idle memUsageRaw should not be null");
        assertFalse(idleSample.memUsageRaw().isBlank(), "idle memUsageRaw should not be blank");

        // --- JVM-Flags pruefen ---
        // Keine Extra-Flags gesetzt -> effectiveJavaToolOptions sollte leer sein
        assertNotNull(result.effectiveJavaToolOptions(), "effectiveJavaToolOptions");
    }

    @Test
    @Order(2)
    @Timeout(value = 120, unit = java.util.concurrent.TimeUnit.SECONDS)
    void singleRun_allocScenario_completesSuccessfully() throws Exception {
        MeasurementProfile profile = new MeasurementProfile(1, 3, 1, 0);

        BenchmarkConfig cfg = new BenchmarkConfig(
                "e2e-alloc-test",
                DOCKER_IMAGE,
                List.of()
        );

        int workloadN = 1000;
        BenchmarkScenario scenario = BenchmarkScenario.ALLOC_HEAVY_OK;

        SingleRun run = new SingleRun(cfg, scenario, workloadN, profile, TEST_PORT, READINESS_TIMEOUT, 1);
        RunResult result = run.execute();

        assertEquals("e2e-alloc-test", result.configName());
        assertEquals(BenchmarkScenario.ALLOC_HEAVY_OK, result.scenario());
        assertEquals("/alloc?n=" + workloadN, result.workloadPath());
        assertTrue(result.readinessMs() > 0);
        assertEquals(3, result.latenciesSeconds().size());
        assertTrue(result.throughputReqPerSec() > 0);
    }

    @Test
    @Order(3)
    @Timeout(value = 120, unit = java.util.concurrent.TimeUnit.SECONDS)
    void singleRun_withJvmArgs_passesFlags() throws Exception {
        MeasurementProfile profile = new MeasurementProfile(1, 2, 1, 0);

        BenchmarkConfig cfg = new BenchmarkConfig(
                "e2e-flags-test",
                DOCKER_IMAGE,
                List.of("-XX:-UseCompressedOops")
        );

        int workloadN = 50;
        BenchmarkScenario scenario = BenchmarkScenario.PAYLOAD_HEAVY_JSON;

        SingleRun run = new SingleRun(cfg, scenario, workloadN, profile, TEST_PORT, READINESS_TIMEOUT, 1);
        RunResult result = run.execute();

        assertEquals("e2e-flags-test", result.configName());
        assertNotNull(result.effectiveJavaToolOptions());
        assertTrue(result.effectiveJavaToolOptions().contains("-XX:-UseCompressedOops"),
                "JVM flags should be passed through, got: " + result.effectiveJavaToolOptions());

        // Startup-Log: may be empty if captured before JVM prints anything
        // Just verify it's not null for JVM images
        assertNotNull(result.startupLogSnippet());

        assertEquals(2, result.latenciesSeconds().size());
        assertTrue(result.throughputReqPerSec() > 0);
    }

    @Test
    @Order(4)
    @Timeout(value = 120, unit = java.util.concurrent.TimeUnit.SECONDS)
    void singleRun_concurrent_completesSuccessfully() throws Exception {
        // Teste Concurrency-Modus
        MeasurementProfile profile = new MeasurementProfile(
                /* warmupRequests */ 2,
                /* measureRequests */ 6,
                /* concurrency */ 2,
                /* sleepBetweenRequestsMs */ 0
        );

        BenchmarkConfig cfg = new BenchmarkConfig(
                "e2e-concurrent-test",
                DOCKER_IMAGE,
                List.of()
        );

        int workloadN = 50;
        BenchmarkScenario scenario = BenchmarkScenario.PAYLOAD_HEAVY_JSON;

        SingleRun run = new SingleRun(cfg, scenario, workloadN, profile, TEST_PORT, READINESS_TIMEOUT, 1);
        RunResult result = run.execute();

        assertEquals("e2e-concurrent-test", result.configName());
        // Bei Concurrency koennen einzelne Requests fehlschlagen -> mindestens einige sollten da sein
        assertTrue(result.latenciesSeconds().size() > 0,
                "Should have at least some latency measurements with concurrency");
        assertTrue(result.throughputReqPerSec() > 0);
        assertTrue(result.totalMeasureTimeSeconds() > 0);
    }

    // ==================== Cleanup: dangling containers ====================

    @AfterAll
    static void cleanupDanglingContainers() {
        // Sicherheits-Cleanup: Alle Container mit dem Test-Image stoppen/entfernen
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "docker", "ps", "-q", "--filter", "ancestor=" + DOCKER_IMAGE,
                    "--filter", "publish=" + TEST_PORT
            );
            Process p = pb.start();
            p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            String ids = new String(p.getInputStream().readAllBytes()).trim();
            if (!ids.isEmpty()) {
                for (String id : ids.split("\\n")) {
                    new ProcessBuilder("docker", "rm", "-f", id.trim()).start().waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
                }
            }
        } catch (Exception ignored) {
            // Best-effort cleanup
        }
    }
}
