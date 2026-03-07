package de.mattis.resourcenoptimierung.bench;

import java.util.List;

/**
 * Beschreibt einen vollständigen Benchmark-Plan.
 *
 * Ein BenchmarkPlan legt fest, welche Konfigurationen
 * in einem Durchlauf ausgeführt werden.
 * Jede Konfiguration entspricht einem BenchmarkConfig-Eintrag.
 *
 * Der Plan enthält selbst keine Ausführungs- oder Messlogik.
 * Er dient als strukturierte Eingabe für den BenchmarkRunner.
 */
public class BenchmarkPlan {

    /**
     * Liste aller Benchmark-Konfigurationen.
     * Die Reihenfolge bestimmt Ausführung und Ausgabe.
     */
    public final List<BenchmarkConfig> configs;

    /**
     * Erstellt einen neuen Benchmark-Plan mit den gegebenen Konfigurationen.
     *
     * @param configs auszuführende Benchmark-Konfigurationen
     */
    public BenchmarkPlan(List<BenchmarkConfig> configs) {
        this.configs = configs;
    }

    /**
     * Erzeugt einen neuen Plan, bei dem alle Configs das angegebene Docker-Image verwenden.
     * Nuetzlich fuer EBICS-Szenarien, die ein anderes Image (mit EK-JARs) benoetigen.
     *
     * @param dockerImage das neue Docker-Image fuer alle Konfigurationen
     * @return neuer Plan mit geaendertem Image
     */
    public BenchmarkPlan withDockerImage(String dockerImage) {
        List<BenchmarkConfig> updated = configs.stream()
                .map(c -> new BenchmarkConfig(c.name(), dockerImage, c.jvmArgs()))
                .toList();
        return new BenchmarkPlan(updated);
    }

