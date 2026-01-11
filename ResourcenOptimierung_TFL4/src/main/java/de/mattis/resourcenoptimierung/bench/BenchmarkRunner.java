package de.mattis.resourcenoptimierung.bench;

public class BenchmarkRunner {

    private final BenchmarkPlan plan;

    public BenchmarkRunner(BenchmarkPlan plan) {
        this.plan = plan;
    }

    public void runAll() throws Exception {
        for (BenchmarkConfig cfg : plan.configs) {
            SingleRun run = new SingleRun(cfg);
            run.execute();
        }
    }
}
