package io.cognodb.benchmark.adapter;

import io.cognodb.benchmark.config.BenchmarkConfig;
import io.cognodb.benchmark.dataset.WikiVoteDataset;
import io.cognodb.benchmark.model.BenchmarkResult;
import io.cognodb.benchmark.util.ResultDigest;
import org.neo4j.driver.AuthToken;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.Transaction;
import org.neo4j.driver.TransactionConfig;
import org.neo4j.driver.Values;

import java.util.ArrayList;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Bolt implementation shared by CognoDB, Neo4j, and Memgraph.
 *
 * <p>The additional {@code CognoBenchmarkPerson} label is intentionally used on every
 * benchmark Person. It makes reset operations safe in a database that also contains
 * unrelated {@code Person} nodes.</p>
 */
public final class BoltGraphAdapter implements GraphAdapter {
    private static final String NODE_LOAD =
            "UNWIND $rows AS row "
                    + "CREATE (:Person:" + AdapterSupport.BENCHMARK_LABEL + " {"
                    + "id: row.id, bucket: row.bucket, benchmarkCounter: 0, "
                    + "benchmarkScope: $scope})";
    private static final String RELATIONSHIP_LOAD =
            "UNWIND $rows AS row "
                    + "MATCH (source:" + AdapterSupport.BENCHMARK_LABEL + " {id: row.sourceId}), "
                    + "(target:" + AdapterSupport.BENCHMARK_LABEL + " {id: row.targetId}) "
                    + "CREATE (source)-[:VOTES_FOR {relationshipId: row.relationshipId, "
                    + "benchmarkScope: $scope}]->(target)";

    private final Driver driver;
    private final String database;
    private final Flavor flavor;
    private final TransactionConfig transactionConfig;

    public BoltGraphAdapter(BenchmarkConfig.Platform config, int queryTimeoutSeconds) throws Exception {
        try {
            this.database = config.database();
            this.flavor = Flavor.from(config.id(), config.adapterFlavor());
            if (queryTimeoutSeconds <= 0) {
                throw new IllegalArgumentException("query timeout must be positive");
            }
            this.transactionConfig = TransactionConfig.builder()
                    .withTimeout(Duration.ofSeconds(queryTimeoutSeconds))
                    .build();
            AuthToken auth = config.username().isEmpty() && config.password().isEmpty()
                    ? AuthTokens.none()
                    : AuthTokens.basic(config.username(), config.password());
            this.driver = GraphDatabase.driver(config.uri(), auth);
        } catch (RuntimeException failure) {
            throw AdapterSupport.failure("Create Bolt adapter", failure);
        }
    }

    @Override
    public void verifyConnectivity() throws Exception {
        try {
            driver.verifyConnectivity();
            try (org.neo4j.driver.Session session = newSession()) {
                session.run("RETURN 1 AS value", transactionConfig).consume();
            }
        } catch (RuntimeException failure) {
            throw AdapterSupport.failure("Bolt connectivity check", failure);
        }
    }

    @Override
    public String serverVersion() throws Exception {
        try {
            driver.verifyConnectivity();
            String agent;
            try (org.neo4j.driver.Session session = newSession()) {
                agent = session.run("RETURN 1 AS value", transactionConfig)
                        .consume().server().agent();
            }
            return agent == null || agent.trim().isEmpty()
                    ? flavor.displayName + " (version unavailable)"
                    : agent;
        } catch (RuntimeException failure) {
            throw AdapterSupport.failure("Read Bolt server version", failure);
        }
    }

    @Override
    public void resetBenchmarkData() throws Exception {
        try (org.neo4j.driver.Session session = newSession()) {
            executeStatement(session,
                    "MATCH (n:" + AdapterSupport.BENCHMARK_LABEL + ") DETACH DELETE n");
            createIdSchema(session);
            createBucketSchema(session);
            awaitIndexesIfSupported(session);
        } catch (RuntimeException failure) {
            throw AdapterSupport.failure("Reset Bolt benchmark data", failure);
        }
    }

