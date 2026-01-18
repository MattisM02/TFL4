package de.mattis.resourcenoptimierung.bench;

/**
 * Repräsentiert einen einzelnen Snapshot der Docker-Ressourcenstatistiken
 * eines Containers.
 *
 * <p>Ein {@code DockerStatSample} entspricht genau <b>einer Zeile</b> der Ausgabe von:</p>
 *
 * <pre>
 * docker stats --no-stream --format "{{.CPUPerc}}|{{.MemUsage}}|{{.MemPerc}}|{{.NetIO}}|{{.BlockIO}}|{{.PIDs}}"
 * </pre>
 *
 * <p>Die Werte werden während eines Benchmark-Runs mehrfach erfasst
 * (z. B. in IDLE-, LOAD- und POST-Phasen) und später zu aussagekräftigen
 * Kennzahlen zusammengefasst.</p>
 *
 * <p>Diese Klasse ist bewusst als {@code record} umgesetzt, da sie:</p>
 * <ul>
 *   <li>immutable ist,</li>
 *   <li>nur Daten transportiert,</li>
 *   <li>keine Logik außer Parsing enthält.</li>
 * </ul>
 *
 * @param cpuPercent     CPU-Auslastung des Containers in Prozent
 * @param memUsageRaw   aktuell genutzter Speicher (z. B. "151.9MiB")
 * @param memLimitRaw   konfiguriertes Speicherlimit des Containers (z. B. "768MiB")
 * @param memPercent    Speicher-Auslastung in Prozent
 * @param netInRaw      eingehender Netzwerktraffic (z. B. "4.9kB")
 * @param netOutRaw     ausgehender Netzwerktraffic (z. B. "2.93kB")
 * @param blockInRaw    Block-I/O gelesen
 * @param blockOutRaw   Block-I/O geschrieben
 * @param pids          Anzahl der Prozesse/Threads im Container
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
     * Parst eine einzelne Zeile der Docker-Stats-Ausgabe in ein {@link DockerStatSample}.
     *
     * <p>Erwartetes Format (Pipe-separiert):</p>
     *
     * <pre>
     * CPUPerc|MemUsage|MemPerc|NetIO|BlockIO|PIDs
     * </pre>
     *
     * <p>Beispiel:</p>
     * <pre>
     * 0.12%|151.9MiB / 768MiB|19.78%|4.9kB / 2.93kB|40.9MB / 0B|29
     * </pre>
     *
     * <p>Die Methode extrahiert und normalisiert die Werte, entfernt Prozentzeichen
     * und trennt kombinierte Felder (z. B. "usage / limit").</p>
     *
     * @param line eine einzelne Zeile aus {@code docker stats --no-stream}
     * @return geparstes {@link DockerStatSample}
     * @throws IllegalArgumentException wenn das Format nicht dem erwarteten Schema entspricht
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
     * Parst einen Prozentwert aus einem String.
     *
     * <p>Beispiel:</p>
     * <ul>
     *   <li>"19.78%" → {@code 19.78}</li>
     * </ul>
     *
     * @param s Prozentwert als String
     * @return numerischer Prozentwert
     * @throws NumberFormatException wenn der String nicht geparst werden kann
     */
    private static double parsePercent(String s) {
        return Double.parseDouble(s.trim().replace("%", ""));
    }
}
