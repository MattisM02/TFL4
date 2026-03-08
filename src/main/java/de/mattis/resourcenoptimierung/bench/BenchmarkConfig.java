package de.mattis.resourcenoptimierung.bench;

import java.util.List;

/**
 * Beschreibt eine einzelne Benchmark-Konfiguration.
 *
 * Eine BenchmarkConfig legt fest:
 * - den Namen des Runs (fuer Ausgabe und Vergleich),
 * - welches Docker-Image gestartet wird,
 * - welche JVM-Flags verwendet werden,
 * - welcher Laufzeittyp (HotSpot, OpenJ9, Native) vorliegt,
 * - die Kategorie (z.B. "GC-Vergleich", "G1-Tuning") fuer Excel-Gruppierung,
 * - das Laufzeitmodell (z.B. "HotSpot", "OpenJ9", "CDS") fuer Excel-Filterung.
 *
 * So kann die gleiche Anwendung mit unterschiedlichen JVM-Optionen,
 * verschiedenen JVM-Implementierungen oder als GraalVM Native Image
 * verglichen werden.
 *
 * Diese Klasse enthaelt nur Konfigurationsdaten.
 * Die tatsaechliche Ausfuehrung erfolgt in SingleRun.
 *
 * @param name sprechender Name der Konfiguration (z.B. "baseline", "P01-hotspot-standard")
 * @param dockerImage Docker-Image fuer den Run (z.B. "tfl4-ek-bench:jvm" oder "...:native")
 * @param jvmArgs JVM-Argumente, die ueber JAVA_TOOL_OPTIONS gesetzt werden
 * @param runtimeType Laufzeittyp (HOTSPOT, OPENJ9, NATIVE) — bestimmt GC-Log-Format und Flag-Injection
 * @param category Analyse-Kategorie (z.B. "GC-Vergleich", "Laufzeitprofil") — fuer Excel-Gruppierung
 * @param runtimeModel Laufzeitmodell (z.B. "HotSpot", "OpenJ9", "CDS", "GraalVM JIT") — fuer Excel-Filterung
 */
public record BenchmarkConfig(
        String name,
        String dockerImage,
        List<String> jvmArgs,
        RuntimeType runtimeType,
        String category,
        String runtimeModel
) {

    /**
     * Gibt an, ob diese Konfiguration ein Native Image verwendet.
     *
     * Delegiert an {@link RuntimeType#isJvm()} — ein Native Image hat keinen JVM-Overhead.
     *
     * Die Information wird genutzt, um:
     * - JVM-spezifische Flags zu unterdruecken,
     * - JVM-spezifische Auswertungen zu vermeiden,
     * - die Ausgabe korrekt als JVM oder NATIVE zu kennzeichnen.
     *
     * @return true, wenn es sich um ein Native Image handelt
     */
    public boolean isNative() {
        return runtimeType == RuntimeType.NATIVE;
    }

    /**
     * Gibt an, ob diese Konfiguration eine OpenJ9-JVM verwendet.
     *
     * @return true, wenn OpenJ9/Semeru Runtime
     */
    public boolean isOpenJ9() {
        return runtimeType == RuntimeType.OPENJ9;
    }
}