    @Override
    public BenchmarkResult.Load load(WikiVoteDataset.Data dataset, int batchSize) throws Exception {
        AdapterSupport.requireBatchSize(batchSize);
        BenchmarkResult.Load metrics = new BenchmarkResult.Load();
        long totalStarted = System.nanoTime();
        try (org.neo4j.driver.Session session = newSession()) {
            createIdSchema(session);
            awaitIndexesIfSupported(session);

            long phaseStarted = System.nanoTime();
            for (int offset = 0; offset < dataset.nodes().size(); offset += batchSize) {
                int end = Math.min(offset + batchSize, dataset.nodes().size());
                List<Map<String, Object>> rows = new ArrayList<>(end - offset);
                for (WikiVoteDataset.Node node : dataset.nodes().subList(offset, end)) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", node.id());
                    row.put("bucket", node.bucket());
                    rows.add(row);
                }
                executeWrite(session, NODE_LOAD, rows);
            }
            double nodeSeconds = AdapterSupport.secondsSince(phaseStarted);

            phaseStarted = System.nanoTime();
            for (int offset = 0; offset < dataset.relationships().size(); offset += batchSize) {
                int end = Math.min(offset + batchSize, dataset.relationships().size());
                List<Map<String, Object>> rows = new ArrayList<>(end - offset);
                for (WikiVoteDataset.Relationship relationship
                        : dataset.relationships().subList(offset, end)) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("relationshipId", relationship.id());
                    row.put("sourceId", relationship.source());
                    row.put("targetId", relationship.target());
                    rows.add(row);
                }
                executeWrite(session, RELATIONSHIP_LOAD, rows);
            }
            double relationshipSeconds = AdapterSupport.secondsSince(phaseStarted);

            createBucketSchema(session);
            awaitIndexesIfSupported(session);

