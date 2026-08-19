package io.cognodb.benchmark.runner;

import org.HdrHistogram.Histogram;

public final class LatencyStatistics {
    private static final long MAX_TRACKABLE_NANOS = 120_000_000_000L;
    private final Histogram histogram = new Histogram(MAX_TRACKABLE_NANOS, 3);

    public void record(long latencyNanos) {
        histogram.recordValue(Math.max(1L, Math.min(latencyNanos, MAX_TRACKABLE_NANOS)));
    }

    public long count() { return histogram.getTotalCount(); }
    public double p50Ms() { return nanosToMillis(histogram.getValueAtPercentile(50.0)); }
    public double p95Ms() { return nanosToMillis(histogram.getValueAtPercentile(95.0)); }

    public void add(LatencyStatistics other) {
        this.histogram.add(other.histogram);
    }

    private static double nanosToMillis(double nanos) {
        return nanos / 1_000_000.0;
    }
}
