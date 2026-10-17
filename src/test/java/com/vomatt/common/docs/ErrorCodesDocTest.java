package com.vomatt.common.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.vomatt.common.i18n.MessageKey;

/**
 * Generates {@code docs/frontend/error-codes.md} from {@link MessageKey}, the i18n bundles and a text scan of
 * {@code src/main/java}. Regenerate with {@code ./mvnw test -Dtest=ErrorCodesDocTest -Dsnapshot.update=true}.
 */
class ErrorCodesDocTest {

    static final Path DOC = Path.of("docs/frontend/error-codes.md");
    private static final Path SOURCES = Path.of("src/main/java");
    private static final Path I18N = Path.of("src/main/resources/i18n");

    private static final Map<String, Integer> FACTORY_STATUS = Map.of(
            "badRequest", 400, "unauthorized", 401, "notFound", 404, "forbidden", 403,
            "conflict", 409, "unprocessable", 422, "tooManyRequests", 429);
    private static final Map<String, Integer> HTTP_STATUS = Map.of(
            "BAD_REQUEST", 400, "UNAUTHORIZED", 401, "FORBIDDEN", 403, "NOT_FOUND", 404, "CONFLICT", 409,
            "UNPROCESSABLE_ENTITY", 422, "TOO_MANY_REQUESTS", 429, "INTERNAL_SERVER_ERROR", 500,
            "SERVICE_UNAVAILABLE", 503);

    private static final Pattern FACTORY = Pattern.compile(
            "ApiException\\.(badRequest|unauthorized|notFound|forbidden|conflict|unprocessable|tooManyRequests)"
                    + "\\(\\s*MessageKey\\.(\\w+)");
    private static final Pattern WITH_DATA = Pattern.compile(
            "ApiException\\.withData\\(\\s*HttpStatus\\.(\\w+)\\s*,\\s*MessageKey\\.(\\w+)");
    private static final Pattern CONSTRUCTOR = Pattern.compile(
            "new ApiException\\(\\s*HttpStatus\\.(\\w+)\\s*,\\s*MessageKey\\.(\\w+)");
    private static final Pattern ANY_USE = Pattern.compile("MessageKey\\.(\\w+)");

    /** Errors written outside ApiException: GlobalExceptionHandler and the security / rate-limit filters. */
    private static final Map<String, Set<Integer>> FRAMEWORK_STATUS = Map.ofEntries(
            Map.entry("COMMON_VALIDATION_FAILED", Set.of(400)),
            Map.entry("COMMON_MISSING_PARAM", Set.of(400)),
            Map.entry("COMMON_TYPE_MISMATCH", Set.of(400)),
            Map.entry("COMMON_BAD_REQUEST", Set.of(400)),
            Map.entry("COMMON_UNAUTHORIZED", Set.of(401)),
            Map.entry("AUTH_TOKEN_EXPIRED", Set.of(401)),
            Map.entry("AUTH_TOKEN_INVALID", Set.of(401)),
            Map.entry("COMMON_FORBIDDEN", Set.of(403)),
            Map.entry("COMMON_RATE_LIMITED", Set.of(429)),
            Map.entry("COMMON_CONFLICT", Set.of(409)),
            Map.entry("COMMON_SERVICE_UNAVAILABLE", Set.of(503)),
            Map.entry("COMMON_INTERNAL_ERROR", Set.of(500)));

    @Test
    @DisplayName("should verify error-codes.md is up to date when MessageKey or source changes")
    void shouldMatchCommittedDocWhenMessageKeysUnchanged() throws IOException {
        SnapshotSupport.verify(DOC, render(),
                "./mvnw test -Dtest=ErrorCodesDocTest -Dsnapshot.update=true");
    }

    @Test
    @DisplayName("should pass when every MessageKey has zh-TW and English messages")
    void shouldHaveBothLocalesForEveryMessageKey() throws IOException {
        Properties zh = load("messages.properties");
        Properties en = load("messages_en.properties");
        for (MessageKey key : MessageKey.values()) {
            assertTrue(zh.containsKey(key.code()), "missing zh-TW message: " + key.code());
            assertTrue(en.containsKey(key.code()), "missing en message: " + key.code());
        }
    }

    @Test
    @DisplayName("should extract ApiException HTTP status from source text")
    void shouldScanStatusFromApiExceptionFactories() {
        Map<String, Set<Integer>> statuses = new TreeMap<>();
        scan("throw ApiException.notFound(MessageKey.VOTE_NOT_FOUND);\n"
                + "ApiException.withData(HttpStatus.UNPROCESSABLE_ENTITY, MessageKey.VOTE_NOT_ALLOWED, data)",
                statuses);

        assertEquals(Set.of(404), statuses.get("VOTE_NOT_FOUND"));
        assertEquals(Set.of(422), statuses.get("VOTE_NOT_ALLOWED"));
    }

