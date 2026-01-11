package de.mattis.resourcenoptimierung.bench;

import java.util.List;

public record RunResult(
        String configName,
        String dockerImage,
        long readinessMs,
        double firstJsonSeconds,
        List<Double> jsonLatenciesSeconds,
        List<DockerStatSample> dockerSamples,
        DockerStatSample dockerEndSample
) {}

