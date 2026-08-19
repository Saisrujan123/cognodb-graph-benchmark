package io.cognodb.benchmark.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class BenchmarkConfigTest {
    @TempDir Path temporary;

    @Test
    void loadsFivePlatformsAndEnforcesTheNinetyTenMix() throws Exception {
        Path root = Paths.get("").toAbsolutePath().normalize();
        Path source = root.resolve("config/benchmark.properties");
        BenchmarkConfig config = BenchmarkConfig.load(root, source, System.getenv());
        assertEquals(5, config.platformIds().size());
        assertEquals(10, config.mixedWritePercent());

        Path invalid = temporary.resolve("invalid.properties");
        Files.writeString(invalid, Files.readString(source, StandardCharsets.UTF_8)
                .replace("mixed.write.percent=10", "mixed.write.percent=20"),
                StandardCharsets.UTF_8);
        BenchmarkConfig invalidConfig = BenchmarkConfig.load(root, invalid, System.getenv());
        assertThrows(IllegalArgumentException.class, invalidConfig::mixedWritePercent);
    }
}
