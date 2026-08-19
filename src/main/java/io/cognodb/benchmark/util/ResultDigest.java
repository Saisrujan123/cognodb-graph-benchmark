package io.cognodb.benchmark.util;

import java.util.Map;

public final class ResultDigest {
    private static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    private ResultDigest() {
    }

    public static long start() {
        return FNV_OFFSET_BASIS;
    }

    public static long add(long digest, long value) {
        long result = digest;
        for (int shift = 0; shift < Long.SIZE; shift += Byte.SIZE) {
            result ^= (value >>> shift) & 0xffL;
            result *= FNV_PRIME;
        }
        return result;
    }

    public static long buckets(Map<Integer, Long> counts) {
        long digest = start();
        for (Map.Entry<Integer, Long> entry : counts.entrySet()) {
            digest = add(digest, entry.getKey());
            digest = add(digest, entry.getValue());
        }
        return digest;
    }

    public static String hex(long digest) {
        return String.format("%016x", digest);
    }
}

