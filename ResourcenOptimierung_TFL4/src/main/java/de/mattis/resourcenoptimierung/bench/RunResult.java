package de.mattis.resourcenoptimierung.bench;

import java.util.List;

/**
 * Ergebnisdatensatz eines einzelnen Benchmark-Runs.
 *
 * <p>Ein {@code RunResult} entspricht genau <b>einer</b> ausgeführten Konfiguration
 * ({@link BenchmarkConfig}) unter genau <b>einem</b> Workload-Szenario
 * ({@link BenchmarkScenario}) und einer festen Workload-Größe {@code n}.</p>
 *
 * <p>Der Record ist bewusst „datenorientiert“ (immutable) und enthält:</p>
 * <ul>
 *   <li>Identität des Runs (Config, Image, Scenario, Workload-Parameter)</li>
 *   <li>Timing-Metriken (Readiness, First Request, Latenz-Serie)</li>
 *   <li>Flag-Transparenz (welche JVM-Flags effektiv gesetzt wurden + optional Log-Proof)</li>
 *   <li>Docker-Ressourcen-Samples in Phasen (IDLE/LOAD/POST) für CPU/Mem-Auswertung</li>
 * </ul>
 *
 * <p>Typische Nutzung:</p>
 * <ul>
 *   <li>{@link SingleRun} erstellt dieses Objekt am Ende eines Runs.</li>
 *   <li>{@link ConsoleSummaryPrinter} liest die Felder und druckt eine Zusammenfassung.</li>
 *   <li>{@link ResultExporters} exportiert die Daten als CSV/JSON.</li>
 * </ul>
 *
 *  @param configName Name der Konfiguration (z. B. "baseline", "coops-off", "coh-on").
 *                   Dient primär zur Ausgabe/Zuordnung und sollte eindeutig sein.
 *
 * @param dockerImage              Docker-Image, das für diesen Run gestartet wurde
 *                                 (z. B. {@code jvm-optim-demo:jvm} oder {@code jvm-optim-demo:native}).
 * @param readinessMs              Zeit in Millisekunden vom Start der Readiness-Messung bis der Service „ready“ war.
 *                                 Wird über {@link ReadinessProber} ermittelt (inkl. Fallbacks).
 * @param firstSeconds             Dauer in Sekunden für den ersten Request nach Readiness.
 *                                 Diese Metrik ist besonders relevant für Start-/Warm-Start-Effekte.
 * @param latenciesSeconds         Liste der gemessenen Request-Latenzen in Sekunden während der Messphase.
 *                                 Diese Werte werden typischerweise nach einem Warmup erhoben und dienen
 *                                 zur Berechnung von median/p95/p99.
 * @param effectiveJavaToolOptions Effektiv gesetzte JVM-Flags als String
 *                                 (typischerweise der Wert von {@code JAVA_TOOL_OPTIONS}).
 *                                 Für "Native Images" in der Regel {@code null}.
 * @param readinessCheckUsed       Gibt an, welcher Readiness-Mechanismus erfolgreich war
 *                                 (z. B. Actuator Readiness vs. Health vs. Fallback-Endpunkt).
 * @param startupLogSnippet        Optionaler Auszug aus den Container-Logs direkt nach dem Start.
 *                                 Dient als „Proof“/Debug-Hilfe, ob Flags wirklich aktiv waren
 *                                 oder um Startprobleme zu sehen. Kann {@code null} sein.
 * @param scenario                 Das Workload-Szenario, unter dem der Run ausgeführt wurde
 *                                 (z. B. PAYLOAD_HEAVY_JSON oder ALLOC_HEAVY_OK).
 * @param workloadN                Workload-Größe {@code n}, die an den Endpunkt übergeben wurde
 *                                 (z. B. {@code /json?n=200000} oder {@code /alloc?n=10000000}).
 * @param workloadPath             Der tatsächlich verwendete Pfad (inkl. Query) für die Messung,
 *                                 z. B. {@code "/json?n=200000"} oder {@code "/alloc?n=10000000"}.
 *                                 Wird für Reproduzierbarkeit und Export genutzt.
 * @param dockerIdleSamples        Docker-Stat-Samples der IDLE-Phase (kurz nach Readiness, vor Last).
 *                                 Dient als Vergleichsbasis für den „Ruhezustand“.
 * @param dockerLoadSamples        Docker-Stat-Samples der LOAD-Phase (während Warmup+Messung bzw. Messphase).
 *                                 Das sind die wichtigsten Samples für CPU/Mem unter Last.
 * @param dockerPostSamples        Docker-Stat-Samples der POST-Phase (kurz nach der Last).
 *                                 Hilfreich, um z. B. Speicher, der nicht zurückgeht, zu erkennen.
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

