package de.mattis.resourcenoptimierung.bench;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests fuer TravicLinkManager und BenchmarkScenario.isEbics().
 * Lifecycle-Tests (start/stop) sind hier nicht enthalten, da sie Docker erfordern.
 */
class TravicLinkManagerTest {

    // ==================== BenchmarkScenario.isEbics ====================

    @Test
    void isEbics_upload_returnsTrue() {
        assertTrue(BenchmarkScenario.EBICS_UPLOAD.isEbics());
    }

    @Test
    void isEbics_json_returnsFalse() {
        assertFalse(BenchmarkScenario.PAYLOAD_HEAVY_JSON.isEbics());
    }

    @Test
    void isEbics_alloc_returnsFalse() {
        assertFalse(BenchmarkScenario.ALLOC_HEAVY_OK.isEbics());
    }

    // ==================== Constructor ====================

    @Test
    void defaultConstructor_doesNotThrow() {
        assertDoesNotThrow(() -> new TravicLinkManager());
    }
}
