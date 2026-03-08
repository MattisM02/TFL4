package de.mattis.resourcenoptimierung.bench;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests fuer RunResult: Record-Konstruktion und Zugriff auf neue Felder.
 */
class RunResultTest {

    @Test
    void construction_allFieldsAccessible() {
        MeasurementProfile profile = MeasurementProfile.defaults();
        List<Double> latencies = List.of(0.01, 0.02, 0.03);

        RunResult result = RunResult.of(
                "baseline",
                "img:jvm",
                1500L,
                0.5,
                latencies,
                3.0,
                33.33,
                "-XX:-UseCompressedOops",
                ReadinessCheckUsed.ACTUATOR_READINESS,
                "Started in 1.5s",
                BenchmarkScenario.PAYLOAD_HEAVY_JSON,
                200000,
                "/json?n=200000",
                profile,
                List.of(),
                List.of(),
                List.of(),
                1,
                null,
                null,
                null,
                null
        );

        assertEquals("baseline", result.configName());
        assertEquals("img:jvm", result.dockerImage());
        assertEquals(1500L, result.readinessMs());
        assertEquals(0.5, result.firstSeconds(), 0.001);
        assertEquals(3, result.latenciesSeconds().size());
        assertEquals(3.0, result.totalMeasureTimeSeconds(), 0.001);
        assertEquals(33.33, result.throughputReqPerSec(), 0.01);
        assertEquals("-XX:-UseCompressedOops", result.effectiveJavaToolOptions());
        assertEquals(ReadinessCheckUsed.ACTUATOR_READINESS, result.readinessCheckUsed());
        assertEquals("Started in 1.5s", result.startupLogSnippet());
        assertEquals(BenchmarkScenario.PAYLOAD_HEAVY_JSON, result.scenario());
        assertEquals(200000, result.workloadN());
        assertEquals("/json?n=200000", result.workloadPath());
        assertEquals(profile, result.measurementProfile());
        assertTrue(result.dockerIdleSamples().isEmpty());
        assertTrue(result.dockerLoadSamples().isEmpty());
        assertTrue(result.dockerPostSamples().isEmpty());
        assertEquals(1, result.repetition());
    }

    @Test
    void nullableFields_acceptNull() {
        MeasurementProfile profile = MeasurementProfile.defaults();
        RunResult result = RunResult.of(
                "native",
                "img:native",
                800L,
                0.1,
                List.of(0.005),
                0.5,
                200.0,
                null,  // effectiveJavaToolOptions null for native
                null,  // readinessCheckUsed
                null,  // startupLogSnippet
                BenchmarkScenario.ALLOC_HEAVY_OK,
                10000000,
                "/alloc?n=10000000",
                profile,
                null,  // dockerIdleSamples
                null,  // dockerLoadSamples
                null,  // dockerPostSamples
                1,
                null,  // gcSummary
                null,  // gcLogPath
                null,  // category
                null   // runtimeModel
        );

        assertNull(result.effectiveJavaToolOptions());
        assertNull(result.readinessCheckUsed());
        assertNull(result.startupLogSnippet());
        assertNull(result.dockerIdleSamples());
    }

    @Test
    void withGcSummary_fieldsAccessible() {
        GcSummary gc = GcSummary.fromEvents(
                List.of(
                        new GcEvent(0.5, "Young", "G1 Evacuation Pause", 24576, 8192, 262144, 3.45),
                        new GcEvent(1.2, "Young", "G1 Evacuation Pause", 30720, 10240, 262144, 2.10),
                        new GcEvent(3.0, "Full", "System.gc()", 200000, 50000, 262144, 120.5)
                ),
                5.0,
                type -> type.toLowerCase().contains("full")
        );
        assertNotNull(gc);

        RunResult result = RunResult.of(
                "gc-test", "img:jvm", 1200L, 0.3,
                List.of(0.010, 0.015, 0.012),
                1.5, 66.67,
                "-Xlog:gc*:stdout",
                ReadinessCheckUsed.ACTUATOR_READINESS, null,
                BenchmarkScenario.PAYLOAD_HEAVY_JSON, 200000, "/json?n=200000",
                MeasurementProfile.defaults(),
                List.of(), List.of(), List.of(),
                1, gc, "/tmp/gc.log", null, null
        );

        assertNotNull(result.gcSummary());
        assertEquals(3, result.gcSummary().gcCount());
        assertEquals(1, result.gcSummary().fullGcCount());
        assertTrue(result.gcSummary().totalPauseMs() > 0);
        assertTrue(result.gcSummary().maxPauseMs() > 100); // Full GC = 120.5ms
        assertEquals("/tmp/gc.log", result.gcLogPath());
        assertEquals(3, result.gcSummary().events().size());
    }

    @Test
    void recordEquality() {
        MeasurementProfile profile = MeasurementProfile.defaults();
        List<Double> lats = List.of(0.01);

        RunResult a = RunResult.of("a", "img:jvm", 100, 0.1, lats, 1.0, 100.0,
                "", ReadinessCheckUsed.ACTUATOR_HEALTH, null,
                BenchmarkScenario.PAYLOAD_HEAVY_JSON, 100, "/json?n=100",
                profile, List.of(), List.of(), List.of(), 1, null, null, null, null);

        RunResult b = RunResult.of("a", "img:jvm", 100, 0.1, lats, 1.0, 100.0,
                "", ReadinessCheckUsed.ACTUATOR_HEALTH, null,
                BenchmarkScenario.PAYLOAD_HEAVY_JSON, 100, "/json?n=100",
                profile, List.of(), List.of(), List.of(), 1, null, null, null, null);

        assertEquals(a, b);
    }
}
