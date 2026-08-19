package io.cognodb.benchmark.adapter;

import io.cognodb.benchmark.config.BenchmarkConfig;
import io.cognodb.benchmark.dataset.WikiVoteDataset;
import io.cognodb.benchmark.model.BenchmarkResult;
import io.cognodb.benchmark.util.ResultDigest;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.commands.ProtocolCommand;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** FalkorDB adapter using its native Redis/RESP graph commands through Jedis. */
public final class FalkorGraphAdapter implements GraphAdapter {
    private final URI endpoint;
    private final String username;
    private final String password;
    private final String graph;
    private final long timeoutMillis;
    private final int clientTimeoutMillis;
    private final Jedis admin;

    public FalkorGraphAdapter(BenchmarkConfig.Platform config, int queryTimeoutSeconds) throws Exception {
        try {
            if (queryTimeoutSeconds <= 0) {
                throw new IllegalArgumentException("query timeout must be positive");
            }
            URI configuredEndpoint = URI.create(config.uri());
            if (configuredEndpoint.getUserInfo() != null) {
                throw new IllegalArgumentException(
                        "FalkorDB credentials must use dedicated environment variables, not URI user-info");
            }
            String scheme = configuredEndpoint.getScheme();
            if (!"redis".equalsIgnoreCase(scheme) && !"rediss".equalsIgnoreCase(scheme)) {
                throw new IllegalArgumentException("FalkorDB URI must use redis:// or rediss://");
            }
            this.endpoint = configuredEndpoint;
            this.username = config.username();
            this.password = config.password();
            this.graph = config.graph();
            if (graph == null || graph.trim().isEmpty()) {
                throw new IllegalArgumentException("FalkorDB graph name must not be empty");
            }
            this.timeoutMillis = Math.multiplyExact((long) queryTimeoutSeconds, 1_000L);
            this.clientTimeoutMillis = Math.toIntExact(timeoutMillis);
            this.admin = openConnection();
        } catch (Exception failure) {
            throw AdapterSupport.failure("Create FalkorDB adapter", failure);
        }
    }

    @Override
    public void verifyConnectivity() throws Exception {
        try {
            admin.ping();
            // GRAPH.LIST is read-only and proves that the endpoint has the FalkorDB module,
            // unlike PING, which would also succeed against an ordinary Redis server.
            admin.sendCommand(FalkorCommand.LIST);
        } catch (RuntimeException failure) {
            throw AdapterSupport.failure("FalkorDB connectivity check", failure);
        }
    }

    @Override
    public String serverVersion() throws Exception {
        try {
            String info = admin.info("server");
            String version = infoValue(info, "redis_version");
            return version.isEmpty()
                    ? "FalkorDB module present; versions unavailable"
                    : "Redis " + version + " with FalkorDB module; module version not queried";
        } catch (RuntimeException failure) {
            throw AdapterSupport.failure("Read FalkorDB server version", failure);
        }
    }

    @Override
    public void resetBenchmarkData() throws Exception {
        try {
            query(admin, "MATCH (n:" + AdapterSupport.BENCHMARK_LABEL + ") DETACH DELETE n");
        } catch (RuntimeException failure) {
            if (!AdapterSupport.isMissingGraph(failure)) {
                throw AdapterSupport.failure("Reset FalkorDB benchmark data", failure);
            }
        }
        try {
            createIndexIgnoringExisting("id");
            createIndexIgnoringExisting("bucket");
        } catch (RuntimeException failure) {
            throw AdapterSupport.failure("Prepare FalkorDB benchmark schema", failure);
        }
    }

