package de.mattis.resourcenoptimierung.bench;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests fuer TravicLinkManager: Szenario-Erkennung und Konstruktion.
 * Lifecycle-Tests (start/stop) sind hier nicht enthalten, da sie Docker erfordern.
 */
class TravicLinkManagerTest {

    // ==================== isEbicsScenario ====================

    @Test
    void isEbicsScenario_upload_returnsTrue() {
        assertTrue(TravicLinkManager.isEbicsScenario(BenchmarkScenario.EBICS_UPLOAD));
    }

    @Test
    void isEbicsScenario_json_returnsFalse() {
        assertFalse(TravicLinkManager.isEbicsScenario(BenchmarkScenario.PAYLOAD_HEAVY_JSON));
    }

    @Test
    void isEbicsScenario_alloc_returnsFalse() {
        assertFalse(TravicLinkManager.isEbicsScenario(BenchmarkScenario.ALLOC_HEAVY_OK));
    }

    // ==================== Constructor ====================

    @Test
    void defaultConstructor_doesNotThrow() {
        assertDoesNotThrow(() -> new TravicLinkManager());
    }
}
