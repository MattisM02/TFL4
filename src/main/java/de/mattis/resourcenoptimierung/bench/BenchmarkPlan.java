package de.mattis.resourcenoptimierung.bench;

import java.util.List;

/**
 * Beschreibt einen vollstaendigen Benchmark-Plan.
 *
 * Ein BenchmarkPlan legt fest, welche Konfigurationen
 * in einem Durchlauf ausgefuehrt werden.
 * Jede Konfiguration entspricht einem BenchmarkConfig-Eintrag.
 *
 * Der Plan enthaelt selbst keine Ausfuehrungs- oder Messlogik.
 * Er dient als strukturierte Eingabe fuer den BenchmarkRunner.
 *
 * <h3>Zwei Analyseebenen</h3>
 * <ul>
 *   <li><b>Level 1 — Flag-Analyse</b> ({@link #defaultPlan()}):
 *       20 HotSpot-Konfigurationen, die einzelne JVM-Flags variieren.</li>
 *   <li><b>Level 2 — Laufzeitprofile</b> ({@link #profilePlan()}):
 *       5 standardisierte Profile (P01–P05), die verschiedene JVM-Implementierungen
 *       und Laufzeitmodelle vergleichen.</li>
 * </ul>
 */
public class BenchmarkPlan {

    /**
     * Liste aller Benchmark-Konfigurationen.
     * Die Reihenfolge bestimmt Ausfuehrung und Ausgabe.
     */
    public final List<BenchmarkConfig> configs;

