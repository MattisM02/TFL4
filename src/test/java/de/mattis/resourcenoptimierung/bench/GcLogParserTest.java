package de.mattis.resourcenoptimierung.bench;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests fuer GcLogParser: Parsen von JDK-Unified-GC-Logs.
 */
class GcLogParserTest {

    // ======================== G1 GC ========================

    private static final String G1_LOG = """
            [0.010s][info][gc] Using G1
            [0.234s][info][gc] GC(0) Pause Young (Normal) (G1 Evacuation Pause) 24M->8M(256M) 3.451ms
            [0.500s][info][gc] GC(1) Pause Young (Normal) (G1 Evacuation Pause) 48M->16M(256M) 5.200ms
            [1.891s][info][gc] GC(2) Pause Young (Concurrent Start) (G1 Humongous Allocation) 128M->42M(256M) 8.234ms
            [2.100s][info][gc] GC(3) Concurrent Mark Cycle
            [5.012s][info][gc] GC(4) Pause Full (G1 Compaction Pause) 240M->89M(256M) 45.678ms
            [5.500s][info][gc] GC(5) Pause Young (Normal) (G1 Evacuation Pause) 110M->20M(256M) 2.100ms
            """;

    @Test
    void parse_g1Log_correctEventCount() {
        GcSummary summary = GcLogParser.parse(G1_LOG, 6.0);
        assertNotNull(summary);
        // 4 Young + 1 Full = 5 pause events (Concurrent Mark has no "Pause" keyword match)
        assertEquals(5, summary.gcCount());
    }

    @Test
    void parse_g1Log_fullGcDetected() {
        GcSummary summary = GcLogParser.parse(G1_LOG, 6.0);
        assertNotNull(summary);
        assertEquals(1, summary.fullGcCount());
    }

    @Test
    void parse_g1Log_totalPause() {
        GcSummary summary = GcLogParser.parse(G1_LOG, 6.0);
        assertNotNull(summary);
        double expected = 3.451 + 5.200 + 8.234 + 45.678 + 2.100;
        assertEquals(expected, summary.totalPauseMs(), 0.001);
    }

    @Test
    void parse_g1Log_maxPause() {
        GcSummary summary = GcLogParser.parse(G1_LOG, 6.0);
        assertNotNull(summary);
        assertEquals(45.678, summary.maxPauseMs(), 0.001);
    }

    @Test
    void parse_g1Log_avgPause() {
        GcSummary summary = GcLogParser.parse(G1_LOG, 6.0);
        assertNotNull(summary);
        double expected = (3.451 + 5.200 + 8.234 + 45.678 + 2.100) / 5.0;
        assertEquals(expected, summary.avgPauseMs(), 0.001);
    }

    @Test
    void parse_g1Log_gcOverhead() {
        GcSummary summary = GcLogParser.parse(G1_LOG, 6.0);
        assertNotNull(summary);
        double totalPause = 3.451 + 5.200 + 8.234 + 45.678 + 2.100;
        double expectedOverhead = (totalPause / 6000.0) * 100.0;
        assertEquals(expectedOverhead, summary.gcOverheadPercent(), 0.001);
    }

    @Test
    void parse_g1Log_peakHeapAfterGc() {
        GcSummary summary = GcLogParser.parse(G1_LOG, 6.0);
        assertNotNull(summary);
        // 89M = 89 * 1024 KiB = 91136 KiB (largest heap-after-GC)
        assertEquals(89L * 1024, summary.peakHeapAfterGcKb());
    }

    @Test
    void parse_g1Log_eventsPreserved() {
        GcSummary summary = GcLogParser.parse(G1_LOG, 6.0);
        assertNotNull(summary);
        List<GcEvent> events = summary.events();
        assertEquals(5, events.size());

        // First event details
        GcEvent first = events.get(0);
        assertEquals(0.234, first.timestampSeconds(), 0.001);
        assertEquals("Young", first.gcType());
        assertEquals("G1 Evacuation Pause", first.gcCause());
        assertEquals(24L * 1024, first.heapBeforeKb());
        assertEquals(8L * 1024, first.heapAfterKb());
        assertEquals(256L * 1024, first.heapMaxKb());
        assertEquals(3.451, first.pauseMs(), 0.001);
    }

