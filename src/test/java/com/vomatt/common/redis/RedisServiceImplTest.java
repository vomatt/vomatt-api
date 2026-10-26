package com.vomatt.common.redis;

import com.vomatt.common.config.RedisConfig;
import com.vomatt.common.security.RefreshTokenService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.testcontainers.containers.GenericContainer;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** execScript passes pre-serialized values to Lua, so it is checked against a real Redis 7. */
class RedisServiceImplTest {

    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7").withExposedPorts(6379);
    static LettuceConnectionFactory connectionFactory;
    static RedisTemplate<String, Object> template;

    RedisServiceImpl redisService;

    @BeforeAll
    static void startRedis() {
        REDIS.start();
        connectionFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        // The production template, so values round-trip through the same serializers
        template = new RedisConfig(new StandardEnvironment(), "").redisTemplate(connectionFactory);
    }

    @AfterAll
    static void stopRedis() {
        connectionFactory.destroy();
        REDIS.stop();
    }

    @BeforeEach
    void setUp() {
        template.execute((org.springframework.data.redis.core.RedisCallback<Object>) c -> {
            c.serverCommands().flushAll();
            return null;
        });
        redisService = new RedisServiceImpl(template, JsonMapper.builder().build());
    }

    private static final org.springframework.data.redis.core.script.RedisScript<Long> SET_AND_HSET =
            org.springframework.data.redis.core.script.RedisScript.of(
                    "redis.call('SET', KEYS[1], ARGV[1]) redis.call('HSET', KEYS[2], ARGV[2], ARGV[1]) return 7", Long.class);

    @Test
    void shouldSerializeValuesAndPassBytesAsIsWhenRunningScript() {
        RefreshTokenService.RefreshTokenData data =
                new RefreshTokenService.RefreshTokenData("user-1", List.of("user"), "session-1", false);

        long result = redisService.execScript(SET_AND_HSET, List.of("ns:k", "idx:u"),
                List.of(data, "field".getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        assertThat(result).isEqualTo(7);
        assertThat(redisService.get("ns", "k", RefreshTokenService.RefreshTokenData.class)).isEqualTo(data);
        // A byte[] field is stored unquoted, as hGetAll reads fields
        assertThat(redisService.hGetAll("idx", "u")).containsOnlyKeys("field");
    }

    @Test
    void shouldThrowWhenScriptReturnsAnError() {
        var failing = org.springframework.data.redis.core.script.RedisScript.of("return redis.error_reply('boom')", Long.class);

        assertThatThrownBy(() -> redisService.execScript(failing, List.of("k"), List.of()))
                .isInstanceOf(RedisOperationException.class);
    }

    private void setRaw(String key, String bytes) {
        template.execute((org.springframework.data.redis.core.RedisCallback<Object>) c -> c.stringCommands()
                .set(key.getBytes(java.nio.charset.StandardCharsets.UTF_8), bytes.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test
    void shouldReadMissAndDropKeyWhenValueIsNotJson() {
        setRaw("ns:bad", "not json");

        assertThat(redisService.getOrThrow("ns", "bad", RefreshTokenService.RefreshTokenData.class)).isNull();
        assertThat(template.hasKey("ns:bad")).isFalse();
    }

    @Test
    void shouldReadMissAndDropKeyWhenValueHasAnotherShape() {
        redisService.set("ns", "other", "a plain string", java.time.Duration.ofMinutes(5));

        assertThat(redisService.getOrThrow("ns", "other", RefreshTokenService.RefreshTokenData.class)).isNull();
        assertThat(template.hasKey("ns:other")).isFalse();
    }

    @Test
    void shouldThrowWhenGettingKeyOfWrongType() {
        template.opsForHash().put("ns:hash", "f", "v");

        // WRONGTYPE is a Redis error, not an undecodable value: the key stays
        assertThatThrownBy(() -> redisService.getOrThrow("ns", "hash", String.class))
                .isInstanceOf(RedisOperationException.class);
        assertThat(template.hasKey("ns:hash")).isTrue();
    }

    @Test
    void shouldThrowWhenReadingHashOfWrongType() {
        redisService.set("idx", "u", "not-a-hash", java.time.Duration.ofMinutes(5));

        assertThatThrownBy(() -> redisService.hGetAllOrThrow("idx", "u")).isInstanceOf(RedisOperationException.class);
    }

    @Test
    void shouldDeleteEveryGivenFieldWhenDeletingHashFields() {
        template.opsForHash().putAll("idx:u", java.util.Map.of("a", "1", "b", "2", "c", "3"));

        assertThat(redisService.hDelete("idx", "u", "a", "b")).isTrue();

        assertThat(redisService.hGetAll("idx", "u")).containsOnlyKeys("c");
    }
}
