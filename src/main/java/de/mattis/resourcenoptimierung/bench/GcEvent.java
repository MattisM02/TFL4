package de.mattis.resourcenoptimierung.bench;

/**
 * Einzelnes GC-Ereignis, extrahiert aus {@code -Xlog:gc*:stdout}.
 *
 * <p>Jede Zeile der Form
 * {@code [0.234s][info][gc] GC(0) Pause Young (Normal) (G1 Evacuation Pause) 24M->8M(256M) 3.451ms}
 * wird in ein {@code GcEvent} ueberfuehrt.
 *
 * <p>Fuer nebenläufige GC-Phasen (z.B. ZGC, Shenandoah) koennen Heap-Werte
 * {@code -1} sein, wenn sie nicht auf der Pause-Zeile stehen.
 *
 * @param timestampSeconds Zeitstempel relativ zum JVM-Start (Sekunden)
 * @param gcType           Art der Pause: "Young", "Mixed", "Full", "Mark Start" etc.
 * @param gcCause          Ursache in Klammern, z.B. "G1 Evacuation Pause" (kann leer sein)
 * @param heapBeforeKb     Heap vor GC in KiB (-1 wenn nicht verfuegbar)
 * @param heapAfterKb      Heap nach GC in KiB (-1 wenn nicht verfuegbar)
 * @param heapMaxKb        Maximaler Heap in KiB (-1 wenn nicht verfuegbar)
 * @param pauseMs          Pausendauer in Millisekunden (NaN fuer rein nebenläufige Phasen)
 */
public record GcEvent(
        double timestampSeconds,
        String gcType,
        String gcCause,
        long heapBeforeKb,
        long heapAfterKb,
        long heapMaxKb,
        double pauseMs
) {}
