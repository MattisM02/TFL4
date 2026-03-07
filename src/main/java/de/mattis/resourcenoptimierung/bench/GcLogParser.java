package de.mattis.resourcenoptimierung.bench;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser fuer JDK-GC-Logs im Unified-Logging-Format ({@code -Xlog:gc*:stdout}).
 *
 * <p>Unterstuetzte Collectors:
 * <ul>
 *   <li>G1 (Young, Mixed, Full)</li>
 *   <li>ZGC (Pause Mark Start/End, Pause Relocate Start)</li>
 *   <li>Shenandoah (Pause Init Mark, Pause Final Mark, etc.)</li>
 *   <li>Serial / Parallel (Pause Young, Pause Full)</li>
 * </ul>
 *
 * <p>Zeilen, die nicht dem erwarteten Format entsprechen, werden still uebersprungen.
 */
public final class GcLogParser {

    private GcLogParser() {}

    // ── Regex fuer Standard-Pause-Zeilen (G1, Serial, Parallel) ──────────
    //
    // Beispiele:
    //   [0.234s][info][gc] GC(0) Pause Young (Normal) (G1 Evacuation Pause) 24M->8M(256M) 3.451ms
    //   [5.012s][info][gc] GC(12) Pause Full (G1 Compaction Pause) 240M->89M(256M) 45.678ms
    //   [0.150s][info][gc] GC(0) Pause Young (Allocation Failure) 12M->4M(64M) 2.100ms
    //
    private static final Pattern PAUSE_WITH_HEAP = Pattern.compile(
            "\\[(\\d+[.,]\\d+)s].*?GC\\(\\d+\\)\\s+Pause\\s+(\\S+)"   // [ts] ... Pause <type>
          + "(?:\\s+\\(([^)]+)\\))?"                                     // optional (Normal)
          + "(?:\\s+\\(([^)]+)\\))?"                                     // optional (G1 Evacuation Pause)
          + ".*?(\\d+[BKMGT])->(\\d+[BKMGT])\\((\\d+[BKMGT])\\)"       // heap before->after(max)
          + "\\s+(\\d+[.,]\\d+)ms"                                       // pause duration
    );

    // ── Regex fuer Pause-Zeilen OHNE Heap (ZGC, Shenandoah) ─────────────
    //
    // Beispiele:
    //   [0.200s][info][gc] GC(0) Pause Mark Start 0.015ms
    //   [0.300s][info][gc] GC(0) Pause Mark End 0.010ms
    //   [0.400s][info][gc] GC(0) Pause Relocate Start 0.020ms
    //   [1.100s][info][gc] GC(1) Pause Init Mark 0.050ms
    //   [1.200s][info][gc] GC(1) Pause Final Mark 0.080ms
    //
    private static final Pattern PAUSE_NO_HEAP = Pattern.compile(
            "\\[(\\d+[.,]\\d+)s].*?GC\\(\\d+\\)\\s+Pause\\s+(.+?)\\s+(\\d+[.,]\\d+)ms"
    );

    // ── Regex fuer ZGC Heap-Info (auf separater Zeile) ───────────────────
    //
    // Beispiel:
    //   [0.500s][info][gc] GC(0) Garbage Collection (Warmup) 24M(12%)->8M(4%)
    //
    private static final Pattern ZGC_HEAP = Pattern.compile(
            "\\[(\\d+[.,]\\d+)s].*?GC\\(\\d+\\)\\s+Garbage Collection.*?(\\d+[BKMGT]).*?->(\\d+[BKMGT])"
    );

