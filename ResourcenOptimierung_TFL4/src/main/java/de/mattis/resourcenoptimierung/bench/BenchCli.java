package de.mattis.resourcenoptimierung.bench;

public class BenchCli {

    public static void main(String[] args) throws Exception {
        BenchmarkPlan plan = BenchmarkPlan.defaultPlan();
        BenchmarkRunner runner = new BenchmarkRunner(plan);
        runner.runAll();
    }
}