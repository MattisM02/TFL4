package de.mattis.resourcenoptimierung.bench;

/**
 * Statistische Hilfsmethoden fuer die Benchmark-Auswertung.
 *
 * <p>Alle Methoden arbeiten auf {@code double[]}-Arrays und sind zustandslos.
 * Die Standardabweichung verwendet Bessel-Korrektur (Stichproben-Stddev, {@code n-1}).
 * Das 95%-Konfidenzintervall nutzt die t-Verteilung mit vorberechneten kritischen Werten.
 */
public final class BenchStats {

    private BenchStats() {}

    // ───────────────────── t-Verteilung (zweiseitig, alpha=0.05) ─────────────────────

    /**
     * Kritische t-Werte fuer 95%-Konfidenzintervall (zweiseitig, alpha=0.05).
     * Index = Freiheitsgrade (df = n-1). Index 0 ist unused,
     * Index 1 = df=1 (n=2), Index 2 = df=2 (n=3), usw.
     * Ab df=121 wird z=1.96 (Normalverteilungs-Approximation) verwendet.
     */
    private static final double[] T_VALUES = {
            Double.NaN,   // df=0 (unused)
            12.706, 4.303, 3.182, 2.776, 2.571,  // df=1..5
            2.447, 2.365, 2.306, 2.262, 2.228,   // df=6..10
            2.201, 2.179, 2.160, 2.145, 2.131,   // df=11..15
            2.120, 2.110, 2.101, 2.093, 2.086,   // df=16..20
            2.080, 2.074, 2.069, 2.064, 2.060,   // df=21..25
            2.056, 2.052, 2.048, 2.045, 2.042,   // df=26..30
            2.040, 2.037, 2.035, 2.032, 2.030,   // df=31..35
            2.028, 2.026, 2.024, 2.023, 2.021,   // df=36..40
            2.020, 2.018, 2.017, 2.015, 2.014,   // df=41..45
            2.013, 2.012, 2.011, 2.010, 2.009,   // df=46..50
            2.008, 2.007, 2.006, 2.005, 2.004,   // df=51..55
            2.003, 2.002, 2.002, 2.001, 2.000,   // df=56..60
            2.000, 1.999, 1.998, 1.998, 1.997,   // df=61..65
            1.997, 1.996, 1.995, 1.995, 1.994,   // df=66..70
            1.994, 1.993, 1.993, 1.993, 1.992,   // df=71..75
            1.992, 1.991, 1.991, 1.990, 1.990,   // df=76..80
            1.990, 1.989, 1.989, 1.989, 1.988,   // df=81..85
            1.988, 1.988, 1.987, 1.987, 1.987,   // df=86..90
            1.986, 1.986, 1.986, 1.986, 1.985,   // df=91..95
            1.985, 1.985, 1.984, 1.984, 1.984,   // df=96..100
            1.984, 1.983, 1.983, 1.983, 1.983,   // df=101..105
            1.983, 1.982, 1.982, 1.982, 1.982,   // df=106..110
            1.982, 1.981, 1.981, 1.981, 1.981,   // df=111..115
            1.981, 1.980, 1.980, 1.980, 1.980     // df=116..120
    };

    private static final double Z_95 = 1.960;

    /**
     * Gibt den kritischen t-Wert fuer die gegebenen Freiheitsgrade zurueck.
     *
     * @param df Freiheitsgrade (= n - 1)
     * @return t-Wert fuer 95%-Konfidenzintervall (zweiseitig)
     */
    static double tValue(int df) {
        if (df < 1) return Double.NaN;
        if (df < T_VALUES.length) return T_VALUES[df];
        return Z_95;
    }

    // ───────────────────── Deskriptive Statistik ─────────────────────

    /**
     * Berechnet den arithmetischen Mittelwert.
     *
     * @param values Messwerte (mind. 1 Element)
     * @return Mittelwert, oder {@code NaN} bei leerem Array
     */
    public static double mean(double[] values) {
        if (values == null || values.length == 0) return Double.NaN;
        double sum = 0;
        for (double v : values) sum += v;
        return sum / values.length;
    }

    /**
     * Berechnet die Stichproben-Standardabweichung (mit Bessel-Korrektur, Division durch {@code n-1}).
     *
     * @param values Messwerte (mind. 2 Elemente fuer sinnvolles Ergebnis)
     * @return Stichproben-Standardabweichung, oder {@code NaN} bei weniger als 2 Werten
     */
    public static double sampleStddev(double[] values) {
        if (values == null || values.length < 2) return Double.NaN;
        double m = mean(values);
        double sqSum = 0;
        for (double v : values) sqSum += (v - m) * (v - m);
        return Math.sqrt(sqSum / (values.length - 1));
    }

    /**
     * Berechnet die Halbbreite des 95%-Konfidenzintervalls.
     *
     * <p>Formel: {@code t(alpha/2, n-1) * s / sqrt(n)}, wobei {@code s} die
     * Stichproben-Standardabweichung ist.
     *
     * @param values Messwerte (mind. 2 Elemente)
     * @return Halbbreite des 95%-KI, oder {@code NaN} bei weniger als 2 Werten
     */
    public static double confidenceInterval95(double[] values) {
        if (values == null || values.length < 2) return Double.NaN;
        double s = sampleStddev(values);
        int df = values.length - 1;
        double t = tValue(df);
        return t * s / Math.sqrt(values.length);
    }

    /**
     * Berechnet den relativen Wert bezogen auf einen Baseline-Wert (= 100%).
     *
     * @param value    aktueller Messwert
     * @param baseline Referenzwert (= 100%)
     * @return relativer Wert in Prozent (z.B. 105.0 = 5% schlechter)
     */
    public static double relativeToBaseline(double value, double baseline) {
        if (baseline == 0 || Double.isNaN(baseline)) return Double.NaN;
        return (value / baseline) * 100.0;
    }
}
