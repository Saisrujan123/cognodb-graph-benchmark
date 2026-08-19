# Benchmark results

Generated: 2026-08-19T12:56:31.617596Z

Missing measurements are shown as `NOT_RUN`, never as zero. Do not compare platforms until CPU, RAM, storage, client, and region are equivalent.

## Status and environment

|Platform|Status|Tier|Service region|Client region|vCPU|RAM MB|Storage GB|Server|
|---|---|---|---|---|---:|---:|---:|---|
|cognodb|NOT_RUN|c0 free|UNKNOWN|NOT_RUN|0.5 burstable|256|1|UNKNOWN|
|neo4j|NOT_RUN|Community 2026.06.0|UNKNOWN|NOT_RUN|0.5 target|256 target|1 logical cap|UNKNOWN|
|memgraph|NOT_RUN|Community 3.12.0|UNKNOWN|NOT_RUN|0.5 target|256 target|1 logical cap|UNKNOWN|
|falkordb|NOT_RUN|Server 4.20.1|UNKNOWN|NOT_RUN|0.5 target|256 target|1 logical cap|UNKNOWN|
|arangodb|NOT_RUN|Community 3.12.9|UNKNOWN|NOT_RUN|0.5 target|256 target|1 logical cap|UNKNOWN|

## Data loading

|Platform|State|Total seconds|Nodes/s|Relationships/s|Load method|
|---|---|---:|---:|---:|---|
|cognodb|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|Bolt driver batches of 500|
|neo4j|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|Bolt driver batches of 500|
|memgraph|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|Bolt driver batches of 500|
|falkordb|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|Redis/Falkor query batches of 500|
|arangodb|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|HTTP/AQL batches of 500|

## Read latency

Values are medians of the trial-level p50/p95 values.

|Platform|Workload|State|p50 ms|p95 ms|Operations|
|---|---|---|---:|---:|---:|
|cognodb|point_lookup|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|
|cognodb|filtered_lookup|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|
|cognodb|traversal_1|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|
|cognodb|traversal_2|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|
|cognodb|traversal_3|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|
|cognodb|aggregation|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|
|neo4j|point_lookup|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|
|neo4j|filtered_lookup|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|
|neo4j|traversal_1|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|
|neo4j|traversal_2|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|
|neo4j|traversal_3|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|
|neo4j|aggregation|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|
|memgraph|point_lookup|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|
|memgraph|filtered_lookup|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|
|memgraph|traversal_1|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|
|memgraph|traversal_2|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|
|memgraph|traversal_3|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|
|memgraph|aggregation|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|
|falkordb|point_lookup|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|
|falkordb|filtered_lookup|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|
|falkordb|traversal_1|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|
|falkordb|traversal_2|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|
|falkordb|traversal_3|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|
|falkordb|aggregation|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|
|arangodb|point_lookup|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|
|arangodb|filtered_lookup|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|
|arangodb|traversal_1|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|
|arangodb|traversal_2|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|
|arangodb|traversal_3|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|
|arangodb|aggregation|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|

## Mixed workload - 90% reads / 10% writes

|Platform|Clients|State|Median ops/s|p50 ms|p95 ms|Failures|Timeouts|Writes validated|
|---|---:|---|---:|---:|---:|---:|---:|---|
|cognodb|1|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|
|cognodb|10|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|
|cognodb|40|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|
|neo4j|1|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|
|neo4j|10|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|
|neo4j|40|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|
|memgraph|1|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|
|memgraph|10|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|
|memgraph|40|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|
|falkordb|1|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|
|falkordb|10|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|
|falkordb|40|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|
|arangodb|1|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|
|arangodb|10|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|
|arangodb|40|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|NOT_RUN|

## Footprint and caveats

|Platform|State|Observed|Caveats|
|---|---|---|---|
|cognodb|NOT_RUN|NOT_RUN|No benchmark run.|
|neo4j|NOT_RUN|NOT_RUN|No benchmark run.|
|memgraph|NOT_RUN|NOT_RUN|No benchmark run.|
|falkordb|NOT_RUN|NOT_RUN|No benchmark run.|
|arangodb|NOT_RUN|NOT_RUN|No benchmark run.|

## Analysis

The comparison is incomplete, so there is no evidence-based winner or performance conclusion yet.