    /**
     * Erstellt einen neuen Benchmark-Plan mit den gegebenen Konfigurationen.
     *
     * @param configs auszufuehrende Benchmark-Konfigurationen
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
                .map(c -> new BenchmarkConfig(c.name(), dockerImage, c.jvmArgs(), c.runtimeType()))
                .toList();
        return new BenchmarkPlan(updated);
    }

    /**
     * Erzeugt den Standard-Benchmark-Plan fuer dieses Projekt (Level 1: Flag-Analyse).
     *
     * Der Default-Plan vergleicht systematisch JVM-Varianten
     * mit demselben Docker-Image (Temurin JRE 25), sodass Unterschiede
     * ausschliesslich durch JVM-Flags entstehen.
     *
     * <h3>Garbage-Collector-Vergleich</h3>
     * <ul>
     *   <li>baseline — G1GC (Default seit JDK 9), keine zusaetzlichen Flags</li>
     *   <li>zgc — ZGC (seit JDK 24 ausschliesslich generational), Sub-Millisekunden-Pausen</li>
     *   <li>shenandoah — ShenandoahGC, pausenarmer GC (verfuegbar in Temurin)</li>
     *   <li>parallel-gc — ParallelGC, Durchsatz-optimiert, laengere Stop-the-World-Pausen</li>
     *   <li>serial-gc — SerialGC, Single-Thread-GC, minimaler Overhead</li>
     * </ul>
     *
     * <h3>G1GC-Tuning</h3>
     * <ul>
     *   <li>g1-low-pause — G1 mit aggressivem Pausenziel (50 ms)</li>
     *   <li>g1-heap-256m — G1 mit eingeschraenktem Heap (256 MB)</li>
     *   <li>g1-heap-512m — G1 mit mittlerem Heap (512 MB)</li>
     * </ul>
     *
     * <h3>JVM-Interna</h3>
     * <ul>
     *   <li>coops-off — Compressed Oops deaktiviert (groesserer Footprint, 64-Bit-Referenzen)</li>
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
        RuntimeType rt = RuntimeType.HOTSPOT;

        return new BenchmarkPlan(List.of(
                // --- Garbage-Collector-Vergleich ---
                new BenchmarkConfig(
                        "baseline",
                        img,
                        List.of(),
                        rt
                ),
                new BenchmarkConfig(
                        "zgc",
                        img,
                        List.of("-XX:+UseZGC"),
                        rt
                ),
                new BenchmarkConfig(
                        "shenandoah",
                        img,
                        List.of("-XX:+UseShenandoahGC"),
                        rt
                ),
                new BenchmarkConfig(
                        "parallel-gc",
                        img,
                        List.of("-XX:+UseParallelGC"),
                        rt
                ),
                new BenchmarkConfig(
                        "serial-gc",
                        img,
                        List.of("-XX:+UseSerialGC"),
                        rt
                ),

                // --- G1GC-Tuning ---
                new BenchmarkConfig(
                        "g1-low-pause",
                        img,
                        List.of("-XX:+UseG1GC", "-XX:MaxGCPauseMillis=50"),
                        rt
                ),
                new BenchmarkConfig(
                        "g1-heap-256m",
                        img,
                        List.of("-Xmx256m"),
                        rt
                ),
                new BenchmarkConfig(
                        "g1-heap-512m",
                        img,
                        List.of("-Xmx512m"),
                        rt
                ),

                // --- JVM-Interna ---
                new BenchmarkConfig(
                        "coops-off",
                        img,
                        List.of("-XX:-UseCompressedOops"),
                        rt
                ),
                new BenchmarkConfig(
                        "coh-on",
                        img,
                        List.of("-XX:+UnlockExperimentalVMOptions", "-XX:+UseCompactObjectHeaders"),
                        rt
                ),

                // --- Cloud-relevante Konfigurationen ---
                new BenchmarkConfig(
                        "ram-percentage-75",
                        img,
                        List.of("-XX:MaxRAMPercentage=75"),
                        rt
                ),
                new BenchmarkConfig(
                        "tiered-stop-1",
                        img,
                        List.of("-XX:TieredStopAtLevel=1"),
                        rt
                ),

                // --- Flag-Kombinationen ---
                new BenchmarkConfig(
                        "serial-gc-256m",
                        img,
                        List.of("-XX:+UseSerialGC", "-Xmx256m"),
                        rt
                ),
                new BenchmarkConfig(
                        "zgc-heap-512m",
                        img,
                        List.of("-XX:+UseZGC", "-Xmx512m"),
                        rt
                ),
                new BenchmarkConfig(
                        "shenandoah-heap-512m",
                        img,
                        List.of("-XX:+UseShenandoahGC", "-Xmx512m"),
                        rt
                ),
                new BenchmarkConfig(
                        "tiered-stop-1-serial",
                        img,
                        List.of("-XX:TieredStopAtLevel=1", "-XX:+UseSerialGC"),
                        rt
                ),
                new BenchmarkConfig(
                        "g1-coh-on",
                        img,
                        List.of("-XX:+UseG1GC", "-XX:+UnlockExperimentalVMOptions", "-XX:+UseCompactObjectHeaders"),
                        rt
                ),
                new BenchmarkConfig(
                        "parallel-gc-256m",
                        img,
                        List.of("-XX:+UseParallelGC", "-Xmx256m"),
                        rt
                ),
                new BenchmarkConfig(
                        "g1-large-young",
                        img,
                        List.of("-XX:+UseG1GC", "-XX:NewRatio=1"),
                        rt
                ),
                new BenchmarkConfig(
                        "zgc-tiered-stop-1",
                        img,
                        List.of("-XX:+UseZGC", "-XX:TieredStopAtLevel=1"),
                        rt
                )
        ));
    }

    /**
     * Erzeugt den Laufzeitprofil-Plan (Level 2: Vergleich standardisierter Laufzeitprofile).
     *
     * <p>Vergleicht 5 repraesentative Laufzeitprofile, die jeweils eine typische
     * Cloud-Deployment-Strategie abbilden:
     *
     * <table>
     *   <tr><th>Profil</th><th>Runtime</th><th>Image</th><th>Beschreibung</th></tr>
     *   <tr><td>P01-hotspot-standard</td><td>HOTSPOT</td><td>tfl4-ek-bench:jvm</td>
     *       <td>G1GC mit 75% RAM — Standard-Cloud-Deployment</td></tr>
     *   <tr><td>P02-hotspot-fast-startup</td><td>HOTSPOT</td><td>tfl4-ek-bench:jvm</td>
     *       <td>G1GC + C1-only — Serverless/Cold-Start-optimiert</td></tr>
     *   <tr><td>P03-hotspot-low-latency</td><td>HOTSPOT</td><td>tfl4-ek-bench:jvm</td>
     *       <td>ZGC — Sub-Millisekunden-Pausen</td></tr>
     *   <tr><td>P04-openj9-low-memory</td><td>OPENJ9</td><td>tfl4-ek-bench:openj9</td>
     *       <td>OpenJ9 gencon GC — Memory-optimiert</td></tr>
     *   <tr><td>P05-native</td><td>NATIVE</td><td>tfl4-ek-bench:native</td>
     *       <td>GraalVM Native Image — kein JVM-Overhead</td></tr>
     * </table>
     *
     * @return Benchmark-Plan mit den 5 Laufzeitprofilen
     */
    public static BenchmarkPlan profilePlan() {
        return new BenchmarkPlan(List.of(
                new BenchmarkConfig(
                        "P01-hotspot-standard",
                        "tfl4-ek-bench:jvm",
                        List.of("-XX:+UseG1GC", "-XX:MaxRAMPercentage=75"),
                        RuntimeType.HOTSPOT
                ),
                new BenchmarkConfig(
                        "P02-hotspot-fast-startup",
                        "tfl4-ek-bench:jvm",
                        List.of("-XX:+UseG1GC", "-XX:TieredStopAtLevel=1", "-XX:MaxRAMPercentage=75"),
                        RuntimeType.HOTSPOT
                ),
                new BenchmarkConfig(
                        "P03-hotspot-low-latency",
                        "tfl4-ek-bench:jvm",
                        List.of("-XX:+UseZGC", "-XX:MaxRAMPercentage=75"),
                        RuntimeType.HOTSPOT
                ),
                new BenchmarkConfig(
                        "P04-openj9-low-memory",
                        "tfl4-ek-bench:openj9",
                        List.of("-XX:MaxRAMPercentage=75"),
                        RuntimeType.OPENJ9
                ),
                new BenchmarkConfig(
                        "P05-native",
                        "tfl4-ek-bench:native",
                        List.of(),
                        RuntimeType.NATIVE
                )
        ));
    }

    /**
     * Erzeugt einen neuen Plan mit EBICS-spezifischen Docker-Images.
     *
     * <p>Fuer EBICS-Szenarien muessen die Docker-Images die EBICS-Konfiguration
     * und Schluessel enthalten. Die Image-Zuordnung erfolgt anhand des RuntimeType:
     * <ul>
     *   <li>HOTSPOT → tfl4-ek-bench:jvm-ek</li>
     *   <li>OPENJ9 → tfl4-ek-bench:openj9-ek</li>
     *   <li>NATIVE → tfl4-ek-bench:native-ek</li>
     * </ul>
     *
     * @return neuer Plan mit EBICS-Images
     */
    public BenchmarkPlan withEbicsImages() {
        List<BenchmarkConfig> updated = configs.stream()
                .map(c -> {
                    String ebicsImage = switch (c.runtimeType()) {
                        case HOTSPOT -> "tfl4-ek-bench:jvm-ek";
                        case OPENJ9  -> "tfl4-ek-bench:openj9-ek";
                        case NATIVE  -> "tfl4-ek-bench:native-ek";
                    };
                    return new BenchmarkConfig(c.name(), ebicsImage, c.jvmArgs(), c.runtimeType());
                })
                .toList();
        return new BenchmarkPlan(updated);
    }
}
