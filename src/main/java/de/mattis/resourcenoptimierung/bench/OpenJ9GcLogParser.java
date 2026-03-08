package de.mattis.resourcenoptimierung.bench;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser fuer OpenJ9 (IBM Semeru) GC-Logs im {@code -verbose:gc}-Format.
 *
 * <p>OpenJ9 erzeugt XML-basierte GC-Logs, die sich grundlegend vom
 * HotSpot Unified Logging ({@code -Xlog:gc*}) unterscheiden.
 *
 * <h3>Unterstuetzte GC-Policies</h3>
 * <ul>
 *   <li><b>gencon</b> (Default) — generational concurrent: nursery (scavenge) + tenure (global)</li>
 *   <li><b>balanced</b> — region-based, mehrere Generationen</li>
 *   <li><b>optavgpause</b> — concurrent mark-sweep</li>
 *   <li><b>optthruput</b> — parallel mark-sweep (stop-the-world)</li>
 * </ul>
 *
 * <h3>Parsing-Strategie</h3>
 * <p>Da vollstaendiges XML-Parsing eine XML-Bibliothek erfordern wuerde und die
 * GC-Logs in docker-logs-Output mit anderem Output vermischt sein koennen,
 * verwenden wir Regex-basiertes Parsing auf den relevanten XML-Elementen:
 * <ul>
 *   <li>{@code <gc-start>} / {@code <gc-end>} — GC-Zyklus mit Typ und Dauer</li>
 *   <li>{@code <mem-info>} und {@code <mem>} — Heap-Nutzung vor/nach GC</li>
 *   <li>{@code <exclusive-start>} / {@code <exclusive-end>} — STW-Pausen</li>
 * </ul>
 *
 * <p>Nicht erkannte Zeilen werden still uebersprungen (wie bei {@link GcLogParser}).
 */
public final class OpenJ9GcLogParser {

    private OpenJ9GcLogParser() {}

    // ── Regex: GC-Zyklus-Typ aus <gc-start> ──────────────────────────────
    // <gc-start ... type="scavenge" ... >
    // <gc-start ... type="global" ... >
    private static final Pattern GC_START = Pattern.compile(
            "<gc-start\\b[^>]*\\btype=\"([^\"]+)\"[^>]*>"
    );

    // ── Regex: Exclusive-Access-Dauer (STW-Pause) ─────────────────────────
    // <exclusive-end ... durationms="3.456" ... />
    private static final Pattern EXCLUSIVE_END = Pattern.compile(
            "<exclusive-end\\b[^>]*\\bdurationms=\"([\\d.,]+)\"[^>]*/?>"
    );

    // ── Regex: Heap-Info nach GC (aus <mem> innerhalb <mem-info>) ─────────
    // <mem type="tenure" free="123456789" total="536870912" ... />
    // <mem type="nursery" free="..." total="..." .../>
    // Wir suchen die aggregierte Heap-Info:
    // <mem ... free="NNN" total="NNN" ... />  (ohne type= fuer Gesamt-Heap)
    // Oder: <mem type="tenure" ...> und <mem type="nursery" ...> einzeln
    private static final Pattern MEM_TOTAL = Pattern.compile(
            "<mem\\b(?![^>]*\\btype=\")[^>]*\\bfree=\"(\\d+)\"[^>]*\\btotal=\"(\\d+)\"[^>]*/?>"
    );

    // ── Regex: Alternativ-Muster fuer typisierten Speicher ────────────────
    // <mem type="tenure" free="NNN" total="NNN" ... />
    private static final Pattern MEM_TYPED = Pattern.compile(
            "<mem\\b[^>]*\\btype=\"([^\"]+)\"[^>]*\\bfree=\"(\\d+)\"[^>]*\\btotal=\"(\\d+)\"[^>]*/?>"
    );

    // ── Regex: gc-end mit Typ ─────────────────────────────────────────────
    // <gc-end ... type="scavenge" ... />
    private static final Pattern GC_END = Pattern.compile(
            "<gc-end\\b[^>]*\\btype=\"([^\"]+)\"[^>]*/?>"
    );

