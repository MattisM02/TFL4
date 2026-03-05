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

        RunResult result = new RunResult(
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
                List.of()
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
    }

    @Test
    void nullableFields_acceptNull() {
        MeasurementProfile profile = MeasurementProfile.defaults();
        RunResult result = new RunResult(
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
                null   // dockerPostSamples
        );

        assertNull(result.effectiveJavaToolOptions());
        assertNull(result.readinessCheckUsed());
        assertNull(result.startupLogSnippet());
        assertNull(result.dockerIdleSamples());
    }

    @Test
    void recordEquality() {
        MeasurementProfile profile = MeasurementProfile.defaults();
        List<Double> lats = List.of(0.01);

        RunResult a = new RunResult("a", "img:jvm", 100, 0.1, lats, 1.0, 100.0,
                "", ReadinessCheckUsed.ACTUATOR_HEALTH, null,
                BenchmarkScenario.PAYLOAD_HEAVY_JSON, 100, "/json?n=100",
                profile, List.of(), List.of(), List.of());

        RunResult b = new RunResult("a", "img:jvm", 100, 0.1, lats, 1.0, 100.0,
                "", ReadinessCheckUsed.ACTUATOR_HEALTH, null,
                BenchmarkScenario.PAYLOAD_HEAVY_JSON, 100, "/json?n=100",
                profile, List.of(), List.of(), List.of());

        assertEquals(a, b);
    }
}
