package io.cognodb.benchmark.adapter;

import io.cognodb.benchmark.model.BenchmarkResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AdapterSupportTest {
    @Test
    void finishesLoadMetricsFromSeparateIngestPhasesAndOneEndToEndDuration() {
        BenchmarkResult.Load metrics = new BenchmarkResult.Load();

        AdapterSupport.finishLoadMetrics(
                metrics, 10, 20, 0.5d, 2.0d, System.nanoTime() - 1_000_000L);

        assertEquals("MEASURED", metrics.state);
        assertEquals(10, metrics.nodes);
        assertEquals(20, metrics.relationships);
        assertEquals(0.5d, metrics.nodeSeconds);
        assertEquals(2.0d, metrics.relationshipSeconds);
        assertTrue(metrics.totalSeconds > 0.0d);
        assertEquals(20.0d, metrics.nodesPerSecond);
        assertEquals(10.0d, metrics.relationshipsPerSecond);
    }

    @Test
    void validatesOnlySupportedHopCounts() {
        assertEquals(1, AdapterSupport.requireHopCount(1));
        assertEquals(2, AdapterSupport.requireHopCount(2));
        assertEquals(3, AdapterSupport.requireHopCount(3));
        assertThrows(IllegalArgumentException.class, () -> AdapterSupport.requireHopCount(0));
        assertThrows(IllegalArgumentException.class, () -> AdapterSupport.requireHopCount(4));
    }

    @Test
    void rejectsIdentifiersThatCouldChangeAqlSyntax() {
        assertEquals("benchmark_01", AdapterSupport.safeIdentifier("benchmark_01", "graph"));
        assertThrows(IllegalArgumentException.class,
                () -> AdapterSupport.safeIdentifier("benchmark-name", "graph"));
        assertThrows(IllegalArgumentException.class,
                () -> AdapterSupport.safeIdentifier("x RETURN secret", "graph"));
    }

    @Test
    void sanitizesClientFailuresWithoutRetainingUnsafeCause() {
        Exception sanitized = AdapterSupport.failure(
                "Connect",
                new IllegalStateException(
                        "failed at bolt+s://user:top-secret@example.invalid password=top-secret"));

        assertTrue(sanitized.getMessage().contains("[REDACTED_URI]"));
        assertTrue(sanitized.getMessage().contains("password=[REDACTED]"));
        assertFalse(sanitized.getMessage().contains("top-secret"));
        assertFalse(sanitized.getMessage().contains("example.invalid"));
        assertNull(sanitized.getCause());
    }

    @Test
    void preservesTimeoutClassificationWithoutRetainingTheVendorCause() {
        Exception sanitized = AdapterSupport.failure(
                "Measured query", new VendorTimeoutFailure());

        assertEquals("Measured query timed out", sanitized.getMessage());
        assertNull(sanitized.getCause());
    }

    private static final class VendorTimeoutFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
