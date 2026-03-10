package de.mattis.resourcenoptimierung.bench;

import java.util.ArrayList;
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
 * <h3>Einheitlicher Plan</h3>
 * <p>Alle Konfigurationen — sowohl Flag-Analysen als auch Laufzeitprofile —
 * befinden sich in einem einzigen Plan. Jede Konfiguration traegt Metadaten
 * (Kategorie und Laufzeitmodell) fuer die Gruppierung und Filterung im Excel-Export.</p>
 *
 * <h3>Kategorien</h3>
 * <ul>
 *   <li><b>GC-Vergleich</b>: Vergleich verschiedener Garbage Collectors</li>
 *   <li><b>G1-Tuning</b>: G1GC mit verschiedenen Parametern</li>
 *   <li><b>JVM-Interna</b>: Interne JVM-Optionen (CompressedOops, CompactObjectHeaders)</li>
 *   <li><b>Cloud-relevant</b>: Container-optimierte Einstellungen</li>
 *   <li><b>Kombination</b>: Mehrere Flags kombiniert</li>
     *   <li><b>Laufzeitprofil</b>: Standardisierte Laufzeitprofile (P01–P13)</li>
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
        this.configs = List.copyOf(configs);
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
                .map(c -> new BenchmarkConfig(c.name(), dockerImage, c.jvmArgs(), c.runtimeType(),
                        c.category(), c.runtimeModel()))
                .toList();
        return new BenchmarkPlan(updated);
    }

    /**
     * Erzeugt den vollstaendigen Benchmark-Plan (34 Konfigurationen).
     *
     * <p>Enthaelt alle Flag-Analyse-Konfigurationen (21) und alle Laufzeitprofile (13).
     * Jede Konfiguration traegt Metadaten (Kategorie, Laufzeitmodell) fuer Excel-Gruppierung.
     *
     * <h3>Garbage-Collector-Vergleich (5)</h3>
     * <ul>
     *   <li>baseline — G1GC (Default seit JDK 9), keine zusaetzlichen Flags</li>
     *   <li>zgc — ZGC (seit JDK 24 ausschliesslich generational), Sub-Millisekunden-Pausen</li>
     *   <li>shenandoah — ShenandoahGC, pausenarmer GC (verfuegbar in Temurin)</li>
     *   <li>parallel-gc — ParallelGC, Durchsatz-optimiert, laengere Stop-the-World-Pausen</li>
     *   <li>serial-gc — SerialGC, Single-Thread-GC, minimaler Overhead</li>
     * </ul>
     *
     * <h3>G1GC-Tuning (3)</h3>
     * <ul>
     *   <li>g1-low-pause — G1 mit aggressivem Pausenziel (50 ms)</li>
     *   <li>g1-heap-256m — G1 mit eingeschraenktem Heap (256 MB)</li>
     *   <li>g1-heap-512m — G1 mit mittlerem Heap (512 MB)</li>
     * </ul>
     *
     * <h3>JVM-Interna (2)</h3>
     * <ul>
     *   <li>coops-off — Compressed Oops deaktiviert (groesserer Footprint, 64-Bit-Referenzen)</li>
     *   <li>coh-on — Compact Object Headers (JEP 450, experimentell ab JDK 24)</li>
     * </ul>
     *
     * <h3>Cloud-relevante Konfigurationen (2)</h3>
     * <ul>
     *   <li>ram-percentage-75 — MaxRAMPercentage=75: Container-aware Heap-Sizing (75% des cgroup-Limits)</li>
     *   <li>tiered-stop-1 — TieredStopAtLevel=1: Nur C1-Kompilierung, schnellerer Start, kein C2-Overhead</li>
     * </ul>
     *
     * <h3>Flag-Kombinationen (9)</h3>
     * <ul>
     *   <li>serial-gc-256m — SerialGC + 256 MB Heap: minimaler Footprint</li>
     *   <li>zgc-heap-512m — ZGC + 512 MB Heap: mehr Spielraum fuer concurrent GC</li>
     *   <li>shenandoah-heap-512m — Shenandoah + 512 MB Heap: mehr Spielraum fuer concurrent GC</li>
     *   <li>tiered-stop-1-serial — C1-only + SerialGC: schnellster Start + bester GC auf 1 CPU</li>
     *   <li>g1-coh-on — G1 + Compact Object Headers: reduziert Objekt-Overhead und GC-Druck</li>
     *   <li>zgc-coh-on — ZGC + Compact Object Headers: Low-Latency-GC mit kleinerem Live-Set</li>
     *   <li>parallel-gc-256m — ParallelGC + 256 MB Heap: Durchsatz-GC mit kleinem Heap</li>
     *   <li>g1-large-young — G1 + NewRatio=1: 50% Young Gen, weniger Full GCs erwartet</li>
     *   <li>zgc-tiered-stop-1 — ZGC + C1-only: niedrige Pausen mit schnellem Start (Serverless/Cold-Start)</li>
     * </ul>
     *
     * <h3>Laufzeitprofile (13, P01–P13)</h3>
     * <ul>
     *   <li>P01-hotspot-standard — G1GC + 75% RAM (Standard-Cloud-Deployment)</li>
     *   <li>P02-hotspot-fast-startup — G1GC + C1-only (Serverless/Cold-Start)</li>
     *   <li>P03-hotspot-low-latency — ZGC (Sub-Millisekunden-Pausen)</li>
     *   <li>P04-openj9-low-memory — OpenJ9 gencon GC (Memory-optimiert)</li>
     *   <li>P05-native — GraalVM Native Image (AOT-kompiliert, kein JVM-Overhead)</li>
     *   <li>P06-openj9-balanced — OpenJ9 balanced GC (Region-basiert, NUMA-aware)</li>
     *   <li>P07-openj9-optthruput — OpenJ9 optthruput GC (Durchsatz-optimiert)</li>
     *   <li>P08-openj9-optavgpause — OpenJ9 optavgpause GC (Pausen-optimiert)</li>
     *   <li>P09-hotspot-heap-256m — G1GC + 256 MB Heap (Speicher-limitiert)</li>
     *   <li>P10-openj9-heap-256m — OpenJ9 + 256 MB Heap (Speicher-limitiert)</li>
     *   <li>P11-hotspot-cds — HotSpot + Dynamic CDS (Startup-optimiert)</li>
     *   <li>P12-graalvm-jit — GraalVM JIT-Compiler (optimierte Codegenerierung)</li>
     *   <li>P13-virtual-threads — HotSpot + Virtual Threads (Project Loom): Tomcat nutzt VT statt Platform-Threads</li>
     * </ul>
     *
     * @return Benchmark-Plan mit 34 Konfigurationen
     */
    public static BenchmarkPlan defaultPlan() {
        String img = "tfl4-ek-bench:jvm";
        RuntimeType rt = RuntimeType.HOTSPOT;
        String model = "HotSpot";

        String imgOpenj9   = "tfl4-ek-bench:openj9";
        String imgCds      = "tfl4-ek-bench:jvm-cds";
        String imgGraalJit = "tfl4-ek-bench:graalvm-jit";
        String imgNative   = "tfl4-ek-bench:native";
        String imgVt       = "tfl4-ek-bench:jvm-vt";

        return new BenchmarkPlan(List.of(
                // ==================== Garbage-Collector-Vergleich ====================
                new BenchmarkConfig(
                        "baseline",
                        img,
                        List.of(),
                        rt,
                        "GC-Vergleich",
                        model
                ),
                new BenchmarkConfig(
                        "zgc",
                        img,
                        List.of("-XX:+UseZGC"),
                        rt,
                        "GC-Vergleich",
                        model
                ),
                new BenchmarkConfig(
                        "shenandoah",
                        img,
                        List.of("-XX:+UseShenandoahGC"),
                        rt,
                        "GC-Vergleich",
                        model
                ),
                new BenchmarkConfig(
                        "parallel-gc",
                        img,
                        List.of("-XX:+UseParallelGC"),
                        rt,
                        "GC-Vergleich",
                        model
                ),
                new BenchmarkConfig(
                        "serial-gc",
                        img,
                        List.of("-XX:+UseSerialGC"),
                        rt,
                        "GC-Vergleich",
                        model
                ),

                // ==================== G1GC-Tuning ====================
                new BenchmarkConfig(
                        "g1-low-pause",
                        img,
                        List.of("-XX:+UseG1GC", "-XX:MaxGCPauseMillis=50"),
                        rt,
                        "G1-Tuning",
                        model
                ),
                new BenchmarkConfig(
                        "g1-heap-256m",
                        img,
                        List.of("-Xmx256m"),
                        rt,
                        "G1-Tuning",
                        model
                ),
                new BenchmarkConfig(
                        "g1-heap-512m",
                        img,
                        List.of("-Xmx512m"),
                        rt,
                        "G1-Tuning",
                        model
                ),

                // ==================== JVM-Interna ====================
                new BenchmarkConfig(
                        "coops-off",
                        img,
                        List.of("-XX:-UseCompressedOops"),
                        rt,
                        "JVM-Interna",
                        model
                ),
                new BenchmarkConfig(
                        "coh-on",
                        img,
                        List.of("-XX:+UnlockExperimentalVMOptions", "-XX:+UseCompactObjectHeaders"),
                        rt,
                        "JVM-Interna",
                        model
                ),

                // ==================== Cloud-relevante Konfigurationen ====================
                new BenchmarkConfig(
                        "ram-percentage-75",
                        img,
                        List.of("-XX:MaxRAMPercentage=75"),
                        rt,
                        "Cloud-relevant",
                        model
                ),
                new BenchmarkConfig(
                        "tiered-stop-1",
                        img,
                        List.of("-XX:TieredStopAtLevel=1"),
                        rt,
                        "Cloud-relevant",
                        model
                ),

                // ==================== Flag-Kombinationen ====================
                new BenchmarkConfig(
                        "serial-gc-256m",
                        img,
                        List.of("-XX:+UseSerialGC", "-Xmx256m"),
                        rt,
                        "Kombination",
                        model
                ),
                new BenchmarkConfig(
                        "zgc-heap-512m",
                        img,
                        List.of("-XX:+UseZGC", "-Xmx512m"),
                        rt,
                        "Kombination",
                        model
                ),
                new BenchmarkConfig(
                        "shenandoah-heap-512m",
                        img,
                        List.of("-XX:+UseShenandoahGC", "-Xmx512m"),
                        rt,
                        "Kombination",
                        model
                ),
                new BenchmarkConfig(
                        "tiered-stop-1-serial",
                        img,
                        List.of("-XX:TieredStopAtLevel=1", "-XX:+UseSerialGC"),
                        rt,
                        "Kombination",
                        model
                ),
                new BenchmarkConfig(
                        "g1-coh-on",
                        img,
                        List.of("-XX:+UseG1GC", "-XX:+UnlockExperimentalVMOptions", "-XX:+UseCompactObjectHeaders"),
                        rt,
                        "Kombination",
                        model
                ),
                new BenchmarkConfig(
                        "zgc-coh-on",
                        img,
                        List.of("-XX:+UseZGC", "-XX:+UnlockExperimentalVMOptions", "-XX:+UseCompactObjectHeaders"),
                        rt,
                        "Kombination",
                        model
                ),
                new BenchmarkConfig(
                        "parallel-gc-256m",
                        img,
                        List.of("-XX:+UseParallelGC", "-Xmx256m"),
                        rt,
                        "Kombination",
                        model
                ),
                new BenchmarkConfig(
                        "g1-large-young",
                        img,
                        List.of("-XX:+UseG1GC", "-XX:NewRatio=1"),
                        rt,
                        "Kombination",
                        model
                ),
                new BenchmarkConfig(
                        "zgc-tiered-stop-1",
                        img,
                        List.of("-XX:+UseZGC", "-XX:TieredStopAtLevel=1"),
                        rt,
                        "Kombination",
                        model
                ),

                // ==================== Laufzeitprofile ====================
                new BenchmarkConfig(
                        "P01-hotspot-standard",
                        img,
                        List.of("-XX:+UseG1GC", "-XX:MaxRAMPercentage=75"),
                        RuntimeType.HOTSPOT,
                        "Laufzeitprofil",
                        "HotSpot"
                ),
                new BenchmarkConfig(
                        "P02-hotspot-fast-startup",
                        img,
                        List.of("-XX:+UseG1GC", "-XX:TieredStopAtLevel=1", "-XX:MaxRAMPercentage=75"),
                        RuntimeType.HOTSPOT,
                        "Laufzeitprofil",
                        "HotSpot"
                ),
                new BenchmarkConfig(
                        "P03-hotspot-low-latency",
                        img,
                        List.of("-XX:+UseZGC", "-XX:MaxRAMPercentage=75"),
                        RuntimeType.HOTSPOT,
                        "Laufzeitprofil",
                        "HotSpot"
                ),
                new BenchmarkConfig(
                        "P04-openj9-low-memory",
                        imgOpenj9,
                        List.of("-XX:MaxRAMPercentage=75"),
                        RuntimeType.OPENJ9,
                        "Laufzeitprofil",
                        "OpenJ9"
                ),
                new BenchmarkConfig(
                        "P05-native",
                        imgNative,
                        List.of(),
                        RuntimeType.NATIVE,
                        "Laufzeitprofil",
                        "Native"
                ),
                new BenchmarkConfig(
                        "P06-openj9-balanced",
                        imgOpenj9,
                        List.of("-Xgcpolicy:balanced", "-XX:MaxRAMPercentage=75"),
                        RuntimeType.OPENJ9,
                        "Laufzeitprofil",
                        "OpenJ9"
                ),
                new BenchmarkConfig(
                        "P07-openj9-optthruput",
                        imgOpenj9,
                        List.of("-Xgcpolicy:optthruput", "-XX:MaxRAMPercentage=75"),
                        RuntimeType.OPENJ9,
                        "Laufzeitprofil",
                        "OpenJ9"
                ),
                new BenchmarkConfig(
                        "P08-openj9-optavgpause",
                        imgOpenj9,
                        List.of("-Xgcpolicy:optavgpause", "-XX:MaxRAMPercentage=75"),
                        RuntimeType.OPENJ9,
                        "Laufzeitprofil",
                        "OpenJ9"
                ),
                new BenchmarkConfig(
                        "P09-hotspot-heap-256m",
                        img,
                        List.of("-XX:+UseG1GC", "-Xmx256m"),
                        RuntimeType.HOTSPOT,
                        "Laufzeitprofil",
                        "HotSpot"
                ),
                new BenchmarkConfig(
                        "P10-openj9-heap-256m",
                        imgOpenj9,
                        List.of("-Xmx256m"),
                        RuntimeType.OPENJ9,
                        "Laufzeitprofil",
                        "OpenJ9"
                ),
                new BenchmarkConfig(
                        "P11-hotspot-cds",
                        imgCds,
                        List.of("-XX:+UseG1GC", "-XX:MaxRAMPercentage=75"),
                        RuntimeType.HOTSPOT,
                        "Laufzeitprofil",
                        "CDS"
                ),
                new BenchmarkConfig(
                        "P12-graalvm-jit",
                        imgGraalJit,
                        List.of("-XX:+UseG1GC", "-XX:MaxRAMPercentage=75"),
                        RuntimeType.HOTSPOT,
                        "Laufzeitprofil",
                        "GraalVM JIT"
                ),
                new BenchmarkConfig(
                        "P13-virtual-threads",
                        imgVt,
                        List.of("-XX:+UseG1GC", "-XX:MaxRAMPercentage=75"),
                        RuntimeType.HOTSPOT,
                        "Laufzeitprofil",
                        "VirtualThreads"
                )
        ));
    }

    /**
     * Erzeugt einen Plan mit nur den Laufzeitprofilen (P01–P13).
     *
     * <p>Enthaelt 13 standardisierte Profile, die verschiedene JVM-Implementierungen
     * und Laufzeitmodelle vergleichen.
     *
     * @return Benchmark-Plan mit 13 Laufzeitprofilen
     */
    public static BenchmarkPlan profilePlan() {
        BenchmarkPlan full = defaultPlan();
        List<BenchmarkConfig> profiles = full.configs.stream()
                .filter(c -> c.name().matches("P\\d{2}-.*"))
                .toList();
        return new BenchmarkPlan(profiles);
    }

    /**
     * Erzeugt einen neuen Plan mit EBICS-spezifischen Docker-Images.
     *
     * <p>Fuer EBICS-Szenarien muessen die Docker-Images die EBICS-Konfiguration
     * und Schluessel enthalten. Die Image-Zuordnung erfolgt per Suffix-Konvention:
     * An den Tag-Teil des Image-Namens wird {@code -ek} angehaengt.
     * Falls der Tag bereits auf {@code -ek} endet, bleibt er unveraendert.
     *
     * <p>Beispiele:
     * <ul>
     *   <li>{@code tfl4-ek-bench:jvm} → {@code tfl4-ek-bench:jvm-ek}</li>
     *   <li>{@code tfl4-ek-bench:openj9} → {@code tfl4-ek-bench:openj9-ek}</li>
     *   <li>{@code tfl4-ek-bench:graalvm-jit} → {@code tfl4-ek-bench:graalvm-jit-ek}</li>
     *   <li>{@code tfl4-ek-bench:jvm-cds} → {@code tfl4-ek-bench:jvm-cds-ek}</li>
     *   <li>{@code tfl4-ek-bench:jvm-ek} → {@code tfl4-ek-bench:jvm-ek} (unchanged)</li>
     * </ul>
     *
     * @return neuer Plan mit EBICS-Images
     */
    public BenchmarkPlan withEbicsImages() {
        List<BenchmarkConfig> updated = configs.stream()
                .map(c -> {
                    String ebicsImage = toEbicsImage(c.dockerImage());
                    return new BenchmarkConfig(c.name(), ebicsImage, c.jvmArgs(), c.runtimeType(),
                            c.category(), c.runtimeModel());
                })
                .toList();
        return new BenchmarkPlan(updated);
    }

    /**
     * Wandelt einen Docker-Image-Tag in die EBICS-Variante um (Suffix {@code -ek}).
     * Idempotent: ein Tag, der bereits auf {@code -ek} endet, bleibt unveraendert.
     *
     * @param image Docker-Image-Tag (z.B. {@code tfl4-ek-bench:jvm})
     * @return EBICS-Variante (z.B. {@code tfl4-ek-bench:jvm-ek})
     */
    static String toEbicsImage(String image) {
        if (image.endsWith("-ek")) return image;
        return image + "-ek";
    }

    /**
     * Erzeugt den kombinierten Plan: identisch mit {@link #defaultPlan()},
     * da der Plan jetzt alle Konfigurationen vereint.
     *
     * <p>Diese Methode existiert fuer Rueckwaertskompatibilitaet mit BenchCli
     * und Tests, die {@code combinedPlan()} aufrufen.
     *
     * @return vollstaendiger Plan mit 34 Konfigurationen
     */
    public static BenchmarkPlan combinedPlan() {
        return defaultPlan();
    }
}
