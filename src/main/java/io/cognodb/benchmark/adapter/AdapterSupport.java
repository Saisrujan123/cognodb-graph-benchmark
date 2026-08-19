package io.cognodb.benchmark.adapter;

import io.cognodb.benchmark.model.BenchmarkResult;
import io.cognodb.benchmark.util.SecretRedactor;

import java.util.Locale;

final class AdapterSupport {
    static final String BENCHMARK_SCOPE = "cognodb-graph-benchmark-v1";
    static final String BENCHMARK_LABEL = "CognoBenchmarkPerson";
    private static final int MAX_ERROR_DETAIL_LENGTH = 320;

    private AdapterSupport() {
    }

    static int requireBatchSize(int batchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        return batchSize;
    }

    static int requireHopCount(int hops) {
        if (hops < 1 || hops > 3) {
            throw new IllegalArgumentException("hops must be 1, 2, or 3");
        }
        return hops;
    }

    static void finishLoadMetrics(
            BenchmarkResult.Load metrics,
            long nodeCount,
            long relationshipCount,
            double nodeSeconds,
            double relationshipSeconds,
            long totalStartedNanos) {
        metrics.nodes = nodeCount;
        metrics.relationships = relationshipCount;
        metrics.nodeSeconds = nodeSeconds;
        metrics.relationshipSeconds = relationshipSeconds;
        metrics.totalSeconds = secondsSince(totalStartedNanos);
        metrics.nodesPerSecond = rate(nodeCount, nodeSeconds);
        metrics.relationshipsPerSecond = rate(relationshipCount, relationshipSeconds);
        metrics.state = "MEASURED";
    }

    static double secondsSince(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000_000.0d;
    }

    static RuntimeException failure(String operation, Throwable failure) {
        String detail = failure == null ? "" : SecretRedactor.redact(failure.getMessage());
        detail = normalizeDetail(detail);
        String message = operation + (isTimeoutFailure(failure) ? " timed out" : " failed");
        if (!detail.isEmpty()) {
            message += ": " + detail;
        }
        // Deliberately omit the original exception as the cause. Several database clients
        // retain the full endpoint (and occasionally URI user-info) in their exception text.
        return new AdapterException(message);
    }

    private static boolean isTimeoutFailure(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            String type = current.getClass().getSimpleName().toLowerCase(Locale.ROOT);
            String message = current.getMessage() == null
                    ? ""
                    : current.getMessage().toLowerCase(Locale.ROOT);
            if (type.contains("timeout") || message.contains("timeout")
                    || message.contains("timed out")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    static boolean isAlreadyExists(Throwable failure) {
        String message = failure == null || failure.getMessage() == null
                ? ""
                : failure.getMessage().toLowerCase(Locale.ROOT);
        return message.contains("already exists")
                || message.contains("equivalent index")
                || message.contains("equivalent constraint")
                || message.contains("duplicate name");
    }

    static boolean isMissingGraph(Throwable failure) {
        String message = failure == null || failure.getMessage() == null
                ? ""
                : failure.getMessage().toLowerCase(Locale.ROOT);
        return message.contains("graph")
                && (message.contains("does not exist") || message.contains("not found"));
    }

    static String safeIdentifier(String value, String description) {
        if (value == null || !value.matches("[A-Za-z][A-Za-z0-9_]{0,63}")) {
            throw new IllegalArgumentException(description
                    + " must start with a letter and contain at most 64 letters, digits, or underscores");
        }
        return value;
    }

    private static double rate(long count, double seconds) {
        return seconds <= 0.0d ? 0.0d : count / seconds;
    }

    private static String normalizeDetail(String detail) {
        if (detail == null) {
            return "";
        }
        String normalized = detail.replace('\r', ' ').replace('\n', ' ').trim();
        if (normalized.length() > MAX_ERROR_DETAIL_LENGTH) {
            return normalized.substring(0, MAX_ERROR_DETAIL_LENGTH) + "...";
        }
        return normalized;
    }

    private static final class AdapterException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private AdapterException(String message) {
            super(message);
        }
    }
}
