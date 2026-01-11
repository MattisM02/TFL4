package de.mattis.resourcenoptimierung.bench;

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
    public static DockerStatSample parse(String line) {
        // format: CPUPerc|MemUsage|MemPerc|NetIO|BlockIO|PIDs
        // example:
        // "0.12%|151.9MiB / 768MiB|19.78%|4.9kB / 2.93kB|40.9MB / 0B|29"

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

    private static double parsePercent(String s) {
        return Double.parseDouble(s.trim().replace("%", ""));
    }
}
