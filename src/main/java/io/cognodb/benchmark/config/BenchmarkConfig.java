package io.cognodb.benchmark.config;

import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/** All benchmark and platform settings from one properties file. */
public final class BenchmarkConfig {
    private final Path projectRoot;
    private final Properties values;
    private final Map<String, String> environment;

    private BenchmarkConfig(Path projectRoot, Properties values, Map<String, String> environment) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.values = values;
        this.environment = environment;
    }

    public static BenchmarkConfig load(Path projectRoot, Path configPath,
                                       Map<String, String> environment) throws IOException {
        Path resolved = configPath.isAbsolute()
                ? configPath : projectRoot.resolve(configPath).normalize();
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(resolved, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return new BenchmarkConfig(projectRoot, properties, environment);
    }

    public URI sourceUri() { return URI.create(required("dataset.source.url")); }
    public String sourceSha256() { return required("dataset.source.sha256"); }
    public int expectedNodes() { return positiveInt("dataset.expected.nodes"); }
    public int expectedRelationships() { return positiveInt("dataset.expected.relationships"); }
    public Path rawPath() { return path("dataset.raw.path"); }
    public Path nodesPath() { return path("dataset.nodes.path"); }
    public Path relationshipsPath() { return path("dataset.relationships.path"); }
    public Path samplePath() { return path("dataset.sample.path"); }
    public int bucketCount() { return positiveInt("dataset.bucket.count"); }
    public int sampleSize() { return positiveInt("sample.size"); }
    public long randomSeed() { return Long.parseLong(required("random.seed")); }
    public int batchSize() { return positiveInt("load.batch.size"); }
    public int queryTimeoutSeconds() { return positiveInt("query.timeout.seconds"); }
    public int readWarmupIterations() { return nonNegativeInt("read.warmup.iterations"); }
    public int readMeasureIterations() { return positiveInt("read.measure.iterations"); }
    public int readTrials() { return positiveInt("read.trials"); }
    public int mixedWarmupSeconds() { return nonNegativeInt("mixed.warmup.seconds"); }
    public int mixedMeasureSeconds() { return positiveInt("mixed.measure.seconds"); }
    public int mixedTrials() { return positiveInt("mixed.trials"); }

    public int mixedWritePercent() {
        int value = nonNegativeInt("mixed.write.percent");
        if (value != 10) {
            throw new IllegalArgumentException("This assignment uses a fixed 90/10 read/write mix");
        }
        return value;
    }

    public List<Integer> mixedConcurrencies() {
        List<Integer> result = new ArrayList<>();
        for (String part : required("mixed.concurrencies").split(",")) {
            int value = Integer.parseInt(part.trim());
            if (value <= 0) {
                throw new IllegalArgumentException("mixed.concurrencies must be positive");
            }
            result.add(value);
        }
        return Collections.unmodifiableList(result);
    }

    public List<String> platformIds() {
        List<String> result = new ArrayList<>();
        for (String part : required("platforms").split(",")) {
            String id = part.trim();
            if (!id.matches("[a-z0-9_-]+")) {
                throw new IllegalArgumentException("Invalid platform id: " + id);
            }
            result.add(id);
        }
        return Collections.unmodifiableList(result);
    }

    public Platform platform(String id) {
        if (!platformIds().contains(id)) {
            throw new IllegalArgumentException("Unknown platform: " + id);
        }
        return new Platform(id);
    }

    public Path resultsPath() { return path("results.path"); }
    public Path resultFile(String platformId) { return resultsPath().resolve(platformId + ".json"); }
    public Path reportFile() { return resultsPath().resolve("REPORT.md"); }
    public Path csvFile() { return resultsPath().resolve("results.csv"); }

    private String required(String key) {
        String value = values.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing setting: " + key);
        }
        return value.trim();
    }

    private String optional(String key, String fallback) {
        String value = values.getProperty(key);
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private int positiveInt(String key) {
        int value = Integer.parseInt(required(key));
        if (value <= 0) throw new IllegalArgumentException(key + " must be positive");
        return value;
    }

    private int nonNegativeInt(String key) {
        int value = Integer.parseInt(required(key));
        if (value < 0) throw new IllegalArgumentException(key + " must not be negative");
        return value;
    }

    private Path path(String key) {
        return projectRoot.resolve(required(key)).normalize();
    }

    /** One platform block from benchmark.properties. Secrets are read only from the environment. */
    public final class Platform {
        private final String id;

        private Platform(String id) { this.id = id; }
        private String key(String suffix) { return id + "." + suffix; }

        public String id() { return id; }
        public String name() { return required(key("name")); }
        public String adapterType() { return required(key("adapter")); }
        public String adapterFlavor() { return optional(key("flavor"), ""); }
        public String uri() { return requiredEnvironment("uri.env"); }
        public String username() { return optionalEnvironment("username.env", ""); }
        public String password() { return optionalEnvironment("password.env", ""); }
        public String database() { return optionalEnvironment("database.env", ""); }
        public String graph() { return optionalEnvironment("graph.env", "benchmark"); }
        public String region() { return optionalEnvironment("region.env", "UNKNOWN"); }

        public Map<String, String> publicDetails() {
            Map<String, String> result = new LinkedHashMap<>();
            result.put("name", name());
            result.put("deployment", optional(key("deployment"), "UNKNOWN"));
            result.put("tier", optional(key("tier"), "UNKNOWN"));
            result.put("region", region());
            result.put("vcpu", optional(key("vcpu"), "UNKNOWN"));
            result.put("memoryMb", optional(key("memory.mb"), "UNKNOWN"));
            result.put("storageGb", optional(key("storage.gb"), "UNKNOWN"));
            result.put("loadMethod", optional(key("load.method"), "driver batches"));
            return result;
        }

        private String requiredEnvironment(String suffix) {
            String variable = required(key(suffix));
            String value = environment.get(variable);
            if (value == null || value.trim().isEmpty()) {
                throw new IllegalArgumentException("Required environment variable is not set: " + variable);
            }
            return value.trim();
        }

        private String optionalEnvironment(String suffix, String fallback) {
            String variable = optional(key(suffix), "");
            if (variable.isEmpty()) return fallback;
            String value = environment.get(variable);
            return value == null || value.trim().isEmpty() ? fallback : value.trim();
        }
    }
}
