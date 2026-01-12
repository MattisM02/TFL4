package de.mattis.resourcenoptimierung.bench;

import java.util.List;

/**
 * Beschreibt eine einzelne Benchmark-Konfiguration.
 *
 * <p>Eine {@code BenchmarkConfig} definiert:</p>
 * <ul>
 *   <li>wie der Run heißt (für Ausgabe, Export, Vergleich),</li>
 *   <li>welches Docker-Image gestartet wird,</li>
 *   <li>welche JVM-Argumente (Flags) für diesen Run gesetzt werden.</li>
 * </ul>
 *
 * <p>Die gleiche Anwendung kann so mit unterschiedlichen JVM-Optionen
 * (z. B. CompressedOops an/aus, Compact Object Headers) oder als Native Image
 * getestet werden.</p>
 *
 * <p>Die tatsächliche Ausführung erfolgt später im {@link SingleRun},
 * diese Klasse ist rein beschreibend (Konfigurationsdaten).</p>
 *
 * @param name        sprechender Name der Konfiguration (z. B. "baseline", "coops-off", "coh-on")
 * @param dockerImage Docker-Image, das für den Run verwendet wird
 *                    (z. B. {@code jvm-optim-demo:jvm} oder {@code jvm-optim-demo:native})
 * @param jvmArgs     Liste von JVM-Argumenten, die über {@code JAVA_TOOL_OPTIONS}
 *                    an den Container übergeben werden.
 *                    Für "Native Images" in der Regel leer oder {@code null}.
 */
public record BenchmarkConfig(
        String name,
        String dockerImage,
        List<String> jvmArgs
) {
    /**
     * Prüft, ob diese Konfiguration ein "Native Image" verwendet.
     *
     * <p>Aktuelle Heuristik:</p>
     * <ul>
     *   <li>Das Docker-Image endet auf {@code ":native"}.</li>
     * </ul>
     *
     * <p>Diese Methode wird genutzt, um:</p>
     * <ul>
     *   <li>JVM-spezifische Flags zu unterdrücken,</li>
     *   <li>Proof-Mechanismen (z. B. JVM-Logs) zu deaktivieren,</li>
     *   <li>Ausgaben korrekt als "NATIVE" vs. "JVM" zu kennzeichnen.</li>
     * </ul>
     *
     * @return {@code true}, wenn es sich um ein "Native Image" handelt,
     *         sonst {@code false}
     */
    public boolean isNative() {
        return dockerImage != null && dockerImage.endsWith(":native");
    }
}
