package de.mattis.resourcenoptimierung.bench;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests fuer RuntimeType-Enum und Hilfsmethoden.
 */
class RuntimeTypeTest {

    // ==================== Enum values ====================

    @Test
    void allThreeValuesExist() {
        RuntimeType[] values = RuntimeType.values();
        assertEquals(3, values.length);
        assertNotNull(RuntimeType.HOTSPOT);
        assertNotNull(RuntimeType.OPENJ9);
        assertNotNull(RuntimeType.NATIVE);
    }

    @Test
    void valueOf_roundtrip() {
        assertEquals(RuntimeType.HOTSPOT, RuntimeType.valueOf("HOTSPOT"));
        assertEquals(RuntimeType.OPENJ9, RuntimeType.valueOf("OPENJ9"));
        assertEquals(RuntimeType.NATIVE, RuntimeType.valueOf("NATIVE"));
    }

    // ==================== isJvm ====================

    @Test
    void isJvm_hotspot_returnsTrue() {
        assertTrue(RuntimeType.HOTSPOT.isJvm());
    }

    @Test
    void isJvm_openj9_returnsTrue() {
        assertTrue(RuntimeType.OPENJ9.isJvm());
    }

    @Test
    void isJvm_native_returnsFalse() {
        assertFalse(RuntimeType.NATIVE.isJvm());
    }

    // ==================== hasGcLogs ====================

    @Test
    void hasGcLogs_hotspot_returnsTrue() {
        assertTrue(RuntimeType.HOTSPOT.hasGcLogs());
    }

    @Test
    void hasGcLogs_openj9_returnsTrue() {
        assertTrue(RuntimeType.OPENJ9.hasGcLogs());
    }

    @Test
    void hasGcLogs_native_returnsFalse() {
        assertFalse(RuntimeType.NATIVE.hasGcLogs());
    }
}