    private String render() throws IOException {
        Properties zh = load("messages.properties");
        Properties en = load("messages_en.properties");

        Map<String, Set<Integer>> statuses = new TreeMap<>();
        Map<String, Set<String>> usedIn = new TreeMap<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java")).sorted()::iterator) {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                // ApiException's own javadoc contains a sample call that is not a real usage
                if (!file.getFileName().toString().equals("ApiException.java")) {
                    scan(text, statuses);
                }
                if (file.getFileName().toString().equals("MessageKey.java")) {
                    continue;
                }
                Matcher use = ANY_USE.matcher(text);
                while (use.find()) {
                    usedIn.computeIfAbsent(use.group(1), k -> new TreeSet<>())
                            .add(file.getFileName().toString().replace(".java", ""));
                }
            }
        }
        FRAMEWORK_STATUS.forEach((name, codes) -> statuses.computeIfAbsent(name, k -> new TreeSet<>()).addAll(codes));

        StringBuilder errors = new StringBuilder();
        StringBuilder others = new StringBuilder();
        for (MessageKey key : MessageKey.values()) {
            String used = String.join(", ", usedIn.getOrDefault(key.name(), Set.of()));
            String zhMsg = cell(zh.getProperty(key.code()));
            String enMsg = cell(en.getProperty(key.code()));
            Set<Integer> codes = statuses.get(key.name());
            if (codes == null) {
                others.append("| `").append(key.code()).append("` | ").append(zhMsg).append(" | ")
                        .append(enMsg).append(" | ").append(cell(used)).append(" |\n");
            } else {
                errors.append("| `").append(key.code()).append("` | ")
                        .append(String.join(" / ", codes.stream().map(String::valueOf).toList())).append(" | ")
                        .append(zhMsg).append(" | ").append(enMsg).append(" | ").append(behavior(codes))
                        .append(" | ").append(cell(used)).append(" |\n");
            }
        }

        return """
                # errorCode reference

                > Generated by ErrorCodesDocTest. Do not edit by hand.
                > Regenerate: `./mvnw test -Dtest=ErrorCodesDocTest -Dsnapshot.update=true`, then commit the diff.

                The frontend branches on `errorCode`; do not match on `message`. The message locale is determined by `Accept-Language` (see [conventions.md](conventions.md)).
                HTTP status is obtained by scanning source text for `ApiException.xxx(MessageKey.X`; the same errorCode may appear under multiple statuses.
                "Suggested UI behavior" is derived generically from HTTP status only; for errorCode-specific context, see the OpenAPI description of the relevant endpoint.

                ## Error codes

                | errorCode | HTTP status | zh-TW message | en message | Suggested UI behavior | Used in |
                |-----------|-------------|-----------|---------|--------------|----------|
                """ + errors + """

                ## Success messages and others

                Keys not found on any error path (mostly success-response `message` values, e.g. `*.success`, `*.sent`). The frontend does not need to branch on these.

                | key | zh-TW message | en message | Used in |
                |-----|-----------|---------|----------|
                """ + others;
    }

    private static void scan(String text, Map<String, Set<Integer>> statuses) {
        Matcher factory = FACTORY.matcher(text);
        while (factory.find()) {
            add(statuses, factory.group(2), FACTORY_STATUS.get(factory.group(1)));
        }
        for (Pattern pattern : List.of(WITH_DATA, CONSTRUCTOR)) {
            Matcher m = pattern.matcher(text);
            while (m.find()) {
                add(statuses, m.group(2), HTTP_STATUS.get(m.group(1)));
            }
        }
    }

    private static void add(Map<String, Set<Integer>> statuses, String key, Integer status) {
        assertTrue(status != null, "unmapped HTTP status for " + key);
        statuses.computeIfAbsent(key, k -> new TreeSet<>()).add(status);
    }

    private static String behavior(Set<Integer> codes) {
        return String.join("; ", codes.stream().map(ErrorCodesDocTest::behavior).distinct().toList());
    }

    private static String behavior(int status) {
        return switch (status) {
            case 400, 422 -> "Show message so the user can fix the input";
            case 401 -> "See [auth-flow.md](auth-flow.md)";
            case 403 -> "Show a permission-denied notice";
            case 404 -> "Show resource not found";
            case 409 -> "Show message (resource state conflict)";
            case 429 -> "Retry after the `Retry-After` seconds";
            case 503 -> "Show service temporarily unavailable, retry later";
            default -> "Show a generic error notice";
        };
    }

    /** Loads the first bundle, filling gaps from the following ones. */
    private static Properties load(String... names) throws IOException {
        Properties merged = new Properties();
        for (int i = names.length - 1; i >= 0; i--) {
            try (Reader reader = Files.newBufferedReader(I18N.resolve(names[i]), StandardCharsets.UTF_8)) {
                merged.load(reader);
            }
        }
        return merged;
    }

    private static String cell(String text) {
        return text == null ? "" : text.replace("|", "\\|").replace("\n", " ");
    }
}
