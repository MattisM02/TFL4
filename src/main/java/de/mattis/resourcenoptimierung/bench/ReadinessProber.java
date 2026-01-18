package de.mattis.resourcenoptimierung.bench;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Ermittelt robust, wann ein gestarteter Container/Service „bereit“ ist.
 *
 * <p>Hintergrund: Je nach Spring-Konfiguration sind Readiness-Endpunkte nicht immer verfügbar
 * (z. B. fehlende Actuator-Exposure, Security, andere Spring-Version). Damit ein Benchmark
 * nicht unnötig fehlschlägt, gibt es eine Fallback-Strategie.</p>
 *
 * <p>Diese Klasse pollt verschiedene URLs in einer festen Reihenfolge und gibt zurück:</p>
 * <ul>
 *   <li>welcher Check erfolgreich war ({@link ReadinessCheckUsed})</li>
 *   <li>wie lange es insgesamt gedauert hat (readinessMs)</li>
 * </ul>
 *
 * <p>Wichtig: Readiness ist hier rein pragmatisch definiert als „ein HTTP-GET liefert 200 OK“.
 * Je nach Endpoint ist das semantisch genauer (Actuator Readiness) oder nur ein Workaround (/json).</p>
 */
public final class ReadinessProber {

    /**
     * HTTP-Client für alle Polling-Requests.
     *
     * <p>Es werden kurze Timeouts genutzt, da während des Starts häufig Verbindungsfehler auftreten
     * (Port noch nicht offen, Server noch nicht gebunden etc.).</p>
     */
    private final HttpClient http;

    /**
     * Erstellt einen {@link ReadinessProber} mit einem {@link HttpClient} und kurzer Connect-Timeout-Policy.
     */
    public ReadinessProber() {
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
    }

    /**
     * Ergebnis der Readiness-Ermittlung.
     *
     * @param used        welcher Readiness-Mechanismus am Ende verwendet wurde
     * @param readinessMs Zeit in Millisekunden vom Start der Messung bis zum „ready“-Zeitpunkt
     */
    public record ReadinessResult(ReadinessCheckUsed used, long readinessMs) {}

    /**
     * Wartet bis der Service unter {@code baseUrl} bereit ist oder das Timeout abläuft.
     *
     * <p>Reihenfolge der Checks (Fallback-Kette):</p>
     * <ol>
     *   <li>{@code /actuator/health/readiness} (bevorzugt, semantisch korrekt)</li>
     *   <li>{@code /actuator/health} (Fallback, weniger präzise)</li>
     *   <li>{@code fallbackPath} (letzter Fallback: „bereit“, sobald der Workload-Endpoint 200 liefert)</li>
     * </ol>
     *
     * <p>Für jeden Schritt wird nur die verbleibende Zeit des Gesamt-Timeouts genutzt.</p>
     *
     * @param baseUrl      Basis-URL des Services, z. B. {@code http://localhost:8080}
     * @param timeout      Gesamtzeit, nach der abgebrochen wird
     * @param fallbackPath Pfad (oder vollständige URL) für den letzten Fallback, z. B. {@code /alloc} oder {@code /json}
     * @return {@link ReadinessResult} mit verwendetem Check und Dauer in ms
     * @throws Exception wenn das Timeout abläuft oder unerwartete Fehler auftreten
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
     * Akzeptiert fallbackPath als:
     * - "/alloc" oder "/json" → wird an baseUrl gehängt
     * - "http:// ..." → wird direkt genutzt
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
     * Pollt eine URL, bis ein HTTP-Status {@code 200} zurückkommt oder das Timeout erreicht ist.
     *
     * <p>Abbruch-Regeln:</p>
     * <ul>
     *   <li>{@code 200} → ready</li>
     *   <li>{@code 401/403/404} → Endpoint ist nicht nutzbar (gesichert oder nicht vorhanden),
     *       daher sofort abbrechen und {@code false} zurückgeben (damit Fallback weiterlaufen kann).</li>
     *   <li>Alles andere (inkl. Netzwerkfehler) → weiter pollen bis Timeout</li>
     * </ul>
     *
     * @param url     vollständige URL, z. B. {@code http://localhost:8080/actuator/health}
     * @param timeout Zeitfenster, in dem gepollt wird
     * @return {@code true} wenn 200 erreicht wurde, sonst {@code false}
     * @throws Exception wenn {@link Thread#sleep(long)} unterbrochen wird
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
     *
     * <p>Der Body wird bewusst verworfen ({@link HttpResponse.BodyHandlers#discarding()}),
     * da es hier nur um Erreichbarkeit/Status geht und nicht um Payload.</p>
     *
     * <p>Bei Netzwerkfehlern (Verbindung abgelehnt, Timeout, etc.) wird {@code -1} zurückgegeben,
     * damit {@link #pollUntil200(String, Duration)} weiter pollen kann.</p>
     *
     * @param url vollständige URL
     * @return HTTP-Statuscode oder {@code -1} bei Netzwerk-/Timeout-Fehlern
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
     * Berechnet die vergangene Zeit seit {@code startNanos} in Millisekunden.
     *
     * @param startNanos Startzeitpunkt in {@code System.nanoTime()}-Ticks
     * @return vergangene Millisekunden
     */
    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    /**
     * Berechnet, wie viel Zeit vom Gesamt-Timeout noch übrig ist.
     *
     * <p>Beispiel: Wenn das Gesamt-Timeout 60s ist und bereits 10s vergangen sind,
     * liefert diese Methode 50s.</p>
     *
     * @param total     Gesamt-Timeout
     * @param startNanos Startzeitpunkt (nanoTime)
     * @return verbleibende Dauer (kann negativ sein, wenn das Timeout überschritten ist)
     */
    private static Duration remaining(Duration total, long startNanos) {
        long usedNanos = System.nanoTime() - startNanos;
        return total.minusNanos(usedNanos);
    }
}
