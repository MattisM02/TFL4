package de.mattis.resourcenoptimierung.bench;

/**
 * Repräsentiert einen einzelnen Snapshot der Docker-Ressourcenstatistiken
 * eines Containers.
 *
 * Ein DockerStatSample entspricht genau einer Zeile der Ausgabe von
 * docker stats --no-stream mit einem festen Format.
 *
 * Die Samples werden während eines Benchmark-Runs mehrfach erfasst
 * (z.B. in IDLE-, LOAD- und POST-Phasen) und später zu Kennzahlen
 * zusammengefasst.
 *
 * Die Klasse ist als record umgesetzt, da sie:
 * - unveränderlich ist,
 * - nur Daten transportiert,
 * - keine Logik außer einfachem Parsing enthält.
 *
 * @param cpuPercent CPU-Auslastung des Containers in Prozent
 * @param memUsageRaw aktuell genutzter Speicher (z.B. "151.9MiB")
 * @param memLimitRaw konfiguriertes Speicherlimit (z.B. "768MiB")
 * @param memPercent Speicherauslastung in Prozent
 * @param netInRaw eingehender Netzwerktraffic
 * @param netOutRaw ausgehender Netzwerktraffic
 * @param blockInRaw gelesener Block-I/O
 * @param blockOutRaw geschriebener Block-I/O
 * @param pids Anzahl der Prozesse/Threads im Container
 */
public record DockerStatSample(
        double cpuPercent,
        String memUsageRaw,
        String memLimitRaw,
        double memPercent,
        String netInRaw,
        String netOutRaw,
        String blockInRaw,
        String blockOutRaw,
        int pids
) {

    /**
     * Parst eine einzelne Zeile aus der docker-stats-Ausgabe
     * in ein DockerStatSample.
     *
     * Erwartetes Format (pipe-separiert):
     * CPUPerc|MemUsage|MemPerc|NetIO|BlockIO|PIDs
     *
     * Beispiel:
     * 0.12%|151.9MiB / 768MiB|19.78%|4.9kB / 2.93kB|40.9MB / 0B|29
     *
     * @param line eine einzelne Zeile aus docker stats --no-stream
     * @return geparstes DockerStatSample
     * @throws IllegalArgumentException wenn das Format unerwartet ist
     */
    public static DockerStatSample parse(String line) {

        String[] parts = line.split("\\|");
        if (parts.length != 6) {
            throw new IllegalArgumentException("Unexpected docker stats format: " + line);
        }

        double cpu = parsePercent(parts[0]);
        String[] mem = parts[1].split("/");
        String memUsage = mem[0].trim();
        String memLimit = mem.length > 1 ? mem[1].trim() : "";

        double memPerc = parsePercent(parts[2]);

        String[] net = parts[3].split("/");
        String netIn = net[0].trim();
        String netOut = net.length > 1 ? net[1].trim() : "";

        String[] block = parts[4].split("/");
        String blockIn = block[0].trim();
        String blockOut = block.length > 1 ? block[1].trim() : "";

        int pids = Integer.parseInt(parts[5].trim());

        return new DockerStatSample(cpu, memUsage, memLimit, memPerc, netIn, netOut, blockIn, blockOut, pids);
    }

    /**
     * Parst einen Prozentwert aus einem String wie "19.78%".
     *
     * @param s Prozentwert als String
     * @return numerischer Prozentwert
     */
    private static double parsePercent(String s) {
        return Double.parseDouble(s.trim().replace("%", ""));
    }
}
