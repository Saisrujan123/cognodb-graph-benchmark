package io.cognodb.benchmark.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.cognodb.benchmark.config.BenchmarkConfig;
import io.cognodb.benchmark.dataset.WikiVoteDataset;
import io.cognodb.benchmark.model.BenchmarkResult;
import io.cognodb.benchmark.util.ResultDigest;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** ArangoDB implementation using Java 11 HttpClient and AQL cursor requests. */
public final class ArangoGraphAdapter implements GraphAdapter {
    private static final int DOCUMENT_COLLECTION = 2;
    private static final int EDGE_COLLECTION = 3;

    private final HttpClient client;
    private final ObjectMapper mapper;
    private final String baseUri;
    private final String database;
    private final String vertexCollection;
    private final String edgeCollection;
    private final String authorizationHeader;
    private final Duration requestTimeout;
    private final double maxRuntimeSeconds;
    private volatile boolean closed;

    public ArangoGraphAdapter(BenchmarkConfig.Platform config, int queryTimeoutSeconds) throws Exception {
        try {
            if (queryTimeoutSeconds <= 0) {
                throw new IllegalArgumentException("query timeout must be positive");
            }
            URI endpoint = URI.create(config.uri());
            if (endpoint.getUserInfo() != null) {
                throw new IllegalArgumentException(
                        "ArangoDB credentials must use dedicated environment variables, not URI user-info");
            }
            if (!"http".equalsIgnoreCase(endpoint.getScheme())
                    && !"https".equalsIgnoreCase(endpoint.getScheme())) {
                throw new IllegalArgumentException("ArangoDB URI must use http:// or https://");
            }
            if (endpoint.getHost() == null) {
                throw new IllegalArgumentException("ArangoDB URI must include a host");
            }

            String endpointText = endpoint.toString();
            this.baseUri = endpointText.endsWith("/")
                    ? endpointText.substring(0, endpointText.length() - 1)
                    : endpointText;
            this.database = config.database().isEmpty() ? "_system" : config.database();
            String prefix = AdapterSupport.safeIdentifier(config.graph(), "ArangoDB graph name");
            this.vertexCollection = prefix + "_benchmark_persons";
            this.edgeCollection = prefix + "_benchmark_votes_for";
            this.requestTimeout = Duration.ofSeconds(queryTimeoutSeconds);
            this.maxRuntimeSeconds = queryTimeoutSeconds;
            this.mapper = new ObjectMapper();
            this.client = HttpClient.newBuilder()
                    .connectTimeout(requestTimeout)
                    .version(HttpClient.Version.HTTP_2)
                    .build();
            if (config.username().isEmpty() && config.password().isEmpty()) {
                this.authorizationHeader = "";
            } else {
                String credentials = config.username() + ':' + config.password();
                this.authorizationHeader = "Basic " + Base64.getEncoder().encodeToString(
                        credentials.getBytes(StandardCharsets.UTF_8));
            }
        } catch (RuntimeException failure) {
            throw AdapterSupport.failure("Create ArangoDB adapter", failure);
        }
    }

    @Override
    public void verifyConnectivity() throws Exception {
        try {
            JsonNode version = request("GET", "/_api/version", null);
            if (version.path("version").asText("").isEmpty()) {
                throw new IllegalStateException("ArangoDB version response was missing its version field");
            }
        } catch (Exception failure) {
            throw AdapterSupport.failure("ArangoDB connectivity check", failure);
        }
    }

    @Override
    public String serverVersion() throws Exception {
        try {
            JsonNode response = request("GET", "/_api/version", null);
            String version = response.path("version").asText("");
            return version.isEmpty() ? "ArangoDB (version unavailable)" : "ArangoDB " + version;
        } catch (Exception failure) {
            throw AdapterSupport.failure("Read ArangoDB server version", failure);
        }
    }

    @Override
    public void resetBenchmarkData() throws Exception {
        try {
            ensureCollections();
            aql("FOR edge IN " + edgeCollection + " REMOVE edge IN " + edgeCollection,
                    emptyBindings());
            aql("FOR vertex IN " + vertexCollection + " REMOVE vertex IN " + vertexCollection,
                    emptyBindings());
            ensureIndex("id", true);
            ensureIndex("bucket", false);
        } catch (Exception failure) {
            throw AdapterSupport.failure("Reset ArangoDB benchmark data", failure);
        }
    }