    @Override
    public BenchmarkResult.Load load(WikiVoteDataset.Data dataset, int batchSize) throws Exception {
        AdapterSupport.requireBatchSize(batchSize);
        BenchmarkResult.Load metrics = new BenchmarkResult.Load();
        long totalStarted = System.nanoTime();
        try {
            createIndexIgnoringExisting("id");

            long phaseStarted = System.nanoTime();
            for (int offset = 0; offset < dataset.nodes().size(); offset += batchSize) {
                int end = Math.min(offset + batchSize, dataset.nodes().size());
                query(admin, nodeBatch(dataset.nodes().subList(offset, end)));
            }
            double nodeSeconds = AdapterSupport.secondsSince(phaseStarted);

            phaseStarted = System.nanoTime();
            for (int offset = 0; offset < dataset.relationships().size(); offset += batchSize) {
                int end = Math.min(offset + batchSize, dataset.relationships().size());
                query(admin, relationshipBatch(dataset.relationships().subList(offset, end)));
            }
            double relationshipSeconds = AdapterSupport.secondsSince(phaseStarted);

            createIndexIgnoringExisting("bucket");
            // FalkorDB's CREATE INDEX command completes only after the range index is usable.

            Counts counts = countBenchmarkData(admin);
            if (counts.nodes() != dataset.nodes().size()
                    || counts.relationships() != dataset.relationships().size()) {
                throw new IllegalStateException("Post-load count validation failed: expected "
                        + dataset.nodes().size() + " nodes and " + dataset.relationships().size()
                        + " relationships, received " + counts.nodes() + " nodes and "
                        + counts.relationships() + " relationships");
            }
            AdapterSupport.finishLoadMetrics(
                    metrics, counts.nodes(), counts.relationships(),
                    nodeSeconds, relationshipSeconds, totalStarted);
            return metrics;
        } catch (Exception failure) {
            throw AdapterSupport.failure("Load FalkorDB benchmark data", failure);
        }
    }

    @Override
    public Counts countBenchmarkData() throws Exception {
        try {
            if (!admin.exists(graph)) {
                return new Counts(0, 0);
            }
            return countBenchmarkData(admin);
        } catch (RuntimeException failure) {
            if (AdapterSupport.isMissingGraph(failure)) {
                return new Counts(0, 0);
            }
            throw AdapterSupport.failure("Count FalkorDB benchmark data", failure);
        }
    }

    @Override
    public void resetCounters() throws Exception {
        try {
            query(admin, "MATCH (n:" + AdapterSupport.BENCHMARK_LABEL
                    + ") SET n.benchmarkCounter = 0");
        } catch (RuntimeException failure) {
            throw AdapterSupport.failure("Reset FalkorDB benchmark counters", failure);
        }
    }

    @Override
    public long counterSum() throws Exception {
        try {
            return scalar(admin, "MATCH (n:" + AdapterSupport.BENCHMARK_LABEL + ") "
                    + "RETURN coalesce(sum(n.benchmarkCounter), 0) AS value");
        } catch (RuntimeException failure) {
            throw AdapterSupport.failure("Read FalkorDB benchmark counter sum", failure);
        }
    }

    @Override
    public GraphAdapter.Session openSession() throws Exception {
        try {
            return new FalkorSession(openConnection());
        } catch (Exception failure) {
            throw AdapterSupport.failure("Open FalkorDB benchmark session", failure);
        }
    }

    @Override
    public BenchmarkResult.Footprint observeFootprint() {
        BenchmarkResult.Footprint metric = new BenchmarkResult.Footprint();
        metric.state = "NOT_OBSERVABLE";
        try {
            String info = admin.info("memory");
            String bytes = infoValue(info, "used_memory");
            if (!bytes.isEmpty()) {
                metric.state = "MEASURED";
                metric.values.put("redisUsedMemoryBytes", Long.parseLong(bytes));
                metric.note = "Redis used_memory observed through INFO; this is process-wide, not graph-only.";
            }
        } catch (RuntimeException ignored) {
            metric.note = "FalkorDB/Redis memory information was not observable.";
        }
        return metric;
    }

    @Override
    public void close() {
        try {
            admin.close();
        } catch (RuntimeException failure) {
            throw AdapterSupport.failure("Close FalkorDB adapter", failure);
        }
    }

    private Jedis openConnection() {
        Jedis connection = new Jedis(endpoint, clientTimeoutMillis, clientTimeoutMillis);
        try {
            if (!password.isEmpty()) {
                if (username.isEmpty()) {
                    connection.auth(password);
                } else {
                    connection.auth(username, password);
                }
            }
            connection.ping();
            return connection;
        } catch (RuntimeException failure) {
            connection.close();
            throw failure;
        }
    }

    private void createIndexIgnoringExisting(String property) {
        try {
            query(admin, "CREATE INDEX FOR (n:" + AdapterSupport.BENCHMARK_LABEL
                    + ") ON (n." + property + ")");
        } catch (RuntimeException failure) {
            if (!AdapterSupport.isAlreadyExists(failure)) {
                throw failure;
            }
        }
    }

