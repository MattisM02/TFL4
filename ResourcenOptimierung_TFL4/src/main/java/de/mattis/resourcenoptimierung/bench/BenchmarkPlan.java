package de.mattis.resourcenoptimierung.bench;

import java.util.List;

public class BenchmarkPlan {

    public final List<BenchmarkConfig> configs;

    public BenchmarkPlan(List<BenchmarkConfig> configs) {
        this.configs = configs;
    }

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
                )
                /*
                new BenchmarkConfig(
                        "native",
                        nativeImage,
                        List.of() // wird später ignoriert
                )
                 */
        ));
    }
}
