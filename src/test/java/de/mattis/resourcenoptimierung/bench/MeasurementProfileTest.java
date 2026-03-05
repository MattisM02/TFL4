package de.mattis.resourcenoptimierung.bench;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests fuer MeasurementProfile: Validierung, Defaults und toString.
 */
class MeasurementProfileTest {

    @Test
    void defaults_returnsExpectedValues() {
        MeasurementProfile p = MeasurementProfile.defaults();
        assertEquals(200, p.warmupRequests());
        assertEquals(500, p.measureRequests());
        assertEquals(1, p.concurrency());
        assertEquals(0, p.sleepBetweenRequestsMs());
    }

    @Test
    void validProfile_createsSuccessfully() {
        MeasurementProfile p = new MeasurementProfile(10, 50, 4, 100);
        assertEquals(10, p.warmupRequests());
        assertEquals(50, p.measureRequests());
        assertEquals(4, p.concurrency());
        assertEquals(100, p.sleepBetweenRequestsMs());
    }

    @Test
    void warmupZero_isValid() {
        MeasurementProfile p = new MeasurementProfile(0, 1, 1, 0);
        assertEquals(0, p.warmupRequests());
    }

    @Test
    void negativeWarmup_throwsException() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new MeasurementProfile(-1, 100, 1, 0));
        assertTrue(ex.getMessage().contains("warmupRequests"));
        assertTrue(ex.getMessage().contains("-1"));
    }

    @Test
    void zeroMeasureRequests_throwsException() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new MeasurementProfile(20, 0, 1, 0));
        assertTrue(ex.getMessage().contains("measureRequests"));
    }

    @Test
    void negativeMeasureRequests_throwsException() {
        assertThrows(IllegalArgumentException.class,
                () -> new MeasurementProfile(20, -5, 1, 0));
    }

    @Test
    void zeroConcurrency_throwsException() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new MeasurementProfile(20, 100, 0, 0));
        assertTrue(ex.getMessage().contains("concurrency"));
    }

    @Test
    void negativeSleep_throwsException() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new MeasurementProfile(20, 100, 1, -1));
        assertTrue(ex.getMessage().contains("sleepBetweenRequestsMs"));
    }

    @Test
    void toString_containsAllFields() {
        MeasurementProfile p = new MeasurementProfile(5, 50, 2, 200);
        String s = p.toString();
        assertTrue(s.contains("warmup=5"));
        assertTrue(s.contains("measure=50"));
        assertTrue(s.contains("concurrency=2"));
        assertTrue(s.contains("sleepMs=200"));
    }

    @Test
    void equality_worksForRecords() {
        MeasurementProfile a = new MeasurementProfile(20, 100, 1, 0);
        MeasurementProfile b = new MeasurementProfile(20, 100, 1, 0);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void inequality_differentValues() {
        MeasurementProfile a = MeasurementProfile.defaults();
        MeasurementProfile b = new MeasurementProfile(10, 100, 1, 0);
        assertNotEquals(a, b);
    }
}
