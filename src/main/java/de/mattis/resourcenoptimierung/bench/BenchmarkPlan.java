package de.mattis.resourcenoptimierung.bench;

import java.util.List;

/**
 * Beschreibt einen vollständigen Benchmark-Plan.
 *
 * <p>Ein {@code BenchmarkPlan} definiert, <b>welche Konfigurationen</b>
 * in einem Benchmark-Durchlauf ausgeführt werden sollen.
 * Jede Konfiguration entspricht genau einem {@link BenchmarkConfig}-Eintrag.</p>
 *
 * <p>Der Plan enthält selbst keinerlei Logik zur Ausführung oder Messung.
 * Er dient ausschließlich als strukturierte Eingabe für den
 * {@link BenchmarkRunner}, der die einzelnen Runs ausführt.</p>
 *
 * <p>Typischer Ablauf:</p>
 * <ol>
 *   <li>{@link #defaultPlan()} erzeugt einen Standard-Plan.</li>
 *   <li>Der {@link BenchmarkRunner} iteriert über {@link #configs}.</li>
 *   <li>Für jede {@link BenchmarkConfig} wird ein {@link SingleRun} gestartet.</li>
 * </ol>
 */
public class BenchmarkPlan {

    /**
     * Liste aller Benchmark-Konfigurationen, die ausgeführt werden sollen.
     *
     * <p>Die Reihenfolge der Liste bestimmt die Reihenfolge der Ausführung
     * und der späteren Ausgabe.</p>
     */
    public final List<BenchmarkConfig> configs;

    /**
     * Erstellt einen neuen Benchmark-Plan mit den gegebenen Konfigurationen.
     *
     * @param configs Liste der auszuführenden {@link BenchmarkConfig}-Einträge
     */
    public BenchmarkPlan(List<BenchmarkConfig> configs) {
        this.configs = configs;
    }

    /**
     * Erzeugt den Standard-Benchmark-Plan für dieses Projekt.
     *
     * <p>Der Default-Plan testet verschiedene JVM-Varianten
     * mit demselben Docker-Image, um die Effekte einzelner JVM-Flags
     * isoliert vergleichen zu können.</p>
     *
     * <p>Aktuell enthaltene Konfigurationen:</p>
     * <ul>
     *   <li><b>baseline</b>: Standard-JVM ohne zusätzliche Flags</li>
     *   <li><b>coops-off</b>: JVM mit deaktivierten Compressed Oops
     *       ({@code -XX:-UseCompressedOops})</li>
     *   <li><b>coh-on</b>: JVM mit aktivierten Compact Object Headers
     *       ({@code -XX:+UseCompactObjectHeaders})</li>
     * </ul>
     *
     * <p>Alle JVM-Varianten verwenden dasselbe Docker-Image
     * ({@code jvm-optim-demo:jvm}), um sicherzustellen, dass Unterschiede
     * ausschließlich durch JVM-Optionen entstehen.</p>
     *
     * <p>Ein Native-Image ist vorbereitet, aber aktuell auskommentiert.
     * Es kann später aktiviert werden, um JVM vs. Native direkt zu vergleichen.</p>
     *
     * @return ein {@link BenchmarkPlan} mit den Standard-Konfigurationen
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