    /**
     * Erzeugt den Standard-Benchmark-Plan für dieses Projekt.
     *
     * Der Default-Plan vergleicht systematisch JVM-Varianten
     * mit demselben Docker-Image (Temurin JRE 25), sodass Unterschiede
     * ausschließlich durch JVM-Flags entstehen.
     *
     * <h3>Garbage-Collector-Vergleich</h3>
     * <ul>
     *   <li>baseline — G1GC (Default seit JDK 9), keine zusätzlichen Flags</li>
     *   <li>zgc — ZGC (seit JDK 24 ausschließlich generational), Sub-Millisekunden-Pausen</li>
     *   <li>shenandoah — ShenandoahGC, pausenarmer GC (verfügbar in Temurin)</li>
     *   <li>parallel-gc — ParallelGC, Durchsatz-optimiert, längere Stop-the-World-Pausen</li>
     *   <li>serial-gc — SerialGC, Single-Thread-GC, minimaler Overhead</li>
     * </ul>
     *
     * <h3>G1GC-Tuning</h3>
     * <ul>
     *   <li>g1-low-pause — G1 mit aggressivem Pausenziel (50 ms)</li>
     *   <li>g1-heap-256m — G1 mit eingeschränktem Heap (256 MB)</li>
     *   <li>g1-heap-512m — G1 mit mittlerem Heap (512 MB)</li>
     * </ul>
     *
     * <h3>JVM-Interna</h3>
     * <ul>
     *   <li>coops-off — Compressed Oops deaktiviert (größerer Footprint, 64-Bit-Referenzen)</li>
     *   <li>coh-on — Compact Object Headers (JEP 450, experimentell ab JDK 24)</li>
     * </ul>
     *
     * <h3>Cloud-relevante Konfigurationen</h3>
     * <ul>
     *   <li>ram-percentage-75 — MaxRAMPercentage=75: Container-aware Heap-Sizing (75% des cgroup-Limits)</li>
     *   <li>tiered-stop-1 — TieredStopAtLevel=1: Nur C1-Kompilierung, schnellerer Start, kein C2-Overhead</li>
     * </ul>
     *
     * <h3>Flag-Kombinationen</h3>
     * <ul>
     *   <li>serial-gc-256m — SerialGC + 256 MB Heap: minimaler Footprint</li>
     *   <li>zgc-heap-512m — ZGC + 512 MB Heap: mehr Spielraum fuer concurrent GC</li>
     *   <li>shenandoah-heap-512m — Shenandoah + 512 MB Heap: mehr Spielraum fuer concurrent GC</li>
     *   <li>tiered-stop-1-serial — C1-only + SerialGC: schnellster Start + bester GC auf 1 CPU</li>
     *   <li>g1-coh-on — G1 + Compact Object Headers: reduziert Objekt-Overhead und GC-Druck</li>
     *   <li>parallel-gc-256m — ParallelGC + 256 MB Heap: Durchsatz-GC mit kleinem Heap</li>
     *   <li>g1-large-young — G1 + NewRatio=1: 50% Young Gen, weniger Full GCs erwartet</li>
     *   <li>zgc-tiered-stop-1 — ZGC + C1-only: niedrige Pausen mit schnellem Start (Serverless/Cold-Start)</li>
     * </ul>
     *
     * @return Benchmark-Plan mit Standard-Konfigurationen
     */
    public static BenchmarkPlan defaultPlan() {
        String img = "tfl4-ek-bench:jvm";

        return new BenchmarkPlan(List.of(
                // --- Garbage-Collector-Vergleich ---
                new BenchmarkConfig(
                        "baseline",
                        img,
                        List.of()
                ),
                new BenchmarkConfig(
                        "zgc",
                        img,
                        List.of("-XX:+UseZGC")
                ),
                new BenchmarkConfig(
                        "shenandoah",
                        img,
                        List.of("-XX:+UseShenandoahGC")
                ),
                new BenchmarkConfig(
                        "parallel-gc",
                        img,
                        List.of("-XX:+UseParallelGC")
                ),
                new BenchmarkConfig(
                        "serial-gc",
                        img,
                        List.of("-XX:+UseSerialGC")
                ),

                // --- G1GC-Tuning ---
                new BenchmarkConfig(
                        "g1-low-pause",
                        img,
                        List.of("-XX:+UseG1GC", "-XX:MaxGCPauseMillis=50")
                ),
                new BenchmarkConfig(
                        "g1-heap-256m",
                        img,
                        List.of("-Xmx256m")
                ),
                new BenchmarkConfig(
                        "g1-heap-512m",
                        img,
                        List.of("-Xmx512m")
                ),

                // --- JVM-Interna ---
                new BenchmarkConfig(
                        "coops-off",
                        img,
                        List.of("-XX:-UseCompressedOops")
                ),
                new BenchmarkConfig(
                        "coh-on",
                        img,
                        List.of("-XX:+UnlockExperimentalVMOptions", "-XX:+UseCompactObjectHeaders")
                ),

                // --- Cloud-relevante Konfigurationen ---
                new BenchmarkConfig(
                        "ram-percentage-75",
                        img,
                        List.of("-XX:MaxRAMPercentage=75")
                ),
                new BenchmarkConfig(
                        "tiered-stop-1",
                        img,
                        List.of("-XX:TieredStopAtLevel=1")
                ),

                // --- Flag-Kombinationen ---
                new BenchmarkConfig(
                        "serial-gc-256m",
                        img,
                        List.of("-XX:+UseSerialGC", "-Xmx256m")
                ),
                new BenchmarkConfig(
                        "zgc-heap-512m",
                        img,
                        List.of("-XX:+UseZGC", "-Xmx512m")
                ),
                new BenchmarkConfig(
                        "shenandoah-heap-512m",
                        img,
                        List.of("-XX:+UseShenandoahGC", "-Xmx512m")
                ),
                new BenchmarkConfig(
                        "tiered-stop-1-serial",
                        img,
                        List.of("-XX:TieredStopAtLevel=1", "-XX:+UseSerialGC")
                ),
                new BenchmarkConfig(
                        "g1-coh-on",
                        img,
                        List.of("-XX:+UseG1GC", "-XX:+UnlockExperimentalVMOptions", "-XX:+UseCompactObjectHeaders")
                ),
                new BenchmarkConfig(
                        "parallel-gc-256m",
                        img,
                        List.of("-XX:+UseParallelGC", "-Xmx256m")
                ),
                new BenchmarkConfig(
                        "g1-large-young",
                        img,
                        List.of("-XX:+UseG1GC", "-XX:NewRatio=1")
                ),
                new BenchmarkConfig(
                        "zgc-tiered-stop-1",
                        img,
                        List.of("-XX:+UseZGC", "-XX:TieredStopAtLevel=1")
                )
        ));
    }
}
