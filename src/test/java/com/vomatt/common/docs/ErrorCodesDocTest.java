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
            Map.entry("COMMON_SERVICE_UNAVAILABLE", Set.of(503)),
            Map.entry("COMMON_INTERNAL_ERROR", Set.of(500)));

    @Test
    @DisplayName("應該在MessageKey或原始碼變更後確認error-codes.md為最新")
    void shouldMatchCommittedDocWhenMessageKeysUnchanged() throws IOException {
        SnapshotSupport.verify(DOC, render(),
                "./mvnw test -Dtest=ErrorCodesDocTest -Dsnapshot.update=true");
    }

    @Test
    @DisplayName("應該在每個MessageKey都有zh-TW與英文訊息時通過")
    void shouldHaveBothLocalesForEveryMessageKey() throws IOException {
        Properties zh = load("messages_zh_TW.properties", "messages.properties");
        Properties en = load("messages_en.properties");
        for (MessageKey key : MessageKey.values()) {
            assertTrue(zh.containsKey(key.code()), "missing zh-TW message: " + key.code());
            assertTrue(en.containsKey(key.code()), "missing en message: " + key.code());
        }
    }

    @Test
    @DisplayName("應該從原始碼文字掃出ApiException的HTTP status")
    void shouldScanStatusFromApiExceptionFactories() {
        Map<String, Set<Integer>> statuses = new TreeMap<>();
        scan("throw ApiException.notFound(MessageKey.VOTE_NOT_FOUND);\n"
                + "ApiException.withData(HttpStatus.UNPROCESSABLE_ENTITY, MessageKey.VOTE_NOT_ALLOWED, data)",
                statuses);

        assertEquals(Set.of(404), statuses.get("VOTE_NOT_FOUND"));
        assertEquals(Set.of(422), statuses.get("VOTE_NOT_ALLOWED"));
    }

    private String render() throws IOException {
        Properties zh = load("messages_zh_TW.properties", "messages.properties");
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
                # errorCode 對照表

                > 由 ErrorCodesDocTest 產生，勿手改。
                > 重新產生：`./mvnw test -Dtest=ErrorCodesDocTest -Dsnapshot.update=true`，並提交差異。

                前端依 `errorCode` 分支，不要比對 `message`。訊息語系由 `Accept-Language` 決定（見 [conventions.md](conventions.md)）。
                HTTP status 由原始碼文字掃描 `ApiException.xxx(MessageKey.X` 取得；同一個 errorCode 可能出現在多個 status。
                「建議 UI 行為」只依 HTTP status 通用推導，各 errorCode 的個別情境請看對應端點的 OpenAPI 說明。

                ## 錯誤碼

                | errorCode | HTTP status | zh-TW 訊息 | en 訊息 | 建議 UI 行為 | 使用位置 |
                |-----------|-------------|-----------|---------|--------------|----------|
                """ + errors + """

                ## 成功訊息與其他

                未在錯誤路徑上被掃到的 key（多為成功回應的 `message`，例如 `*.success`、`*.sent`）。前端不需要依這些值分支。

                | key | zh-TW 訊息 | en 訊息 | 使用位置 |
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
        return String.join("；", codes.stream().map(ErrorCodesDocTest::behavior).distinct().toList());
    }

    private static String behavior(int status) {
        return switch (status) {
            case 400, 422 -> "顯示 message，讓使用者修正輸入";
            case 401 -> "見 [auth-flow.md](auth-flow.md)";
            case 403 -> "顯示無權限提示";
            case 404 -> "顯示找不到資源";
            case 409 -> "顯示 message（資源狀態衝突）";
            case 429 -> "依 `Retry-After` 秒數後再重試";
            case 503 -> "顯示服務暫時無法使用，稍後重試";
            default -> "顯示通用錯誤提示";
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
