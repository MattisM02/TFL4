package de.mattis.resourcenoptimierung.bench;

/**
 * Parametrisierbares Messprofil fuer Benchmark-Runs.
 *
 * Steuert, wie viele Requests in den einzelnen Phasen
 * (Warmup, Messung) ausgefuehrt werden und ob zwischen
 * den Requests gewartet wird (fuer konstante Lastmuster).
 *
 * Die Defaults entsprechen dem bisherigen Verhalten:
 * 20 Warmup-Requests, 100 Mess-Requests, kein Sleep.
 *
 * CLI-Argumente:
 *   --warmupRequests       Anzahl Warmup-Requests (default: 20)
 *   --measureRequests      Anzahl Mess-Requests (default: 100)
 *   --concurrency          Parallele Requests (default: 1, sequentiell)
 *   --sleepBetweenRequestsMs  Pause zwischen Requests in ms (default: 0)
 *                             Nützlich fuer konstante Last, z.B. 100ms = ~10 req/s
 *
 * @param warmupRequests Anzahl Warmup-Requests vor der Messphase
 * @param measureRequests Anzahl Requests in der Messphase
 * @param concurrency Anzahl paralleler Requests (1 = sequentiell)
 * @param sleepBetweenRequestsMs Pause in ms zwischen aufeinanderfolgenden Requests (0 = so schnell wie moeglich)
 */
public record MeasurementProfile(
        int warmupRequests,
        int measureRequests,
        int concurrency,
        long sleepBetweenRequestsMs
) {

    /**
     * Standard-Messprofil: 20 Warmup, 100 Messung, sequentiell, kein Sleep.
     * Entspricht dem bisherigen hartcodierten Verhalten.
     */
    public static MeasurementProfile defaults() {
        return new MeasurementProfile(20, 100, 1, 0);
    }

    /**
     * Validiert die Profilwerte und gibt Warnungen bei fragwuerdigen Werten aus.
     *
     * @throws IllegalArgumentException bei ungueltigen Werten
     */
    public MeasurementProfile {
        if (warmupRequests < 0) throw new IllegalArgumentException("warmupRequests must be >= 0, got: " + warmupRequests);
        if (measureRequests < 1) throw new IllegalArgumentException("measureRequests must be >= 1, got: " + measureRequests);
        if (concurrency < 1) throw new IllegalArgumentException("concurrency must be >= 1, got: " + concurrency);
        if (sleepBetweenRequestsMs < 0) throw new IllegalArgumentException("sleepBetweenRequestsMs must be >= 0, got: " + sleepBetweenRequestsMs);
    }

    @Override
    public String toString() {
        return String.format("warmup=%d measure=%d concurrency=%d sleepMs=%d",
                warmupRequests, measureRequests, concurrency, sleepBetweenRequestsMs);
    }
}
