package com.example.ccbuild;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Util {
    private Util() {
    }

    public static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static final DateTimeFormatter HUMAN_INSTANT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")
            .withLocale(Locale.ROOT)
            .withZone(ZoneId.systemDefault());

    private static final Pattern SIMPLE_DURATION = Pattern.compile("(?i)^(\\d+)([smhdw])$");

    public static String requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    public static Duration parseDuration(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Duration value must not be blank");
        }

        String trimmed = value.trim();
        Matcher matcher = SIMPLE_DURATION.matcher(trimmed);
        if (matcher.matches()) {
            long amount = Long.parseLong(matcher.group(1));
            char unit = Character.toLowerCase(matcher.group(2).charAt(0));
            return switch (unit) {
                case 's' -> Duration.ofSeconds(amount);
                case 'm' -> Duration.ofMinutes(amount);
                case 'h' -> Duration.ofHours(amount);
                case 'd' -> Duration.ofDays(amount);
                case 'w' -> Duration.ofDays(amount * 7);
                default -> throw new IllegalArgumentException("Unsupported duration unit: " + unit);
            };
        }

        try {
            return Duration.parse(trimmed);
        } catch (DateTimeParseException ignored) {
            // continue trying other formats
        }

        throw new IllegalArgumentException("Unsupported duration format: " + value);
    }

    public static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        String trimmed = value.trim();
        try {
            return Instant.parse(trimmed);
        } catch (DateTimeParseException ignored) {
        }

        try {
            return OffsetDateTime.parse(trimmed).toInstant();
        } catch (DateTimeParseException ignored) {
        }

        try {
            return LocalDateTime.parse(trimmed).atZone(ZoneId.systemDefault()).toInstant();
        } catch (DateTimeParseException ignored) {
        }

        return null;
    }

    public static String formatInstant(Instant instant) {
        if (instant == null) {
            return "n/a";
        }
        return HUMAN_INSTANT.format(instant);
    }

    public static String formatRelative(Instant instant) {
        if (instant == null) {
            return "unknown";
        }
        Duration duration = Duration.between(instant, Instant.now());
        if (duration.isNegative() || duration.isZero()) {
            return "just now";
        }

        List<Duration> checkpoints = new ArrayList<>();
        checkpoints.add(Duration.ofDays(365));
        checkpoints.add(Duration.ofDays(30));
        checkpoints.add(Duration.ofDays(7));
        checkpoints.add(Duration.ofDays(1));
        checkpoints.add(Duration.ofHours(1));
        checkpoints.add(Duration.ofMinutes(1));
        checkpoints.add(Duration.ofSeconds(1));

        for (Duration checkpoint : checkpoints) {
            long units = duration.dividedBy(checkpoint);
            if (units > 0) {
                return units + " " + pluralize(unitName(checkpoint), units) + " ago";
            }
        }
        return "just now";
    }

    private static String unitName(Duration duration) {
        if (duration.compareTo(Duration.ofDays(365)) >= 0) {
            return "year";
        }
        if (duration.compareTo(Duration.ofDays(30)) >= 0) {
            return "month";
        }
        if (duration.compareTo(Duration.ofDays(7)) >= 0) {
            return "week";
        }
        if (duration.compareTo(Duration.ofDays(1)) >= 0) {
            return "day";
        }
        if (duration.compareTo(Duration.ofHours(1)) >= 0) {
            return "hour";
        }
        if (duration.compareTo(Duration.ofMinutes(1)) >= 0) {
            return "minute";
        }
        return "second";
    }

    public static String pluralize(String word, long count) {
        return count == 1 ? word : word + "s";
    }

    public static void printBuildTable(List<Models.Build> builds) {
        if (builds.isEmpty()) {
            System.out.println("No builds found.");
            return;
        }

        int idWidth = Math.max("ID".length(), builds.stream().map(Models.Build::id).filter(Objects::nonNull).mapToInt(String::length).max().orElse(2));
        int codeWidth = Math.max("Code".length(), builds.stream().map(Models.Build::code).filter(Objects::nonNull).mapToInt(String::length).max().orElse(4));
        int branchWidth = Math.max("Branch".length(), builds.stream().map(Models.Build::branch).filter(Objects::nonNull).mapToInt(String::length).max().orElse(6));
        int statusWidth = Math.max("Status".length(), builds.stream().map(Models.Build::status).filter(Objects::nonNull).mapToInt(String::length).max().orElse(6));

        String header = String.format("%-" + idWidth + "s  %-" + codeWidth + "s  %-" + branchWidth + "s  %-19s  %-12s  %-" + statusWidth + "s  %-9s",
                "ID", "Code", "Branch", "Created", "Age", "Status", "Deletable");
        System.out.println(header);
        System.out.println("-".repeat(header.length()));

        builds.stream()
                .sorted(Comparator.comparing(Models.Build::createdAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .forEach(build -> {
                    String id = valueOrDash(build.id());
                    String code = valueOrDash(build.code());
                    String branch = valueOrDash(build.branch());
                    String created = formatInstant(build.createdAt());
                    String age = formatRelative(build.createdAt());
                    String status = valueOrDash(build.status());
                    String deletable = build.deletable() ? "yes" : "no";
                    System.out.printf("%-" + idWidth + "s  %-" + codeWidth + "s  %-" + branchWidth + "s  %-19s  %-12s  %-" + statusWidth + "s  %-9s%n",
                            id, code, branch, created, age, status, deletable);
                });
    }

    private static String valueOrDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
