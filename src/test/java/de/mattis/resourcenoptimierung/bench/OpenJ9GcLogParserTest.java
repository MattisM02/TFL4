package de.mattis.resourcenoptimierung.bench;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests fuer OpenJ9GcLogParser — Parsen von OpenJ9 verbose:gc XML-Output.
 */
class OpenJ9GcLogParserTest {

    // ==================== Null / Leer ====================

    @Test
    void parse_null_returnsNull() {
        assertNull(OpenJ9GcLogParser.parse(null, 10.0));
    }

    @Test
    void parse_empty_returnsNull() {
        assertNull(OpenJ9GcLogParser.parse("", 10.0));
    }

    @Test
    void parse_blank_returnsNull() {
        assertNull(OpenJ9GcLogParser.parse("   \n  \n  ", 10.0));
    }

    @Test
    void parse_noGcEvents_returnsNull() {
        String log = "Spring Boot started in 2.5 seconds\nSome application log line\n";
        assertNull(OpenJ9GcLogParser.parse(log, 10.0));
    }

    // ==================== gencon (Scavenge) ====================

    /**
     * Simuliert einen minimalen gencon Scavenge-Zyklus.
     * Reale OpenJ9-Logs haben mehr XML-Elemente, aber der Parser
     * extrahiert gc-start type, exclusive-end durationms und gc-end.
     */
    @Test
    void parse_genconScavenge_singleCycle() {
        String log = """
                <exclusive-start id="1" timestamp="2026-03-07T10:00:00.100" intervalms="0.000" />
                <gc-start id="2" type="scavenge" timestamp="2026-03-07T10:00:00.101" intervalms="0.000">
                  <mem-info id="3" free="100000000" total="536870912" percent="18" />
                </gc-start>
                <gc-end id="4" type="scavenge" timestamp="2026-03-07T10:00:00.104" durationms="3.456">
                  <mem-info id="5" free="400000000" total="536870912" percent="74" />
                </gc-end>
                <exclusive-end id="6" timestamp="2026-03-07T10:00:00.105" durationms="4.123" />
                """;

        GcSummary summary = OpenJ9GcLogParser.parse(log, 10.0);

        assertNotNull(summary);
        // gc-end erzeugt ein Event; exclusive-end setzt pauseMs beim naechsten Event
        // Da gc-end VOR exclusive-end kommt, hat das Event pauseMs=NaN (keine Pause gesetzt)
        // und das exclusive-end erzeugt kein eigenes Event.
        // Aber es gibt mindestens 1 GC-Event (vom gc-end).
        assertTrue(summary.events().size() >= 1, "Should have at least 1 GC event");
        assertEquals("scavenge", summary.events().get(0).gcType());
    }

    // ==================== gencon (Global) ====================

    @Test
    void parse_genconGlobal_isFullGc() {
        String log = """
                <exclusive-start id="10" timestamp="2026-03-07T10:00:01.000" intervalms="500.000" />
                <gc-start id="11" type="global" timestamp="2026-03-07T10:00:01.001" intervalms="500.000">
                </gc-start>
                <gc-end id="12" type="global" timestamp="2026-03-07T10:00:01.050" durationms="49.123">
                </gc-end>
                <exclusive-end id="13" timestamp="2026-03-07T10:00:01.051" durationms="50.500" />
                """;

        GcSummary summary = OpenJ9GcLogParser.parse(log, 10.0);

        assertNotNull(summary);
        assertTrue(summary.fullGcCount() >= 1, "Global GC should count as full GC");
    }

    // ==================== Pause-Dauer mit exclusive-end vor gc-start ====================

    @Test
    void parse_exclusiveEndBeforeGcEnd_pauseCaptured() {
        // Realistischere Reihenfolge: exclusive-end kommt nach gc-end
        String log = """
                <gc-start id="1" type="scavenge" timestamp="2026-03-07T10:00:00.100">
                </gc-start>
                <exclusive-end id="2" timestamp="2026-03-07T10:00:00.104" durationms="3.500" />
                <gc-end id="3" type="scavenge" timestamp="2026-03-07T10:00:00.104">
                </gc-end>
                """;

        GcSummary summary = OpenJ9GcLogParser.parse(log, 10.0);

        assertNotNull(summary);
        assertEquals(1, summary.events().size());
        // exclusive-end sets pauseMs=3.5 before gc-end creates the event
        assertEquals(3.5, summary.events().get(0).pauseMs(), 0.01);
        assertEquals(1, summary.gcCount());
        assertEquals(3.5, summary.maxPauseMs(), 0.01);
    }

    // ==================== Heap-Info ====================

