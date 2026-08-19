package io.cognodb.benchmark.runner;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LatencyStatisticsTest {
    @Test
    void reportsPercentilesInMilliseconds() {
        LatencyStatistics statistics = new LatencyStatistics();
        for (long millis = 1; millis <= 100; millis++) {
            statistics.record(millis * 1_000_000L);
        }

        assertThat(statistics.count()).isEqualTo(100);
        assertThat(statistics.p50Ms()).isBetween(49.0, 51.0);
        assertThat(statistics.p95Ms()).isBetween(94.0, 96.0);
    }
}

