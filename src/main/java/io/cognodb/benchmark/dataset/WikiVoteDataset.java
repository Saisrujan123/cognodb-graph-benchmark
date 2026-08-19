package io.cognodb.benchmark.dataset;

import io.cognodb.benchmark.config.BenchmarkConfig;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/** Downloads, normalizes, samples, and loads the SNAP Wiki-Vote dataset. */
public final class WikiVoteDataset {
    private WikiVoteDataset() {
    }

    public static void prepare(BenchmarkConfig config, boolean forceDownload) throws Exception {
        if (forceDownload || !Files.isRegularFile(config.rawPath())) {
            download(config);
        }
        requireHash(config.rawPath(), config.sourceSha256(), "source archive");

        Set<Long> nodeIds = new LinkedHashSet<>();
        List<Relationship> relationships = new ArrayList<>();
        Map<Long, Integer> outDegrees = new HashMap<>();
        try (GZIPInputStream gzip = new GZIPInputStream(Files.newInputStream(config.rawPath()));
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(gzip, StandardCharsets.UTF_8))) {
            String line;
            long relationshipId = 1;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                String[] parts = trimmed.split("\\s+");
                if (parts.length != 2) throw new IOException("Malformed Wiki-Vote edge: " + line);
                long source = Long.parseLong(parts[0]);
                long target = Long.parseLong(parts[1]);
                nodeIds.add(source);
                nodeIds.add(target);
                relationships.add(new Relationship(relationshipId++, source, target));
                outDegrees.merge(source, 1, Integer::sum);
            }
        }

        if (nodeIds.size() != config.expectedNodes()
                || relationships.size() != config.expectedRelationships()) {
            throw new IOException("Unexpected dataset size: " + nodeIds.size() + " nodes, "
                    + relationships.size() + " relationships");
        }

        List<Long> nodes = new ArrayList<>(nodeIds);
        Collections.sort(nodes);
        writeNodes(config, nodes);
        writeRelationships(config, relationships);
        writeSample(config, nodes, outDegrees);
    }

    public static Data load(BenchmarkConfig config) throws IOException {
        List<Node> nodes = new ArrayList<>();
        Set<Long> nodeIds = new HashSet<>();
        try (BufferedReader reader = Files.newBufferedReader(config.nodesPath(), StandardCharsets.UTF_8)) {
            requireHeader(reader, "id,bucket,benchmark_counter", config.nodesPath());
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(",", -1);
                if (parts.length != 3) throw new IOException("Malformed node row: " + line);
                long id = Long.parseLong(parts[0]);
                int bucket = Integer.parseInt(parts[1]);
                if (!"0".equals(parts[2]) || bucket < 0 || bucket >= config.bucketCount()) {
                    throw new IOException("Invalid node row: " + line);
                }
                if (!nodeIds.add(id)) throw new IOException("Duplicate node id: " + id);
                nodes.add(new Node(id, bucket));
            }
        }

        List<Relationship> relationships = new ArrayList<>();
        Map<Long, Integer> outDegrees = new HashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(
                config.relationshipsPath(), StandardCharsets.UTF_8)) {
            requireHeader(reader, "relationship_id,source_id,target_id", config.relationshipsPath());
            String line;
            long expectedId = 1;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(",", -1);
                if (parts.length != 3) throw new IOException("Malformed relationship row: " + line);
                long id = Long.parseLong(parts[0]);
                long source = Long.parseLong(parts[1]);
                long target = Long.parseLong(parts[2]);
                if (id != expectedId++ || !nodeIds.contains(source) || !nodeIds.contains(target)) {
                    throw new IOException("Invalid relationship row: " + line);
                }
                relationships.add(new Relationship(id, source, target));
                outDegrees.merge(source, 1, Integer::sum);
            }
        }

        List<Long> sample = new ArrayList<>();
        Set<Long> sampledIds = new HashSet<>();
        try (BufferedReader reader = Files.newBufferedReader(config.samplePath(), StandardCharsets.UTF_8)) {
            requireHeader(reader, "ordinal,node_id,out_degree", config.samplePath());
            String line;
            int ordinal = 1;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(",", -1);
                if (parts.length != 3) throw new IOException("Malformed sample row: " + line);
                long nodeId = Long.parseLong(parts[1]);
                if (Integer.parseInt(parts[0]) != ordinal++ || !nodeIds.contains(nodeId)
                        || !sampledIds.add(nodeId)
                        || Integer.parseInt(parts[2]) != outDegrees.getOrDefault(nodeId, 0)) {
                    throw new IOException("Invalid sample row: " + line);
                }
                sample.add(nodeId);
            }
        }

        if (nodes.size() != config.expectedNodes()
                || relationships.size() != config.expectedRelationships()
                || sample.size() != config.sampleSize()) {
            throw new IOException("Prepared dataset counts do not match benchmark.properties");
        }
        return new Data(nodes, relationships, sample,
                sha256(config.nodesPath()), sha256(config.relationshipsPath()), sha256(config.samplePath()));
    }

    private static void download(BenchmarkConfig config) throws Exception {
        Files.createDirectories(config.rawPath().getParent());
        Path temporary = config.rawPath().resolveSibling(config.rawPath().getFileName() + ".part");
        try {
            HttpResponse<Path> response = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build()
                    .send(HttpRequest.newBuilder(config.sourceUri()).GET().build(),
                            HttpResponse.BodyHandlers.ofFile(temporary));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("Dataset download failed with HTTP " + response.statusCode());
            }
            requireHash(temporary, config.sourceSha256(), "downloaded source archive");
            Files.move(temporary, config.rawPath(), StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void writeNodes(BenchmarkConfig config, List<Long> nodeIds) throws IOException {
        Files.createDirectories(config.nodesPath().getParent());
        try (BufferedWriter writer = Files.newBufferedWriter(config.nodesPath(), StandardCharsets.UTF_8)) {
            writer.write("id,bucket,benchmark_counter\n");
            for (long id : nodeIds) {
                writer.write(id + "," + Math.floorMod(id, config.bucketCount()) + ",0\n");
            }
        }
    }

    private static void writeRelationships(BenchmarkConfig config,
                                           List<Relationship> relationships) throws IOException {
        Files.createDirectories(config.relationshipsPath().getParent());
        try (BufferedWriter writer = Files.newBufferedWriter(
                config.relationshipsPath(), StandardCharsets.UTF_8)) {
            writer.write("relationship_id,source_id,target_id\n");
            for (Relationship relationship : relationships) {
                writer.write(relationship.id + "," + relationship.source + ","
                        + relationship.target + "\n");
            }
        }
    }

    private static void writeSample(BenchmarkConfig config, List<Long> nodeIds,
                                    Map<Long, Integer> outDegrees) throws IOException {
        if (config.sampleSize() > nodeIds.size()) {
            throw new IOException("sample.size exceeds the node count");
        }
        List<Long> shuffled = new ArrayList<>(nodeIds);
        Collections.shuffle(shuffled, new Random(config.randomSeed()));
        Files.createDirectories(config.samplePath().getParent());
        try (BufferedWriter writer = Files.newBufferedWriter(config.samplePath(), StandardCharsets.UTF_8)) {
            writer.write("ordinal,node_id,out_degree\n");
            for (int index = 0; index < config.sampleSize(); index++) {
                long id = shuffled.get(index);
                writer.write((index + 1) + "," + id + "," + outDegrees.getOrDefault(id, 0) + "\n");
            }
        }
    }

    private static void requireHeader(BufferedReader reader, String expected, Path path)
            throws IOException {
        if (!expected.equals(reader.readLine())) {
            throw new IOException("Unexpected CSV header in " + path.getFileName());
        }
    }

    private static void requireHash(Path path, String expected, String label) throws IOException {
        if (!expected.equalsIgnoreCase(sha256(path))) {
            throw new IOException("SHA-256 mismatch for " + label);
        }
    }

    public static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[16_384];
            try (java.io.InputStream input = Files.newInputStream(path)) {
                int read;
                while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
            }
            StringBuilder result = new StringBuilder();
            for (byte value : digest.digest()) result.append(String.format("%02x", value));
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    public static final class Node {
        private final long id;
        private final int bucket;
        public Node(long id, int bucket) { this.id = id; this.bucket = bucket; }
        public long id() { return id; }
        public int bucket() { return bucket; }
    }

    public static final class Relationship {
        private final long id;
        private final long source;
        private final long target;
        public Relationship(long id, long source, long target) {
            this.id = id; this.source = source; this.target = target;
        }
        public long id() { return id; }
        public long source() { return source; }
        public long target() { return target; }
    }

    public static final class Data {
        private final List<Node> nodes;
        private final List<Relationship> relationships;
        private final List<Long> sample;
        private final String nodesSha256;
        private final String relationshipsSha256;
        private final String sampleSha256;

        public Data(List<Node> nodes, List<Relationship> relationships, List<Long> sample,
                    String nodesSha256, String relationshipsSha256, String sampleSha256) {
            this.nodes = Collections.unmodifiableList(nodes);
            this.relationships = Collections.unmodifiableList(relationships);
            this.sample = Collections.unmodifiableList(sample);
            this.nodesSha256 = nodesSha256;
            this.relationshipsSha256 = relationshipsSha256;
            this.sampleSha256 = sampleSha256;
        }

        public List<Node> nodes() { return nodes; }
        public List<Relationship> relationships() { return relationships; }
        public List<Long> sample() { return sample; }
        public String nodesSha256() { return nodesSha256; }
        public String relationshipsSha256() { return relationshipsSha256; }
        public String sampleSha256() { return sampleSha256; }
    }
}
