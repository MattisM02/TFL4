package de.mattis.resourcenoptimierung.bench;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests fuer DockerStatSample.parse() mit verschiedenen Formaten.
 */
class DockerStatSampleTest {

    @Test
    void parse_typicalLine() {
        String line = "0.12%|151.9MiB / 768MiB|19.78%|4.9kB / 2.93kB|40.9MB / 0B|29";
        DockerStatSample s = DockerStatSample.parse(line);

        assertEquals(0.12, s.cpuPercent(), 0.001);
        assertEquals("151.9MiB", s.memUsageRaw());
        assertEquals("768MiB", s.memLimitRaw());
        assertEquals(19.78, s.memPercent(), 0.001);
        assertEquals("4.9kB", s.netInRaw());
        assertEquals("2.93kB", s.netOutRaw());
        assertEquals("40.9MB", s.blockInRaw());
        assertEquals("0B", s.blockOutRaw());
        assertEquals(29, s.pids());
    }

    @Test
    void parse_highCpu() {
        String line = "150.25%|512MiB / 1GiB|50.00%|1.2MB / 500kB|100MB / 50MB|42";
        DockerStatSample s = DockerStatSample.parse(line);

        assertEquals(150.25, s.cpuPercent(), 0.001);
        assertEquals("512MiB", s.memUsageRaw());
        assertEquals("1GiB", s.memLimitRaw());
        assertEquals(50.00, s.memPercent(), 0.001);
        assertEquals(42, s.pids());
    }

    @Test
    void parse_zeroCpuAndMem() {
        String line = "0.00%|0B / 0B|0.00%|0B / 0B|0B / 0B|1";
        DockerStatSample s = DockerStatSample.parse(line);

        assertEquals(0.0, s.cpuPercent(), 0.001);
        assertEquals(0.0, s.memPercent(), 0.001);
        assertEquals(1, s.pids());
    }

    @Test
    void parse_spacesAroundValues() {
        String line = " 0.50% | 200MiB / 1GiB | 20.00% | 10kB / 5kB | 1MB / 0B | 15 ";
        DockerStatSample s = DockerStatSample.parse(line);

        assertEquals(0.50, s.cpuPercent(), 0.001);
        assertEquals("200MiB", s.memUsageRaw());
        assertEquals("1GiB", s.memLimitRaw());
        assertEquals(20.00, s.memPercent(), 0.001);
        assertEquals(15, s.pids());
    }

    @Test
    void parse_wrongNumberOfParts_throwsException() {
        String line = "0.12%|151.9MiB / 768MiB|19.78%";
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> DockerStatSample.parse(line));
        assertTrue(ex.getMessage().contains("Unexpected docker stats format"));
    }

    @Test
    void parse_emptyString_throwsException() {
        assertThrows(IllegalArgumentException.class,
                () -> DockerStatSample.parse(""));
    }

    @Test
    void parse_memWithoutSlash_handlesGracefully() {
        // Edge case: mem field without "/" separator
        String line = "0.10%|200MiB|10.00%|0B / 0B|0B / 0B|5";
        DockerStatSample s = DockerStatSample.parse(line);
        assertEquals("200MiB", s.memUsageRaw());
        assertEquals("", s.memLimitRaw());
    }

    @Test
    void recordEquality() {
        DockerStatSample a = new DockerStatSample(1.0, "100MiB", "512MiB", 19.5, "1kB", "2kB", "0B", "0B", 10);
        DockerStatSample b = new DockerStatSample(1.0, "100MiB", "512MiB", 19.5, "1kB", "2kB", "0B", "0B", 10);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }
}
