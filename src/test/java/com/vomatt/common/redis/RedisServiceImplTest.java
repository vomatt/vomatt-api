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

/** rotateRefreshToken runs a Lua script on pre-serialized values, so it is checked against a real Redis 7. */
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

    private static final RefreshTokenService.RotateResult PAIR = new RefreshTokenService.RotateResult("access", "refresh",
            new RefreshTokenService.RefreshTokenData("user-1", List.of("user"), "session-1", false));

    private static RedisService.RefreshRotation rotation(Object gracePair, Duration graceTtl) {
        return new RedisService.RefreshRotation("user-1", "session-1", "old", "new", PAIR.data(), gracePair,
                Duration.ofDays(30), graceTtl);
    }

    /** The old token as rotate finds it: stored and listed in the user index. */
    private void storeOldToken() {
        redisService.set("refresh", "old", PAIR.data(), Duration.ofMinutes(5));
        template.opsForHash().put("refresh:idx:user-1", "old", "1");
    }

    @Test
    void shouldConsumeTokenAndApplyWritesWhenTokenExists() {
        storeOldToken();

        RedisService.RotationOutcome outcome = redisService.rotateRefreshToken(rotation(PAIR, Duration.ofSeconds(60)));

        assertThat(outcome).isEqualTo(RedisService.RotationOutcome.ROTATED);
        assertThat(redisService.hasKey("refresh", "old")).isFalse();
        assertThat(redisService.get("refresh:consumed", "old", String.class)).isEqualTo("session-1");
        assertThat(redisService.get("refresh", "new", RefreshTokenService.RefreshTokenData.class)).isEqualTo(PAIR.data());
        assertThat(template.getExpire("refresh:new", TimeUnit.DAYS)).isBetween(29L, 30L);
        assertThat(redisService.get("refresh:grace", "old", RefreshTokenService.RotateResult.class)).isEqualTo(PAIR);
        assertThat(template.getExpire("refresh:grace:old", TimeUnit.SECONDS)).isBetween(55L, 60L);
        // Unquoted fields, so revokeAllForUser finds the token through the index
        assertThat(redisService.hGetAll("refresh:idx", "user-1")).containsOnlyKeys("new");
        assertThat(redisService.hGet("refresh:idx", "user-1", "new", String.class)).isEqualTo("1");
        assertThat(template.getExpire("refresh:idx:user-1", TimeUnit.DAYS)).isBetween(29L, 30L);
    }

    @Test
    void shouldDropTokenAndWriteNothingWhenSessionIsRevoked() {
        storeOldToken();
        redisService.set("refresh:revoked-session", "session-1", "1", Duration.ofMinutes(5));

        RedisService.RotationOutcome outcome = redisService.rotateRefreshToken(rotation(PAIR, Duration.ofSeconds(60)));

        assertThat(outcome).isEqualTo(RedisService.RotationOutcome.SESSION_REVOKED);
        assertThat(redisService.hasKey("refresh", "old")).isFalse();
        assertThat(redisService.hGetAll("refresh:idx", "user-1")).isEmpty();
        assertThat(redisService.hasKey("refresh", "new")).isFalse();
        assertThat(redisService.hasKey("refresh:consumed", "old")).isFalse();
        assertThat(redisService.hasKey("refresh:grace", "old")).isFalse();
    }

    @Test
    void shouldKeepTheTokenWhenAWriteFails() {
        storeOldToken();

        // Redis refuses a zero expiry; the script stops at the grace write, and the old token must survive for a retry
        assertThatThrownBy(() -> redisService.rotateRefreshToken(rotation(PAIR, Duration.ZERO)))
                .isInstanceOf(RedisOperationException.class);

        assertThat(redisService.hasKey("refresh", "old")).isTrue();
    }

    @Test
    void shouldWriteNothingWhenTokenIsAlreadyConsumed() {
        RedisService.RotationOutcome outcome = redisService.rotateRefreshToken(rotation(PAIR, Duration.ofSeconds(60)));

        assertThat(outcome).isEqualTo(RedisService.RotationOutcome.TOKEN_GONE);
        assertThat(redisService.hasKey("refresh:grace", "old")).isFalse();
        assertThat(redisService.hasKey("refresh", "new")).isFalse();
    }

    @Test
    void shouldWriteNothingWhenAnotherCallerConsumedTheTokenFirst() {
        storeOldToken();

        RedisService.RotationOutcome first = redisService.rotateRefreshToken(rotation("first", Duration.ofSeconds(60)));
        RedisService.RotationOutcome second = redisService.rotateRefreshToken(rotation("second", Duration.ofSeconds(60)));

        assertThat(first).isEqualTo(RedisService.RotationOutcome.ROTATED);
        assertThat(second).isEqualTo(RedisService.RotationOutcome.TOKEN_GONE);
        assertThat(redisService.get("refresh:grace", "old", String.class)).isEqualTo("first");
    }
}
