package com.example.ccbuild;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

public class Client {
    private final HttpClient httpClient;
    private final URI baseUri;
    private final String token;

    public Client(String baseUrl, String token) {
        this(HttpClient.newHttpClient(), baseUrl, token);
    }

    Client(HttpClient httpClient, String baseUrl, String token) {
        this.httpClient = httpClient;
        this.baseUri = normalizeBaseUri(Util.requireNonBlank(baseUrl, "Base URL is required"));
        this.token = Util.requireNonBlank(token, "API token is required");
    }

    public List<Models.Build> listBuilds(String projectId, String environmentId, int limit) throws IOException, InterruptedException {
        String resolvedProject = Util.requireNonBlank(projectId, "Project ID is required");
        String resolvedEnvironment = Util.requireNonBlank(environmentId, "Environment ID is required");

        int effectiveLimit = limit <= 0 ? 50 : limit;
        String path = String.format("subscriptions/%s/builds?environmentCode=%s&limit=%d",
                encode(resolvedProject), encode(resolvedEnvironment), effectiveLimit);
        HttpRequest request = requestBuilder(baseUri.resolve(path)).GET().build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("Failed to fetch builds (status " + response.statusCode() + "): " + response.body());
        }

        JsonNode root = Util.MAPPER.readTree(response.body());
        ArrayNode items = extractBuildArray(root);
        List<Models.Build> result = new ArrayList<>();
        for (JsonNode node : items) {
            result.add(parseBuild(node));
        }
        return result;
    }

    public Models.PruneOutcome deleteBuild(String projectId, String environmentId, Models.Build build) throws IOException, InterruptedException {
        String resolvedProject = Util.requireNonBlank(projectId, "Project ID is required");
        String resolvedEnvironment = Util.requireNonBlank(environmentId, "Environment ID is required");
        String buildId = Util.requireNonBlank(build.id(), "Build ID is required");

        String path = String.format("projects/%s/environments/%s/builds/%s",
                encode(resolvedProject), encode(resolvedEnvironment), encode(buildId));
        HttpRequest request = requestBuilder(baseUri.resolve(path)).DELETE().build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        boolean deleted = response.statusCode() / 100 == 2;
        String message = deleted ? "Deleted" : extractErrorMessage(response.body()).orElse("Delete failed");
        return new Models.PruneOutcome(buildId, deleted, response.statusCode(), message);
    }

    private HttpRequest.Builder requestBuilder(URI uri) {
        return HttpRequest.newBuilder(uri)
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json");
    }

    private static URI normalizeBaseUri(String baseUrl) {
        String trimmed = baseUrl.trim();
        if (!trimmed.endsWith("/")) {
            trimmed = trimmed + "/";
        }
        return URI.create(trimmed);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private ArrayNode extractBuildArray(JsonNode root) throws IOException {
        if (root.isArray()) {
            return (ArrayNode) root;
        }
        for (String key : List.of("builds", "items", "data", "results")) {
            JsonNode node = root.path(key);
            if (node.isArray()) {
                return (ArrayNode) node;
            }
        }
        throw new IOException("Response JSON does not contain a builds array");
    }

    private Models.Build parseBuild(JsonNode node) {
        String id = text(node, "id", "buildId", "code");
        String code = text(node, "code", "name", "buildCode");
        String branch = text(node, "branch", "branchName", "branchId");
        Instant createdAt = instant(node, "createdAt", "creationTime", "created", "created_on");
        Instant lastUsedAt = instant(node, "lastUsedAt", "lastUsage", "last_used_at", "lastUsed");
        String status = text(node, "status", "state");
        boolean deletable = booleanValue(node, "deletable", "deleteAllowed", "deleteEnabled", "canBeDeleted");
        String reason = text(node, "deleteReason", "reason", "message");
        URI self = uri(node, "self", "href", "url");
        return new Models.Build(id, code, branch, createdAt, lastUsedAt, status, deletable, reason, self, node);
    }

    private static String text(JsonNode node, String... keys) {
        for (String key : keys) {
            JsonNode value = node.path(key);
            if (value.isTextual() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return null;
    }

    private static Instant instant(JsonNode node, String... keys) {
        for (String key : keys) {
            JsonNode value = node.path(key);
            if (value.isTextual()) {
                Instant parsed = Util.parseInstant(value.asText());
                if (parsed != null) {
                    return parsed;
                }
            }
        }
        return null;
    }

    private static boolean booleanValue(JsonNode node, String... keys) {
        for (String key : keys) {
            JsonNode value = node.path(key);
            if (!value.isMissingNode()) {
                if (value.isBoolean()) {
                    return value.asBoolean();
                }
                if (value.isTextual()) {
                    String text = value.asText().trim().toLowerCase();
                    if (text.equals("true") || text.equals("yes")) {
                        return true;
                    }
                    if (text.equals("false") || text.equals("no")) {
                        return false;
                    }
                }
                if (value.isInt() || value.isLong()) {
                    return value.asInt() != 0;
                }
            }
        }
        return false;
    }

    private static URI uri(JsonNode node, String... keys) {
        for (String key : keys) {
            JsonNode value = node.path(key);
            if (value.isTextual() && !value.asText().isBlank()) {
                try {
                    return URI.create(value.asText());
                } catch (IllegalArgumentException ignored) {
                }
            }
        }

        JsonNode links = node.path("links");
        if (links.isObject()) {
            Iterator<String> fieldNames = links.fieldNames();
            while (fieldNames.hasNext()) {
                String field = fieldNames.next();
                JsonNode candidate = links.path(field).path("href");
                if (candidate.isTextual()) {
                    try {
                        return URI.create(candidate.asText());
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
        }
        return null;
    }

    private Optional<String> extractErrorMessage(String body) {
        if (body == null || body.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode root = Util.MAPPER.readTree(body);
            for (String key : List.of("message", "error", "detail")) {
                JsonNode value = root.path(key);
                if (value.isTextual() && !value.asText().isBlank()) {
                    return Optional.of(value.asText());
                }
            }
        } catch (IOException ignored) {
        }
        return Optional.of(body.strip());
    }
}
