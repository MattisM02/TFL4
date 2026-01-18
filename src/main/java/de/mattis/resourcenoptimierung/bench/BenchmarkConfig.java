package de.mattis.resourcenoptimierung.bench;

import java.util.List;

/**
 * Beschreibt eine einzelne Benchmark-Konfiguration.
 *
 * Eine BenchmarkConfig legt fest:
 * - den Namen des Runs (für Ausgabe und Vergleich),
 * - welches Docker-Image gestartet wird,
 * - welche JVM-Flags verwendet werden.
 *
 * So kann die gleiche Anwendung mit unterschiedlichen JVM-Optionen
 * oder als GraalVM Native Image verglichen werden.
 *
 * Diese Klasse enthält nur Konfigurationsdaten.
 * Die tatsächliche Ausführung erfolgt in SingleRun.
 *
 * @param name sprechender Name der Konfiguration (z.B. "baseline", "coops-off", "coh-on")
 * @param dockerImage Docker-Image für den Run (z.B. "jvm-optim-demo:jvm" oder "...:native")
 * @param jvmArgs JVM-Argumente, die über JAVA_TOOL_OPTIONS gesetzt werden
 */
public record BenchmarkConfig(
        String name,
        String dockerImage,
        List<String> jvmArgs
) {

    /**
     * Gibt an, ob diese Konfiguration ein Native Image verwendet.
     *
     * Aktuelle Heuristik:
     * - Das Docker-Image endet auf ":native".
     *
     * Die Information wird genutzt, um:
     * - JVM-spezifische Flags zu unterdrücken,
     * - JVM-spezifische Auswertungen zu vermeiden,
     * - die Ausgabe korrekt als JVM oder NATIVE zu kennzeichnen.
     *
     * @return true, wenn es sich um ein Native Image handelt
     */
    public boolean isNative() {
        return dockerImage != null && dockerImage.endsWith(":native");
    }
}
