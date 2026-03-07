package de.mattis.resourcenoptimierung.bench;

import java.util.List;

/**
 * Aggregierte GC-Kennzahlen eines einzelnen Benchmark-Runs.
 *
 * <p>Wird aus den {@code -Xlog:gc*:stdout}-Zeilen der Container-Logs berechnet.
 * Fuer Native-Images ist kein GC-Log vorhanden – dort bleibt dieses Objekt {@code null}.
 *
 * @param gcCount             Gesamtzahl der GC-Pausen (Young + Mixed + Full)
 * @param fullGcCount         Anzahl Full-GC-Pausen (teuer, sollte 0 sein)
 * @param totalPauseMs        Summe aller Pausendauern (ms)
 * @param maxPauseMs          Laengste Einzelpause (ms) – korreliert mit p99-Latenz
 * @param avgPauseMs          Durchschnittliche Pausendauer (ms)
 * @param gcOverheadPercent   Anteil GC-Pausen an Gesamtlaufzeit (%)
 * @param peakHeapAfterGcKb   Maximaler Heap-Verbrauch nach GC (KiB) – zeigt Live-Data-Set-Groesse
 * @param events              Alle einzelnen GC-Ereignisse (fuer Timeline-Auswertung)
 */
public record GcSummary(
        int gcCount,
        int fullGcCount,
        double totalPauseMs,
        double maxPauseMs,
        double avgPauseMs,
        double gcOverheadPercent,
        long peakHeapAfterGcKb,
        List<GcEvent> events
) {}
