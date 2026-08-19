package io.cognodb.benchmark.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The one JSON result written for a platform. */
public final class BenchmarkResult {
    public String platformId = "";
    public String status = "NOT_RUN";
    public String startedAtUtc = "";
    public String completedAtUtc = "";
    public Map<String, String> platform = new LinkedHashMap<>();
    public Map<String, Object> dataset = new LinkedHashMap<>();
    public Map<String, Object> client = new LinkedHashMap<>();
    public Load load = new Load();
    public List<Latency> latency = new ArrayList<>();
    public List<Mixed> mixed = new ArrayList<>();
    public Footprint footprint = new Footprint();
    public List<String> caveats = new ArrayList<>();

    public static final class Load {
        public String state = "NOT_RUN";
        public long nodes;
        public long relationships;
        public double nodeSeconds;
        public double relationshipSeconds;
        public double totalSeconds;
        public double nodesPerSecond;
        public double relationshipsPerSecond;
    }

    public static final class Latency {
        public String workload = "";
        public int trial;
        public String state = "NOT_RUN";
        public long operations;
        public double p50Ms;
        public double p95Ms;
    }

    public static final class Mixed {
        public int concurrency;
        public int trial;
        public String state = "NOT_RUN";
        public double seconds;
        public long attempted;
        public long successful;
        public long failed;
        public long timedOut;
        public long reads;
        public long writes;
        public double operationsPerSecond;
        public double p50Ms;
        public double p95Ms;
        public boolean writesValidated;
    }

    public static final class Footprint {
        public String state = "NOT_RUN";
        public Map<String, Object> values = new LinkedHashMap<>();
        public String note = "Footprint observation was not attempted.";
    }
}
