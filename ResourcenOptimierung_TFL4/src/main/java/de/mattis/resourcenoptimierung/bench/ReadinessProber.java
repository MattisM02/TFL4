package de.mattis.resourcenoptimierung.bench;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public final class ReadinessProber {

    private final HttpClient http;

    public ReadinessProber() {
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
    }

    public record ReadinessResult(ReadinessCheckUsed used, long readinessMs) {}

    public ReadinessResult waitUntilReady(String baseUrl, Duration timeout) throws Exception {
        long start = System.nanoTime();

        // 1) /actuator/health/readiness
        if (pollUntil200(baseUrl + "/actuator/health/readiness", timeout)) {
            return new ReadinessResult(ReadinessCheckUsed.ACTUATOR_READINESS, elapsedMs(start));
        }

        // 2) /actuator/health
        Duration remaining = remaining(timeout, start);
        if (!remaining.isNegative() && pollUntil200(baseUrl + "/actuator/health", remaining)) {
            return new ReadinessResult(ReadinessCheckUsed.ACTUATOR_HEALTH, elapsedMs(start));
        }

        // 3) fallback: /json until 200
        remaining = remaining(timeout, start);
        if (!remaining.isNegative() && pollUntil200(baseUrl + "/json", remaining)) {
            return new ReadinessResult(ReadinessCheckUsed.JSON_UNTIL_200, elapsedMs(start));
        }

        throw new RuntimeException("Readiness timeout after " + timeout);
    }

    private boolean pollUntil200(String url, Duration timeout) throws Exception {
        long start = System.nanoTime();
        long deadlineNanos = start + timeout.toNanos();

        while (System.nanoTime() < deadlineNanos) {
            int code = tryGetStatus(url);
            if (code == 200) return true;

            // bei 401/403/404 etc. nicht “abbrechen”, sondern einfach Fallback später ermöglichen
            // hier: kurzer Sleep, damit wir nicht spammen
            Thread.sleep(150);
        }
        return false;
    }

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

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private static Duration remaining(Duration total, long startNanos) {
        long usedNanos = System.nanoTime() - startNanos;
        return total.minusNanos(usedNanos);
    }
}