    // ======================== ZGC ========================

    private static final String ZGC_LOG = """
            [0.010s][info][gc] Using The Z Garbage Collector
            [0.200s][info][gc] GC(0) Pause Mark Start 0.015ms
            [0.300s][info][gc] GC(0) Pause Mark End 0.010ms
            [0.400s][info][gc] GC(0) Pause Relocate Start 0.020ms
            [0.500s][info][gc] GC(0) Garbage Collection (Warmup) 24M(12%)->8M(4%)
            [1.200s][info][gc] GC(1) Pause Mark Start 0.012ms
            [1.300s][info][gc] GC(1) Pause Mark End 0.008ms
            [1.400s][info][gc] GC(1) Pause Relocate Start 0.018ms
            """;

    @Test
    void parse_zgcLog_shortPauses() {
        GcSummary summary = GcLogParser.parse(ZGC_LOG, 2.0);
        assertNotNull(summary);
        // 6 pause events (3 per cycle * 2 cycles)
        assertEquals(6, summary.gcCount());
    }

    @Test
    void parse_zgcLog_noFullGc() {
        GcSummary summary = GcLogParser.parse(ZGC_LOG, 2.0);
        assertNotNull(summary);
        assertEquals(0, summary.fullGcCount());
    }

    @Test
    void parse_zgcLog_subMillisecondPauses() {
        GcSummary summary = GcLogParser.parse(ZGC_LOG, 2.0);
        assertNotNull(summary);
        // All pauses are sub-millisecond
        assertTrue(summary.maxPauseMs() < 1.0);
        double expected = 0.015 + 0.010 + 0.020 + 0.012 + 0.008 + 0.018;
        assertEquals(expected, summary.totalPauseMs(), 0.001);
    }

    @Test
    void parse_zgcLog_pauseEventsHaveNoHeap() {
        GcSummary summary = GcLogParser.parse(ZGC_LOG, 2.0);
        assertNotNull(summary);
        // ZGC pause lines don't have heap info
        GcEvent first = summary.events().get(0);
        assertEquals(-1, first.heapBeforeKb());
        assertEquals(-1, first.heapAfterKb());
    }

    // ======================== Serial GC ========================

    private static final String SERIAL_LOG = """
            [0.010s][info][gc] Using Serial
            [0.150s][info][gc] GC(0) Pause Young (Allocation Failure) 12M->4M(64M) 2.100ms
            [0.400s][info][gc] GC(1) Pause Young (Allocation Failure) 16M->6M(64M) 3.500ms
            [1.200s][info][gc] GC(2) Pause Full (Allocation Failure) 60M->20M(64M) 30.000ms
            """;

    @Test
    void parse_serialLog_allEvents() {
        GcSummary summary = GcLogParser.parse(SERIAL_LOG, 2.0);
        assertNotNull(summary);
        assertEquals(3, summary.gcCount());
        assertEquals(1, summary.fullGcCount());
        assertEquals(30.0, summary.maxPauseMs(), 0.001);
    }

    @Test
    void parse_serialLog_heapSizes() {
        GcSummary summary = GcLogParser.parse(SERIAL_LOG, 2.0);
        assertNotNull(summary);
        GcEvent fullGc = summary.events().get(2);
        assertEquals("Full", fullGc.gcType());
        assertEquals(60L * 1024, fullGc.heapBeforeKb());
        assertEquals(20L * 1024, fullGc.heapAfterKb());
        assertEquals(64L * 1024, fullGc.heapMaxKb());
    }

    // ======================== Shenandoah ========================

    private static final String SHENANDOAH_LOG = """
            [0.010s][info][gc] Using Shenandoah
            [0.200s][info][gc] GC(0) Pause Init Mark 0.050ms
            [0.400s][info][gc] GC(0) Pause Final Mark 0.080ms
            [0.600s][info][gc] GC(0) Pause Init Update Refs 0.030ms
            [0.800s][info][gc] GC(0) Pause Final Update Refs 0.060ms
            """;

    @Test
    void parse_shenandoahLog_allPauses() {
        GcSummary summary = GcLogParser.parse(SHENANDOAH_LOG, 1.0);
        assertNotNull(summary);
        assertEquals(4, summary.gcCount());
        assertEquals(0, summary.fullGcCount());
        double expected = 0.050 + 0.080 + 0.030 + 0.060;
        assertEquals(expected, summary.totalPauseMs(), 0.001);
    }