    private Counts countBenchmarkData(Jedis connection) {
        long nodes = scalar(connection, "MATCH (n:" + AdapterSupport.BENCHMARK_LABEL
                + ") RETURN count(n) AS value");
        long relationships = scalar(connection, "MATCH (:" + AdapterSupport.BENCHMARK_LABEL
                + ")-[r:VOTES_FOR]->(:" + AdapterSupport.BENCHMARK_LABEL + ") "
                + "WHERE r.benchmarkScope = '" + AdapterSupport.BENCHMARK_SCOPE
                + "' RETURN count(r) AS value");
        return new Counts(nodes, relationships);
    }

    private List<List<Object>> query(Jedis connection, String cypher) {
        Object response = connection.sendCommand(FalkorCommand.QUERY, commandArguments(
                graph, cypher, "TIMEOUT", Long.toString(timeoutMillis), "--compact"));
        return resultRows(response);
    }

    private long scalar(Jedis connection, String cypher) {
        List<List<Object>> rows = query(connection, cypher);
        if (rows.size() != 1 || rows.get(0).size() != 1) {
            throw new IllegalStateException("FalkorDB scalar query returned an unexpected result shape");
        }
        return asLong(rows.get(0).get(0));
    }

    private static String nodeBatch(List<WikiVoteDataset.Node> nodes) {
        StringBuilder query = new StringBuilder("UNWIND [");
        for (int index = 0; index < nodes.size(); index++) {
            if (index > 0) {
                query.append(',');
            }
            WikiVoteDataset.Node node = nodes.get(index);
            query.append("{id:").append(node.id())
                    .append(",bucket:").append(node.bucket()).append('}');
        }
        return query.append("] AS row CREATE (:Person:")
                .append(AdapterSupport.BENCHMARK_LABEL)
                .append(" {id:row.id,bucket:row.bucket,benchmarkCounter:0,benchmarkScope:'")
                .append(AdapterSupport.BENCHMARK_SCOPE)
                .append("'})")
                .toString();
    }

    private static String relationshipBatch(List<WikiVoteDataset.Relationship> relationships) {
        StringBuilder query = new StringBuilder("UNWIND [");
        for (int index = 0; index < relationships.size(); index++) {
            if (index > 0) {
                query.append(',');
            }
            WikiVoteDataset.Relationship relationship = relationships.get(index);
            query.append("{relationshipId:").append(relationship.id())
                    .append(",sourceId:").append(relationship.source())
                    .append(",targetId:").append(relationship.target()).append('}');
        }
        return query.append("] AS row MATCH (source:")
                .append(AdapterSupport.BENCHMARK_LABEL)
                .append(" {id:row.sourceId}),(target:")
                .append(AdapterSupport.BENCHMARK_LABEL)
                .append(" {id:row.targetId}) CREATE (source)-[:VOTES_FOR {relationshipId:")
                .append("row.relationshipId,benchmarkScope:'")
                .append(AdapterSupport.BENCHMARK_SCOPE)
                .append("'}]->(target)")
                .toString();
    }

    private static String traversalQuery(int hops) {
        AdapterSupport.requireHopCount(hops);
        return "MATCH (start:" + AdapterSupport.BENCHMARK_LABEL + " {id:$nodeId})"
                + "-[:VOTES_FOR*" + hops + "]->(endpoint:"
                + AdapterSupport.BENCHMARK_LABEL + ") "
                + "RETURN count(DISTINCT endpoint.id) AS value";
    }

    private static String withLongParameter(String name, long value, String query) {
        return "CYPHER " + name + '=' + value + ' ' + query;
    }

    @SuppressWarnings("unchecked")
    private static List<List<Object>> resultRows(Object response) {
        if (!(response instanceof List)) {
            throw new IllegalStateException("FalkorDB query returned an unexpected response type");
        }
        List<Object> sections = (List<Object>) response;
        if (sections.size() < 2 || !(sections.get(1) instanceof List)) {
            return new ArrayList<>();
        }
        List<Object> rawRows = (List<Object>) sections.get(1);
        List<List<Object>> rows = new ArrayList<>(rawRows.size());
        for (Object rawRow : rawRows) {
            if (rawRow instanceof List) {
                rows.add((List<Object>) rawRow);
            } else {
                List<Object> row = new ArrayList<>(1);
                row.add(rawRow);
                rows.add(row);
            }
        }
        return rows;
    }

