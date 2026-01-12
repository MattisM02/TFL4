package de.mattis.resourcenoptimierung.bench;

import java.util.List;

public record RunResult(
        String configName,
        String dockerImage,
        long readinessMs,
        double firstSeconds,
        List<Double> jsonLatenciesSeconds,
        String effectiveJavaToolOptions,
        ReadinessCheckUsed readinessCheckUsed,
        String startupLogSnippet,
        BenchmarkScenario scenario,
        int workloadN,
        String workloadPath,
        List<DockerStatSample> dockerIdleSamples,
        List<DockerStatSample> dockerLoadSamples,
        List<DockerStatSample> dockerPostSamples) { }

