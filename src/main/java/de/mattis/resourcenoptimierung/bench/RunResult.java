package de.mattis.resourcenoptimierung.bench;

import java.util.List;

/**
 * Ergebnis eines einzelnen Benchmark-Runs.
 *
 * <p>Ein RunResult gehoert zu genau einer BenchmarkConfig, einem BenchmarkScenario,
 * einer festen Workload-Groesse n und einer Wiederholung (repetition).
 *
 * <p>Die Felder sind in drei Sub-Records gruppiert:
 * <ul>
 *   <li>{@link Metadata} — Konfiguration, Szenario, Messprofil, Identitaet</li>
 *   <li>{@link Timing} — Readiness, First Request, Latenzen, Durchsatz</li>
 *   <li>{@link Docker} — Docker-Stat-Samples (IDLE/LOAD/POST), GC-Zusammenfassung</li>
 * </ul>
 *
 * <p>Fuer Abwaertskompatibilitaet stehen eine statische Factory-Methode {@link #of} mit allen
 * 22 Parametern sowie delegierende Accessor-Methoden zur Verfuegung, sodass bestehender Code
 * wie {@code result.configName()} unveraendert funktioniert.
 */
public record RunResult(Metadata metadata, Timing timing, Docker docker) {

    // ======================== Sub-Records ========================

    /**
     * Metadaten: Welche Konfiguration wurde mit welchem Szenario getestet?
     *
     * @param configName Name der Konfiguration (z.B. "baseline", "coops-off")
     * @param dockerImage verwendetes Docker-Image
     * @param effectiveJavaToolOptions effektiv gesetzte JVM-Flags (JAVA_TOOL_OPTIONS), null bei native
     * @param readinessCheckUsed welcher Readiness-Check erfolgreich war
     * @param startupLogSnippet optionaler Log-Auszug direkt nach dem Start (Debug/Proof), kann null sein
     * @param scenario Benchmark-Szenario (json, alloc, ebics-upload)
     * @param workloadN Workload-Groesse n
     * @param workloadPath verwendeter Pfad inkl. Query (z.B. "/json?n=200000")
     * @param measurementProfile verwendetes Messprofil (Warmup/Messung/Concurrency/Sleep)
     * @param repetition 1-basierte Wiederholungsnummer (1..N)
     * @param category Analyse-Kategorie (z.B. "GC-Vergleich", "Laufzeitprofil") — aus BenchmarkConfig
     * @param runtimeModel Laufzeitmodell (z.B. "HotSpot", "OpenJ9", "CDS") — aus BenchmarkConfig
     */
    public record Metadata(
            String configName,
            String dockerImage,
            String effectiveJavaToolOptions,
            ReadinessCheckUsed readinessCheckUsed,
            String startupLogSnippet,
            BenchmarkScenario scenario,
            int workloadN,
            String workloadPath,
            MeasurementProfile measurementProfile,
            int repetition,
            String category,
            String runtimeModel) { }

    /**
     * Timing-Metriken: Performance-Messwerte des Benchmark-Runs.
     *
     * @param readinessMs Zeit von docker run bis "ready" in Millisekunden (inkl. Container-Startup)
     * @param firstSeconds Dauer des ersten Requests nach Readiness in Sekunden
     * @param latenciesSeconds gemessene Request-Latenzen in Sekunden
     * @param totalMeasureTimeSeconds Gesamtdauer der Messphase (alle measureRequests) in Sekunden
     * @param throughputReqPerSec Durchsatz: measureRequests / totalMeasureTimeSeconds
     * @param wallClockSeconds Gesamte Wall-Clock-Dauer des Runs in Sekunden (Container-Start bis Cleanup-Ende).
     *                         Dient als Grundlage fuer den {@link DurationEstimator}.
     */
    public record Timing(
            long readinessMs,
            double firstSeconds,
            List<Double> latenciesSeconds,
            double totalMeasureTimeSeconds,
            double throughputReqPerSec,
            double wallClockSeconds) { }

    /**
     * Docker-Metriken: Ressourcenverbrauch und GC-Zusammenfassung.
     *
     * @param dockerIdleSamples Docker-Stats kurz nach Readiness (vor Last)
     * @param dockerLoadSamples Docker-Stats waehrend der Lastphase
     * @param dockerPostSamples Docker-Stats nach der Lastphase
     * @param gcSummary aggregierte GC-Kennzahlen (null bei Native-Images oder wenn kein GC-Log vorhanden)
     * @param gcLogPath Pfad zur gespeicherten GC-Log-Datei (null wenn nicht gespeichert)
     */
    public record Docker(
            List<DockerStatSample> dockerIdleSamples,
            List<DockerStatSample> dockerLoadSamples,
            List<DockerStatSample> dockerPostSamples,
            GcSummary gcSummary,
            String gcLogPath) { }

