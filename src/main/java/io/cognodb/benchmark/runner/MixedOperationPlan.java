package io.cognodb.benchmark.runner;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public final class MixedOperationPlan {
    public enum Operation {
        POINT,
        TRAVERSAL_1,
        TRAVERSAL_2,
        TRAVERSAL_3,
        FILTERED,
        AGGREGATION,
        WRITE
    }

    private final long seed;
    private final int worker;
    private final int writePercent;
    private long blockNumber;
    private List<Operation> block = new ArrayList<>();
    private int position;

    public MixedOperationPlan(long seed, int worker, int writePercent) {
        this.seed = seed;
        this.worker = worker;
        this.writePercent = writePercent;
        refill();
    }

    public Operation next() {
        if (position >= block.size()) {
            refill();
        }
        return block.get(position++);
    }

    private void refill() {
        block = new ArrayList<>(100);
        int reads = 100 - writePercent;
        int point = (int) Math.round(reads * 35.0 / 90.0);
        int traversal1 = (int) Math.round(reads * 20.0 / 90.0);
        int traversal2 = (int) Math.round(reads * 15.0 / 90.0);
        int traversal3 = (int) Math.round(reads * 5.0 / 90.0);
        int filtered = (int) Math.round(reads * 10.0 / 90.0);
        int aggregation = reads - point - traversal1 - traversal2 - traversal3 - filtered;

        add(Operation.POINT, point);
        add(Operation.TRAVERSAL_1, traversal1);
        add(Operation.TRAVERSAL_2, traversal2);
        add(Operation.TRAVERSAL_3, traversal3);
        add(Operation.FILTERED, filtered);
        add(Operation.AGGREGATION, aggregation);
        add(Operation.WRITE, writePercent);

        Random random = new Random(seed ^ ((long) worker << 32) ^ blockNumber++);
        for (int index = block.size() - 1; index > 0; index--) {
            int swapWith = random.nextInt(index + 1);
            Operation value = block.get(index);
            block.set(index, block.get(swapWith));
            block.set(swapWith, value);
        }
        position = 0;
    }

    private void add(Operation operation, int count) {
        for (int index = 0; index < count; index++) {
            block.add(operation);
        }
    }
}

