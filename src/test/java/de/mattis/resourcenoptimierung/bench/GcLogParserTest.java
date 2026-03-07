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

    // ======================== ZGC (JDK 25 Generational) ========================

    // Real JDK 25 format: pause lines on [gc,phases] with Y:/y:/O: prefix,
    // summary lines on [gc] with Major/Minor Collection and duration in seconds.
    private static final String ZGC_LOG = """
            [0.010s][info][gc     ] Using The Z Garbage Collector
            [0.347s][info][gc          ] GC(0) Major Collection (Warmup)
            [0.347s][info][gc,phases   ] GC(0) Y: Pause Mark Start (Major) 0.044ms
            [0.353s][info][gc,phases   ] GC(0) Y: Pause Mark End 0.014ms
            [0.355s][info][gc,phases   ] GC(0) Y: Pause Relocate Start 0.020ms
            [0.428s][info][gc,phases   ] GC(0) O: Pause Mark End 0.015ms
            [0.431s][info][gc,phases   ] GC(0) O: Pause Relocate Start 0.007ms
            [0.432s][info][gc          ] GC(0) Major Collection (Warmup) 18M(9%)->8M(4%) 0.085s
            [3.556s][info][gc          ] GC(3) Minor Collection (Allocation Rate)
            [3.556s][info][gc,phases   ] GC(3) y: Pause Mark Start 0.017ms
            [3.729s][info][gc,phases   ] GC(3) y: Pause Mark End 0.013ms
            [3.734s][info][gc,phases   ] GC(3) y: Pause Relocate Start 0.009ms
            [3.750s][info][gc          ] GC(3) Minor Collection (Allocation Rate) 158M(82%)->32M(17%) 0.193s
            """;

    @Test
    void parse_zgcLog_pauseCount() {
        GcSummary summary = GcLogParser.parse(ZGC_LOG, 4.0);
        assertNotNull(summary);
        // 5 pause events from Y: (3) + O: (2) + y: (3) = 8 pause events
        assertEquals(8, summary.gcCount());
    }

    @Test
    void parse_zgcLog_noFullGc() {
        GcSummary summary = GcLogParser.parse(ZGC_LOG, 4.0);
        assertNotNull(summary);
        assertEquals(0, summary.fullGcCount());
    }

    @Test
    void parse_zgcLog_subMillisecondPauses() {
        GcSummary summary = GcLogParser.parse(ZGC_LOG, 4.0);
        assertNotNull(summary);
        // All pauses are sub-millisecond
        assertTrue(summary.maxPauseMs() < 1.0);
        double expectedTotal = 0.044 + 0.014 + 0.020 + 0.015 + 0.007 + 0.017 + 0.013 + 0.009;
        assertEquals(expectedTotal, summary.totalPauseMs(), 0.001);
    }

    @Test
    void parse_zgcLog_heapFromSummaryLines() {
        GcSummary summary = GcLogParser.parse(ZGC_LOG, 4.0);
        assertNotNull(summary);
        // Heap data comes from Major/Minor Collection summary lines
        // 158M->32M is the largest heapAfter: 158M before, but we want peak after = 32M
        // Actually the largest heapAfter across all heap events: 8M and 32M -> peak = 32M
        assertEquals(32L * 1024, summary.peakHeapAfterGcKb());
    }

    @Test
    void parse_zgcLog_pauseEventsHaveNoHeap() {
        GcSummary summary = GcLogParser.parse(ZGC_LOG, 4.0);
        assertNotNull(summary);
        // First event is a Pause event — no heap info
        GcEvent firstPause = summary.events().get(0);
        assertEquals(-1, firstPause.heapBeforeKb());
        assertEquals(-1, firstPause.heapAfterKb());
    }

    @Test
    void parse_zgcLog_summaryEventsHaveHeap() {
        GcSummary summary = GcLogParser.parse(ZGC_LOG, 4.0);
        assertNotNull(summary);
        // Find Major Collection summary event
        GcEvent majorSummary = summary.events().stream()
                .filter(e -> "Major Collection".equals(e.gcType()))
                .findFirst().orElse(null);
        assertNotNull(majorSummary);
        assertEquals(18L * 1024, majorSummary.heapBeforeKb());
        assertEquals(8L * 1024, majorSummary.heapAfterKb());
        assertTrue(Double.isNaN(majorSummary.pauseMs()));
    }

    @Test
    void parse_zgcLog_minorCollectionHeap() {
        GcSummary summary = GcLogParser.parse(ZGC_LOG, 4.0);
        assertNotNull(summary);
        GcEvent minorSummary = summary.events().stream()
                .filter(e -> "Minor Collection".equals(e.gcType()))
                .findFirst().orElse(null);
        assertNotNull(minorSummary);
        assertEquals(158L * 1024, minorSummary.heapBeforeKb());
        assertEquals(32L * 1024, minorSummary.heapAfterKb());
    }

    // ======================== ZGC (old non-generational format, backward compat) ========================

    private static final String ZGC_OLD_LOG = """
            [0.010s][info][gc] Using The Z Garbage Collector
            [0.200s][info][gc] GC(0) Pause Mark Start 0.015ms
            [0.300s][info][gc] GC(0) Pause Mark End 0.010ms
            [0.400s][info][gc] GC(0) Pause Relocate Start 0.020ms
            """;

    @Test
    void parse_zgcOldLog_backwardCompatible() {
        GcSummary summary = GcLogParser.parse(ZGC_OLD_LOG, 1.0);
        assertNotNull(summary);
        assertEquals(3, summary.gcCount());
        double expected = 0.015 + 0.010 + 0.020;
        assertEquals(expected, summary.totalPauseMs(), 0.001);
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

    // Real JDK 25 format: pause on [gc] tag, heap on "Concurrent cleanup" lines.
    // Qualifiers like "(unload classes)" appear in the Pause lines.
    private static final String SHENANDOAH_LOG = """
            [0.004s][info][gc     ] Using Shenandoah
            [0.931s][info][gc          ] GC(0) Pause Init Mark (unload classes) 0.064ms
            [0.939s][info][gc          ] GC(0) Pause Final Mark (unload classes) 0.180ms
            [0.941s][info][gc          ] GC(0) Concurrent cleanup (unload classes) 49M->44M(50M) 0.014ms
            [1.008s][info][gc          ] GC(0) Pause Init Update Refs 0.054ms
            [1.011s][info][gc          ] GC(0) Pause Final Update Refs 0.091ms
            [1.011s][info][gc          ] GC(0) Concurrent cleanup (unload classes) 47M->6M(53M) 0.065ms
            """;

    @Test
    void parse_shenandoahLog_allPauses() {
        GcSummary summary = GcLogParser.parse(SHENANDOAH_LOG, 2.0);
        assertNotNull(summary);
        // 4 pause events (Init Mark, Final Mark, Init Update Refs, Final Update Refs)
        assertEquals(4, summary.gcCount());
        assertEquals(0, summary.fullGcCount());
        double expected = 0.064 + 0.180 + 0.054 + 0.091;
        assertEquals(expected, summary.totalPauseMs(), 0.001);
    }

    @Test
    void parse_shenandoahLog_heapFromCleanup() {
        GcSummary summary = GcLogParser.parse(SHENANDOAH_LOG, 2.0);
        assertNotNull(summary);
        // Heap data from Concurrent cleanup lines.
        // Two cleanup lines: 49M->44M(50M) and 47M->6M(53M)
        // Peak heapAfter = 44M = 44*1024 = 45056 KiB
        assertEquals(44L * 1024, summary.peakHeapAfterGcKb());
    }

    @Test
    void parse_shenandoahLog_cleanupEventsInList() {
        GcSummary summary = GcLogParser.parse(SHENANDOAH_LOG, 2.0);
        assertNotNull(summary);
        // Total events: 4 pause + 2 cleanup = 6
        assertEquals(6, summary.events().size());
    }

    @Test
    void parse_shenandoahLog_cleanupEventDetails() {
        GcSummary summary = GcLogParser.parse(SHENANDOAH_LOG, 2.0);
        assertNotNull(summary);
        // Find the second Concurrent Cleanup event (47M->6M)
        List<GcEvent> cleanups = summary.events().stream()
                .filter(e -> "Concurrent Cleanup".equals(e.gcType()))
                .toList();
        assertEquals(2, cleanups.size());

        GcEvent second = cleanups.get(1);
        assertEquals(47L * 1024, second.heapBeforeKb());
        assertEquals(6L * 1024, second.heapAfterKb());
        assertEquals(53L * 1024, second.heapMaxKb());
        assertTrue(Double.isNaN(second.pauseMs()));
    }

    @Test
    void parse_shenandoahLog_qualifierStrippedFromType() {
        GcSummary summary = GcLogParser.parse(SHENANDOAH_LOG, 2.0);
        assertNotNull(summary);
        // "(unload classes)" should be stripped from the type
        GcEvent initMark = summary.events().get(0);
        assertEquals("Init Mark", initMark.gcType());

        GcEvent finalMark = summary.events().get(1);
        assertEquals("Final Mark", finalMark.gcType());
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

    // ======================== parseSizeWithUnit ========================

    @Test
    void parseSizeWithUnit_megabytes() {
        assertEquals(18L * 1024, GcLogParser.parseSizeWithUnit("18", "M"));
    }

    @Test
    void parseSizeWithUnit_kilobytes() {
        assertEquals(512L, GcLogParser.parseSizeWithUnit("512", "K"));
    }

    @Test
    void parseSizeWithUnit_null_returnsMinusOne() {
        assertEquals(-1, GcLogParser.parseSizeWithUnit(null, "M"));
        assertEquals(-1, GcLogParser.parseSizeWithUnit("18", null));
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
    void parseLine_zgcPauseMarkStart_generational() {
        // JDK 25 Generational ZGC with Y: prefix and (Major) qualifier
        GcEvent e = GcLogParser.parseLine(
                "[0.347s][info][gc,phases   ] GC(0) Y: Pause Mark Start (Major) 0.044ms");
        assertNotNull(e);
        assertEquals(0.347, e.timestampSeconds(), 0.001);
        assertEquals("Mark Start", e.gcType());  // "(Major)" stripped
        assertEquals(0.044, e.pauseMs(), 0.001);
        assertEquals(-1, e.heapBeforeKb());
    }

    @Test
    void parseLine_zgcPauseMarkEnd_youngGen() {
        // JDK 25 lowercase y: for minor-only young generation
        GcEvent e = GcLogParser.parseLine(
                "[3.729s][info][gc,phases   ] GC(3) y: Pause Mark End 0.013ms");
        assertNotNull(e);
        assertEquals(3.729, e.timestampSeconds(), 0.001);
        assertEquals("Mark End", e.gcType());
        assertEquals(0.013, e.pauseMs(), 0.001);
    }

    @Test
    void parseLine_zgcPauseRelocateStart_oldGen() {
        // O: prefix for old generation
        GcEvent e = GcLogParser.parseLine(
                "[0.431s][info][gc,phases   ] GC(0) O: Pause Relocate Start 0.007ms");
        assertNotNull(e);
        assertEquals("Relocate Start", e.gcType());
        assertEquals(0.007, e.pauseMs(), 0.001);
    }

    @Test
    void parseLine_zgcOldFormat_noPrefix() {
        // Old non-generational ZGC format (no Y:/O: prefix)
        GcEvent e = GcLogParser.parseLine(
                "[0.200s][info][gc] GC(0) Pause Mark Start 0.015ms");
        assertNotNull(e);
        assertEquals("Mark Start", e.gcType());
        assertEquals(0.015, e.pauseMs(), 0.001);
    }

    @Test
    void parseLine_shenandoahWithQualifier() {
        // Shenandoah pause with "(unload classes)" qualifier — should be stripped
        GcEvent e = GcLogParser.parseLine(
                "[0.931s][info][gc          ] GC(0) Pause Init Mark (unload classes) 0.064ms");
        assertNotNull(e);
        assertEquals("Init Mark", e.gcType());
        assertEquals(0.064, e.pauseMs(), 0.001);
    }

    // ======================== parseHeapLine ========================

    @Test
    void parseHeapLine_zgcMajorCollection() {
        GcEvent e = GcLogParser.parseHeapLine(
                "[0.432s][info][gc          ] GC(0) Major Collection (Warmup) 18M(9%)->8M(4%) 0.085s");
        assertNotNull(e);
        assertEquals("Major Collection", e.gcType());
        assertEquals(18L * 1024, e.heapBeforeKb());
        assertEquals(8L * 1024, e.heapAfterKb());
        assertEquals(-1, e.heapMaxKb());  // ZGC summary doesn't report max
        assertTrue(Double.isNaN(e.pauseMs()));
    }

    @Test
    void parseHeapLine_zgcMinorCollection() {
        GcEvent e = GcLogParser.parseHeapLine(
                "[3.750s][info][gc          ] GC(3) Minor Collection (Allocation Rate) 158M(82%)->32M(17%) 0.193s");
        assertNotNull(e);
        assertEquals("Minor Collection", e.gcType());
        assertEquals(158L * 1024, e.heapBeforeKb());
        assertEquals(32L * 1024, e.heapAfterKb());
    }

    @Test
    void parseHeapLine_zgcCollectionStart_noHeap_returnsNull() {
        // Start-of-collection line has no heap info — should return null
        GcEvent e = GcLogParser.parseHeapLine(
                "[0.347s][info][gc          ] GC(0) Major Collection (Warmup)");
        assertNull(e);
    }

    @Test
    void parseHeapLine_shenandoahCleanup() {
        GcEvent e = GcLogParser.parseHeapLine(
                "[1.011s][info][gc          ] GC(0) Concurrent cleanup (unload classes) 47M->6M(53M) 0.065ms");
        assertNotNull(e);
        assertEquals("Concurrent Cleanup", e.gcType());
        assertEquals(47L * 1024, e.heapBeforeKb());
        assertEquals(6L * 1024, e.heapAfterKb());
        assertEquals(53L * 1024, e.heapMaxKb());
        assertTrue(Double.isNaN(e.pauseMs()));
    }

    @Test
    void parseHeapLine_null_returnsNull() {
        assertNull(GcLogParser.parseHeapLine(null));
    }

    @Test
    void parseHeapLine_irrelevantLine_returnsNull() {
        assertNull(GcLogParser.parseHeapLine("[0.100s][info][gc] Using G1"));
    }

    // ======================== Integration: real ZGC log excerpt ========================

    private static final String ZGC_REAL_EXCERPT = """
            [0.003s][info][gc,init] Initializing The Z Garbage Collector
            [0.010s][info][gc     ] Using The Z Garbage Collector
            [0.347s][info][gc          ] GC(0) Major Collection (Warmup)
            [0.347s][info][gc,task     ] GC(0) Using 1 Workers for Young Generation
            [0.347s][info][gc,phases   ] GC(0) Y: Young Generation
            [0.347s][info][gc,phases   ] GC(0) Y: Pause Mark Start (Major) 0.044ms
            [0.353s][info][gc,phases   ] GC(0) Y: Concurrent Mark 5.454ms
            [0.353s][info][gc,phases   ] GC(0) Y: Pause Mark End 0.014ms
            [0.355s][info][gc,phases   ] GC(0) Y: Pause Relocate Start 0.020ms
            [0.360s][info][gc,phases   ] GC(0) Y: Concurrent Relocate 5.352ms
            [0.360s][info][gc,phases   ] GC(0) Y: Young Generation 18M(9%)->8M(4%) 0.013s
            [0.360s][info][gc,phases   ] GC(0) O: Old Generation
            [0.428s][info][gc,phases   ] GC(0) O: Concurrent Mark 68.015ms
            [0.428s][info][gc,phases   ] GC(0) O: Pause Mark End 0.015ms
            [0.431s][info][gc,phases   ] GC(0) O: Pause Relocate Start 0.007ms
            [0.432s][info][gc          ] GC(0) Major Collection (Warmup) 18M(9%)->8M(4%) 0.085s
            """;

    @Test
    void parse_zgcRealExcerpt_allEventsFound() {
        GcSummary summary = GcLogParser.parse(ZGC_REAL_EXCERPT, 1.0);
        assertNotNull(summary);
        // 5 pause events + 1 Major Collection heap event = 6 total events
        // Pause events: Y: Mark Start, Y: Mark End, Y: Relocate Start, O: Mark End, O: Relocate Start
        assertEquals(5, summary.gcCount());
        assertEquals(6, summary.events().size());
    }

    @Test
    void parse_zgcRealExcerpt_heapCaptured() {
        GcSummary summary = GcLogParser.parse(ZGC_REAL_EXCERPT, 1.0);
        assertNotNull(summary);
        // 18M->8M from the Major Collection summary
        assertEquals(8L * 1024, summary.peakHeapAfterGcKb());
    }

    // ======================== Integration: real Shenandoah log excerpt ========================

    private static final String SHENANDOAH_REAL_EXCERPT = """
            [0.004s][info][gc     ] Using Shenandoah
            [0.931s][info][gc          ] GC(0) Pause Init Mark (unload classes) 0.064ms
            [0.938s][info][gc,start    ] GC(0) Pause Final Mark (unload classes)
            [0.939s][info][gc          ] GC(0) Pause Final Mark (unload classes) 0.180ms
            [0.941s][info][gc,start    ] GC(0) Concurrent cleanup (unload classes)
            [0.941s][info][gc          ] GC(0) Concurrent cleanup (unload classes) 49M->44M(50M) 0.014ms
            [1.008s][info][gc          ] GC(0) Pause Init Update Refs 0.054ms
            [1.011s][info][gc          ] GC(0) Pause Final Update Refs 0.091ms
            [1.011s][info][gc          ] GC(0) Concurrent cleanup (unload classes) 47M->6M(53M) 0.065ms
            """;

    @Test
    void parse_shenandoahRealExcerpt_pauseAndHeap() {
        GcSummary summary = GcLogParser.parse(SHENANDOAH_REAL_EXCERPT, 2.0);
        assertNotNull(summary);
        // 4 pause events
        assertEquals(4, summary.gcCount());
        // Peak heap after = max(44M, 6M) = 44M
        assertEquals(44L * 1024, summary.peakHeapAfterGcKb());
        // Total events = 4 pause + 2 cleanup = 6
        assertEquals(6, summary.events().size());
    }
}
