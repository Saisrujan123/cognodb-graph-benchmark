package io.cognodb.benchmark.adapter;

import io.cognodb.benchmark.dataset.WikiVoteDataset;
import io.cognodb.benchmark.model.BenchmarkResult;

/** Small common contract implemented by the three protocol adapters. */
public interface GraphAdapter extends AutoCloseable {
    void verifyConnectivity() throws Exception;
    String serverVersion() throws Exception;
    void resetBenchmarkData() throws Exception;
    BenchmarkResult.Load load(WikiVoteDataset.Data dataset, int batchSize) throws Exception;
    Counts countBenchmarkData() throws Exception;
    void resetCounters() throws Exception;
    long counterSum() throws Exception;
    Session openSession() throws Exception;
    BenchmarkResult.Footprint observeFootprint();

    @Override
    void close();

    final class Counts {
        private final long nodes;
        private final long relationships;

        public Counts(long nodes, long relationships) {
            this.nodes = nodes;
            this.relationships = relationships;
        }

        public long nodes() { return nodes; }
        public long relationships() { return relationships; }
    }

    interface Session extends AutoCloseable {
        long pointLookup(long nodeId) throws Exception;
        long filteredLookup(int bucket) throws Exception;
        long traversal(long nodeId, int hops) throws Exception;
        long aggregationDigest() throws Exception;
        long incrementCounter(long nodeId) throws Exception;

        @Override
        void close();
    }
}
