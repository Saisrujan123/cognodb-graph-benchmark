package io.cognodb.benchmark.runner;

import io.cognodb.benchmark.adapter.GraphAdapter;
import io.cognodb.benchmark.config.BenchmarkConfig;
import io.cognodb.benchmark.dataset.WikiVoteDataset;
import io.cognodb.benchmark.model.BenchmarkResult;
import io.cognodb.benchmark.util.SecretRedactor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Loads one platform, runs the required workloads, and returns one compact result. */
public final class BenchmarkRunner {
    private static final String[] READ_WORKLOADS = {
            "point_lookup", "filtered_lookup", "traversal_1", "traversal_2",
            "traversal_3", "aggregation"
    };

    public BenchmarkResult run(BenchmarkConfig config, BenchmarkConfig.Platform platform,
                               WikiVoteDataset.Data data, GraphAdapter adapter) {
        BenchmarkResult result = new BenchmarkResult();
        result.platformId = platform.id();
        result.startedAtUtc = Instant.now().toString();
        result.platform.putAll(platform.publicDetails());
        result.dataset.put("name", "SNAP Wiki-Vote");
        result.dataset.put("nodes", data.nodes().size());
        result.dataset.put("relationships", data.relationships().size());
        result.dataset.put("nodesSha256", data.nodesSha256());
        result.dataset.put("relationshipsSha256", data.relationshipsSha256());
        result.dataset.put("sampleSha256", data.sampleSha256());
        result.client.put("os", System.getProperty("os.name") + " / " + System.getProperty("os.arch"));
        result.client.put("java", System.getProperty("java.version"));
        result.client.put("processors", Runtime.getRuntime().availableProcessors());
        result.client.put("region", System.getenv().getOrDefault("BENCHMARK_CLIENT_REGION", "UNKNOWN"));

        if ("UNKNOWN".equals(platform.region())
                || "UNKNOWN".equals(result.client.get("region"))) {
            result.caveats.add("Service/client region parity has not been verified.");
        }

        try {
            adapter.verifyConnectivity();
            result.platform.put("serverVersion", SecretRedactor.redact(adapter.serverVersion()));
            adapter.resetBenchmarkData();
            result.load = adapter.load(data, config.batchSize());
            validateCounts(config, adapter);

            ReferenceGraph reference = new ReferenceGraph(data);
            for (int trial = 1; trial <= config.readTrials(); trial++) {
                for (String workload : READ_WORKLOADS) {
                    result.latency.add(runRead(config, adapter, reference, data.sample(), workload, trial));
                }
            }
            result.mixed.addAll(new MixedWorkloadRunner(config, data).run(adapter));
            result.footprint = adapter.observeFootprint();
            result.status = result.mixed.stream().allMatch(metric -> "MEASURED".equals(metric.state))
                    ? "MEASURED" : "FAILED";
        } catch (Exception failure) {
            result.status = "FAILED";
            result.caveats.add("Run failed: " + SecretRedactor.redact(failure.getMessage()));
        }
        result.completedAtUtc = Instant.now().toString();
        return result;
    }

    private BenchmarkResult.Latency runRead(BenchmarkConfig config, GraphAdapter adapter,
                                            ReferenceGraph reference, List<Long> sample,
                                            String workload, int trial) throws Exception {
        BenchmarkResult.Latency metric = new BenchmarkResult.Latency();
        metric.workload = workload;
        metric.trial = trial;
        List<Long> warmup = parameters(sample, config.randomSeed(), workload + "-warmup",
                trial, config.readWarmupIterations());
        List<Long> measured = parameters(sample, config.randomSeed(), workload,
                trial, config.readMeasureIterations());
        LatencyStatistics statistics = new LatencyStatistics();

        try (GraphAdapter.Session session = adapter.openSession()) {
            for (long parameter : warmup) {
                execute(session, workload, parameter, config.bucketCount());
            }
            for (long parameter : measured) {
                long expected = expected(reference, workload, parameter, config.bucketCount());
                long started = System.nanoTime();
                long actual = execute(session, workload, parameter, config.bucketCount());
                statistics.record(System.nanoTime() - started);
                if (actual != expected) {
                    throw new IllegalStateException(workload + " returned " + actual
                            + " but the source graph expected " + expected);
                }
            }
        }

        metric.state = "MEASURED";
        metric.operations = statistics.count();
        metric.p50Ms = statistics.p50Ms();
        metric.p95Ms = statistics.p95Ms();
        return metric;
    }

    private List<Long> parameters(List<Long> sample, long seed, String workload,
                                  int trial, int count) {
        Random random = new Random(seed ^ ((long) workload.hashCode() << 32) ^ trial);
        List<Long> result = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            result.add(sample.get(random.nextInt(sample.size())));
        }
        return result;
    }

    private long execute(GraphAdapter.Session session, String workload,
                         long parameter, int bucketCount) throws Exception {
        switch (workload) {
            case "point_lookup": return session.pointLookup(parameter);
            case "filtered_lookup": return session.filteredLookup(Math.floorMod(parameter, bucketCount));
            case "traversal_1": return session.traversal(parameter, 1);
            case "traversal_2": return session.traversal(parameter, 2);
            case "traversal_3": return session.traversal(parameter, 3);
            case "aggregation": return session.aggregationDigest();
            default: throw new IllegalArgumentException("Unknown workload: " + workload);
        }
    }

    private long expected(ReferenceGraph reference, String workload,
                          long parameter, int bucketCount) {
        switch (workload) {
            case "point_lookup": return reference.pointLookup(parameter);
            case "filtered_lookup": return reference.filteredLookup(Math.floorMod(parameter, bucketCount));
            case "traversal_1": return reference.traversal(parameter, 1);
            case "traversal_2": return reference.traversal(parameter, 2);
            case "traversal_3": return reference.traversal(parameter, 3);
            case "aggregation": return reference.aggregationDigest();
            default: throw new IllegalArgumentException("Unknown workload: " + workload);
        }
    }

    private void validateCounts(BenchmarkConfig config, GraphAdapter adapter) throws Exception {
        GraphAdapter.Counts counts = adapter.countBenchmarkData();
        if (counts.nodes() != config.expectedNodes()
                || counts.relationships() != config.expectedRelationships()) {
            throw new IllegalStateException("Loaded count mismatch: " + counts.nodes() + " nodes, "
                    + counts.relationships() + " relationships");
        }
    }
}