    @Test
    void parse_memInfo_heapCaptured() {
        // mem-info ohne type= Attribut → aggregierter Heap
        String log = """
                <gc-start id="1" type="scavenge" timestamp="2026-03-07T10:00:00.100">
                  <mem-info id="2" free="300000000" total="536870912" percent="55" />
                </gc-start>
                <exclusive-end id="3" durationms="2.000" />
                <gc-end id="4" type="scavenge" timestamp="2026-03-07T10:00:00.104">
                  <mem-info id="5" free="400000000" total="536870912" percent="74" />
                </gc-end>
                """;

        GcSummary summary = OpenJ9GcLogParser.parse(log, 10.0);

        assertNotNull(summary);
        // Heap after: (536870912 - 400000000) / 1024 ≈ 133664 KiB
        assertTrue(summary.peakHeapAfterGcKb() > 0, "Heap info should be captured");
    }

    // ==================== Mehrere Zyklen ====================

    @Test
    void parse_multipleCycles_aggregatesCorrectly() {
        String log = """
                <gc-start id="1" type="scavenge" timestamp="2026-03-07T10:00:00.100">
                </gc-start>
                <exclusive-end id="2" durationms="2.000" />
                <gc-end id="3" type="scavenge">
                </gc-end>
                <gc-start id="4" type="scavenge" timestamp="2026-03-07T10:00:01.000">
                </gc-start>
                <exclusive-end id="5" durationms="3.000" />
                <gc-end id="6" type="scavenge">
                </gc-end>
                <gc-start id="7" type="global" timestamp="2026-03-07T10:00:02.000">
                </gc-start>
                <exclusive-end id="8" durationms="10.000" />
                <gc-end id="9" type="global">
                </gc-end>
                """;

        GcSummary summary = OpenJ9GcLogParser.parse(log, 10.0);

        assertNotNull(summary);
        assertEquals(3, summary.events().size(), "Should have 3 GC events");
        assertEquals(3, summary.gcCount(), "Should count 3 pauses");
        assertEquals(1, summary.fullGcCount(), "Global should count as full GC");
        assertEquals(15.0, summary.totalPauseMs(), 0.01);
        assertEquals(10.0, summary.maxPauseMs(), 0.01);
        assertEquals(5.0, summary.avgPauseMs(), 0.01);
    }

    // ==================== Overhead-Berechnung ====================

    @Test
    void parse_overheadCalculation() {
        String log = """
                <gc-start id="1" type="scavenge">
                </gc-start>
                <exclusive-end id="2" durationms="100.0" />
                <gc-end id="3" type="scavenge">
                </gc-end>
                """;

        // 100ms pause in 10s total = 1% overhead
        GcSummary summary = OpenJ9GcLogParser.parse(log, 10.0);

        assertNotNull(summary);
        assertEquals(1.0, summary.gcOverheadPercent(), 0.01);
    }

    // ==================== Gemischter Output ====================

    @Test
    void parse_mixedWithApplicationLogs_ignoresNonGcLines() {
        String log = """
                2026-03-07 10:00:00.000 INFO  --- Spring Boot started in 2.5s
                <gc-start id="1" type="scavenge" timestamp="2026-03-07T10:00:00.500">
                </gc-start>
                2026-03-07 10:00:00.501 INFO  --- Processing request...
                <exclusive-end id="2" durationms="1.500" />
                <gc-end id="3" type="scavenge">
                </gc-end>
                2026-03-07 10:00:01.000 INFO  --- Request completed
                """;

        GcSummary summary = OpenJ9GcLogParser.parse(log, 10.0);

        assertNotNull(summary);
        assertEquals(1, summary.gcCount());
        assertEquals(1.5, summary.maxPauseMs(), 0.01);
    }

    // ==================== Typisierte Mem-Elemente ====================

    @Test
    void parse_typedMem_fallbackHeapCapture() {
        // Wenn kein aggregiertes <mem> ohne type= vorhanden ist,
        // soll ein typisiertes <mem type="tenure" ...> als Fallback dienen.
        String log = """
                <gc-start id="1" type="scavenge">
                  <mem type="tenure" free="200000000" total="402653184" percent="49" />
                </gc-start>
                <exclusive-end id="2" durationms="2.000" />
                <gc-end id="3" type="scavenge">
                  <mem type="tenure" free="350000000" total="402653184" percent="86" />
                </gc-end>
                """;

        GcSummary summary = OpenJ9GcLogParser.parse(log, 10.0);

        assertNotNull(summary);
        assertTrue(summary.peakHeapAfterGcKb() > 0, "Typed mem should be used as fallback");
    }
}
