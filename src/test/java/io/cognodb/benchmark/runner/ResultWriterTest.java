package io.cognodb.benchmark.runner;

import io.cognodb.benchmark.config.BenchmarkConfig;
import io.cognodb.benchmark.model.BenchmarkResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

final class ResultWriterTest {
    @TempDir Path temporary;

    @Test
    void writesOnePlatformJsonAndAnHonestReport() throws Exception {
        Path properties = temporary.resolve("benchmark.properties");
        Files.writeString(properties, String.join("\n",
                "results.path=results",
                "platforms=cognodb",
                "cognodb.name=CognoDB Cloud",
                "cognodb.adapter=bolt",
                "read.trials=3",
                "mixed.trials=3",
                "mixed.concurrencies=1,10,40",
                ""), StandardCharsets.UTF_8);
        BenchmarkConfig config = BenchmarkConfig.load(
                temporary, properties, Collections.emptyMap());
        BenchmarkResult result = new BenchmarkResult();
        result.platformId = "cognodb";
        result.status = "FAILED";
        BenchmarkResult.Latency partial = new BenchmarkResult.Latency();
        partial.workload = "point_lookup";
        partial.trial = 1;
        partial.state = "MEASURED";
        partial.operations = 1024;
        partial.p50Ms = 1.0;
        partial.p95Ms = 2.0;
        result.latency.add(partial);
        BenchmarkResult.Mixed partialMixed = new BenchmarkResult.Mixed();
        partialMixed.concurrency = 1;
        partialMixed.trial = 1;
        partialMixed.state = "MEASURED";
        partialMixed.operationsPerSecond = 10.0;
        partialMixed.writesValidated = true;
        result.mixed.add(partialMixed);

        ResultWriter writer = new ResultWriter();
        writer.writeResult(config, result);
        writer.writeReport(config);

        assertThat(config.resultFile("cognodb")).isRegularFile();
        assertThat(Files.readString(config.reportFile()))
                .contains("|cognodb|FAILED|")
                .contains("|cognodb|point_lookup|FAILED|NOT_RUN|NOT_RUN|1024|")
                .contains("|cognodb|1|FAILED|")
                .contains("|cognodb|NOT_RUN|Footprint observation was not attempted.|")
                .contains("no evidence-based winner");
    }
}
