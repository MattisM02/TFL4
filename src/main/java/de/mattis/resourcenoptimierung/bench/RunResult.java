package de.mattis.resourcenoptimierung.bench;

import java.util.List;

/**
 * Ergebnis eines einzelnen Benchmark-Runs.
 *
 * Ein RunResult gehört zu:
 * - genau einer BenchmarkConfig,
 * - genau einem BenchmarkScenario,
 * - einer festen Workload-Größe n.
 *
 * Das Objekt enthält Metadaten (welche Konfiguration wurde getestet),
 * Timing-Metriken (Readiness, First Request, Latenzen) und optionale
 * Docker-Stat-Samples für CPU/Memory.
 *
 * @param configName Name der Konfiguration (z.B. "baseline", "coops-off")
 * @param dockerImage verwendetes Docker-Image
 * @param readinessMs Zeit bis "ready" in Millisekunden
 * @param firstSeconds Dauer des ersten Requests nach Readiness in Sekunden
 * @param latenciesSeconds gemessene Request-Latenzen in Sekunden
 * @param effectiveJavaToolOptions effektiv gesetzte JVM-Flags (JAVA_TOOL_OPTIONS), null bei native
 * @param readinessCheckUsed welcher Readiness-Check erfolgreich war
 * @param startupLogSnippet optionaler Log-Auszug direkt nach dem Start (Debug/Proof), kann null sein
 * @param scenario Benchmark-Szenario (json oder alloc)
 * @param workloadN Workload-Größe n
 * @param workloadPath verwendeter Pfad inkl. Query (z.B. "/json?n=200000")
 * @param dockerIdleSamples Docker-Stats kurz nach Readiness (vor Last)
 * @param dockerLoadSamples Docker-Stats während der Lastphase
 * @param dockerPostSamples Docker-Stats nach der Lastphase
 */
public record RunResult(
        String configName,
        String dockerImage,
        long readinessMs,
        double firstSeconds,
        List<Double> latenciesSeconds,
        String effectiveJavaToolOptions,
        ReadinessCheckUsed readinessCheckUsed,
        String startupLogSnippet,
        BenchmarkScenario scenario,
        int workloadN,
        String workloadPath,
        List<DockerStatSample> dockerIdleSamples,
        List<DockerStatSample> dockerLoadSamples,
        List<DockerStatSample> dockerPostSamples) { }

