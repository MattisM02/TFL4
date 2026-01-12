package de.mattis.resourcenoptimierung.bench;

import java.util.List;

public record RunResult(
        String configName,
        String dockerImage,
        long readinessMs,
        double firstSeconds,
        List<Double> jsonLatenciesSeconds,
        List<DockerStatSample> dockerSamples,
        DockerStatSample dockerEndSample,
        String effectiveJavaToolOptions,
        ReadinessCheckUsed readinessCheckUsed,
        String startupLogSnippet,
        BenchmarkScenario scenario,
        int workloadN,
        String workloadPath) { }

