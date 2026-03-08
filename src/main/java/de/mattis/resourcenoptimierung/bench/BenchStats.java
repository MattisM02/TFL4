package de.mattis.resourcenoptimierung.bench;

import java.util.List;

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
     * Berechnet den arithmetischen Mittelwert (NaN-Werte werden ignoriert).
     *
     * @param values Messwerte (mind. 1 nicht-NaN-Element)
     * @return Mittelwert, oder {@code NaN} bei leerem Array oder wenn alle Werte NaN sind
     */
    public static double mean(double[] values) {
        if (values == null || values.length == 0) return Double.NaN;
        double sum = 0;
        int count = 0;
        for (double v : values) {
            if (!Double.isNaN(v)) {
                sum += v;
                count++;
            }
        }
        return count > 0 ? sum / count : Double.NaN;
    }

    /**
     * Berechnet die Stichproben-Standardabweichung (mit Bessel-Korrektur, Division durch {@code n-1}).
     * NaN-Werte werden ignoriert.
     *
     * @param values Messwerte (mind. 2 nicht-NaN-Elemente fuer sinnvolles Ergebnis)
     * @return Stichproben-Standardabweichung, oder {@code NaN} bei weniger als 2 nicht-NaN-Werten
     */
    public static double sampleStddev(double[] values) {
        if (values == null || values.length < 2) return Double.NaN;
        double m = mean(values);
        if (Double.isNaN(m)) return Double.NaN;
        double sqSum = 0;
        int count = 0;
        for (double v : values) {
            if (!Double.isNaN(v)) {
                sqSum += (v - m) * (v - m);
                count++;
            }
        }
        return count < 2 ? Double.NaN : Math.sqrt(sqSum / (count - 1));
    }

    /**
     * Berechnet die Halbbreite des 95%-Konfidenzintervalls.
     * NaN-Werte werden ignoriert.
     *
     * <p>Formel: {@code t(alpha/2, n-1) * s / sqrt(n)}, wobei {@code s} die
     * Stichproben-Standardabweichung ist und {@code n} die Anzahl nicht-NaN-Werte.
     *
     * @param values Messwerte (mind. 2 nicht-NaN-Elemente)
     * @return Halbbreite des 95%-KI, oder {@code NaN} bei weniger als 2 nicht-NaN-Werten
     */
    public static double confidenceInterval95(double[] values) {
        if (values == null || values.length < 2) return Double.NaN;
        int count = 0;
        for (double v : values) if (!Double.isNaN(v)) count++;
        if (count < 2) return Double.NaN;
        double s = sampleStddev(values);
        int df = count - 1;
        double t = tValue(df);
        return t * s / Math.sqrt(count);
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

    // ───────────────────── Percentile ─────────────────────

    /**
     * Berechnet das p-te Perzentil einer bereits sortierten Liste.
     *
     * <p>Verwendet nearest-rank Methode: Index = ceil(p * n) - 1, geclampt auf [0, n-1].
     *
     * @param sorted  aufsteigend sortierte Werte (darf null/leer sein)
     * @param p       Perzentil als Bruchteil, z.B. 0.95 fuer p95
     * @return Perzentil-Wert, oder {@code NaN} bei null/leerer Liste
     */
    public static double percentile(List<Double> sorted, double p) {
        if (sorted == null || sorted.isEmpty()) return Double.NaN;
        int idx = (int) Math.ceil(p * sorted.size()) - 1;
        idx = Math.max(0, Math.min(idx, sorted.size() - 1));
        return sorted.get(idx);
    }

    // ───────────────────── Docker-Phase-Aggregation ─────────────────────

    /**
     * Aggregierte Ressourcen-Kennzahlen einer Docker-Sampling-Phase (IDLE, LOAD, POST).
     *
     * @param cpuAvg         durchschnittliche CPU-Auslastung (%)
     * @param memAvg         durchschnittliche Speicherauslastung (%)
     * @param memMax         maximale Speicherauslastung (%)
     * @param memUsageAtMax  lesbare Speicherangabe zum Zeitpunkt des Maximums (z.B. "512MiB / 768MiB"),
     *                       oder {@code null} wenn nicht verfuegbar
     */
    public record DockerPhaseAvg(double cpuAvg, double memAvg, double memMax, String memUsageAtMax) {

        /** Konstruktor ohne memUsageAtMax (fuer Kontexte, die es nicht benoetigen). */
        public DockerPhaseAvg(double cpuAvg, double memAvg, double memMax) {
            this(cpuAvg, memAvg, memMax, null);
        }
    }

    /**
     * Berechnet die aggregierten Docker-Statistiken fuer eine Sampling-Phase.
     *
     * @param samples Liste von Docker-Stat-Samples (darf null/leer sein)
     * @return aggregierte Kennzahlen, oder {@code null} bei null/leerer Liste
     */
    public static DockerPhaseAvg dockerPhaseAvg(List<DockerStatSample> samples) {
        if (samples == null || samples.isEmpty()) return null;

        double cpuSum = 0.0;
        double memSum = 0.0;
        double memMax = -1.0;
        String memUsageAtMax = null;

        for (DockerStatSample s : samples) {
            cpuSum += s.cpuPercent();
            memSum += s.memPercent();
            if (s.memPercent() > memMax) {
                memMax = s.memPercent();
                memUsageAtMax = s.memUsageRaw() + " / " + s.memLimitRaw();
            }
        }

        return new DockerPhaseAvg(
                cpuSum / samples.size(),
                memSum / samples.size(),
                memMax,
                memUsageAtMax
        );
    }

    /**
     * Extrahiert einen Wert aus einem {@link DockerPhaseAvg} mit null-Safety.
     *
     * @param phase Phasen-Aggregate (darf null sein)
     * @param fn    Extractor-Funktion
     * @return extrahierter Wert, oder {@code NaN} wenn phase null ist
     */
    public static double dval(DockerPhaseAvg phase, java.util.function.ToDoubleFunction<DockerPhaseAvg> fn) {
        return phase != null ? fn.applyAsDouble(phase) : Double.NaN;
    }
}
