package io.cognodb.benchmark.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.cognodb.benchmark.config.BenchmarkConfig;
import io.cognodb.benchmark.model.BenchmarkResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Writes one JSON file per platform and turns those files into Markdown/CSV tables. */
public final class ResultWriter {
    private static final String[] WORKLOADS = {
            "point_lookup", "filtered_lookup", "traversal_1", "traversal_2",
            "traversal_3", "aggregation"
    };
    private final ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public Path writeResult(BenchmarkConfig config, BenchmarkResult result) throws IOException {
        Files.createDirectories(config.resultsPath());
        Path output = config.resultFile(result.platformId);
        Path temporary = output.resolveSibling(output.getFileName() + ".part");
        try {
            json.writeValue(temporary.toFile(), result);
            Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
        return output;
    }

    public Path writeReport(BenchmarkConfig config) throws IOException {
        Files.createDirectories(config.resultsPath());
        Map<String, BenchmarkResult> results = load(config);
        Files.writeString(config.reportFile(), markdown(config, results), StandardCharsets.UTF_8);
        Files.writeString(config.csvFile(), csv(config, results), StandardCharsets.UTF_8);
        return config.reportFile();
    }

    private Map<String, BenchmarkResult> load(BenchmarkConfig config) throws IOException {
        Map<String, BenchmarkResult> results = new LinkedHashMap<>();
        for (String platformId : config.platformIds()) {
            Path path = config.resultFile(platformId);
            if (Files.isRegularFile(path)) {
                results.put(platformId, json.readValue(path.toFile(), BenchmarkResult.class));
            }
        }
        return results;
    }

    private String markdown(BenchmarkConfig config, Map<String, BenchmarkResult> results) {
        StringBuilder text = new StringBuilder();
        text.append("# Benchmark results\n\nGenerated: ").append(Instant.now()).append("\n\n")
                .append("Missing measurements are shown as `NOT_RUN`, never as zero. ")
                .append("Do not compare platforms until CPU, RAM, storage, client, and region are equivalent.\n\n")
                .append("## Status and environment\n\n")
                .append("|Platform|Status|Tier|Service region|Client region|vCPU|RAM MB|Storage GB|Server|\n")
                .append("|---|---|---|---|---|---:|---:|---:|---|\n");
        for (String id : config.platformIds()) {
            BenchmarkResult result = results.get(id);
            Map<String, String> details = result == null
                    ? config.platform(id).publicDetails() : result.platform;
            text.append('|').append(id).append('|').append(result == null ? "NOT_RUN" : result.status)
                    .append('|').append(value(details, "tier"))
                    .append('|').append(value(details, "region"))
                    .append('|').append(result == null ? "NOT_RUN"
                            : escape(String.valueOf(result.client.getOrDefault("region", "UNKNOWN"))))
                    .append('|').append(value(details, "vcpu"))
                    .append('|').append(value(details, "memoryMb"))
                    .append('|').append(value(details, "storageGb"))
                    .append('|').append(value(details, "serverVersion")).append("|\n");
        }

        text.append("\n## Data loading\n\n")
                .append("|Platform|State|Total seconds|Nodes/s|Relationships/s|Load method|\n")
                .append("|---|---|---:|---:|---:|---|\n");
        for (String id : config.platformIds()) {
            BenchmarkResult result = results.get(id);
            if (result == null || !"MEASURED".equals(result.load.state)) {
                text.append('|').append(id).append("|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|")
                        .append(value(config.platform(id).publicDetails(), "loadMethod")).append("|\n");
            } else {
                text.append('|').append(id).append("|MEASURED|")
                        .append(number(result.load.totalSeconds)).append('|')
                        .append(number(result.load.nodesPerSecond)).append('|')
                        .append(number(result.load.relationshipsPerSecond)).append('|')
                        .append(value(result.platform, "loadMethod")).append("|\n");
            }
        }

        text.append("\n## Read latency\n\nValues are medians of the trial-level p50/p95 values.\n\n")
                .append("|Platform|Workload|State|p50 ms|p95 ms|Operations|\n")
                .append("|---|---|---|---:|---:|---:|\n");
        for (String id : config.platformIds()) {
            BenchmarkResult result = results.get(id);
            for (String workload : WORKLOADS) {
                List<BenchmarkResult.Latency> rows = new ArrayList<>();
                if (result != null) {
                    for (BenchmarkResult.Latency metric : result.latency) {
                        if (workload.equals(metric.workload) && "MEASURED".equals(metric.state)) rows.add(metric);
                    }
                }
                text.append('|').append(id).append('|').append(workload).append('|');
                if (rows.isEmpty()) {
                    text.append("NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|\n");
                } else if (rows.size() != config.readTrials()) {
                    text.append("FAILED|NOT_RUN|NOT_RUN|")
                            .append(rows.stream().mapToLong(row -> row.operations).sum()).append("|\n");
                } else {
                    text.append("MEASURED|").append(number(medianLatency(rows, true))).append('|')
                            .append(number(medianLatency(rows, false))).append('|')
                            .append(rows.stream().mapToLong(row -> row.operations).sum()).append("|\n");
                }
            }
        }

        text.append("\n## Mixed workload - 90% reads / 10% writes\n\n")
                .append("|Platform|Clients|State|Median ops/s|p50 ms|p95 ms|Failures|Timeouts|Writes validated|\n")
                .append("|---|---:|---|---:|---:|---:|---:|---:|---|\n");
        for (String id : config.platformIds()) {
            BenchmarkResult result = results.get(id);
            for (int concurrency : config.mixedConcurrencies()) {
                List<BenchmarkResult.Mixed> rows = new ArrayList<>();
                if (result != null) {
                    for (BenchmarkResult.Mixed metric : result.mixed) {
                        if (metric.concurrency == concurrency) rows.add(metric);
                    }
                }
                text.append('|').append(id).append('|').append(concurrency).append('|');
                if (rows.isEmpty()) {
                    text.append("NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|\n");
                } else {
                    boolean measured = rows.size() == config.mixedTrials()
                            && rows.stream().allMatch(row -> "MEASURED".equals(row.state));
                    text.append(measured ? "MEASURED" : "FAILED").append('|')
                            .append(number(medianMixed(rows, 0))).append('|')
                            .append(number(medianMixed(rows, 1))).append('|')
                            .append(number(medianMixed(rows, 2))).append('|')
                            .append(rows.stream().mapToLong(row -> row.failed).sum()).append('|')
                            .append(rows.stream().mapToLong(row -> row.timedOut).sum()).append('|')
                            .append(rows.stream().allMatch(row -> row.writesValidated) ? "yes" : "no")
                            .append("|\n");
                }
            }
        }

        text.append("\n## Footprint and caveats\n\n|Platform|State|Observed|Caveats|\n")
                .append("|---|---|---|---|\n");
        for (String id : config.platformIds()) {
            BenchmarkResult result = results.get(id);
            if (result == null) {
                text.append('|').append(id).append("|NOT_RUN|NOT_RUN|No benchmark run.|\n");
            } else {
                text.append('|').append(id).append('|').append(result.footprint.state).append('|')
                        .append(escape(result.footprint.values.isEmpty()
                                ? result.footprint.note : result.footprint.values.toString()))
                        .append('|').append(escape(String.join("; ", result.caveats))).append("|\n");
            }
        }
        text.append("\n## Analysis\n\n")
                .append(results.size() == config.platformIds().size()
                        && results.values().stream().allMatch(result -> "MEASURED".equals(result.status))
                        ? "All five platforms have measured rows. Interpret differences only after confirming the resource and region table.\n"
                        : "The comparison is incomplete, so there is no evidence-based winner or performance conclusion yet.\n");
        return text.toString();
    }

    private String csv(BenchmarkConfig config, Map<String, BenchmarkResult> results) {
        StringBuilder text = new StringBuilder("platform,category,metric,concurrency,state,value,unit\n");
        for (String id : config.platformIds()) {
            BenchmarkResult result = results.get(id);
            if (result == null) {
                text.append(id).append(",coverage,run,,NOT_RUN,,\n");
                continue;
            }
            boolean loaded = "MEASURED".equals(result.load.state);
            text.append(id).append(",load,nodes_per_second,,").append(result.load.state).append(',')
                    .append(loaded ? result.load.nodesPerSecond : "").append(",nodes/s\n");
            text.append(id).append(",load,relationships_per_second,,").append(result.load.state).append(',')
                    .append(loaded ? result.load.relationshipsPerSecond : "").append(",relationships/s\n");
            for (BenchmarkResult.Latency metric : result.latency) {
                text.append(id).append(",latency,").append(metric.workload).append("_p95,,")
                        .append(metric.state).append(',').append(metric.p95Ms).append(",ms\n");
            }
            for (BenchmarkResult.Mixed metric : result.mixed) {
                text.append(id).append(",mixed,throughput,").append(metric.concurrency).append(',')
                        .append(metric.state).append(',').append(metric.operationsPerSecond)
                        .append(",ops/s\n");
            }
        }
        return text.toString();
    }

    private double medianLatency(List<BenchmarkResult.Latency> rows, boolean p50) {
        List<Double> values = new ArrayList<>();
        for (BenchmarkResult.Latency row : rows) values.add(p50 ? row.p50Ms : row.p95Ms);
        return median(values);
    }

    private double medianMixed(List<BenchmarkResult.Mixed> rows, int field) {
        List<Double> values = new ArrayList<>();
        for (BenchmarkResult.Mixed row : rows) {
            values.add(field == 0 ? row.operationsPerSecond : field == 1 ? row.p50Ms : row.p95Ms);
        }
        return median(values);
    }

    private double median(List<Double> values) {
        values.sort(Double::compareTo);
        int middle = values.size() / 2;
        return values.size() % 2 == 1 ? values.get(middle)
                : (values.get(middle - 1) + values.get(middle)) / 2.0;
    }

    private String value(Map<String, String> values, String key) {
        return escape(values.getOrDefault(key, "UNKNOWN"));
    }

    private String number(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private String escape(String value) {
        return value == null || value.isEmpty() ? "NONE"
                : value.replace("|", "\\|").replace('\n', ' ');
    }
}
