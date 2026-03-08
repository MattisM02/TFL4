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
 *   <li>ZGC / Generational ZGC (JDK 25) — Pause on {@code [gc,phases]} with
 *       generation prefix {@code Y:}, {@code y:}, {@code O:}; heap on
 *       {@code [gc]} via {@code Major/Minor Collection} summary lines</li>
 *   <li>Shenandoah (Pause Init Mark, Pause Final Mark, etc.) — heap on
 *       {@code Concurrent cleanup} lines</li>
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
    // JDK 25 Generational ZGC uses [gc,phases] tag and has a generation prefix:
    //   [0.347s][info][gc,phases   ] GC(0) Y: Pause Mark Start (Major) 0.044ms
    //   [3.556s][info][gc,phases   ] GC(3) y: Pause Mark Start 0.017ms
    //   [0.428s][info][gc,phases   ] GC(0) O: Pause Mark End 0.015ms
    //
    // Shenandoah uses [gc] tag without prefix:
    //   [0.931s][info][gc          ] GC(0) Pause Init Mark (unload classes) 0.064ms
    //   [0.939s][info][gc          ] GC(0) Pause Final Mark (unload classes) 0.180ms
    //
    // Old non-generational ZGC (also matches):
    //   [0.200s][info][gc] GC(0) Pause Mark Start 0.015ms
    //
    private static final Pattern PAUSE_NO_HEAP = Pattern.compile(
            "\\[(\\d+[.,]\\d+)s]"                                        // [timestamp]
          + ".*?GC\\(\\d+\\)"                                            // GC(N)
          + "\\s+(?:[YyO]:\\s+)?"                                        // optional generation prefix Y:/y:/O:
          + "Pause\\s+(.+?)"                                             // Pause <type...>
          + "\\s+(\\d+[.,]\\d+)ms"                                       // pause duration in ms
    );

    // ── Regex fuer ZGC Heap-Info (Major/Minor Collection summary) ────────
    //
    // JDK 25 Generational ZGC — on [gc] tag:
    //   [0.432s][info][gc          ] GC(0) Major Collection (Warmup) 18M(9%)->8M(4%) 0.085s
    //   [3.750s][info][gc          ] GC(3) Minor Collection (Allocation Rate) 158M(82%)->32M(17%) 0.193s
    //
    // Duration is in SECONDS (not ms). Heap sizes have percentage suffix like 18M(9%).
    //
    private static final Pattern ZGC_HEAP = Pattern.compile(
            "\\[(\\d+[.,]\\d+)s]"                                        // [timestamp]
          + ".*?GC\\(\\d+\\)\\s+"
          + "(Major|Minor) Collection"                                    // collection type
          + ".*?(\\d+)[BKMGT]\\(\\d+%\\)"                               // heap before (size only, ignore %)
          + "->(\\d+)[BKMGT]\\(\\d+%\\)"                                 // heap after  (size only, ignore %)
          + "\\s+(\\d+[.,]\\d+)s"                                        // duration in seconds
    );

    // ── Regex fuer ZGC Heap mit Einheit-Capture ──────────────────────────
    // Wir brauchen die Einheit, um korrekt nach KiB zu konvertieren.
    private static final Pattern ZGC_HEAP_FULL = Pattern.compile(
            "\\[(\\d+[.,]\\d+)s]"                                        // [timestamp]
          + ".*?GC\\(\\d+\\)\\s+"
          + "(Major|Minor) Collection"                                    // collection type
          + ".*?(\\d+)([BKMGT])\\(\\d+%\\)"                             // heap before + unit
          + "->(\\d+)([BKMGT])\\(\\d+%\\)"                              // heap after  + unit
          + "\\s+(\\d+[.,]\\d+)s"                                        // duration in seconds
    );

    // ── Regex fuer Shenandoah Heap-Info (Concurrent cleanup) ─────────────
    //
    // Beispiel:
    //   [1.011s][info][gc          ] GC(0) Concurrent cleanup (unload classes) 47M->6M(53M) 0.065ms
    //
    private static final Pattern SHENANDOAH_CLEANUP = Pattern.compile(
            "\\[(\\d+[.,]\\d+)s]"                                        // [timestamp]
          + ".*?GC\\(\\d+\\)\\s+Concurrent cleanup"                      // Concurrent cleanup
          + ".*?(\\d+)([BKMGT])->(\\d+)([BKMGT])\\((\\d+)([BKMGT])\\)"  // before->after(max)
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
                continue;
            }
            // Try heap-only lines (ZGC summary, Shenandoah cleanup)
            GcEvent heapEvent = parseHeapLine(line);
            if (heapEvent != null) {
                events.add(heapEvent);
            }
        }

        if (events.isEmpty()) return null;

        return GcSummary.fromEvents(events, totalRuntimeSeconds, GcLogParser::isFullGc);
    }

    // ── Interne Hilfsmethoden ────────────────────────────────────────────

    /**
     * Versucht, eine einzelne Log-Zeile als GC-Pause-Event zu parsen.
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

            // Entferne trailing qualifier in Klammern, z.B. "(unload classes)" oder "(Major)"
            String cleanType = rawType.replaceAll("\\s*\\([^)]*\\)\\s*", " ").trim();

            return new GcEvent(ts, cleanType, "", -1, -1, -1, pauseMs);
        }

        return null;
    }

    /**
     * Versucht, eine Zeile als Heap-Info-Event zu parsen (kein Pause-Event).
     * Betrifft ZGC Major/Minor Collection summary lines und Shenandoah Concurrent cleanup.
     * Diese Events haben {@code pauseMs = NaN} und dienen nur der Heap-Daten-Erfassung.
     */
    static GcEvent parseHeapLine(String line) {
        if (line == null) return null;

        // 1. ZGC Major/Minor Collection summary
        if (line.contains("Collection")) {
            Matcher m = ZGC_HEAP_FULL.matcher(line);
            if (m.find()) {
                double ts = parseDouble(m.group(1));
                String collType = m.group(2);            // Major or Minor
                long heapBefore = parseSizeWithUnit(m.group(3), m.group(4));
                long heapAfter = parseSizeWithUnit(m.group(5), m.group(6));
                // ZGC doesn't report max heap on summary line — use -1
                return new GcEvent(ts, collType + " Collection", "",
                        heapBefore, heapAfter, -1, Double.NaN);
            }
        }

        // 2. Shenandoah Concurrent cleanup with heap info
        if (line.contains("Concurrent cleanup")) {
            Matcher m = SHENANDOAH_CLEANUP.matcher(line);
            if (m.find()) {
                double ts = parseDouble(m.group(1));
                long heapBefore = parseSizeWithUnit(m.group(2), m.group(3));
                long heapAfter = parseSizeWithUnit(m.group(4), m.group(5));
                long heapMax = parseSizeWithUnit(m.group(6), m.group(7));
                return new GcEvent(ts, "Concurrent Cleanup", "",
                        heapBefore, heapAfter, heapMax, Double.NaN);
            }
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

        return convertToKib(value, unit);
    }

    /**
     * Parst Groesse aus separaten Wert- und Einheit-Strings (z.B. "18", "M").
     */
    static long parseSizeWithUnit(String value, String unit) {
        if (value == null || unit == null || value.isEmpty() || unit.isEmpty()) return -1;
        long val = Long.parseLong(value);
        return convertToKib(val, unit.charAt(0));
    }

    private static long convertToKib(long value, char unit) {
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
