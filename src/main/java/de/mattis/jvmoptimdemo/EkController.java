package de.mattis.jvmoptimdemo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * REST-Controller fuer EBICS-Operationen.
 *
 * <p>Stellt die HTTP-Endpunkte bereit und delegiert die gesamte
 * EBICS-Business-Logik an {@link EbicsService}.</p>
 *
 * <p>Endpunkte:
 * <ul>
 *   <li>{@code GET /ebics/health} — Statusabfrage</li>
 *   <li>{@code GET /ebics/upload?n=1} — EBICS-Upload (n-mal)</li>
 *   <li>{@code GET /ebics/stats} — Request-/Upload-Zaehler</li>
 *   <li>{@code GET /ebics/test} — Schritt-fuer-Schritt Verbindungstest</li>
 * </ul>
 *
 * <p><b>Design-Entscheidung: GET statt POST fuer /upload und /test:</b>
 * Obwohl Upload und Test mutierende Operationen sind, verwenden sie GET,
 * weil das Benchmark-Harness ({@code SingleRun.measureEndpointSeconds()})
 * alle Szenarien einheitlich per HTTP GET aufruft. Diese App ist kein
 * produktives API, sondern ein reiner Benchmark-Workload.</p>
 */
@RestController
@RequestMapping("/ebics")
public class EkController {

    private static final Logger log = LoggerFactory.getLogger(EkController.class); // used for upload errors

    private final EbicsService ebicsService;

    /** Zaehlt alle eingehenden Upload-Requests (HTTP-Level Metrik). */
    private final AtomicLong requestCounter = new AtomicLong(0);

    /** Zaehlt alle erfolgreich ausgefuehrten EBICS-Uploads. */
    private final AtomicLong uploadCounter = new AtomicLong(0);

    /** Maximale Anzahl Uploads pro Request (Schutz vor uebermaessiger Last). */
    private static final int MAX_UPLOAD_N = 100;

    public EkController(EbicsService ebicsService) {
        this.ebicsService = ebicsService;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "UP");
        result.put("ekInitialized", ebicsService.isInitialized());
        String initError = ebicsService.getInitError();
        if (initError != null) {
            result.put("initError", initError);
        }
        result.put("totalRequests", requestCounter.get());
        return result;
    }

    @GetMapping("/upload")
    public Map<String, Object> upload(
            @RequestParam(name = "n", defaultValue = "1") int n
    ) {
        n = Math.max(0, Math.min(n, MAX_UPLOAD_N));
        requestCounter.incrementAndGet();
        long startTime = System.nanoTime();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ok");
        result.put("requested", n);

        try {
            for (int i = 0; i < n; i++) {
                ebicsService.performUpload();
            }
            result.put("uploadCount", uploadCounter.addAndGet(n));
        } catch (Exception e) {
            log.error("EK upload error", e);
            result.put("status", "error");
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        long durationMs = (System.nanoTime() - startTime) / 1_000_000;
        result.put("durationMs", durationMs);
        return result;
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalRequests", requestCounter.get());
        result.put("totalUploads", uploadCounter.get());
        result.put("ekInitialized", ebicsService.isInitialized());
        return result;
    }

    /**
     * Verbindungstest: Prueft Schritt fuer Schritt, ob Config, Schluessel,
     * Lizenz, HPB und ein Test-Upload funktionieren.
     *
     * <p>Nuetzlich um nach dem Deployment schnell zu verifizieren,
     * dass die EBICS-Verbindung zum TravicLink steht, bevor ein
     * vollstaendiger Benchmark gestartet wird.</p>
     */
    @GetMapping("/test")
    public Map<String, Object> test() {
        requestCounter.incrementAndGet();
        return ebicsService.testConnection();
    }
}
