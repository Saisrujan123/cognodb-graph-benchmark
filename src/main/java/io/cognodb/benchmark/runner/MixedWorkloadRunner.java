package io.cognodb.benchmark.runner;

import io.cognodb.benchmark.adapter.GraphAdapter;
import io.cognodb.benchmark.config.BenchmarkConfig;
import io.cognodb.benchmark.dataset.WikiVoteDataset;
import io.cognodb.benchmark.model.BenchmarkResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Runs the required closed-loop 90% read / 10% write concurrency test. */
public final class MixedWorkloadRunner {
    private final BenchmarkConfig config;
    private final WikiVoteDataset.Data data;

    public MixedWorkloadRunner(BenchmarkConfig config, WikiVoteDataset.Data data) {
        this.config = config;
        this.data = data;
    }

    public List<BenchmarkResult.Mixed> run(GraphAdapter adapter) throws Exception {
        List<BenchmarkResult.Mixed> results = new ArrayList<>();
        for (int concurrency : config.mixedConcurrencies()) {
            for (int trial = 1; trial <= config.mixedTrials(); trial++) {
                if (config.mixedWarmupSeconds() > 0) {
                    Window warmup = executeWindow(adapter, concurrency, trial,
                            config.mixedWarmupSeconds());
                    if (warmup.successful == 0 || warmup.failed > 0) {
                        throw new IllegalStateException("Mixed warm-up failed at concurrency " + concurrency);
                    }
                }
                adapter.resetCounters();
                Window window = executeWindow(adapter, concurrency, trial,
                        config.mixedMeasureSeconds());
                results.add(toResult(window, concurrency, trial, adapter.counterSum()));
            }
        }
        return results;
    }

    private Window executeWindow(GraphAdapter adapter, int concurrency,
                                 int trial, int seconds) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        long[] deadline = new long[1];
        List<Future<Window>> futures = new ArrayList<>();
        try {
            for (int worker = 0; worker < concurrency; worker++) {
                final int workerId = worker;
                futures.add(executor.submit(() -> worker(adapter, concurrency, trial,
                        workerId, ready, start, deadline)));
            }
            ready.await();
            long started = System.nanoTime();
            deadline[0] = started + TimeUnit.SECONDS.toNanos(seconds);
            start.countDown();

            Window combined = new Window();
            for (Future<Window> future : futures) combined.add(future.get());
            combined.elapsedNanos = System.nanoTime() - started;
            return combined;
        } finally {
            start.countDown();
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    private Window worker(GraphAdapter adapter, int concurrency, int trial, int worker,
                          CountDownLatch ready, CountDownLatch start, long[] deadline) {
        Window result = new Window();
        boolean announced = false;
        try (GraphAdapter.Session session = adapter.openSession()) {
            ready.countDown();
            announced = true;
            start.await();
            MixedOperationPlan plan = new MixedOperationPlan(
                    config.randomSeed() ^ trial, worker, config.mixedWritePercent());
            long operationIndex = 0;
            while (System.nanoTime() < deadline[0]) {
                MixedOperationPlan.Operation operation = plan.next();
                long nodeId = data.sample().get(
                        Math.floorMod(operationIndex + worker, data.sample().size()));
                result.attempted++;
                long started = System.nanoTime();
                try {
                    execute(session, operation, nodeId);
                    result.latencies.record(System.nanoTime() - started);
                    result.successful++;
                    if (operation == MixedOperationPlan.Operation.WRITE) {
                        result.writes++;
                    } else {
                        result.reads++;
                    }
                } catch (Exception failure) {
                    result.failed++;
                    if (isTimeout(failure)) result.timedOut++;
                }
                operationIndex++;
            }
        } catch (Exception failure) {
            result.failed++;
            if (isTimeout(failure)) result.timedOut++;
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
        } finally {
            if (!announced) ready.countDown();
        }
        return result;
    }

    private long execute(GraphAdapter.Session session, MixedOperationPlan.Operation operation,
                         long nodeId) throws Exception {
        switch (operation) {
            case POINT: return session.pointLookup(nodeId);
            case FILTERED: return session.filteredLookup(Math.floorMod(nodeId, config.bucketCount()));
            case TRAVERSAL_1: return session.traversal(nodeId, 1);
            case TRAVERSAL_2: return session.traversal(nodeId, 2);
            case TRAVERSAL_3: return session.traversal(nodeId, 3);
            case AGGREGATION: return session.aggregationDigest();
            case WRITE: return session.incrementCounter(nodeId);
            default: throw new IllegalStateException("Unknown mixed operation");
        }
    }

    private BenchmarkResult.Mixed toResult(Window window, int concurrency,
                                           int trial, long counterSum) {
        BenchmarkResult.Mixed result = new BenchmarkResult.Mixed();
        result.concurrency = concurrency;
        result.trial = trial;
        result.seconds = window.elapsedNanos / 1_000_000_000.0;
        result.attempted = window.attempted;
        result.successful = window.successful;
        result.failed = window.failed;
        result.timedOut = window.timedOut;
        result.reads = window.reads;
        result.writes = window.writes;
        result.operationsPerSecond = result.seconds == 0 ? 0 : result.successful / result.seconds;
        if (window.latencies.count() > 0) {
            result.p50Ms = window.latencies.p50Ms();
            result.p95Ms = window.latencies.p95Ms();
        }
        result.writesValidated = counterSum == window.writes;
        result.state = window.successful > 0 && window.failed == 0 && result.writesValidated
                ? "MEASURED" : "FAILED";
        return result;
    }

    private boolean isTimeout(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            String text = (current.getClass().getSimpleName() + " " + current.getMessage())
                    .toLowerCase(Locale.ROOT);
            if (text.contains("timeout") || text.contains("timed out")) return true;
            current = current.getCause();
        }
        return false;
    }

    private static final class Window {
        private long elapsedNanos;
        private long attempted;
        private long successful;
        private long failed;
        private long timedOut;
        private long reads;
        private long writes;
        private final LatencyStatistics latencies = new LatencyStatistics();

        private void add(Window other) {
            attempted += other.attempted;
            successful += other.successful;
            failed += other.failed;
            timedOut += other.timedOut;
            reads += other.reads;
            writes += other.writes;
            latencies.add(other.latencies);
        }
    }
}
