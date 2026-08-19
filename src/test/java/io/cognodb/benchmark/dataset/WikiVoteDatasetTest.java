package io.cognodb.benchmark.dataset;

import io.cognodb.benchmark.config.BenchmarkConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class WikiVoteDatasetTest {
    @TempDir Path temporary;

    @Test
    void normalizesAndValidatesACompressedEdgeList() throws Exception {
        Path raw = temporary.resolve("data/raw/fixture.txt.gz");
        Files.createDirectories(raw.getParent());
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                new GZIPOutputStream(Files.newOutputStream(raw)), StandardCharsets.UTF_8))) {
            writer.write("# fixture\n1 2\n2 3\n3 1\n");
        }

        Path properties = temporary.resolve("fixture.properties");
        Files.writeString(properties, String.join("\n",
                "dataset.source.url=https://example.invalid/fixture.txt.gz",
                "dataset.source.sha256=" + WikiVoteDataset.sha256(raw),
                "dataset.expected.nodes=3",
                "dataset.expected.relationships=3",
                "dataset.raw.path=data/raw/fixture.txt.gz",
                "dataset.nodes.path=data/prepared/nodes.csv",
                "dataset.relationships.path=data/prepared/relationships.csv",
                "dataset.sample.path=data/samples/start-nodes.csv",
                "dataset.bucket.count=10",
                "sample.size=3",
                "random.seed=7",
                ""), StandardCharsets.UTF_8);

        BenchmarkConfig config = BenchmarkConfig.load(temporary, properties, Collections.emptyMap());
        WikiVoteDataset.prepare(config, false);
        WikiVoteDataset.Data data = WikiVoteDataset.load(config);
        assertThat(data.nodes()).hasSize(3);
        assertThat(data.relationships()).hasSize(3);
        assertThat(data.sample()).containsExactlyInAnyOrder(1L, 2L, 3L);

        Files.writeString(config.samplePath(), "\n", StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND);
        assertThatThrownBy(() -> WikiVoteDataset.load(config))
                .isInstanceOf(java.io.IOException.class);
    }
}
