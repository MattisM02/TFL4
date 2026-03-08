package de.mattis.resourcenoptimierung.bench;

/**
 * Laufzeittyp einer Benchmark-Konfiguration.
 *
 * <p>Bestimmt:
 * <ul>
 *   <li>welches GC-Log-Format erwartet wird (HotSpot Unified Logging vs. OpenJ9 verbose:gc XML),</li>
 *   <li>welche JVM-Flags in {@code JAVA_TOOL_OPTIONS} injiziert werden,</li>
 *   <li>ob ueberhaupt JVM-Argumente gesetzt werden (Native Images benoetigen keine).</li>
 * </ul>
 *
 * <p>Die drei Typen decken die gaengigen JVM-Laufzeitmodelle ab:
 * <ul>
 *   <li>{@link #HOTSPOT} — Oracle HotSpot / Eclipse Temurin (OpenJDK-basiert)</li>
 *   <li>{@link #OPENJ9} — Eclipse OpenJ9 / IBM Semeru Runtimes</li>
 *   <li>{@link #NATIVE} — GraalVM Native Image (kein JVM-Overhead, kein GC-Log)</li>
 * </ul>
 */
public enum RuntimeType {

    /**
     * HotSpot-basierte JVM (Temurin, Oracle JDK, etc.).
     * GC-Logging via {@code -Xlog:gc*:stdout} (Unified Logging, JEP 158).
     */
    HOTSPOT,

    /**
     * Eclipse OpenJ9 / IBM Semeru Runtimes.
     * GC-Logging via {@code -verbose:gc} (XML-basiertes Format).
     */
    OPENJ9,

    /**
     * GraalVM Native Image.
     * Kein JVM-Overhead, kein GC-Log, keine JAVA_TOOL_OPTIONS.
     */
    NATIVE;

    /**
     * Prueft, ob dieser Laufzeittyp eine JVM ist (d.h. JAVA_TOOL_OPTIONS sinnvoll).
     *
     * @return {@code true} fuer HOTSPOT und OPENJ9, {@code false} fuer NATIVE
     */
    public boolean isJvm() {
        return this != NATIVE;
    }

    /**
     * Prueft, ob dieser Laufzeittyp GC-Logs erzeugt, die geparst werden koennen.
     *
     * @return {@code true} fuer HOTSPOT und OPENJ9, {@code false} fuer NATIVE
     */
    public boolean hasGcLogs() {
        return this != NATIVE;
    }
}
