package de.mattis.resourcenoptimierung.bench;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests fuer BenchmarkPlan: Default-Plan und Struktur.
 */
class BenchmarkPlanTest {

    @Test
    void defaultPlan_isNotNull() {
        BenchmarkPlan plan = BenchmarkPlan.defaultPlan();
        assertNotNull(plan);
        assertNotNull(plan.configs);
    }

    @Test
    void defaultPlan_containsAtLeastOneConfig() {
        BenchmarkPlan plan = BenchmarkPlan.defaultPlan();
        assertFalse(plan.configs.isEmpty());
    }

    @Test
    void defaultPlan_containsBaseline() {
        BenchmarkPlan plan = BenchmarkPlan.defaultPlan();
        boolean hasBaseline = plan.configs.stream()
                .anyMatch(c -> "baseline".equals(c.name()));
        assertTrue(hasBaseline, "Default plan should contain a 'baseline' config");
    }

    @Test
    void defaultPlan_baselineHasNoFlags() {
        BenchmarkPlan plan = BenchmarkPlan.defaultPlan();
        BenchmarkConfig baseline = plan.configs.stream()
                .filter(c -> "baseline".equals(c.name()))
                .findFirst()
                .orElseThrow();
        assertTrue(baseline.jvmArgs().isEmpty());
    }

    @Test
    void defaultPlan_containsCoopsOff() {
        BenchmarkPlan plan = BenchmarkPlan.defaultPlan();
        boolean hasCoopsOff = plan.configs.stream()
                .anyMatch(c -> "coops-off".equals(c.name()));
        assertTrue(hasCoopsOff, "Default plan should contain a 'coops-off' config");
    }

    @Test
    void defaultPlan_coopsOffHasFlag() {
        BenchmarkPlan plan = BenchmarkPlan.defaultPlan();
        BenchmarkConfig coopsOff = plan.configs.stream()
                .filter(c -> "coops-off".equals(c.name()))
                .findFirst()
                .orElseThrow();
        assertTrue(coopsOff.jvmArgs().contains("-XX:-UseCompressedOops"));
    }

    @Test
    void customPlan_constructsCorrectly() {
        List<BenchmarkConfig> configs = List.of(
                new BenchmarkConfig("custom", "myimage:latest", List.of("-Xmx256m"))
        );
        BenchmarkPlan plan = new BenchmarkPlan(configs);
        assertEquals(1, plan.configs.size());
        assertEquals("custom", plan.configs.get(0).name());
    }
}