    @SuppressWarnings("unchecked")
    private static long asLong(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof byte[]) {
            return Long.parseLong(new String((byte[]) value, StandardCharsets.UTF_8));
        }
        if (value instanceof List) {
            List<Object> values = (List<Object>) value;
            if (values.size() == 1) {
                return asLong(values.get(0));
            }
            // Non-compact FalkorDB cells are encoded as [type, value].
            if (values.size() == 2) {
                return asLong(values.get(1));
            }
        }
        return Long.parseLong(String.valueOf(value));
    }

    private static String infoValue(String info, String key) {
        if (info == null) {
            return "";
        }
        String prefix = key.toLowerCase(Locale.ROOT) + ':';
        for (String line : info.split("\\r?\\n")) {
            if (line.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                return line.substring(prefix.length()).trim();
            }
        }
        return "";
    }

    private static byte[][] commandArguments(String... values) {
        byte[][] result = new byte[values.length][];
        for (int index = 0; index < values.length; index++) {
            result[index] = values[index].getBytes(StandardCharsets.UTF_8);
        }
        return result;
    }

    private final class FalkorSession implements GraphAdapter.Session {
        private final Jedis connection;

        private FalkorSession(Jedis connection) {
            this.connection = connection;
        }

        @Override
        public long pointLookup(long nodeId) throws Exception {
            return safeScalar("FalkorDB point lookup", withLongParameter(
                    "nodeId", nodeId, "MATCH (n:" + AdapterSupport.BENCHMARK_LABEL
                            + " {id:$nodeId}) RETURN count(n) AS value"));
        }

        @Override
        public long filteredLookup(int bucket) throws Exception {
            return safeScalar("FalkorDB filtered lookup", withLongParameter(
                    "bucket", bucket, "MATCH (n:" + AdapterSupport.BENCHMARK_LABEL
                            + " {bucket:$bucket}) RETURN count(n) AS value"));
        }

        @Override
        public long traversal(long nodeId, int hops) throws Exception {
            return safeScalar("FalkorDB " + AdapterSupport.requireHopCount(hops) + "-hop traversal",
                    withLongParameter("nodeId", nodeId, traversalQuery(hops)));
        }

        @Override
        public long aggregationDigest() throws Exception {
            try {
                List<List<Object>> rows = query(connection,
                        "MATCH (n:" + AdapterSupport.BENCHMARK_LABEL + ") "
                                + "RETURN n.bucket AS bucket, count(n) AS nodeCount ORDER BY bucket");
                Map<Integer, Long> counts = new TreeMap<>();
                for (List<Object> row : rows) {
                    if (row.size() != 2) {
                        throw new IllegalStateException(
                                "FalkorDB aggregation returned an unexpected result shape");
                    }
                    counts.put((int) asLong(row.get(0)), asLong(row.get(1)));
                }
                return ResultDigest.buckets(counts);
            } catch (RuntimeException failure) {
                throw AdapterSupport.failure("FalkorDB aggregation", failure);
            }
        }

        @Override
        public long incrementCounter(long nodeId) throws Exception {
            return safeScalar("FalkorDB counter increment", withLongParameter(
                    "nodeId", nodeId, "MATCH (n:" + AdapterSupport.BENCHMARK_LABEL
                            + " {id:$nodeId}) SET n.benchmarkCounter = n.benchmarkCounter + 1 "
                            + "RETURN n.benchmarkCounter AS value"));
        }

        @Override
        public void close() {
            try {
                connection.close();
            } catch (RuntimeException failure) {
                throw AdapterSupport.failure("Close FalkorDB benchmark session", failure);
            }
        }

        private long safeScalar(String operation, String cypher) throws Exception {
            try {
                return scalar(connection, cypher);
            } catch (RuntimeException failure) {
                throw AdapterSupport.failure(operation, failure);
            }
        }
    }

    private enum FalkorCommand implements ProtocolCommand {
        QUERY("GRAPH.QUERY"),
        LIST("GRAPH.LIST");

        private final byte[] raw;

        FalkorCommand(String command) {
            this.raw = command.getBytes(StandardCharsets.US_ASCII);
        }

        @Override
        public byte[] getRaw() {
            return raw;
        }
    }
}
