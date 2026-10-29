package com.vomatt.common.security;

import com.vomatt.common.config.RedisConfig;
import com.vomatt.common.redis.RedisOperationException;
import com.vomatt.common.redis.RedisService;
import com.vomatt.common.redis.RedisServiceImpl;
import com.vomatt.entity.User;
import com.vomatt.repository.UserRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.vomatt.common.security.RefreshTokenService.keyOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The rotation script against a real Redis 7: what it writes, and that it writes nothing when it cannot finish. */
class RefreshTokenServiceRedisTest {

    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7").withExposedPorts(6379);
    static LettuceConnectionFactory connectionFactory;
    static RedisTemplate<String, Object> template;

    RedisServiceImpl redisService;
    RefreshTokenService service;
    String userId = UUID.randomUUID().toString();

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
        template.execute((RedisCallback<Object>) c -> {
            c.serverCommands().flushAll();
            return null;
        });
        redisService = new RedisServiceImpl(template, JsonMapper.builder().build());
        JwtUtil jwtUtil = mock(JwtUtil.class);
        when(jwtUtil.generateToken(anyString(), any(), anyBoolean(), any())).thenReturn("access");
        UserRepository userRepository = mock(UserRepository.class);
        when(userRepository.findById(UUID.fromString(userId))).thenReturn(Optional.of(User.builder()
                .id(UUID.fromString(userId)).username("u").email("u@example.com").roles(new String[] { "user" })
                .active(true).build()));
        service = new RefreshTokenService(redisService, jwtUtil, userRepository);
        ReflectionTestUtils.setField(service, "refreshExpirationDays", 30L);
        ReflectionTestUtils.setField(service, "refreshGraceSeconds", 60L);
    }

    private String sessionOf(String token) {
        return redisService.get("refresh", keyOf(token), RefreshTokenService.RefreshTokenData.class).sessionId();
    }

    @Test
    void shouldConsumeTokenAndWriteRotationWhenTokenIsLive() {
        String t0 = service.issue(userId, List.of("user"), false).refreshToken();
        String session = sessionOf(t0);

        RefreshTokenService.RotateResult r = service.rotate(t0);

        String newKey = keyOf(r.newRefreshToken());
        assertThat(redisService.hasKeyOrThrow("refresh", keyOf(t0))).isFalse();
        assertThat(redisService.get("refresh", newKey, RefreshTokenService.RefreshTokenData.class)).isEqualTo(r.data());
        assertThat(template.getExpire("refresh:" + newKey, TimeUnit.DAYS)).isBetween(29L, 30L);
        assertThat(redisService.get("refresh:consumed", keyOf(t0), String.class)).isEqualTo(session);
        assertThat(redisService.get("refresh:grace", keyOf(t0), RefreshTokenService.RotateResult.class)).isEqualTo(r);
        assertThat(template.getExpire("refresh:grace:" + keyOf(t0), TimeUnit.SECONDS)).isBetween(55L, 60L);
        // Unquoted fields, so revokeAllForUser finds the token; values name the session
        assertThat(redisService.hGetAll("refresh:idx", userId)).containsOnly(org.assertj.core.api.Assertions.entry(newKey, session));
        assertThat(template.getExpire("refresh:idx:" + userId, TimeUnit.DAYS)).isBetween(29L, 30L);
    }

    @Test
    void shouldReturnSamePairWhenTokenIsSentAgainWithinGrace() {
        String t0 = service.issue(userId, List.of("user"), false).refreshToken();

        RefreshTokenService.RotateResult first = service.rotate(t0);

        assertThat(service.rotate(t0)).isEqualTo(first);
    }

    @Test
    void shouldRejectGraceReplayWhenSessionIsLoggedOutAfterRotatingAgain() {
        String t0 = service.issue(userId, List.of("user"), false).refreshToken();
        String t1 = service.rotate(t0).newRefreshToken();
        String t2 = service.rotate(t1).newRefreshToken();
        assertThat(service.rotate(t0)).isNotNull();

        service.revoke(t2);

        assertThat(service.rotate(t0)).isNull();
        assertThat(service.rotate(t1)).isNull();
    }

    @Test
    void shouldRejectGraceReplayWhenAllSessionsAreRevokedAfterRotatingAgain() {
        String t0 = service.issue(userId, List.of("user"), false).refreshToken();
        String t1 = service.rotate(t0).newRefreshToken();
        service.rotate(t1);

        service.revokeAllForUser(userId);

        assertThat(service.rotate(t0)).isNull();
    }

    @Test
    void shouldDropTokenWhenSessionIsRevoked() {
        String t0 = service.issue(userId, List.of("user"), false).refreshToken();
        redisService.set("refresh:revoked-session", sessionOf(t0), "1", Duration.ofMinutes(5));

        assertThat(service.rotate(t0)).isNull();

        assertThat(redisService.hasKeyOrThrow("refresh", keyOf(t0))).isFalse();
        assertThat(redisService.hGetAll("refresh:idx", userId)).isEmpty();
        assertThat(redisService.hasKeyOrThrow("refresh:consumed", keyOf(t0))).isFalse();
        assertThat(redisService.hasKeyOrThrow("refresh:grace", keyOf(t0))).isFalse();
    }

    @Test
    void shouldDropTokenWhenSessionIsRevokedAndUserIndexHasWrongType() {
        String t0 = service.issue(userId, List.of("user"), false).refreshToken();
        redisService.set("refresh:revoked-session", sessionOf(t0), "1", Duration.ofMinutes(5));
        template.delete("refresh:idx:" + userId);
        redisService.set("refresh:idx", userId, "not-a-hash", Duration.ofMinutes(5));

        assertThat(service.rotate(t0)).isNull();
        assertThat(redisService.hasKeyOrThrow("refresh", keyOf(t0))).isFalse();
    }

    @Test
    void shouldWriteNothingWhenGraceTtlIsInvalid() {
        String t0 = service.issue(userId, List.of("user"), false).refreshToken();
        ReflectionTestUtils.setField(service, "refreshGraceSeconds", 0L);

        assertThatThrownBy(() -> service.rotate(t0)).isInstanceOf(RedisOperationException.class);

        // Only what generate wrote: the old token and its index entry, ready for a retry
        assertThat(template.keys("*")).containsExactlyInAnyOrder("refresh:" + keyOf(t0), "refresh:idx:" + userId);
        assertThat(redisService.hGetAll("refresh:idx", userId)).containsOnlyKeys(keyOf(t0));
    }

    @Test
    void shouldWriteNothingWhenUserIndexHasWrongType() {
        String t0 = service.issue(userId, List.of("user"), false).refreshToken();
        template.delete("refresh:idx:" + userId);
        redisService.set("refresh:idx", userId, "not-a-hash", Duration.ofMinutes(5));

        assertThatThrownBy(() -> service.rotate(t0)).isInstanceOf(RedisOperationException.class);

        assertThat(template.keys("*")).containsExactlyInAnyOrder("refresh:" + keyOf(t0), "refresh:idx:" + userId);
    }

    @Test
    void shouldStopSuccessorWhenLoggingOutWithAlreadyRotatedToken() {
        String t0 = service.issue(userId, List.of("user"), false).refreshToken();
        String t1 = service.rotate(t0).newRefreshToken();

        service.revoke(t0);

        assertThat(service.rotate(t0)).isNull();
        assertThat(service.rotate(t1)).isNull();
        assertThat(redisService.hasKeyOrThrow("refresh", keyOf(t1))).isFalse();
    }

    @Test
    void shouldStopSuccessorWrittenAfterSnapshotWhenRevokingAll() {
        String t0 = service.issue(userId, List.of("user"), false).refreshToken();
        String t1 = service.rotate(t0).newRefreshToken();
        // As seen by a revokeAll that read the index just before that rotation: T0 listed, T1 not yet
        template.opsForHash().delete("refresh:idx:" + userId, keyOf(t1));
        template.opsForHash().put("refresh:idx:" + userId, keyOf(t0), sessionOf(t1));

        service.revokeAllForUser(userId);

        assertThat(service.rotate(t1)).isNull();
        assertThat(service.rotate(t0)).isNull();
    }

    @Test
    void shouldRevokeSessionOfLegacyIndexEntryWhenRevokingAll() {
        String t0 = service.issue(userId, List.of("user"), false).refreshToken();
        String session = sessionOf(t0);
        // Written before index values named the session
        redisService.execInTransaction(List.of(RedisService.RedisWrite.hput("refresh:idx", userId, keyOf(t0), "1")));

        service.revokeAllForUser(userId);

        assertThat(redisService.hasKeyOrThrow("refresh:revoked-session", session)).isTrue();
        assertThat(redisService.hasKeyOrThrow("refresh", keyOf(t0))).isFalse();
        assertThat(redisService.hGetAll("refresh:idx", userId)).isEmpty();
    }

    @Test
    void shouldRevokeSessionNamedAfterKeyWhenLegacyTokenIsRevokedOrRotated() {
        String t0 = service.issue(userId, List.of("user"), false).refreshToken();
        // Issued before session ids existed
        redisService.set("refresh", keyOf(t0), new RefreshTokenService.RefreshTokenData(userId, List.of("user"), null, false, null, null, null),
                Duration.ofDays(30));

        // Its next rotation names the session after the key...
        assertThat(service.rotate(t0).data().sessionId()).isEqualTo(keyOf(t0));
        // ...the session revokeAllForUser flags for a legacy token it finds live, so a rotation racing it is refused
        String t2 = service.issue(userId, List.of("user"), false).refreshToken();
        redisService.set("refresh", keyOf(t2), new RefreshTokenService.RefreshTokenData(userId, List.of("user"), null, false, null, null, null),
                Duration.ofDays(30));
        template.opsForHash().put("refresh:idx:" + userId, keyOf(t2), "1");
        service.revokeAllForUser(userId);
        assertThat(redisService.hasKeyOrThrow("refresh:revoked-session", keyOf(t2))).isTrue();
    }

    @Test
    void shouldLogOutWhenStoredTokenIsUndecodable() {
        String t0 = service.issue(userId, List.of("user"), false).refreshToken();
        template.execute((RedisCallback<Object>) c -> c.stringCommands().set(
                ("refresh:" + keyOf(t0)).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "not json".getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        service.revoke(t0);

        assertThat(redisService.hasKeyOrThrow("refresh", keyOf(t0))).isFalse();
    }
}
