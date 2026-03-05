package de.mattis.jvmoptimdemo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * MockMvc-Tests fuer EkController.
 *
 * EK-Klassen SIND im Test-Classpath (system-scope), aber die Initialisierung schlaegt
 * fehl (keine Lizenz vorhanden). Daher:
 * - checkEkAvailable() = true  (Klassen gefunden)
 * - mode = SIMULATION           (initializeEk() wirft Exception, mode bleibt default)
 * - upload = "error"             (ensureInitialized() wirft in den catch-Block)
 */
@WebMvcTest(EkController.class)
class EkControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void health_returnsOkWithMode() throws Exception {
        mockMvc.perform(get("/ebics/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.mode").exists())
                // EK JARs are on the classpath (system-scope), so ekAvailable = true
                .andExpect(jsonPath("$.ekAvailable").value(true))
                .andExpect(jsonPath("$.totalRequests").exists());
    }

    @Test
    void upload_returnsErrorWithoutLicense() throws Exception {
        // EK init fails (no license) -> status = error
        mockMvc.perform(get("/ebics/upload").param("n", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.mode").value("SIMULATION"))
                .andExpect(jsonPath("$.requested").value(1))
                .andExpect(jsonPath("$.error").exists())
                .andExpect(jsonPath("$.durationMs").exists());
    }

    @Test
    void upload_defaultN_returnsError() throws Exception {
        mockMvc.perform(get("/ebics/upload"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.mode").value("SIMULATION"))
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void stats_returnsCounters() throws Exception {
        mockMvc.perform(get("/ebics/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").exists())
                .andExpect(jsonPath("$.totalRequests").exists())
                .andExpect(jsonPath("$.totalUploads").exists())
                // EK JARs are on the classpath (system-scope), so ekAvailable = true
                .andExpect(jsonPath("$.ekAvailable").value(true))
                .andExpect(jsonPath("$.ekInitialized").exists());
    }

    @Test
    void upload_incrementsRequestCounter() throws Exception {
        // Call upload (will fail with error, but still increments counter)
        mockMvc.perform(get("/ebics/upload").param("n", "1"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/ebics/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRequests").isNumber());
    }

    // ==================== maskSensitive (package-private) ====================

    @Test
    void maskSensitive_null_returnsNotSet() {
        assertEquals("(not set)", EkController.maskSensitive(null));
    }

    @Test
    void maskSensitive_empty_returnsNotSet() {
        assertEquals("(not set)", EkController.maskSensitive(""));
    }

    @Test
    void maskSensitive_short_returnsStars() {
        assertEquals("***", EkController.maskSensitive("abc"));
        assertEquals("***", EkController.maskSensitive("abcdef"));
    }

    @Test
    void maskSensitive_long_masksMiddle() {
        String result = EkController.maskSensitive("my-secret-license-key");
        assertEquals("my-***key", result);
    }

    @Test
    void maskSensitive_sevenChars_showsEnds() {
        String result = EkController.maskSensitive("1234567");
        assertEquals("123***567", result);
    }

    private static void assertEquals(String expected, String actual) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual);
    }
}
