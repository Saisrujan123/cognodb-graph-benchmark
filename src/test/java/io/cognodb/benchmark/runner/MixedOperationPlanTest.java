package io.cognodb.benchmark.runner;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MixedOperationPlanTest {
    @Test
    void emitsAnExactNinetyTenBlock() {
        MixedOperationPlan plan = new MixedOperationPlan(20260818L, 0, 10);
        Map<MixedOperationPlan.Operation, Integer> counts = new EnumMap<>(MixedOperationPlan.Operation.class);
        for (int index = 0; index < 100; index++) {
            counts.merge(plan.next(), 1, Integer::sum);
        }

        assertThat(counts.get(MixedOperationPlan.Operation.WRITE)).isEqualTo(10);
        assertThat(counts.entrySet().stream()
                .filter(entry -> entry.getKey() != MixedOperationPlan.Operation.WRITE)
                .mapToInt(Map.Entry::getValue)
                .sum()).isEqualTo(90);
    }

    @Test
    void isDeterministicPerWorker() {
        MixedOperationPlan first = new MixedOperationPlan(44L, 3, 10);
        MixedOperationPlan second = new MixedOperationPlan(44L, 3, 10);
        for (int index = 0; index < 250; index++) {
            assertThat(first.next()).isEqualTo(second.next());
        }
    }
}

