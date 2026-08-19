package io.cognodb.benchmark;

import io.cognodb.benchmark.adapter.ArangoGraphAdapter;
import io.cognodb.benchmark.adapter.BoltGraphAdapter;
import io.cognodb.benchmark.adapter.FalkorGraphAdapter;
import io.cognodb.benchmark.adapter.GraphAdapter;
import io.cognodb.benchmark.config.BenchmarkConfig;
import io.cognodb.benchmark.dataset.WikiVoteDataset;
import io.cognodb.benchmark.model.BenchmarkResult;
import io.cognodb.benchmark.runner.BenchmarkRunner;
import io.cognodb.benchmark.runner.ResultWriter;
import io.cognodb.benchmark.util.SecretRedactor;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.ScopeType;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.Callable;

@Command(name = "graph-benchmark", version = "1.0.0", mixinStandardHelpOptions = true,
        description = "Small Java benchmark for CognoDB and four graph databases.",
        subcommands = {BenchmarkMain.Prepare.class, BenchmarkMain.Doctor.class,
                BenchmarkMain.Run.class, BenchmarkMain.All.class, BenchmarkMain.Report.class})
public final class BenchmarkMain implements Callable<Integer> {
    @Option(names = "--project-root", defaultValue = ".", scope = ScopeType.INHERIT,
            description = "Project directory.")
    private Path projectRoot;

    @Option(names = "--config", defaultValue = "config/benchmark.properties", scope = ScopeType.INHERIT,
            description = "Properties file relative to the project directory.")
    private Path configPath;

    public static void main(String[] args) {
        BenchmarkMain application = new BenchmarkMain();
        CommandLine cli = new CommandLine(application);
        cli.setExecutionExceptionHandler((failure, command, parsed) -> {
            command.getErr().println("ERROR: " + SecretRedactor.redact(failure.getMessage()));
            return 1;
        });
        System.exit(cli.execute(args));
    }

    @Override
    public Integer call() {
        new CommandLine(this).usage(System.out);
        return 0;
    }

    private BenchmarkConfig config() throws Exception {
        Path root = projectRoot.toAbsolutePath().normalize();
        return BenchmarkConfig.load(root, configPath, System.getenv());
    }

    private GraphAdapter adapter(BenchmarkConfig config, BenchmarkConfig.Platform platform)
            throws Exception {
        switch (platform.adapterType().toLowerCase(Locale.ROOT)) {
            case "bolt": return new BoltGraphAdapter(platform, config.queryTimeoutSeconds());
            case "falkor": return new FalkorGraphAdapter(platform, config.queryTimeoutSeconds());
            case "arango": return new ArangoGraphAdapter(platform, config.queryTimeoutSeconds());
            default: throw new IllegalArgumentException("Unknown adapter: " + platform.adapterType());
        }
    }

    private int runPlatform(BenchmarkConfig config, WikiVoteDataset.Data data,
                            String platformId) throws Exception {
        BenchmarkConfig.Platform platform = config.platform(platformId);
        BenchmarkResult result;
        try {
            try (GraphAdapter adapter = adapter(config, platform)) {
                result = new BenchmarkRunner().run(config, platform, data, adapter);
            }
        } catch (Exception failure) {
            result = new BenchmarkResult();
            result.platformId = platformId;
            result.status = "FAILED";
            result.startedAtUtc = Instant.now().toString();
            result.completedAtUtc = result.startedAtUtc;
            result.platform.putAll(platform.publicDetails());
            result.caveats.add("Run failed: " + SecretRedactor.redact(failure.getMessage()));
        }
        Path output = new ResultWriter().writeResult(config, result);
        System.out.println(platformId + ": " + result.status + " -> " + output);
        return "MEASURED".equals(result.status) ? 0 : 1;
    }

    @Command(name = "prepare-data", mixinStandardHelpOptions = true,
            description = "Download, verify, and normalize SNAP Wiki-Vote.")
    static final class Prepare implements Callable<Integer> {
        @ParentCommand private BenchmarkMain parent;
        @Option(names = "--force-download", description = "Download the source archive again.")
        private boolean forceDownload;

        @Override
        public Integer call() throws Exception {
            BenchmarkConfig config = parent.config();
            WikiVoteDataset.prepare(config, forceDownload);
            WikiVoteDataset.Data data = WikiVoteDataset.load(config);
            System.out.printf("Prepared %,d nodes and %,d relationships.%n",
                    data.nodes().size(), data.relationships().size());
            return 0;
        }
    }

    @Command(name = "doctor", mixinStandardHelpOptions = true,
            description = "Check one database connection without changing data.")
    static final class Doctor implements Callable<Integer> {
        @ParentCommand private BenchmarkMain parent;
        @Option(names = "--platform", required = true) private String platformId;

        @Override
        public Integer call() throws Exception {
            BenchmarkConfig config = parent.config();
            BenchmarkConfig.Platform platform = config.platform(platformId);
            try (GraphAdapter adapter = parent.adapter(config, platform)) {
                adapter.verifyConnectivity();
                GraphAdapter.Counts counts = adapter.countBenchmarkData();
                System.out.printf("Connection OK: %s (%s), benchmark rows %,d / %,d%n",
                        platform.name(), SecretRedactor.redact(adapter.serverVersion()),
                        counts.nodes(), counts.relationships());
            }
            return 0;
        }
    }

    @Command(name = "run", mixinStandardHelpOptions = true,
            description = "Reset benchmark-scoped data and benchmark one platform.")
    static final class Run implements Callable<Integer> {
        @ParentCommand private BenchmarkMain parent;
        @Option(names = "--platform", required = true) private String platformId;
        @Option(names = "--confirm-reset", required = true,
                description = "Acknowledge replacement of benchmark-scoped data.")
        private boolean confirmReset;

        @Override
        public Integer call() throws Exception {
            if (!confirmReset) throw new IllegalArgumentException("--confirm-reset is required");
            BenchmarkConfig config = parent.config();
            return parent.runPlatform(config, WikiVoteDataset.load(config), platformId);
        }
    }

    @Command(name = "all", mixinStandardHelpOptions = true,
            description = "Run all five platforms, then regenerate the report.")
    static final class All implements Callable<Integer> {
        @ParentCommand private BenchmarkMain parent;
        @Option(names = "--confirm-reset", required = true,
                description = "Acknowledge replacement of benchmark-scoped data.")
        private boolean confirmReset;

        @Override
        public Integer call() throws Exception {
            if (!confirmReset) throw new IllegalArgumentException("--confirm-reset is required");
            BenchmarkConfig config = parent.config();
            WikiVoteDataset.Data data = WikiVoteDataset.load(config);
            int failed = 0;
            for (String platformId : config.platformIds()) {
                try {
                    failed |= parent.runPlatform(config, data, platformId);
                } catch (Exception failure) {
                    System.err.println(platformId + " failed: "
                            + SecretRedactor.redact(failure.getMessage()));
                    failed = 1;
                }
            }
            new ResultWriter().writeReport(config);
            return failed;
        }
    }

    @Command(name = "report", mixinStandardHelpOptions = true,
            description = "Generate Markdown and CSV tables from platform JSON files.")
    static final class Report implements Callable<Integer> {
        @ParentCommand private BenchmarkMain parent;

        @Override
        public Integer call() throws Exception {
            Path report = new ResultWriter().writeReport(parent.config());
            System.out.println("Report: " + report);
            return 0;
        }
    }
}
