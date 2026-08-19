package io.cognodb.benchmark.runner;

import io.cognodb.benchmark.dataset.WikiVoteDataset;
import io.cognodb.benchmark.util.ResultDigest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public final class ReferenceGraph {
    private final Set<Long> nodeIds = new HashSet<>();
    private final Map<Integer, Long> bucketCounts = new TreeMap<>();
    private final Map<Long, List<Edge>> outgoing = new HashMap<>();
    private final Map<String, Long> traversalCache = new HashMap<>();

    public ReferenceGraph(WikiVoteDataset.Data dataset) {
        for (WikiVoteDataset.Node node : dataset.nodes()) {
            nodeIds.add(node.id());
            bucketCounts.merge(node.bucket(), 1L, Long::sum);
        }
        for (WikiVoteDataset.Relationship relationship : dataset.relationships()) {
            outgoing.computeIfAbsent(relationship.source(), ignored -> new ArrayList<>())
                    .add(new Edge(relationship.id(), relationship.target()));
        }
    }

    public long pointLookup(long id) {
        return nodeIds.contains(id) ? 1L : 0L;
    }

    public long filteredLookup(int bucket) {
        return bucketCounts.getOrDefault(bucket, 0L);
    }

    public long aggregationDigest() {
        return ResultDigest.buckets(bucketCounts);
    }

    public synchronized long traversal(long startId, int hops) {
        if (hops < 1 || hops > 3) {
            throw new IllegalArgumentException("Only 1, 2, and 3 hop traversals are supported");
        }
        String key = startId + ":" + hops;
        Long cached = traversalCache.get(key);
        if (cached != null) {
            return cached;
        }
        Set<Long> endpoints = new HashSet<>();
        collectEndpoints(startId, hops, new HashSet<Long>(), endpoints);
        long result = endpoints.size();
        traversalCache.put(key, result);
        return result;
    }

    private void collectEndpoints(long current, int remaining, Set<Long> usedRelationships, Set<Long> endpoints) {
        List<Edge> edges = outgoing.get(current);
        if (edges == null) {
            return;
        }
        for (Edge edge : edges) {
            if (!usedRelationships.add(edge.relationshipId)) {
                continue;
            }
            if (remaining == 1) {
                endpoints.add(edge.targetId);
            } else {
                collectEndpoints(edge.targetId, remaining - 1, usedRelationships, endpoints);
            }
            usedRelationships.remove(edge.relationshipId);
        }
    }

    private static final class Edge {
        private final long relationshipId;
        private final long targetId;

        private Edge(long relationshipId, long targetId) {
            this.relationshipId = relationshipId;
            this.targetId = targetId;
        }
    }
}