    // ======================== Factory ========================

    /**
     * Erstellt ein RunResult aus allen 22 Einzelfeldern (Abwaertskompatibilitaet).
     *
     * <p>Diese Factory-Methode erlaubt es, bestehenden Code unveraendert zu lassen,
     * der bisher den flachen 22-Parameter-Konstruktor verwendet hat.
     */
    public static RunResult of(
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
            String gcLogPath,
            String category,
            String runtimeModel) {

        return new RunResult(
                new Metadata(configName, dockerImage, effectiveJavaToolOptions, readinessCheckUsed,
                        startupLogSnippet, scenario, workloadN, workloadPath,
                        measurementProfile, repetition, category, runtimeModel),
                new Timing(readinessMs, firstSeconds, latenciesSeconds,
                        totalMeasureTimeSeconds, throughputReqPerSec, 0.0),
                new Docker(dockerIdleSamples, dockerLoadSamples, dockerPostSamples,
                        gcSummary, gcLogPath));
    }

    // ======================== Delegating Accessors ========================

    /** Name der Konfiguration (z.B. "baseline", "coops-off"). */
    public String configName()                   { return metadata.configName(); }
    /** Verwendetes Docker-Image. */
    public String dockerImage()                  { return metadata.dockerImage(); }
    /** Effektiv gesetzte JVM-Flags (JAVA_TOOL_OPTIONS), null bei native. */
    public String effectiveJavaToolOptions()      { return metadata.effectiveJavaToolOptions(); }
    /** Welcher Readiness-Check erfolgreich war. */
    public ReadinessCheckUsed readinessCheckUsed(){ return metadata.readinessCheckUsed(); }
    /** Optionaler Log-Auszug direkt nach dem Start, kann null sein. */
    public String startupLogSnippet()            { return metadata.startupLogSnippet(); }
    /** Benchmark-Szenario (json, alloc, ebics-upload). */
    public BenchmarkScenario scenario()          { return metadata.scenario(); }
    /** Workload-Groesse n. */
    public int workloadN()                       { return metadata.workloadN(); }
    /** Verwendeter Pfad inkl. Query (z.B. "/json?n=200000"). */
    public String workloadPath()                 { return metadata.workloadPath(); }
    /** Verwendetes Messprofil (Warmup/Messung/Concurrency/Sleep). */
    public MeasurementProfile measurementProfile(){ return metadata.measurementProfile(); }
    /** 1-basierte Wiederholungsnummer (1..N). */
    public int repetition()                      { return metadata.repetition(); }
    /** Analyse-Kategorie — aus BenchmarkConfig. */
    public String category()                     { return metadata.category(); }
    /** Laufzeitmodell (z.B. "HotSpot", "OpenJ9", "CDS") — aus BenchmarkConfig. */
    public String runtimeModel()                 { return metadata.runtimeModel(); }

    /** Zeit von docker run bis "ready" in Millisekunden. */
    public long readinessMs()                    { return timing.readinessMs(); }
    /** Dauer des ersten Requests nach Readiness in Sekunden. */
    public double firstSeconds()                 { return timing.firstSeconds(); }
    /** Gemessene Request-Latenzen in Sekunden. */
    public List<Double> latenciesSeconds()       { return timing.latenciesSeconds(); }
    /** Gesamtdauer der Messphase in Sekunden. */
    public double totalMeasureTimeSeconds()      { return timing.totalMeasureTimeSeconds(); }
    /** Durchsatz: measureRequests / totalMeasureTimeSeconds. */
    public double throughputReqPerSec()          { return timing.throughputReqPerSec(); }
    /** Gesamte Wall-Clock-Dauer des Runs in Sekunden (Container-Start bis Cleanup-Ende). */
    public double wallClockSeconds()             { return timing.wallClockSeconds(); }

    /** Docker-Stats kurz nach Readiness (vor Last). */
    public List<DockerStatSample> dockerIdleSamples()  { return docker.dockerIdleSamples(); }
    /** Docker-Stats waehrend der Lastphase. */
    public List<DockerStatSample> dockerLoadSamples()  { return docker.dockerLoadSamples(); }
    /** Docker-Stats nach der Lastphase. */
    public List<DockerStatSample> dockerPostSamples()  { return docker.dockerPostSamples(); }
    /** Aggregierte GC-Kennzahlen (null bei Native-Images). */
    public GcSummary gcSummary()                 { return docker.gcSummary(); }
    /** Pfad zur gespeicherten GC-Log-Datei (null wenn nicht gespeichert). */
    public String gcLogPath()                    { return docker.gcLogPath(); }
}