    @Override
    public BenchmarkResult.Load load(WikiVoteDataset.Data dataset, int batchSize) throws Exception {
        AdapterSupport.requireBatchSize(batchSize);
        BenchmarkResult.Load metrics = new BenchmarkResult.Load();
        long totalStarted = System.nanoTime();
        try {
            ensureCollections();
            ensureIndex("id", true);

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
                Map<String, Object> bindings = new LinkedHashMap<>();
                bindings.put("rows", rows);
                bindings.put("scope", AdapterSupport.BENCHMARK_SCOPE);
                aql("FOR row IN @rows INSERT {"
                                + "_key: TO_STRING(row.id), id: row.id, bucket: row.bucket, "
                                + "benchmarkCounter: 0, benchmarkScope: @scope} INTO "
                                + vertexCollection,
                        bindings);
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
                Map<String, Object> bindings = new LinkedHashMap<>();
                bindings.put("rows", rows);
                bindings.put("scope", AdapterSupport.BENCHMARK_SCOPE);
                aql("FOR row IN @rows INSERT {"
                                + "_key: TO_STRING(row.relationshipId), "
                                + "_from: CONCAT('" + vertexCollection + "/', TO_STRING(row.sourceId)), "
                                + "_to: CONCAT('" + vertexCollection + "/', TO_STRING(row.targetId)), "
                                + "relationshipId: row.relationshipId, benchmarkScope: @scope} INTO "
                                + edgeCollection,
                        bindings);
            }
            double relationshipSeconds = AdapterSupport.secondsSince(phaseStarted);

            ensureIndex("bucket", false);
            // ArangoDB's index creation request returns after the persistent index is usable.

            Counts counts = countBenchmarkDataInternal();
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
            throw AdapterSupport.failure("Load ArangoDB benchmark data", failure);
        }
    }

    @Override
    public Counts countBenchmarkData() throws Exception {
        try {
            boolean verticesExist = collectionExists(vertexCollection);
            boolean edgesExist = collectionExists(edgeCollection);
            if (!verticesExist && !edgesExist) {
                return new Counts(0, 0);
            }
            if (verticesExist != edgesExist) {
                throw new IllegalStateException(
                        "ArangoDB benchmark collections are in a partial state");
            }
            return countBenchmarkDataInternal();
        } catch (Exception failure) {
            throw AdapterSupport.failure("Count ArangoDB benchmark data", failure);
        }
    }

    @Override
    public void resetCounters() throws Exception {
        try {
            aql("FOR person IN " + vertexCollection
                    + " UPDATE person WITH {benchmarkCounter: 0} IN " + vertexCollection,
                    emptyBindings());
        } catch (Exception failure) {
            throw AdapterSupport.failure("Reset ArangoDB benchmark counters", failure);
        }
    }

    @Override
    public long counterSum() throws Exception {
        try {
            return scalar("RETURN SUM((FOR person IN " + vertexCollection
                    + " RETURN person.benchmarkCounter))", emptyBindings());
        } catch (Exception failure) {
            throw AdapterSupport.failure("Read ArangoDB benchmark counter sum", failure);
        }
    }

    @Override
    public GraphAdapter.Session openSession() {
        if (closed) {
            throw new IllegalStateException("ArangoDB adapter is closed");
        }
        return new ArangoSession();
    }

    @Override
    public BenchmarkResult.Footprint observeFootprint() {
        BenchmarkResult.Footprint metric = new BenchmarkResult.Footprint();
        metric.state = "NOT_OBSERVABLE";
        metric.note = "The portable ArangoDB HTTP surface does not provide graph-scoped process memory.";
        return metric;
    }

    @Override
    public void close() {
        closed = true;
        // Java 11 HttpClient owns no caller-closeable resource and safely reuses its pool
        // until this adapter becomes unreachable.
    }

    private void ensureCollections() throws Exception {
        ensureCollection(vertexCollection, DOCUMENT_COLLECTION);
        ensureCollection(edgeCollection, EDGE_COLLECTION);
    }

    private void ensureCollection(String name, int type) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("name", name);
        body.put("type", type);
        try {
            request("POST", "/_api/collection", body);
        } catch (Exception failure) {
            if (!AdapterSupport.isAlreadyExists(failure)) {
                throw failure;
            }
        }
    }

    private boolean collectionExists(String name) throws Exception {
        try {
            request("GET", "/_api/collection/" + encode(name), null);
            return true;
        } catch (IOException failure) {
            if (failure.getMessage() != null
                    && failure.getMessage().startsWith("ArangoDB HTTP 404:")) {
                return false;
            }
            throw failure;
        }
    }

    private void ensureIndex(String property, boolean unique) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("type", "persistent");
        body.put("name", vertexCollection + '_' + property + "_idx");
        body.put("unique", unique);
        ArrayNode fields = body.putArray("fields");
        fields.add(property);
        request("POST", "/_api/index?collection=" + encode(vertexCollection), body);
    }

    private Counts countBenchmarkDataInternal() throws Exception {
        long nodes = scalar("RETURN LENGTH((FOR document IN " + vertexCollection
                + " RETURN 1))", emptyBindings());
        long relationships = scalar("RETURN LENGTH((FOR document IN " + edgeCollection
                + " RETURN 1))", emptyBindings());
        return new Counts(nodes, relationships);
    }

    private long scalar(String query, Map<String, Object> bindings) throws Exception {
        List<JsonNode> rows = aql(query, bindings);
        if (rows.size() != 1 || !rows.get(0).isNumber()) {
            throw new IllegalStateException("ArangoDB scalar query returned an unexpected result shape");
        }
        return rows.get(0).asLong();
    }

    private List<JsonNode> aql(String query, Map<String, Object> bindings) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("query", query);
        body.set("bindVars", mapper.valueToTree(bindings));
        body.put("batchSize", 10_000);
        ObjectNode options = body.putObject("options");
        options.put("maxRuntime", maxRuntimeSeconds);

        JsonNode page = request("POST", "/_api/cursor", body);
        List<JsonNode> rows = new ArrayList<>();
        appendRows(page, rows);
        while (page.path("hasMore").asBoolean(false)) {
            String cursorId = page.path("id").asText("");
            if (!cursorId.matches("[A-Za-z0-9_-]+")) {
                throw new IllegalStateException("ArangoDB returned an invalid cursor identifier");
            }
            page = request("PUT", "/_api/cursor/" + cursorId, mapper.createObjectNode());
            appendRows(page, rows);
        }
        return rows;
    }

    private static void appendRows(JsonNode page, List<JsonNode> rows) {
        JsonNode result = page.path("result");
        if (!result.isArray()) {
            throw new IllegalStateException("ArangoDB cursor response was missing its result array");
        }
        for (JsonNode row : result) {
            rows.add(row);
        }
    }

    private JsonNode request(String method, String path, JsonNode body) throws Exception {
        if (closed) {
            throw new IllegalStateException("ArangoDB adapter is closed");
        }
        URI uri = URI.create(baseUri + "/_db/" + encode(database) + path);
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .header("Accept", "application/json");
        if (!authorizationHeader.isEmpty()) {
            request.header("Authorization", authorizationHeader);
        }
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json");
            request.method(method, HttpRequest.BodyPublishers.ofString(
                    mapper.writeValueAsString(body), StandardCharsets.UTF_8));
        }

        HttpResponse<String> response;
        try {
            response = client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw failure;
        }
        JsonNode parsed = parseBody(response.body());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String detail = parsed.path("errorMessage").asText("request rejected");
            int errorNumber = parsed.path("errorNum").asInt(-1);
            if (isTimeoutResponse(response.statusCode(), errorNumber)) {
                throw new IOException("ArangoDB HTTP request timed out (status "
                        + response.statusCode() + ", error " + errorNumber + ")");
            }
            throw new IOException("ArangoDB HTTP " + response.statusCode() + ": " + detail);
        }
        return parsed;
    }

    static boolean isTimeoutResponse(int statusCode, int errorNumber) {
        // ArangoDB error 1500 is ERROR_QUERY_KILLED, which is how an AQL maxRuntime
        // expiry is surfaced to this harness. There is no operator cancellation path here.
        return statusCode == 408 || statusCode == 504 || errorNumber == 1500;
    }

    private JsonNode parseBody(String body) throws JsonProcessingException {
        if (body == null || body.trim().isEmpty()) {
            return mapper.createObjectNode();
        }
        return mapper.readTree(body);
    }

    private String pointLookupQuery() {
        return "RETURN LENGTH((FOR person IN " + vertexCollection
                + " FILTER person.id == @id LIMIT 1 RETURN 1))";
    }

    private String filteredLookupQuery() {
        return "RETURN LENGTH((FOR person IN " + vertexCollection
                + " FILTER person.bucket == @bucket RETURN 1))";
    }

    private String traversalQuery(int hops) {
        AdapterSupport.requireHopCount(hops);
        return "WITH " + vertexCollection + ' '
                + "LET endpoints = (FOR start IN " + vertexCollection
                + " FILTER start.id == @id LIMIT 1 "
                + "FOR endpoint, edge, path IN " + hops + ".." + hops
                + " OUTBOUND start " + edgeCollection + ' '
                + "OPTIONS {uniqueVertices: 'none', uniqueEdges: 'path'} "
                + "RETURN endpoint._id) RETURN LENGTH(UNIQUE(endpoints))";
    }

    private static Map<String, Object> emptyBindings() {
        return new LinkedHashMap<>();
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException impossible) {
            throw new IllegalStateException("UTF-8 is unavailable", impossible);
        }
    }

    private final class ArangoSession implements GraphAdapter.Session {
        private boolean sessionClosed;

        @Override
        public long pointLookup(long nodeId) throws Exception {
            Map<String, Object> bindings = new LinkedHashMap<>();
            bindings.put("id", nodeId);
            return safeScalar("ArangoDB point lookup", pointLookupQuery(), bindings);
        }

        @Override
        public long filteredLookup(int bucket) throws Exception {
            Map<String, Object> bindings = new LinkedHashMap<>();
            bindings.put("bucket", bucket);
            return safeScalar("ArangoDB filtered lookup", filteredLookupQuery(), bindings);
        }

        @Override
        public long traversal(long nodeId, int hops) throws Exception {
            Map<String, Object> bindings = new LinkedHashMap<>();
            bindings.put("id", nodeId);
            int validatedHops = AdapterSupport.requireHopCount(hops);
            return safeScalar("ArangoDB " + validatedHops + "-hop traversal",
                    traversalQuery(validatedHops), bindings);
        }

        @Override
        public long aggregationDigest() throws Exception {
            requireOpen();
            try {
                List<JsonNode> rows = aql("FOR person IN " + vertexCollection
                                + " COLLECT bucket = person.bucket WITH COUNT INTO nodeCount "
                                + "SORT bucket RETURN {bucket: bucket, nodeCount: nodeCount}",
                        emptyBindings());
                Map<Integer, Long> counts = new TreeMap<>();
                for (JsonNode row : rows) {
                    if (!row.path("bucket").isNumber() || !row.path("nodeCount").isNumber()) {
                        throw new IllegalStateException(
                                "ArangoDB aggregation returned an unexpected result shape");
                    }
                    counts.put(row.path("bucket").asInt(), row.path("nodeCount").asLong());
                }
                return ResultDigest.buckets(counts);
            } catch (Exception failure) {
                throw AdapterSupport.failure("ArangoDB aggregation", failure);
            }
        }

        @Override
        public long incrementCounter(long nodeId) throws Exception {
            Map<String, Object> bindings = new LinkedHashMap<>();
            bindings.put("id", nodeId);
            return safeScalar("ArangoDB counter increment",
                    "FOR person IN " + vertexCollection + " FILTER person.id == @id LIMIT 1 "
                            + "UPDATE person WITH {benchmarkCounter: person.benchmarkCounter + 1} IN "
                            + vertexCollection + " RETURN NEW.benchmarkCounter",
                    bindings);
        }

        @Override
        public void close() {
            sessionClosed = true;
        }

        private long safeScalar(String operation, String query, Map<String, Object> bindings)
                throws Exception {
            requireOpen();
            try {
                return scalar(query, bindings);
            } catch (Exception failure) {
                throw AdapterSupport.failure(operation, failure);
            }
        }

        private void requireOpen() {
            if (sessionClosed || closed) {
                throw new IllegalStateException("ArangoDB benchmark session is closed");
            }
        }
    }
}
