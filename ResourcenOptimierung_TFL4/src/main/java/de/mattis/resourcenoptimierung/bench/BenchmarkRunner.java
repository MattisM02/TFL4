package de.mattis.resourcenoptimierung.bench;

import java.util.ArrayList;
import java.util.List;

public class BenchmarkRunner {

    private final BenchmarkPlan plan;
    private final BenchmarkScenario scenario;
    private final int n;

    public BenchmarkRunner(BenchmarkPlan plan, BenchmarkScenario scenario, int n) {
        this.plan = plan;
        this.scenario = scenario;
        this.n = n;
    }

    public List<RunResult> runAll() throws Exception {
        List<RunResult> results = new ArrayList<>();
        for (BenchmarkConfig cfg : plan.configs) {
            SingleRun run = new SingleRun(cfg, scenario, n);
            results.add(run.execute());
        }
        return results;
    }
}
