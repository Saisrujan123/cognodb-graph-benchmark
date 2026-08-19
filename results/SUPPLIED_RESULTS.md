# Supplied result snapshot

`supplied-summary.csv` is an unchanged copy of the numeric CSV found in the ZIP provided after the benchmark was said to have run. It is preserved separately from the current harness output so provenance is not blurred.

## What the snapshot contains

- one summary row for CognoDB Cloud, Neo4j, Memgraph, FalkorDB, and ArangoDB;
- total ingest time plus reported node and relationship rates;
- p50/p95 for exact 1-, 2-, and 3-hop traversal;
- p50 only for point lookup, filtered/range lookup, and aggregation;
- mixed-workload QPS at 1, 10, and 40 clients;
- disk and RAM numbers.

## What is missing

- per-run JSON, console logs, timestamps, warm-up evidence, trial values, and query-result validation;
- p95 for point lookup, filtered lookup, and aggregation;
- mixed-workload p50/p95, operation counts, read/write counts, timeouts, and per-trial values;
- live instance versions, client/service regions, JVM settings, and proof of equal CPU, RAM, and storage;
- an observable source for the footprint values.

## Integrity observations

The snapshot is not treated as verified output from the Java code in this repository:

1. The ZIP contained a different, shorter Java implementation and a 100-node sample; this repository uses a deterministic 256-node sample and stronger result validation.
2. The ZIP's prepared graph is not the source-derived Wiki-Vote topology. Its node IDs are contiguous `1..7115` and its maximum out-degree is 30; the verified source-derived data has non-contiguous IDs `3..8297` and maximum out-degree 893.
3. The ZIP's runner divides both loaded counts by total load time. With 7,115 nodes, 103,689 relationships, and its reported total times, the CSV rates do not reproduce that formula. For example, 7,115 / 5.04 is about 1,412 nodes/s, not 14,230 nodes/s.
4. The ZIP's adapter methods returned fixed disk/RAM values, so those footprint cells are code constants rather than observations.
5. The ZIP described an 80/20 mixed workload, while this repository's checked methodology is 90/10.

The numbers remain useful as a supplied summary for follow-up, but they must not be described as a verified, fair five-platform comparison until the missing evidence is recovered or the current harness is rerun.
