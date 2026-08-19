package io.cognodb.benchmark.runner;

import io.cognodb.benchmark.dataset.WikiVoteDataset;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

final class ReferenceGraphTest {
    @Test
    void usesDirectedExactDepthRelationshipTrails() {
        WikiVoteDataset.Data data = new WikiVoteDataset.Data(
                Arrays.asList(new WikiVoteDataset.Node(1, 1),
                        new WikiVoteDataset.Node(2, 2), new WikiVoteDataset.Node(3, 3)),
                Arrays.asList(new WikiVoteDataset.Relationship(1, 1, 2),
                        new WikiVoteDataset.Relationship(2, 2, 1),
                        new WikiVoteDataset.Relationship(3, 2, 3)),
                Collections.singletonList(1L), "nodes", "relationships", "sample");

        ReferenceGraph graph = new ReferenceGraph(data);
        assertThat(graph.traversal(1, 1)).isEqualTo(1);
        assertThat(graph.traversal(1, 2)).isEqualTo(2);
        assertThat(graph.traversal(1, 3)).isZero();
    }
}
