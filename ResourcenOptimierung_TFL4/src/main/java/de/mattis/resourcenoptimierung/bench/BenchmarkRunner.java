package de.mattis.resourcenoptimierung.bench;

import java.util.ArrayList;
import java.util.List;

public class BenchmarkRunner {

    private final BenchmarkPlan plan;

    public BenchmarkRunner(BenchmarkPlan plan) {
        this.plan = plan;
    }

    public List<RunResult> runAll() throws Exception {
        List<RunResult> results = new ArrayList<>();

        for (BenchmarkConfig cfg : plan.configs) {
            SingleRun run = new SingleRun(cfg);
            RunResult result = run.execute(); // execute() muss RunResult liefern
            results.add(result);
        }

        return results;
    }
}
