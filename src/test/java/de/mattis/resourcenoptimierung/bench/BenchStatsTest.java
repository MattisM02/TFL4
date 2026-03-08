package de.mattis.resourcenoptimierung.bench;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests fuer BenchStats: mean, sampleStddev, confidenceInterval95, relativeToBaseline, tValue.
 */
class BenchStatsTest {

    private static final double EPS = 1e-6;

    // ======================== mean ========================

    @Test
    void mean_singleValue() {
        assertEquals(5.0, BenchStats.mean(new double[]{5.0}), EPS);
    }

    @Test
    void mean_multipleValues() {
        // (2 + 4 + 6) / 3 = 4.0
        assertEquals(4.0, BenchStats.mean(new double[]{2, 4, 6}), EPS);
    }

    @Test
    void mean_nullReturnsNaN() {
        assertTrue(Double.isNaN(BenchStats.mean(null)));
    }

    @Test
    void mean_emptyReturnsNaN() {
        assertTrue(Double.isNaN(BenchStats.mean(new double[]{})));
    }

    @Test
    void mean_identicalValues() {
        assertEquals(7.0, BenchStats.mean(new double[]{7, 7, 7, 7}), EPS);
    }

    // ======================== sampleStddev ========================

    @Test
    void sampleStddev_knownValues() {
        // values: 2, 4, 4, 4, 5, 5, 7, 9
        // mean = 5.0
        // sum of sq diffs = 9+1+1+1+0+0+4+16 = 32
        // sample variance = 32/7 = 4.571...
        // sample stddev = sqrt(4.571...) = 2.13809...
        double[] values = {2, 4, 4, 4, 5, 5, 7, 9};
        assertEquals(2.138090, BenchStats.sampleStddev(values), 1e-4);
    }

    @Test
    void sampleStddev_twoValues() {
        // values: 10, 20 -> mean = 15, sq diffs = 25+25 = 50, var = 50/1 = 50, sd = 7.0711
        assertEquals(7.07107, BenchStats.sampleStddev(new double[]{10, 20}), 1e-4);
    }

    @Test
    void sampleStddev_identicalValues() {
        assertEquals(0.0, BenchStats.sampleStddev(new double[]{5, 5, 5}), EPS);
    }

    @Test
    void sampleStddev_singleValueReturnsNaN() {
        assertTrue(Double.isNaN(BenchStats.sampleStddev(new double[]{5.0})));
    }

    @Test
    void sampleStddev_nullReturnsNaN() {
        assertTrue(Double.isNaN(BenchStats.sampleStddev(null)));
    }

    @Test
    void sampleStddev_emptyReturnsNaN() {
        assertTrue(Double.isNaN(BenchStats.sampleStddev(new double[]{})));
    }

    // ======================== confidenceInterval95 ========================

    @Test
    void ci95_twoValues() {
        // values: 10, 20 -> mean=15, sd=7.0711, t(df=1)=12.706
        // CI = 12.706 * 7.0711 / sqrt(2) = 12.706 * 5.0 = 63.53
        double ci = BenchStats.confidenceInterval95(new double[]{10, 20});
        assertEquals(63.53, ci, 0.1);
    }

    @Test
    void ci95_threeValues() {
        // values: 10, 20, 30 -> mean=20, sd=10.0, t(df=2)=4.303
        // CI = 4.303 * 10 / sqrt(3) = 4.303 * 5.7735 = 24.85
        double ci = BenchStats.confidenceInterval95(new double[]{10, 20, 30});
        assertEquals(24.85, ci, 0.1);
    }

    @Test
    void ci95_identicalValues() {
        // stddev=0 -> CI=0
        double ci = BenchStats.confidenceInterval95(new double[]{5, 5, 5, 5, 5});
        assertEquals(0.0, ci, EPS);
    }

    @Test
    void ci95_singleValueReturnsNaN() {
        assertTrue(Double.isNaN(BenchStats.confidenceInterval95(new double[]{5.0})));
    }

    @Test
    void ci95_nullReturnsNaN() {
        assertTrue(Double.isNaN(BenchStats.confidenceInterval95(null)));
    }

    // ======================== relativeToBaseline ========================

    @Test
    void relativeToBaseline_same() {
        assertEquals(100.0, BenchStats.relativeToBaseline(50, 50), EPS);
    }

    @Test
    void relativeToBaseline_double() {
        assertEquals(200.0, BenchStats.relativeToBaseline(100, 50), EPS);
    }

    @Test
    void relativeToBaseline_half() {
        assertEquals(50.0, BenchStats.relativeToBaseline(25, 50), EPS);
    }

    @Test
    void relativeToBaseline_zeroBaseline() {
        assertTrue(Double.isNaN(BenchStats.relativeToBaseline(10, 0)));
    }

    @Test
    void relativeToBaseline_nanBaseline() {
        assertTrue(Double.isNaN(BenchStats.relativeToBaseline(10, Double.NaN)));
    }

    // ======================== tValue ========================

    @Test
    void tValue_df1() {
        assertEquals(12.706, BenchStats.tValue(1), 0.001);
    }

    @Test
    void tValue_df2() {
        assertEquals(4.303, BenchStats.tValue(2), 0.001);
    }

    @Test
    void tValue_df30() {
        assertEquals(2.042, BenchStats.tValue(30), 0.001);
    }

    @Test
    void tValue_df120() {
        assertEquals(1.980, BenchStats.tValue(120), 0.001);
    }

    @Test
    void tValue_largeDF_usesZ() {
        // df > 120 -> z = 1.96
        assertEquals(1.960, BenchStats.tValue(500), 0.001);
    }

    @Test
    void tValue_df0_returnsNaN() {
        assertTrue(Double.isNaN(BenchStats.tValue(0)));
    }

    @Test
    void tValue_negative_returnsNaN() {
        assertTrue(Double.isNaN(BenchStats.tValue(-1)));
    }
}
