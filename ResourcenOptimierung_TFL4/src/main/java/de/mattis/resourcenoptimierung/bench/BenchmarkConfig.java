package de.mattis.resourcenoptimierung.bench;

import java.util.List;

public record BenchmarkConfig(
        String name,
        String dockerImage,
        List<String> jvmArgs
) {

    public static BenchmarkConfig baseline() {
        return new BenchmarkConfig(
                "baseline",
                "jvm-optim-demo:baseline",
                List.of()
        );
    }

    public static BenchmarkConfig compressedOopsOff() {
        return new BenchmarkConfig(
                "compressedOopsOff",
                "jvm-optim-demo:coops-off",
                List.of("-XX:-UseCompressedOops")
        );
    }

    public static BenchmarkConfig compactObjectHeadersOn() {
        return new BenchmarkConfig(
                "compactObjectHeadersOn",
                "jvm-optim-demo:coh-on",
                List.of(
                        "-XX:+UnlockExperimentalVMOptions",
                        "-XX:+UseCompactObjectHeaders"
                )
        );
    }

    public static BenchmarkConfig graalVmNative() {
        return new BenchmarkConfig(
                "graalvm-native",
                "jvm-optim-demo:native",
                List.of()
        );
    }
}
