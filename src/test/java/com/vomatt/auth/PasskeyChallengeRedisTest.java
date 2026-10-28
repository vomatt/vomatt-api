package com.vomatt.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.testcontainers.containers.GenericContainer;

import com.vomatt.common.config.RedisConfig;
import com.vomatt.common.redis.RedisServiceImpl;

import tools.jackson.databind.json.JsonMapper;

/** The challenge record must survive the production Redis serializers, or every registration would read a miss. */
@DisplayName("PasskeyChallenge (Redis)")
class PasskeyChallengeRedisTest {

    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7").withExposedPorts(6379);
    static LettuceConnectionFactory connectionFactory;
    static RedisServiceImpl redisService;

    @BeforeAll
    static void startRedis() {
        REDIS.start();
        connectionFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        redisService = new RedisServiceImpl(new RedisConfig(new StandardEnvironment(), "").redisTemplate(connectionFactory),
                JsonMapper.builder().build());
    }

    @AfterAll
    static void stopRedis() {
        connectionFactory.destroy();
        REDIS.stop();
    }

    @Test
    @DisplayName("should read the challenge back once with its purpose and user, then never again")
    void shouldRoundTripChallengeWhenStoredInRedis() {
        var challenge = new PasskeyService.PasskeyChallenge(PasskeyService.PasskeyChallenge.Purpose.REGISTRATION, "user-1");
        redisService.setOrThrow(PasskeyService.CHALLENGE_NS, "abc", challenge, PasskeyService.CHALLENGE_TTL);

        assertThat(redisService.getAndDelete(PasskeyService.CHALLENGE_NS, "abc", PasskeyService.PasskeyChallenge.class))
                .isEqualTo(challenge);
        assertThat(redisService.getAndDelete(PasskeyService.CHALLENGE_NS, "abc", PasskeyService.PasskeyChallenge.class)).isNull();
    }
}
