package de.mattis.resourcenoptimierung.bench;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests fuer ConsoleSummaryPrinter: Konsole-Ausgabe pruefen.
 */
class ConsoleSummaryPrinterTest {

    private final PrintStream originalOut = System.out;
    private ByteArrayOutputStream capturedOutput;

    @BeforeEach
    void captureOutput() {
        capturedOutput = new ByteArrayOutputStream();
        System.setOut(new PrintStream(capturedOutput));
    }

    @AfterEach
    void restoreOutput() {
        System.setOut(originalOut);
    }

    private String getCaptured() {
        return capturedOutput.toString();
    }

    private RunResult sampleResult(String name, BenchmarkScenario scenario) {
        return new RunResult(
                name, "img:jvm", 1000, 0.3,
                List.of(0.010, 0.012, 0.015, 0.020, 0.025),
                2.5, 40.0,
                "-XX:-UseCompressedOops",
                ReadinessCheckUsed.ACTUATOR_READINESS,
                null, scenario, 200000, "/json?n=200000",
                MeasurementProfile.defaults(),
                List.of(), List.of(), List.of(), 1,
                null, null
        );
    }

    @Test
    void print_emptyResults_printsNoResults() {
        ConsoleSummaryPrinter.print(List.of());
        String output = getCaptured();
        assertTrue(output.contains("No results."));
    }

    @Test
    void print_nullResults_printsNoResults() {
        ConsoleSummaryPrinter.print(null);
        String output = getCaptured();
        assertTrue(output.contains("No results."));
    }

    @Test
    void print_containsBenchmarkSummaryHeader() {
        ConsoleSummaryPrinter.print(List.of(sampleResult("baseline", BenchmarkScenario.PAYLOAD_HEAVY_JSON)));
        String output = getCaptured();
        assertTrue(output.contains("=== Benchmark Summary ==="));
    }

    @Test
    void print_containsScenarioHeader() {
        ConsoleSummaryPrinter.print(List.of(sampleResult("baseline", BenchmarkScenario.PAYLOAD_HEAVY_JSON)));
        String output = getCaptured();
        assertTrue(output.contains("=== Scenario: PAYLOAD_HEAVY_JSON ==="));
    }

    @Test
    void print_containsReadinessAndFirst() {
        ConsoleSummaryPrinter.print(List.of(sampleResult("baseline", BenchmarkScenario.PAYLOAD_HEAVY_JSON)));
        String output = getCaptured();
        assertTrue(output.contains("Readiness (ms)"));
        assertTrue(output.contains("First (s)"));
    }

    @Test
    void print_containsThroughput() {
        ConsoleSummaryPrinter.print(List.of(sampleResult("baseline", BenchmarkScenario.PAYLOAD_HEAVY_JSON)));
        String output = getCaptured();
        assertTrue(output.contains("Throughput (req/s)"));
    }

    @Test
    void print_containsProfile() {
        ConsoleSummaryPrinter.print(List.of(sampleResult("baseline", BenchmarkScenario.PAYLOAD_HEAVY_JSON)));
        String output = getCaptured();
        assertTrue(output.contains("Profile:"));
        assertTrue(output.contains("warmup=200"));
        assertTrue(output.contains("measure=500"));
    }

    @Test
    void print_containsPerRunDetails() {
        ConsoleSummaryPrinter.print(List.of(sampleResult("baseline", BenchmarkScenario.PAYLOAD_HEAVY_JSON)));
        String output = getCaptured();
        assertTrue(output.contains("baseline"));
        assertTrue(output.contains("JVM"));
        assertTrue(output.contains("readiness=1000ms"));
        assertTrue(output.contains("throughput="));
        assertTrue(output.contains("totalTime="));
    }

    @Test
    void print_nativeConfig_showsNATIVE() {
        RunResult nativeResult = new RunResult(
                "native", "img:native", 200, 0.05,
                List.of(0.005, 0.006), 0.5, 400.0,
                null, ReadinessCheckUsed.ACTUATOR_HEALTH,
                null, BenchmarkScenario.PAYLOAD_HEAVY_JSON, 100, "/json?n=100",
                MeasurementProfile.defaults(),
                List.of(), List.of(), List.of(), 1,
                null, null
        );
        ConsoleSummaryPrinter.print(List.of(nativeResult));
        String output = getCaptured();
        assertTrue(output.contains("NATIVE"));
        assertTrue(output.contains("(native)"));
    }

    @Test
    void print_multipleScenarios_groupsCorrectly() {
        List<RunResult> results = List.of(
                sampleResult("baseline", BenchmarkScenario.PAYLOAD_HEAVY_JSON),
                sampleResult("baseline", BenchmarkScenario.ALLOC_HEAVY_OK)
        );
        ConsoleSummaryPrinter.print(results);
        String output = getCaptured();
        assertTrue(output.contains("PAYLOAD_HEAVY_JSON"));
        assertTrue(output.contains("ALLOC_HEAVY_OK"));
    }

    @Test
    void print_withDockerStats_showsLoadStats() {
        DockerStatSample sample = new DockerStatSample(25.5, "200MiB", "512MiB", 39.0, "1kB", "2kB", "0B", "0B", 20);
        RunResult result = new RunResult(
                "baseline", "img:jvm", 1000, 0.3,
                List.of(0.01, 0.02), 1.0, 100.0,
                "", ReadinessCheckUsed.ACTUATOR_READINESS,
                null, BenchmarkScenario.PAYLOAD_HEAVY_JSON, 100, "/json?n=100",
                MeasurementProfile.defaults(),
                List.of(sample),  // idle
                List.of(sample),  // load
                List.of(sample),  // post
                1,
                null, null
        );
        ConsoleSummaryPrinter.print(List.of(result));
        String output = getCaptured();
        assertTrue(output.contains("docker LOAD:"));
        assertTrue(output.contains("docker IDLE:"));
        assertTrue(output.contains("docker POST:"));
    }

    @Test
    void print_multipleRepetitions_showsAggregation() {
        RunResult rep1 = new RunResult(
                "baseline", "img:jvm", 1000, 0.3,
                List.of(0.010, 0.012, 0.015, 0.020, 0.025),
                2.5, 40.0,
                "-XX:-UseCompressedOops",
                ReadinessCheckUsed.ACTUATOR_READINESS,
                null, BenchmarkScenario.PAYLOAD_HEAVY_JSON, 200000, "/json?n=200000",
                MeasurementProfile.defaults(),
                List.of(), List.of(), List.of(), 1,
                null, null
        );
        RunResult rep2 = new RunResult(
                "baseline", "img:jvm", 1100, 0.35,
                List.of(0.011, 0.013, 0.016, 0.021, 0.026),
                2.6, 38.5,
                "-XX:-UseCompressedOops",
                ReadinessCheckUsed.ACTUATOR_READINESS,
                null, BenchmarkScenario.PAYLOAD_HEAVY_JSON, 200000, "/json?n=200000",
                MeasurementProfile.defaults(),
                List.of(), List.of(), List.of(), 2,
                null, null
        );
        ConsoleSummaryPrinter.print(List.of(rep1, rep2));
        String output = getCaptured();
        assertTrue(output.contains("Aggregation"));
        assertTrue(output.contains("baseline"));
        assertTrue(output.contains("n=2"));
        assertTrue(output.contains("+/-"));
    }
}
