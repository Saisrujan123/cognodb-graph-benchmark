package io.cognodb.benchmark.adapter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ArangoGraphAdapterTest {
    @Test
    void recognizesHttpAndServerSideQueryTimeouts() {
        assertTrue(ArangoGraphAdapter.isTimeoutResponse(408, -1));
        assertTrue(ArangoGraphAdapter.isTimeoutResponse(504, -1));
        assertTrue(ArangoGraphAdapter.isTimeoutResponse(400, 1500));
        assertFalse(ArangoGraphAdapter.isTimeoutResponse(400, 1501));
    }
}
