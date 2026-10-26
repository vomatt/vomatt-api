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

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** consumeAndExec runs a Lua script on pre-serialized values, so it is checked against a real Redis 7. */
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

    @Test
    void shouldConsumeKeyAndApplyWritesWhenKeyExists() {
        RefreshTokenService.RotateResult pair = new RefreshTokenService.RotateResult("access", "refresh",
                new RefreshTokenService.RefreshTokenData("user-1", List.of("user"), "session-1", false));
        redisService.set("ns", "old", "v", Duration.ofMinutes(5));
        template.opsForHash().put("idx:user-1", "old", "1");

        boolean consumed = redisService.consumeAndExec("ns", "old", List.of(
                RedisService.RedisWrite.set("grace", "old", pair, Duration.ofSeconds(60)),
                RedisService.RedisWrite.hput("idx", "user-1", "new", "1"),
                RedisService.RedisWrite.expire("idx", "user-1", Duration.ofDays(30)),
                RedisService.RedisWrite.hdelete("idx", "user-1", "old")));

        assertThat(consumed).isTrue();
        assertThat(redisService.get("ns", "old", String.class)).isNull();
        assertThat(redisService.get("grace", "old", RefreshTokenService.RotateResult.class)).isEqualTo(pair);
        assertThat(template.getExpire("grace:old", TimeUnit.SECONDS)).isBetween(55L, 60L);
        assertThat(redisService.hGetAll("idx", "user-1")).containsOnlyKeys("new");
        assertThat(redisService.hGet("idx", "user-1", "new", String.class)).isEqualTo("1");
        assertThat(template.getExpire("idx:user-1", TimeUnit.DAYS)).isBetween(29L, 30L);
    }

    @Test
    void shouldKeepTheKeyWhenAWriteFails() {
        redisService.set("ns", "old", "v", Duration.ofMinutes(5));

        // Redis refuses a zero expiry; the script stops there, and its key must survive for a retry
        assertThatThrownBy(() -> redisService.consumeAndExec("ns", "old", List.of(
                RedisService.RedisWrite.set("grace", "old", "pair", Duration.ZERO))))
                .isInstanceOf(RedisOperationException.class);

        assertThat(redisService.get("ns", "old", String.class)).isEqualTo("v");
    }

    @Test
    void shouldWriteNothingWhenKeyIsAlreadyConsumed() {
        boolean consumed = redisService.consumeAndExec("ns", "gone", List.of(
                RedisService.RedisWrite.set("grace", "gone", "pair", Duration.ofSeconds(60))));

        assertThat(consumed).isFalse();
        assertThat(redisService.get("grace", "gone", String.class)).isNull();
    }

    @Test
    void shouldWriteNothingWhenAnotherCallerConsumedTheKeyFirst() {
        redisService.set("ns", "old", "v", Duration.ofMinutes(5));

        boolean first = redisService.consumeAndExec("ns", "old",
                List.of(RedisService.RedisWrite.set("grace", "old", "first", Duration.ofSeconds(60))));
        boolean second = redisService.consumeAndExec("ns", "old",
                List.of(RedisService.RedisWrite.set("grace", "old", "second", Duration.ofSeconds(60))));

        assertThat(first).isTrue();
        assertThat(second).isFalse();
        assertThat(redisService.get("grace", "old", String.class)).isEqualTo("first");
    }
}
