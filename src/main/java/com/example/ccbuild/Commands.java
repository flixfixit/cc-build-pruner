package com.example.ccbuild;

import picocli.CommandLine;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

public final class Commands {
    private Commands() {
    }

    static class ConnectionOptions {
        @CommandLine.Option(names = "--base-url", description = "Commerce Cloud build API base URL (env: CC_BASE_URL)",
                defaultValue = "${env:CC_BASE_URL}")
        String baseUrl;

        @CommandLine.Option(names = "--project-id", description = "Commerce Cloud project ID (env: CC_PROJECT_ID)",
                defaultValue = "${env:CC_PROJECT_ID}")
        String projectId;

        @CommandLine.Option(names = "--environment-id", description = "Commerce Cloud environment ID (env: CC_ENVIRONMENT_ID)",
                defaultValue = "${env:CC_ENVIRONMENT_ID}")
        String environmentId;

        @CommandLine.Option(names = "--token", description = "Commerce Cloud personal access token (env: CC_TOKEN)",
                defaultValue = "${env:CC_TOKEN}")
        String token;

        Client createClient() {
            return new Client(Util.requireNonBlank(baseUrl, "--base-url or CC_BASE_URL must be provided"),
                    Util.requireNonBlank(token, "--token or CC_TOKEN must be provided"));
        }
    }

    @CommandLine.Command(name = "list", description = "List builds for a project/environment")
    public static final class ListCmd implements Callable<Integer> {
        @CommandLine.ArgGroup(exclusive = false, multiplicity = "1")
        ConnectionOptions options = new ConnectionOptions();

        @CommandLine.Option(names = {"-l", "--limit"}, description = "Maximum number of builds to fetch"
                + " (default: ${DEFAULT-VALUE})", defaultValue = "50")
        int limit;

        @CommandLine.Option(names = "--include-non-deletable", description = "Include builds that cannot be deleted")
        boolean includeNonDeletable;

        @CommandLine.Option(names = "--json", description = "Render the response as JSON")
        boolean json;

        @Override
        public Integer call() {
            try {
                Client client = options.createClient();
                List<Models.Build> builds = client.listBuilds(
                        Util.requireNonBlank(options.projectId, "--project-id or CC_PROJECT_ID must be provided"),
                        Util.requireNonBlank(options.environmentId, "--environment-id or CC_ENVIRONMENT_ID must be provided"),
                        limit);

                if (!includeNonDeletable) {
                    builds = builds.stream().filter(Models.Build::deletable).collect(Collectors.toList());
                }

                if (json) {
                    String output = Util.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(builds);
                    System.out.println(output);
                } else {
                    Util.printBuildTable(builds);
                }
                return 0;
            } catch (IllegalArgumentException ex) {
                System.err.println("Configuration error: " + ex.getMessage());
                return 2;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                System.err.println("Listing builds interrupted: " + safeMessage(ex));
                return 1;
            } catch (IOException ex) {
                System.err.println("Failed to list builds: " + safeMessage(ex));
                return 1;
            }
        }
    }

    @CommandLine.Command(name = "prune", description = "Delete builds older than a certain age")
    public static final class PruneCmd implements Callable<Integer> {
        @CommandLine.ArgGroup(exclusive = false, multiplicity = "1")
        ConnectionOptions options = new ConnectionOptions();

        @CommandLine.Option(names = "--older-than", required = true,
                description = "Only delete builds created before now minus the given duration (e.g. 30d, P2DT3H)")
        String olderThan;

        @CommandLine.Option(names = "--limit", description = "Maximum number of builds to inspect"
                + " (default: ${DEFAULT-VALUE})", defaultValue = "200")
        int limit;

        @CommandLine.Option(names = "--max", description = "Maximum number of builds to delete"
                + " (default: unlimited)", defaultValue = "-1")
        int max;

        @CommandLine.Option(names = "--dry-run", description = "Only print builds that would be deleted")
        boolean dryRun;

        @Override
        public Integer call() {
            try {
                Duration retention = Util.parseDuration(olderThan);
                Instant cutoff = Instant.now().minus(retention);
                Client client = options.createClient();
                List<Models.Build> builds = client.listBuilds(
                        Util.requireNonBlank(options.projectId, "--project-id or CC_PROJECT_ID must be provided"),
                        Util.requireNonBlank(options.environmentId, "--environment-id or CC_ENVIRONMENT_ID must be provided"),
                        limit);

                List<Models.Build> candidates = builds.stream()
                        .filter(Models.Build::deletable)
                        .filter(build -> build.createdAt() != null && build.createdAt().isBefore(cutoff))
                        .sorted(Comparator.comparing(Models.Build::createdAt))
                        .collect(Collectors.toList());

                if (candidates.isEmpty()) {
                    System.out.println("No builds matched the prune criteria.");
                    return 0;
                }

                if (dryRun) {
                    System.out.printf("[dry-run] %d build%s would be deleted (older than %s).%n",
                            candidates.size(), candidates.size() == 1 ? "" : "s", retention);
                    Util.printBuildTable(candidates);
                    return 0;
                }

                int limitDeletes = max < 0 ? candidates.size() : Math.min(max, candidates.size());
                List<Models.PruneOutcome> outcomes = new ArrayList<>();
                for (int i = 0; i < limitDeletes; i++) {
                    Models.Build build = candidates.get(i);
                    try {
                        outcomes.add(client.deleteBuild(options.projectId, options.environmentId, build));
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        outcomes.add(new Models.PruneOutcome(build.id(), false, 0, "Interrupted"));
                        break;
                    } catch (IOException ex) {
                        outcomes.add(new Models.PruneOutcome(build.id(), false, 0, safeMessage(ex)));
                    }
                }

                long deletedCount = outcomes.stream().filter(Models.PruneOutcome::deleted).count();
                long failedCount = outcomes.size() - deletedCount;
                System.out.printf("Deleted %d build%s", deletedCount, deletedCount == 1 ? "" : "s");
                if (failedCount > 0) {
                    System.out.printf(" (%d failure%s)", failedCount, failedCount == 1 ? "" : "s");
                }
                System.out.println('.');

                outcomes.stream().filter(outcome -> !outcome.deleted()).forEach(outcome ->
                        System.out.printf("- %s: %s (status %d)%n", outcome.buildId(), outcome.message(), outcome.statusCode()));

                return failedCount == 0 ? 0 : 1;
            } catch (IllegalArgumentException ex) {
                System.err.println("Configuration error: " + ex.getMessage());
                return 2;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                System.err.println("Pruning interrupted: " + safeMessage(ex));
                return 1;
            } catch (IOException ex) {
                System.err.println("Failed to prune builds: " + safeMessage(ex));
                return 1;
            }
        }
    }

    private static String safeMessage(Exception ex) {
        String message = ex.getMessage();
        return message != null ? message : ex.toString();
    }
}
