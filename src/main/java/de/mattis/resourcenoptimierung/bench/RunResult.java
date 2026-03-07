package de.mattis.resourcenoptimierung.bench;

import java.util.List;

/**
 * Ergebnis eines einzelnen Benchmark-Runs.
 *
 * Ein RunResult gehoert zu:
 * - genau einer BenchmarkConfig,
 * - genau einem BenchmarkScenario,
 * - einer festen Workload-Groesse n,
 * - einer Wiederholung (repetition) innerhalb eines Durchlaufs.
 *
 * Das Objekt enthaelt Metadaten (welche Konfiguration wurde getestet),
 * Timing-Metriken (Readiness, First Request, Latenzen, Gesamtzeit, Durchsatz)
 * und optionale Docker-Stat-Samples fuer CPU/Memory.
 *
 * @param configName Name der Konfiguration (z.B. "baseline", "coops-off")
 * @param dockerImage verwendetes Docker-Image
 * @param readinessMs Zeit von docker run bis "ready" in Millisekunden (inkl. Container-Startup)
 * @param firstSeconds Dauer des ersten Requests nach Readiness in Sekunden
 * @param latenciesSeconds gemessene Request-Latenzen in Sekunden
 * @param totalMeasureTimeSeconds Gesamtdauer der Messphase (alle measureRequests) in Sekunden
 * @param throughputReqPerSec Durchsatz: measureRequests / totalMeasureTimeSeconds
 * @param effectiveJavaToolOptions effektiv gesetzte JVM-Flags (JAVA_TOOL_OPTIONS), null bei native
 * @param readinessCheckUsed welcher Readiness-Check erfolgreich war
 * @param startupLogSnippet optionaler Log-Auszug direkt nach dem Start (Debug/Proof), kann null sein
 * @param scenario Benchmark-Szenario (json, alloc, ebics-upload)
 * @param workloadN Workload-Groesse n
 * @param workloadPath verwendeter Pfad inkl. Query (z.B. "/json?n=200000")
 * @param measurementProfile verwendetes Messprofil (Warmup/Messung/Concurrency/Sleep)
 * @param dockerIdleSamples Docker-Stats kurz nach Readiness (vor Last)
 * @param dockerLoadSamples Docker-Stats waehrend der Lastphase
 * @param dockerPostSamples Docker-Stats nach der Lastphase
 * @param repetition 1-basierte Wiederholungsnummer (1..N)
 * @param gcSummary aggregierte GC-Kennzahlen (null bei Native-Images oder wenn kein GC-Log vorhanden)
 * @param gcLogPath Pfad zur gespeicherten GC-Log-Datei (null wenn nicht gespeichert)
 */
public record RunResult(
        String configName,
        String dockerImage,
        long readinessMs,
        double firstSeconds,
        List<Double> latenciesSeconds,
        double totalMeasureTimeSeconds,
        double throughputReqPerSec,
        String effectiveJavaToolOptions,
        ReadinessCheckUsed readinessCheckUsed,
        String startupLogSnippet,
        BenchmarkScenario scenario,
        int workloadN,
        String workloadPath,
        MeasurementProfile measurementProfile,
        List<DockerStatSample> dockerIdleSamples,
        List<DockerStatSample> dockerLoadSamples,
        List<DockerStatSample> dockerPostSamples,
        int repetition,
        GcSummary gcSummary,
        String gcLogPath) { }
