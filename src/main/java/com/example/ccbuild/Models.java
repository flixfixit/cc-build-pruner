package com.example.ccbuild;

import com.fasterxml.jackson.databind.JsonNode;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;

public final class Models {
    private Models() {
    }

    public record Build(
            String id,
            String code,
            String branch,
            Instant createdAt,
            Instant lastUsedAt,
            String status,
            boolean deletable,
            String deleteReason,
            URI self,
            JsonNode raw
    ) {
        public String displayName() {
            if (code != null && !code.isBlank()) {
                return code;
            }
            return id != null ? id : "<unknown>";
        }

        public boolean isOlderThan(Instant cutoff) {
            return createdAt != null && createdAt.isBefore(cutoff);
        }

        public boolean isInactiveSince(Instant cutoff) {
            return lastUsedAt != null && lastUsedAt.isBefore(cutoff);
        }

        public String effectiveDeleteReason() {
            if (deleteReason != null && !deleteReason.isBlank()) {
                return deleteReason;
            }
            if (!deletable) {
                return "Build is not deletable";
            }
            return "";
        }
    }

    public record PruneOutcome(String buildId, boolean deleted, int statusCode, String message) {
        public PruneOutcome {
            Objects.requireNonNull(buildId, "buildId");
            Objects.requireNonNull(message, "message");
        }
    }
}
