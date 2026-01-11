package de.mattis.resourcenoptimierung.bench;

import java.util.List;

public record BenchmarkConfig(
        String name,
        String dockerImage,
        List<String> jvmArgs
) {
    public boolean isNative() {
        return dockerImage != null && dockerImage.endsWith(":native");
    }
}
