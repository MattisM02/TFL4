package de.mattis.resourcenoptimierung.bench;

import java.util.List;
import java.util.function.Predicate;

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
) {

    /**
     * Erzeugt eine {@code GcSummary} aus einer Liste von GC-Ereignissen.
     *
     * <p>Aggregiert Pausenanzahl, Full-GC-Anzahl, Gesamtpause, maximale Pause,
     * durchschnittliche Pause, Overhead-Prozent und Peak-Heap.
     *
     * @param events              Liste der geparseten GC-Ereignisse (darf leer sein)
     * @param totalRuntimeSeconds Gesamtlaufzeit des Containers in Sekunden
     * @param isFullGc            Praedikat, das bestimmt, ob ein GC-Typ als Full-GC zaehlt
     * @return aggregierte GC-Kennzahlen, oder {@code null} wenn die Event-Liste leer ist
     */
    public static GcSummary fromEvents(List<GcEvent> events, double totalRuntimeSeconds,
                                       Predicate<String> isFullGc) {
        if (events == null || events.isEmpty()) return null;

        int gcCount = 0;
        int fullGcCount = 0;
        double totalPauseMs = 0;
        double maxPauseMs = 0;
        long peakHeapAfterGcKb = -1;

        for (GcEvent e : events) {
            if (!Double.isNaN(e.pauseMs()) && e.pauseMs() >= 0) {
                gcCount++;
                totalPauseMs += e.pauseMs();
                if (e.pauseMs() > maxPauseMs) maxPauseMs = e.pauseMs();
            }
            if (isFullGc.test(e.gcType())) {
                fullGcCount++;
            }
            if (e.heapAfterKb() > peakHeapAfterGcKb) {
                peakHeapAfterGcKb = e.heapAfterKb();
            }
        }

        double avgPauseMs = gcCount > 0 ? totalPauseMs / gcCount : 0;
        double runtimeMs = totalRuntimeSeconds * 1000.0;
        double overheadPercent = runtimeMs > 0 ? (totalPauseMs / runtimeMs) * 100.0 : 0;

        return new GcSummary(
                gcCount, fullGcCount,
                totalPauseMs, maxPauseMs, avgPauseMs,
                overheadPercent, peakHeapAfterGcKb,
                List.copyOf(events)
        );
    }
}