            Counts counts = countBenchmarkData(session);
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
            throw AdapterSupport.failure("Load Bolt benchmark data", failure);
        }
    }

    @Override
    public Counts countBenchmarkData() throws Exception {
        try (org.neo4j.driver.Session session = newSession()) {
            return countBenchmarkData(session);
        } catch (RuntimeException failure) {
            throw AdapterSupport.failure("Count Bolt benchmark data", failure);
        }
    }

    @Override
    public void resetCounters() throws Exception {
        try (org.neo4j.driver.Session session = newSession()) {
            executeStatement(session, "MATCH (n:" + AdapterSupport.BENCHMARK_LABEL + ") "
                    + "SET n.benchmarkCounter = 0");
        } catch (RuntimeException failure) {
            throw AdapterSupport.failure("Reset Bolt benchmark counters", failure);
        }
    }

    @Override
    public long counterSum() throws Exception {
        try (org.neo4j.driver.Session session = newSession()) {
            return executeScalar(session, "MATCH (n:" + AdapterSupport.BENCHMARK_LABEL + ") "
                    + "RETURN coalesce(sum(n.benchmarkCounter), 0) AS value");
        } catch (RuntimeException failure) {
            throw AdapterSupport.failure("Read Bolt benchmark counter sum", failure);
        }
    }

    @Override
    public GraphAdapter.Session openSession() throws Exception {
        try {
            return new BoltSession(newSession(), transactionConfig);
        } catch (RuntimeException failure) {
            throw AdapterSupport.failure("Open Bolt benchmark session", failure);
        }
    }

    @Override
    public BenchmarkResult.Footprint observeFootprint() {
        BenchmarkResult.Footprint metric = new BenchmarkResult.Footprint();
        metric.state = "NOT_OBSERVABLE";
        metric.note = "The Bolt protocol does not expose a portable process-footprint metric.";
        return metric;
    }

    @Override
    public void close() {
        try {
            driver.close();
        } catch (RuntimeException failure) {
            throw AdapterSupport.failure("Close Bolt adapter", failure);
        }
    }

    private org.neo4j.driver.Session newSession() {
        if (database == null || database.isEmpty()) {
            return driver.session();
        }
        return driver.session(SessionConfig.builder().withDatabase(database).build());
    }

    private void createIdSchema(org.neo4j.driver.Session session) {
        if (flavor == Flavor.NEO4J) {
            executeStatement(session, "CREATE CONSTRAINT benchmark_person_id IF NOT EXISTS "
                    + "FOR (n:" + AdapterSupport.BENCHMARK_LABEL + ") REQUIRE n.id IS UNIQUE");
            return;
        }

        if (flavor == Flavor.MEMGRAPH) {
            createSchemaIgnoringExisting(session,
                    "CREATE INDEX ON :" + AdapterSupport.BENCHMARK_LABEL + "(id)");
            return;
        }

        createSchemaIgnoringExisting(session,
                "CREATE INDEX FOR (n:" + AdapterSupport.BENCHMARK_LABEL + ") ON (n.id)");
    }

    private void createBucketSchema(org.neo4j.driver.Session session) {
        if (flavor == Flavor.NEO4J) {
            executeStatement(session, "CREATE INDEX benchmark_person_bucket IF NOT EXISTS "
                    + "FOR (n:" + AdapterSupport.BENCHMARK_LABEL + ") ON (n.bucket)");
            return;
        }
        if (flavor == Flavor.MEMGRAPH) {
            createSchemaIgnoringExisting(session,
                    "CREATE INDEX ON :" + AdapterSupport.BENCHMARK_LABEL + "(bucket)");
            return;
        }
        createSchemaIgnoringExisting(session,
                "CREATE INDEX FOR (n:" + AdapterSupport.BENCHMARK_LABEL + ") ON (n.bucket)");
    }

    private void awaitIndexesIfSupported(org.neo4j.driver.Session session) {
        if (flavor == Flavor.NEO4J) {
            executeStatement(session, "CALL db.awaitIndexes(300)");
        }
        // Memgraph and CognoDB make CREATE INDEX synchronous through their supported
        // Bolt/Cypher surface, so successful command completion is their readiness boundary.
    }

    private void createSchemaIgnoringExisting(
            org.neo4j.driver.Session session, String statement) {
        try {
            executeStatement(session, statement);
        } catch (RuntimeException failure) {
            if (!AdapterSupport.isAlreadyExists(failure)) {
                throw failure;
            }
        }
    }

    private void executeWrite(
            org.neo4j.driver.Session session,
            String query,
            List<Map<String, Object>> rows) {
        try (Transaction transaction = session.beginTransaction(transactionConfig)) {
            transaction.run(query, Values.parameters(
                    "rows", rows,
                    "scope", AdapterSupport.BENCHMARK_SCOPE)).consume();
            transaction.commit();
        }
    }

    private Counts countBenchmarkData(org.neo4j.driver.Session session) {
        long nodes = executeScalar(session, "MATCH (n:" + AdapterSupport.BENCHMARK_LABEL + ") "
                + "RETURN count(n) AS value");
        long relationships = executeScalar(session, "MATCH (:" + AdapterSupport.BENCHMARK_LABEL
                        + ")-[r:VOTES_FOR]->(:" + AdapterSupport.BENCHMARK_LABEL + ") "
                        + "WHERE r.benchmarkScope = $scope RETURN count(r) AS value",
                Values.parameters("scope", AdapterSupport.BENCHMARK_SCOPE));
        return new Counts(nodes, relationships);
    }

    private void executeStatement(org.neo4j.driver.Session session, String query) {
        try (Transaction transaction = session.beginTransaction(transactionConfig)) {
            transaction.run(query).consume();
            transaction.commit();
        }
    }

    private long executeScalar(org.neo4j.driver.Session session, String query) {
        try (Transaction transaction = session.beginTransaction(transactionConfig)) {
            Result result = transaction.run(query);
            long value = result.single().get("value").asLong();
            result.consume();
            transaction.commit();
            return value;
        }
    }

    private long executeScalar(
            org.neo4j.driver.Session session,
            String query,
            org.neo4j.driver.Value parameters) {
        try (Transaction transaction = session.beginTransaction(transactionConfig)) {
            Result result = transaction.run(query, parameters);
            long value = result.single().get("value").asLong();
            result.consume();
            transaction.commit();
            return value;
        }
    }

    private static String traversalQuery(int hops) {
        AdapterSupport.requireHopCount(hops);
        return "MATCH (start:" + AdapterSupport.BENCHMARK_LABEL + " {id: $id})"
                + "-[:VOTES_FOR*" + hops + "]->(endpoint:"
                + AdapterSupport.BENCHMARK_LABEL + ") "
                + "RETURN count(DISTINCT endpoint.id) AS value";
    }

    private static final class BoltSession implements GraphAdapter.Session {
        private final org.neo4j.driver.Session session;
        private final TransactionConfig transactionConfig;

        private BoltSession(
                org.neo4j.driver.Session session,
                TransactionConfig transactionConfig) {
            this.session = session;
            this.transactionConfig = transactionConfig;
        }

        @Override
        public long pointLookup(long nodeId) throws Exception {
            return scalar("Bolt point lookup",
                    "MATCH (n:" + AdapterSupport.BENCHMARK_LABEL + " {id: $id}) "
                            + "RETURN count(n) AS value",
                    Values.parameters("id", nodeId));
        }

        @Override
        public long filteredLookup(int bucket) throws Exception {
            return scalar("Bolt filtered lookup",
                    "MATCH (n:" + AdapterSupport.BENCHMARK_LABEL + " {bucket: $bucket}) "
                            + "RETURN count(n) AS value",
                    Values.parameters("bucket", bucket));
        }

        @Override
        public long traversal(long nodeId, int hops) throws Exception {
            return scalar("Bolt " + AdapterSupport.requireHopCount(hops) + "-hop traversal",
                    traversalQuery(hops), Values.parameters("id", nodeId));
        }

        @Override
        public long aggregationDigest() throws Exception {
            try (Transaction transaction = session.beginTransaction(transactionConfig)) {
                Result result = transaction.run("MATCH (n:" + AdapterSupport.BENCHMARK_LABEL + ") "
                        + "RETURN n.bucket AS bucket, count(n) AS nodeCount ORDER BY bucket");
                Map<Integer, Long> counts = new TreeMap<>();
                while (result.hasNext()) {
                    Record row = result.next();
                    counts.put(row.get("bucket").asInt(), row.get("nodeCount").asLong());
                }
                result.consume();
                transaction.commit();
                return ResultDigest.buckets(counts);
            } catch (RuntimeException failure) {
                throw AdapterSupport.failure("Bolt aggregation", failure);
            }
        }

        @Override
        public long incrementCounter(long nodeId) throws Exception {
            return scalar("Bolt counter increment",
                    "MATCH (n:" + AdapterSupport.BENCHMARK_LABEL + " {id: $id}) "
                            + "SET n.benchmarkCounter = n.benchmarkCounter + 1 "
                            + "RETURN n.benchmarkCounter AS value",
                    Values.parameters("id", nodeId));
        }

        @Override
        public void close() {
            try {
                session.close();
            } catch (RuntimeException failure) {
                throw AdapterSupport.failure("Close Bolt benchmark session", failure);
            }
        }

        private long scalar(String operation, String query, org.neo4j.driver.Value parameters)
                throws Exception {
            try {
                try (Transaction transaction = session.beginTransaction(transactionConfig)) {
                    Result result = transaction.run(query, parameters);
                    long value = result.single().get("value").asLong();
                    result.consume();
                    transaction.commit();
                    return value;
                }
            } catch (RuntimeException failure) {
                throw AdapterSupport.failure(operation, failure);
            }
        }
    }

    private enum Flavor {
        COGNODB("CognoDB"),
        MEMGRAPH("Memgraph"),
        NEO4J("Neo4j");

        private final String displayName;

        Flavor(String displayName) {
            this.displayName = displayName;
        }

        private static Flavor from(String platformId, String configuredFlavor) {
            String id = platformId == null ? "" : platformId.toLowerCase(Locale.ROOT);
            String flavor = configuredFlavor == null ? "" : configuredFlavor.toLowerCase(Locale.ROOT);
            if ("cognodb".equals(id) || "cognodb".equals(flavor)) {
                return COGNODB;
            }
            if ("memgraph".equals(id) || "memgraph".equals(flavor)) {
                return MEMGRAPH;
            }
            if ("neo4j".equals(id) || "neo4j".equals(flavor)) {
                return NEO4J;
            }
            throw new IllegalArgumentException("Unsupported Bolt adapter flavor");
        }
    }
}
