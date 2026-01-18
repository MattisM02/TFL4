package de.mattis.resourcenoptimierung.bench;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Ermittelt, wann ein gestarteter Service als bereit gilt.
 *
 * Der Prober pollt mehrere URLs in einer festen Reihenfolge, damit der Benchmark
 * auch dann funktioniert, wenn Actuator-Endpunkte fehlen oder gesichert sind.
 *
 * Readiness bedeutet hier pragmatisch: Ein HTTP-GET liefert Status 200.
 * Je nach Endpoint ist das semantisch genauer (Actuator Readiness) oder nur
 * ein Fallback (Workload-Endpoint).
 */
public final class ReadinessProber {

    /**
     * HTTP-Client für Polling-Requests mit kurzen Timeouts.
     */
    private final HttpClient http;

    /**
     * Erstellt einen ReadinessProber mit kurzem Connect-Timeout.
     */
    public ReadinessProber() {
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
    }

    /**
     * Ergebnis der Readiness-Ermittlung.
     *
     * @param used welcher Check erfolgreich war
     * @param readinessMs Dauer bis "ready" in Millisekunden
     */
    public record ReadinessResult(ReadinessCheckUsed used, long readinessMs) {}

    /**
     * Wartet bis der Service unter baseUrl bereit ist oder das Timeout abläuft.
     *
     * Checks in Reihenfolge:
     * - /actuator/health/readiness
     * - /actuator/health
     * - fallbackPath (z.B. /json oder /alloc)
     *
     * @param baseUrl Basis-URL des Services (z.B. "http://localhost:8080")
     * @param timeout Gesamt-Timeout
     * @param fallbackPath Pfad oder vollständige URL für den letzten Fallback
     * @return ReadinessResult mit verwendetem Check und Dauer
     * @throws Exception wenn das Timeout abläuft oder ein unerwarteter Fehler auftritt
     */
    public ReadinessResult waitUntilReady(String baseUrl, Duration timeout, String fallbackPath) throws Exception {
        long start = System.nanoTime();

        // 1) /actuator/health/readiness
        if (pollUntil200(baseUrl + "/actuator/health/readiness", timeout)) {
            return new ReadinessResult(ReadinessCheckUsed.ACTUATOR_READINESS, elapsedMs(start));
        }

        // 2) /actuator/health (nur wenn noch Zeit übrig ist)
        Duration remaining = remaining(timeout, start);
        if (!remaining.isNegative() && pollUntil200(baseUrl + "/actuator/health", remaining)) {
            return new ReadinessResult(ReadinessCheckUsed.ACTUATOR_HEALTH, elapsedMs(start));
        }

        // 3) letzter Fallback: workload endpoint until 200 (nur wenn noch Zeit übrig ist)
        remaining = remaining(timeout, start);
        if (!remaining.isNegative()) {
            String fallbackUrl = toUrl(baseUrl, fallbackPath);
            if (pollUntil200(fallbackUrl, remaining)) {
                return new ReadinessResult(ReadinessCheckUsed.WORKLOAD_UNTIL_200, elapsedMs(start));
            }
        }

        throw new RuntimeException("Readiness timeout after " + timeout);
    }

    /**
     * Baut aus baseUrl und fallbackPath eine vollständige URL.
     *
     * fallbackPath darf sein:
     * - "/alloc" oder "/json" (wird an baseUrl gehängt)
     * - "http://..." oder "https://..." (wird direkt genutzt)
     *
     * @param baseUrl Basis-URL des Services
     * @param fallbackPath Pfad oder vollständige URL
     * @return vollständige URL
     */
    private static String toUrl(String baseUrl, String fallbackPath) {
        if (fallbackPath == null || fallbackPath.isBlank()) {
            // "sicherer" Default, falls jemand es vergisst zu setzen
            return baseUrl + "/json";
        }
        String p = fallbackPath.trim();
        if (p.startsWith("http://") || p.startsWith("https://")) {
            return p;
        }
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        return baseUrl + p;
    }

    /**
     * Pollt eine URL, bis Status 200 zurückkommt oder das Timeout erreicht ist.
     *
     * Abbruchregeln:
     * - 200: ready
     * - 401/403/404: Endpoint nicht nutzbar, sofort abbrechen (damit Fallback weitergehen kann)
     * - sonst: weiter pollen bis Timeout
     *
     * @param url vollständige URL
     * @param timeout Zeitfenster für das Polling
     * @return true, wenn 200 erreicht wurde, sonst false
     * @throws Exception wenn der Sleep unterbrochen wird
     */
    private boolean pollUntil200(String url, Duration timeout) throws Exception {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();

        while (System.nanoTime() < deadlineNanos) {
            int code = tryGetStatus(url);
            if (code == 200) return true;

            // Fallback, wenn Endpoint nicht verfügbar/gesichert ist
            if (code == 401 || code == 403 || code == 404) {
                return false;
            }

            Thread.sleep(150);
        }
        return false;
    }

    /**
     * Führt einen HTTP-GET aus und gibt nur den Statuscode zurück.
     * Der Response-Body wird verworfen.
     *
     * Bei Netzwerkfehlern wird -1 zurückgegeben, damit weiter gepollt werden kann.
     *
     * @param url vollständige URL
     * @return HTTP-Statuscode oder -1 bei Fehlern
     */
    private int tryGetStatus(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();

            HttpResponse<Void> res = http.send(req, HttpResponse.BodyHandlers.discarding());
            return res.statusCode();
        } catch (Exception e) {
            return -1; // network/timeout/etc.
        }
    }

    /**
     * Berechnet die vergangene Zeit seit startNanos in Millisekunden.
     *
     * @param startNanos Startzeitpunkt (System.nanoTime)
     * @return vergangene Millisekunden
     */
    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    /**
     * Berechnet die verbleibende Zeit des Gesamt-Timeouts.
     *
     * @param total Gesamt-Timeout
     * @param startNanos Startzeitpunkt (System.nanoTime)
     * @return verbleibende Zeit (kann negativ sein)
     */
    private static Duration remaining(Duration total, long startNanos) {
        long usedNanos = System.nanoTime() - startNanos;
        return total.minusNanos(usedNanos);
    }
}
