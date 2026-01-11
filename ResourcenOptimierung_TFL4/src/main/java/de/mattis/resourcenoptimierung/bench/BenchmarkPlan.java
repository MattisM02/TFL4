package de.mattis.resourcenoptimierung.bench;

import java.util.List;

public class BenchmarkPlan {

    public final List<BenchmarkConfig> configs;

    private BenchmarkPlan(List<BenchmarkConfig> configs) {
        this.configs = configs;
    }

    public static BenchmarkPlan defaultPlan() {
        return new BenchmarkPlan(List.of(
                BenchmarkConfig.baseline(),
                BenchmarkConfig.compressedOopsOff(),
                BenchmarkConfig.compactObjectHeadersOn(),
                BenchmarkConfig.graalVmNative()
        ));
    }
}
