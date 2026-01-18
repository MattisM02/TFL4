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
     * Erzeugt den Standard-Benchmark-Plan für dieses Projekt.
     *
     * Der Default-Plan vergleicht mehrere JVM-Varianten
     * mit demselben Docker-Image, sodass Unterschiede
     * ausschließlich durch JVM-Flags entstehen.
     *
     * Enthaltene Konfigurationen:
     * - baseline: Standard-JVM ohne zusätzliche Flags
     * - coops-off: JVM ohne Compressed Oops
     * - coh-on: JVM mit Compact Object Headers
     * - native: GraalVM Native Image
     *
     * @return Benchmark-Plan mit Standard-Konfigurationen
     */
    public static BenchmarkPlan defaultPlan() {
        String jvmImage = "jvm-optim-demo:jvm";
        String nativeImage = "jvm-optim-demo:native";

        return new BenchmarkPlan(List.of(
                new BenchmarkConfig(
                        "baseline",
                        jvmImage,
                        List.of()
                ),
                new BenchmarkConfig(
                        "coops-off",
                        jvmImage,
                        List.of("-XX:-UseCompressedOops")
                ),
                new BenchmarkConfig(
                        "coh-on",
                        jvmImage,
                        List.of("-XX:+UnlockExperimentalVMOptions", "-XX:+UseCompactObjectHeaders")
                ),
                new BenchmarkConfig(
                        "native",
                        nativeImage,
                        List.of() // wird später ignoriert
                )
        ));
    }
}