    /**
     * Parst den gesamten Docker-Log-Output und extrahiert GC-Ereignisse.
     *
     * @param dockerLog         vollstaendiger {@code docker logs}-Output (darf null/leer sein)
     * @param totalRuntimeSeconds Gesamtlaufzeit des Containers in Sekunden (fuer Overhead-Berechnung)
     * @return aggregierte GC-Kennzahlen oder {@code null} wenn keine GC-Events gefunden
     */
    public static GcSummary parse(String dockerLog, double totalRuntimeSeconds) {
        if (dockerLog == null || dockerLog.isBlank()) return null;

        List<GcEvent> events = new ArrayList<>();

        for (String line : dockerLog.split("\n")) {
            GcEvent event = parseLine(line);
            if (event != null) {
                events.add(event);
            }
        }

        if (events.isEmpty()) return null;

        // ── Aggregate berechnen ──
        int gcCount = 0;
        int fullGcCount = 0;
        double totalPauseMs = 0;
        double maxPauseMs = 0;
        long peakHeapAfterGcKb = -1;

        for (GcEvent e : events) {
            if (!Double.isNaN(e.pauseMs())) {
                gcCount++;
                totalPauseMs += e.pauseMs();
                if (e.pauseMs() > maxPauseMs) maxPauseMs = e.pauseMs();
            }
            if (isFullGc(e.gcType())) {
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

    // ── Interne Hilfsmethoden ────────────────────────────────────────────

    /**
     * Versucht, eine einzelne Log-Zeile als GC-Event zu parsen.
     * Gibt {@code null} zurueck wenn die Zeile kein GC-Pause-Event ist.
     */
    static GcEvent parseLine(String line) {
        if (line == null || !line.contains("Pause")) return null;

        // 1. Versuch: Pause mit Heap-Info (G1, Serial, Parallel)
        Matcher m = PAUSE_WITH_HEAP.matcher(line);
        if (m.find()) {
            double ts = parseDouble(m.group(1));
            String type = m.group(2);                       // Young, Full, Mixed
            String qualifier = m.group(3);                   // Normal, Allocation Failure, ...
            String cause = m.group(4);                       // G1 Evacuation Pause, ...
            long heapBefore = parseSize(m.group(5));
            long heapAfter = parseSize(m.group(6));
            long heapMax = parseSize(m.group(7));
            double pauseMs = parseDouble(m.group(8));

            String gcCause = cause != null ? cause : (qualifier != null ? qualifier : "");
            return new GcEvent(ts, type, gcCause, heapBefore, heapAfter, heapMax, pauseMs);
        }

        // 2. Versuch: Pause ohne Heap-Info (ZGC, Shenandoah)
        Matcher m2 = PAUSE_NO_HEAP.matcher(line);
        if (m2.find()) {
            double ts = parseDouble(m2.group(1));
            String rawType = m2.group(2).trim();
            double pauseMs = parseDouble(m2.group(3));

            // Typ normalisieren: "Mark Start" -> "Mark Start", "Init Mark" -> "Init Mark"
            return new GcEvent(ts, rawType, "", -1, -1, -1, pauseMs);
        }

        return null;
    }

    /**
     * Parst eine Groessenangabe wie "24M", "512K", "1G", "8192B" nach KiB.
     * Unterstuetzt B, K, M, G, T Suffixe.
     */
    static long parseSize(String s) {
        if (s == null || s.isEmpty()) return -1;

        char unit = s.charAt(s.length() - 1);
        long value = Long.parseLong(s.substring(0, s.length() - 1));

        return switch (Character.toUpperCase(unit)) {
            case 'B' -> Math.max(1, value / 1024);    // Bytes -> KiB (mindestens 1)
            case 'K' -> value;                          // bereits KiB
            case 'M' -> value * 1024;                   // MiB -> KiB
            case 'G' -> value * 1024 * 1024;            // GiB -> KiB
            case 'T' -> value * 1024 * 1024 * 1024;     // TiB -> KiB
            default  -> value;                           // Fallback: als KiB interpretieren
        };
    }

    /**
     * Prueft ob der GC-Typ ein Full-GC ist.
     * Full GCs sind teuer und sollten im Normalbetrieb nicht auftreten.
     */
    private static boolean isFullGc(String gcType) {
        if (gcType == null) return false;
        String lower = gcType.toLowerCase();
        return lower.contains("full");
    }

    /**
     * Parst einen Double-Wert der sowohl Punkt als auch Komma als
     * Dezimaltrenner enthalten kann.
     */
    private static double parseDouble(String s) {
        if (s == null) return Double.NaN;
        return Double.parseDouble(s.replace(',', '.'));
    }
}