    /**
     * Parst den gesamten Docker-Log-Output fuer OpenJ9-Container.
     *
     * @param dockerLog         vollstaendiger {@code docker logs}-Output (darf null/leer sein)
     * @param totalRuntimeSeconds Gesamtlaufzeit des Containers in Sekunden
     * @return aggregierte GC-Kennzahlen oder {@code null} wenn keine GC-Events gefunden
     */
    public static GcSummary parse(String dockerLog, double totalRuntimeSeconds) {
        if (dockerLog == null || dockerLog.isBlank()) return null;

        List<GcEvent> events = new ArrayList<>();

        // Parsing-State: wir sammeln Infos ueber den aktuellen GC-Zyklus
        String currentGcType = null;
        double currentPauseMs = Double.NaN;
        long currentHeapAfterKb = -1;
        long currentHeapBeforeKb = -1;
        long currentHeapMaxKb = -1;

        for (String line : dockerLog.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;

            // 1. GC-Zyklus-Start erkennen
            Matcher gcStartMatcher = GC_START.matcher(trimmed);
            if (gcStartMatcher.find()) {
                currentGcType = gcStartMatcher.group(1);
                currentHeapAfterKb = -1;
                currentHeapBeforeKb = -1;
                currentHeapMaxKb = -1;
                currentPauseMs = Double.NaN;
                continue;
            }

            // 2. Exclusive-end (STW-Pause-Dauer)
            Matcher exclusiveMatcher = EXCLUSIVE_END.matcher(trimmed);
            if (exclusiveMatcher.find()) {
                double pauseMs = parseDouble(exclusiveMatcher.group(1));
                if (!Double.isNaN(pauseMs)) {
                    currentPauseMs = pauseMs;
                }
                continue;
            }

            // 3. Heap-Info: aggregierter Speicher (ohne type=)
            Matcher memTotalMatcher = MEM_TOTAL.matcher(trimmed);
            if (memTotalMatcher.find()) {
                long free = Long.parseLong(memTotalMatcher.group(1));
                long total = Long.parseLong(memTotalMatcher.group(2));
                long usedBytes = total - free;
                currentHeapAfterKb = usedBytes / 1024;
                currentHeapMaxKb = total / 1024;
                if (currentHeapBeforeKb < 0) {
                    currentHeapBeforeKb = currentHeapAfterKb;
                }
                continue;
            }

            // 4. Typisierter Speicher als Fallback (nursery + tenure summieren)
            Matcher memTypedMatcher = MEM_TYPED.matcher(trimmed);
            if (memTypedMatcher.find()) {
                // Nur verwenden wenn noch kein aggregierter Wert da
                if (currentHeapAfterKb < 0) {
                    long free = Long.parseLong(memTypedMatcher.group(2));
                    long total = Long.parseLong(memTypedMatcher.group(3));
                    long usedBytes = total - free;
                    currentHeapAfterKb = usedBytes / 1024;
                    currentHeapMaxKb = total / 1024;
                }
                continue;
            }

            // 5. GC-Zyklus-Ende: Event abschliessen
            Matcher gcEndMatcher = GC_END.matcher(trimmed);
            if (gcEndMatcher.find()) {
                String endType = gcEndMatcher.group(1);
                String gcType = currentGcType != null ? currentGcType : endType;

                // Event erzeugen (auch ohne Pause-Dauer — dann NaN fuer Heap-only-Events)
                // OpenJ9 verwendet ISO-8601 Timestamps (z.B. "2026-03-07T10:12:34.567")
                // statt monotoner Sekunden wie HotSpot. Da GcSummary.fromEvents() die
                // timestampSeconds nur fuer die Reihenfolge nutzt und wir hier keine
                // monotone Konvertierung vornehmen, setzen wir 0.0 als Platzhalter.
                events.add(new GcEvent(
                        0.0,
                        gcType,
                        "",
                        currentHeapBeforeKb,
                        currentHeapAfterKb,
                        currentHeapMaxKb,
                        currentPauseMs
                ));

                // Reset
                currentGcType = null;
                currentPauseMs = Double.NaN;
                currentHeapAfterKb = -1;
                currentHeapBeforeKb = -1;
                currentHeapMaxKb = -1;
            }
        }

        if (events.isEmpty()) return null;

        return GcSummary.fromEvents(events, totalRuntimeSeconds, OpenJ9GcLogParser::isFullGc);
    }

    /**
     * Prueft ob der GC-Typ ein Full/Global GC ist.
     * Bei OpenJ9 heissen diese "global" (gencon), "global garbage collect" (balanced),
     * oder enthalten "sys" (system-triggered).
     */
    private static boolean isFullGc(String gcType) {
        if (gcType == null) return false;
        String lower = gcType.toLowerCase();
        return lower.contains("global") || lower.contains("sys");
    }

    /**
     * Parst einen Double-Wert der sowohl Punkt als auch Komma als
     * Dezimaltrenner enthalten kann.
     */
    private static double parseDouble(String s) {
        if (s == null) return Double.NaN;
        try {
            return Double.parseDouble(s.replace(',', '.'));
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }
}
