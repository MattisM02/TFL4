package de.mattis.resourcenoptimierung.bench;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Ermittelt, wann ein gestarteter Service als bereit gilt.
 *
 * <p>Der Prober pollt mehrere URLs in einer festen Reihenfolge, damit der Benchmark
 * auch dann funktioniert, wenn Actuator-Endpunkte fehlen oder gesichert sind.
 *
 * <p>Readiness bedeutet hier pragmatisch: Ein HTTP-GET liefert Status 200.
 * Je nach Endpoint ist das semantisch genauer (Actuator Readiness) oder nur
 * ein Fallback (Workload-Endpoint).
 *
 * <p>Optional kann ein {@link ContainerAliveCheck} uebergeben werden, um bei jedem
 * Poll-Zyklus zu pruefen, ob der Container noch laeuft. Wenn der Container
 * bereits beendet ist (z.B. Crash beim Start), wird sofort abgebrochen,
 * statt das volle Timeout abzuwarten.
 *
 * <h3>Testbarkeit</h3>
 * <p>Diese Klasse ist absichtlich nicht unit-getestet: Sie ist ein duenner Wrapper um
 * {@link HttpClient}, der reale HTTP-Requests an einen Docker-Container sendet.
 * Ein sinnvoller Test wuerde entweder einen echten HTTP-Server (z.B. WireMock) oder
 * umfangreiches Mocking des {@code HttpClient} erfordern — beides waere fuer den
 * geringen Mehrwert unverhältnismaessig aufwendig. Die Klasse wird stattdessen durch
 * die End-to-End-Benchmarks implizit getestet.
 */
public final class ReadinessProber implements AutoCloseable {

    /**
     * Callback-Interface zur Pruefung, ob der Container noch lebt.
     * Wird waehrend des Pollings aufgerufen, um fruehzeitig abzubrechen.
     */
    @FunctionalInterface
    public interface ContainerAliveCheck {
        /**
         * Prueft, ob der Container noch laeuft.
         * @return true wenn der Container laeuft, false wenn er beendet ist
         */
        boolean isAlive();
    }

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
                .proxy(java.net.ProxySelector.of(null))
                .build();
    }

    /**
     * Schliesst den internen HttpClient und gibt Ressourcen frei.
     * Nutzt Reflection, da HttpClient.close() erst ab JDK 21 verfuegbar ist
     * und das Projekt mit source-level 17 kompiliert wird.
     */
    @Override
    public void close() {
        try {
            var m = http.getClass().getMethod("close");
            m.invoke(http);
        } catch (NoSuchMethodException e) {
            // JDK < 21: close() existiert nicht, nichts zu tun
        } catch (Exception e) {
            // best-effort: ignorieren
        }
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
        return waitUntilReady(baseUrl, timeout, fallbackPath, null);
    }

    /**
     * Wartet bis der Service unter baseUrl bereit ist oder das Timeout abläuft.
     * Prueft optional bei jedem Poll-Zyklus, ob der Container noch lebt.
     *
     * Checks in Reihenfolge:
     * - /actuator/health/readiness
     * - /actuator/health
     * - fallbackPath (z.B. /json oder /alloc)
     *
     * @param baseUrl Basis-URL des Services (z.B. "http://localhost:8080")
     * @param timeout Gesamt-Timeout
     * @param fallbackPath Pfad oder vollständige URL für den letzten Fallback
     * @param aliveCheck optionaler Check, ob der Container noch laeuft (null = kein Check)
     * @return ReadinessResult mit verwendetem Check und Dauer
     * @throws Exception wenn das Timeout abläuft, der Container beendet ist oder ein unerwarteter Fehler auftritt
     */
    public ReadinessResult waitUntilReady(String baseUrl, Duration timeout, String fallbackPath,
                                          ContainerAliveCheck aliveCheck) throws Exception {
        long start = System.nanoTime();

        // 1) /actuator/health/readiness
        if (pollUntil200(baseUrl + "/actuator/health/readiness", timeout, aliveCheck)) {
            return new ReadinessResult(ReadinessCheckUsed.ACTUATOR_READINESS, elapsedMs(start));
        }

        // 2) /actuator/health (nur wenn noch Zeit übrig ist)
        Duration remaining = remaining(timeout, start);
        if (!remaining.isNegative() && pollUntil200(baseUrl + "/actuator/health", remaining, aliveCheck)) {
            return new ReadinessResult(ReadinessCheckUsed.ACTUATOR_HEALTH, elapsedMs(start));
        }

        // 3) letzter Fallback: workload endpoint until 200 (nur wenn noch Zeit übrig ist)
        remaining = remaining(timeout, start);
        if (!remaining.isNegative()) {
            String fallbackUrl = toUrl(baseUrl, fallbackPath);
            if (pollUntil200(fallbackUrl, remaining, aliveCheck)) {
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
     * Prueft optional bei jedem Zyklus, ob der Container noch lebt.
     *
     * Abbruchregeln:
     * - 200: ready
     * - 401/403/404: Endpoint nicht nutzbar, sofort abbrechen (damit Fallback weitergehen kann)
     * - Container nicht mehr am Leben: sofort RuntimeException
     * - sonst: weiter pollen bis Timeout
     *
     * @param url vollständige URL
     * @param timeout Zeitfenster für das Polling
     * @param aliveCheck optionaler Check, ob der Container noch laeuft (null = kein Check)
     * @return true, wenn 200 erreicht wurde, sonst false
     * @throws Exception wenn der Sleep unterbrochen wird oder der Container beendet ist
     */
    private boolean pollUntil200(String url, Duration timeout, ContainerAliveCheck aliveCheck) throws Exception {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        int pollsSinceLastAliveCheck = 0;

        while (System.nanoTime() < deadlineNanos) {
            int code = tryGetStatus(url);
            if (code == 200) return true;

            // Fallback, wenn Endpoint nicht verfügbar/gesichert ist
            if (code == 401 || code == 403 || code == 404) {
                return false;
            }

            // Container-Alive-Check alle ~10 Polls (~1.5s) um Docker-CLI-Overhead zu begrenzen
            pollsSinceLastAliveCheck++;
            if (aliveCheck != null && pollsSinceLastAliveCheck >= 10) {
                pollsSinceLastAliveCheck = 0;
                if (!aliveCheck.isAlive()) {
                    throw new RuntimeException(
                            "Container exited during readiness polling (detected via container-status check). " +
                            "The container likely crashed at startup.");
                }
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
