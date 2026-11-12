package com.vomatt.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.testcontainers.containers.GenericContainer;

import com.vomatt.common.config.RedisConfig;
import com.vomatt.common.redis.CacheKeyUtil;
import com.vomatt.common.redis.RedisService;

import tools.jackson.databind.json.JsonMapper;

/** An OTP stored before a deploy must still verify after it, through the production Redis serializer. */
class OtpRecordRedisTest {

    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7").withExposedPorts(6379);
    static LettuceConnectionFactory connectionFactory;
    static RedisTemplate<String, Object> template;

    @BeforeAll
    static void startRedis() {
        REDIS.start();
        connectionFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        template = new RedisConfig(new StandardEnvironment(), "").redisTemplate(connectionFactory);
    }

    @AfterAll
    static void stopRedis() {
        connectionFactory.destroy();
        REDIS.stop();
    }

    @Test
    void shouldReadOtpRecordWhenStoredWithRemovedFailCountField() {
        OtpRecord record = new OtpRecord("hash-1", "login", Instant.parse("2026-11-12T01:02:03Z"));
        @SuppressWarnings("unchecked")
        byte[] current = ((org.springframework.data.redis.serializer.RedisSerializer<Object>) template.getValueSerializer())
                .serialize(record);
        // The shape written before failCount was removed
        String old = new String(current, StandardCharsets.UTF_8).replaceFirst("\\}$", ",\"failCount\":0}");
        assertThat(old).contains("\"failCount\":0");
        String key = CacheKeyUtil.buildKey("otp:email", "k1");
        template.execute(c -> c.stringCommands().set(key.getBytes(StandardCharsets.UTF_8), old.getBytes(StandardCharsets.UTF_8)), true);

        assertThat(new RedisService(template, JsonMapper.builder().build()).get("otp:email", "k1", OtpRecord.class))
                .isEqualTo(record);
    }
}