    // ======================== Edge Cases ========================

    @Test
    void parse_nullInput_returnsNull() {
        assertNull(GcLogParser.parse(null, 1.0));
    }

    @Test
    void parse_emptyInput_returnsNull() {
        assertNull(GcLogParser.parse("", 1.0));
    }

    @Test
    void parse_blankInput_returnsNull() {
        assertNull(GcLogParser.parse("   \n  \n  ", 1.0));
    }

    @Test
    void parse_noGcLines_returnsNull() {
        String log = """
                [0.010s][info][gc] Using G1
                [0.100s][info][gc,init] Mark Stack Size: 4096K
                Spring Boot started in 1.234 seconds
                """;
        assertNull(GcLogParser.parse(log, 1.0));
    }

    @Test
    void parse_malformedLines_skipped() {
        String log = """
                [0.234s][info][gc] GC(0) Pause Young (Normal) (G1 Evacuation Pause) 24M->8M(256M) 3.451ms
                [BROKEN LINE not a real gc log
                [0.500s][info][gc] GC(1) Pause Young (Normal) (G1 Evacuation Pause) 48M->16M(256M) 5.200ms
                """;
        GcSummary summary = GcLogParser.parse(log, 1.0);
        assertNotNull(summary);
        assertEquals(2, summary.gcCount());
    }

    @Test
    void parse_zeroRuntime_noOverflowOrNaN() {
        String log = "[0.234s][info][gc] GC(0) Pause Young (Normal) (G1 Evacuation Pause) 24M->8M(256M) 3.451ms\n";
        GcSummary summary = GcLogParser.parse(log, 0.0);
        assertNotNull(summary);
        assertEquals(0.0, summary.gcOverheadPercent());
    }

    // ======================== parseSize ========================

    @Test
    void parseSize_megabytes() {
        assertEquals(24L * 1024, GcLogParser.parseSize("24M"));
    }

    @Test
    void parseSize_kilobytes() {
        assertEquals(512L, GcLogParser.parseSize("512K"));
    }

    @Test
    void parseSize_gigabytes() {
        assertEquals(1L * 1024 * 1024, GcLogParser.parseSize("1G"));
    }

    @Test
    void parseSize_bytes() {
        // 8192 bytes = 8 KiB
        assertEquals(8L, GcLogParser.parseSize("8192B"));
    }

    @Test
    void parseSize_null_returnsMinusOne() {
        assertEquals(-1, GcLogParser.parseSize(null));
    }

    @Test
    void parseSize_empty_returnsMinusOne() {
        assertEquals(-1, GcLogParser.parseSize(""));
    }

    // ======================== parseLine ========================

    @Test
    void parseLine_null_returnsNull() {
        assertNull(GcLogParser.parseLine(null));
    }

    @Test
    void parseLine_nonPauseLine_returnsNull() {
        assertNull(GcLogParser.parseLine("[0.100s][info][gc] Using G1"));
    }

    @Test
    void parseLine_g1YoungPause() {
        GcEvent e = GcLogParser.parseLine(
                "[0.234s][info][gc] GC(0) Pause Young (Normal) (G1 Evacuation Pause) 24M->8M(256M) 3.451ms");
        assertNotNull(e);
        assertEquals(0.234, e.timestampSeconds(), 0.001);
        assertEquals("Young", e.gcType());
        assertEquals("G1 Evacuation Pause", e.gcCause());
        assertEquals(24L * 1024, e.heapBeforeKb());
        assertEquals(8L * 1024, e.heapAfterKb());
        assertEquals(256L * 1024, e.heapMaxKb());
        assertEquals(3.451, e.pauseMs(), 0.001);
    }

    @Test
    void parseLine_zgcPauseMarkStart() {
        GcEvent e = GcLogParser.parseLine(
                "[0.200s][info][gc] GC(0) Pause Mark Start 0.015ms");
        assertNotNull(e);
        assertEquals(0.200, e.timestampSeconds(), 0.001);
        assertEquals("Mark Start", e.gcType());
        assertEquals(0.015, e.pauseMs(), 0.001);
        assertEquals(-1, e.heapBeforeKb());
    }
}
