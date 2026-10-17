package com.vomatt.common.security;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.vomatt.common.redis.RedisOperationException;
import com.vomatt.common.redis.RedisService;
import com.vomatt.entity.User;
import com.vomatt.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    private static final String NAMESPACE = "refresh";
    private static final String CONSUMED_NS = "refresh:consumed";
    private static final String GRACE_NS = "refresh:grace";
    private static final String USER_IDX_NS = "refresh:idx";

    @Mock
    RedisService redisService;

    @Mock
    JwtUtil jwtUtil;

    @Mock
    UserRepository userRepository;

    RefreshTokenService service;

    ListAppender<ILoggingEvent> logAppender;
    Logger serviceLogger;

    /** Builds a User with the given roles (id parsed from userId); banned=true maps to active=false */
    private User userWithRoles(String userId, boolean banned, String... roles) {
        return User.builder()
                .id(UUID.fromString(userId))
                .username("u_" + userId.substring(0, 8))
                .email("p@example.com")
                .roles(roles.length > 0 ? roles : new String[] { "user" })
                .active(!banned)
                .build();
    }

    @BeforeEach
    void setUp() {
        service = new RefreshTokenService(redisService, jwtUtil, userRepository);
        ReflectionTestUtils.setField(service, "refreshExpirationDays", 30L);
        ReflectionTestUtils.setField(service, "refreshGraceSeconds", 10L);

        serviceLogger = (Logger) LoggerFactory.getLogger(RefreshTokenService.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        serviceLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        serviceLogger.detachAppender(logAppender);
    }

    // ── rotate: unknown token ─────────────────────────────────────────

    @Test
    void rotate_unknownToken_returnsNull() {
        String oldToken = "unknown-token";
        when(redisService.get(GRACE_NS, oldToken, RefreshTokenService.RotateResult.class)).thenReturn(null);
        when(redisService.getAndDelete(NAMESPACE, oldToken, RefreshTokenService.RefreshTokenData.class)).thenReturn(null);
        when(redisService.get(CONSUMED_NS, oldToken, String.class)).thenReturn(null);

        RefreshTokenService.RotateResult result = service.rotate(oldToken);

        assertThat(result).isNull();
        verify(redisService, never()).set(eq(GRACE_NS), anyString(), any(), any());
        verify(redisService, never()).setOrThrow(eq(CONSUMED_NS), anyString(), any(), any());
        verify(jwtUtil, never()).generateToken(anyString(), anyString(), any());
    }

    // ── rotate: happy path ────────────────────────────────────────────

    @Test
    void rotate_happyPath_writesGraceAndConsumedAndNewToken() {
        String oldToken = "old-refresh-token";
        String userId = UUID.randomUUID().toString();
        RefreshTokenService.RefreshTokenData data = new RefreshTokenService.RefreshTokenData(
                userId, "user@example.com", List.of("user"));

        when(redisService.get(GRACE_NS, oldToken, RefreshTokenService.RotateResult.class)).thenReturn(null);
        when(redisService.getAndDelete(NAMESPACE, oldToken, RefreshTokenService.RefreshTokenData.class)).thenReturn(data);
        // Step 4 reload User: not banned, roles match the token
        when(userRepository.findById(UUID.fromString(userId)))
                .thenReturn(Optional.of(userWithRoles(userId, false, "user")));
        when(jwtUtil.generateToken(eq(userId), eq("user@example.com"), eq(List.of("user")))).thenReturn("new-access-token");

        RefreshTokenService.RotateResult result = service.rotate(oldToken);

        assertThat(result).isNotNull();
        assertThat(result.newAccessToken()).isEqualTo("new-access-token");
        assertThat(result.newRefreshToken()).isNotBlank().isNotEqualTo(oldToken);
        assertThat(result.data().userId()).isEqualTo(userId);
        assertThat(result.data().roles()).isEqualTo(List.of("user"));

        // GRACE remains a separate fail-soft write (race rendezvous point); the other correctness-critical writes are combined into a single MULTI/EXEC (P1-#17).
        InOrder inOrder = inOrder(redisService);
        inOrder.verify(redisService).set(eq(GRACE_NS), eq(oldToken), eq(result), eq(Duration.ofSeconds(10)));

        ArgumentCaptor<List<RedisService.RedisWrite>> writesCaptor = ArgumentCaptor.captor();
        inOrder.verify(redisService).execInTransaction(writesCaptor.capture());
        List<RedisService.RedisWrite> writes = writesCaptor.getValue();
        assertThat(writes).containsExactly(
                RedisService.RedisWrite.set(CONSUMED_NS, oldToken, userId, Duration.ofDays(30)),
                RedisService.RedisWrite.set(NAMESPACE, result.newRefreshToken(), result.data(), Duration.ofDays(30)),
                RedisService.RedisWrite.hput(USER_IDX_NS, userId, result.newRefreshToken(), "1"),
                RedisService.RedisWrite.expire(USER_IDX_NS, userId, Duration.ofDays(30)),
                RedisService.RedisWrite.hdelete(USER_IDX_NS, userId, oldToken)
        );
    }

    // ── rotate: grace cache hit ───────────────────────────────────────

    @Test
    void rotate_withinGraceWindow_returnsSamePairWithoutRotating() {
        String oldToken = "racing-token";
        RefreshTokenService.RefreshTokenData data = new RefreshTokenService.RefreshTokenData(
                "user-2", "racer@example.com", List.of("user"));
        RefreshTokenService.RotateResult cached = new RefreshTokenService.RotateResult(
                "cached-access", "cached-refresh", data);

        when(redisService.get(GRACE_NS, oldToken, RefreshTokenService.RotateResult.class)).thenReturn(cached);

        RefreshTokenService.RotateResult result = service.rotate(oldToken);

        assertThat(result).isSameAs(cached);
        verify(redisService, never()).getAndDelete(anyString(), anyString(), any());
        verify(redisService, never()).set(eq(GRACE_NS), anyString(), any(), any());
        verify(redisService, never()).setOrThrow(eq(CONSUMED_NS), anyString(), any(), any());
        verify(redisService, never()).setOrThrow(eq(NAMESPACE), anyString(), any(), any());
        verify(jwtUtil, never()).generateToken(anyString(), anyString(), any());
    }

    // ── rotate: concurrent race ───────────────────────────────────────

    @Test
    void rotate_concurrent_bothReturnSamePair() throws Exception {
        String oldToken = "concurrent-token";
        String userId = UUID.randomUUID().toString();
        RefreshTokenService.RefreshTokenData data = new RefreshTokenService.RefreshTokenData(
                userId, "race@example.com", List.of("user"));

        // The first thread in gets data; the second thread gets null (GETDEL already consumed)
        AtomicBoolean getAndDeleteConsumed = new AtomicBoolean(false);
        when(redisService.getAndDelete(NAMESPACE, oldToken, RefreshTokenService.RefreshTokenData.class))
                .thenAnswer(inv -> getAndDeleteConsumed.compareAndSet(false, true) ? data : null);

        // Step 4 reload User: not banned
        when(userRepository.findById(UUID.fromString(userId)))
                .thenReturn(Optional.of(userWithRoles(userId, false, "user")));

        // jwtUtil returns a fixed token for the same data (simulates a deterministic access token; real JWTs differ by iat, but the grace cache handles that)
        when(jwtUtil.generateToken(eq(userId), eq("race@example.com"), eq(List.of("user"))))
                .thenReturn("concurrent-access-token");

        // grace cache behavior: after the first write, subsequent lookups return that result
        AtomicReference<RefreshTokenService.RotateResult> graceStore = new AtomicReference<>();
        when(redisService.get(eq(GRACE_NS), eq(oldToken), eq(RefreshTokenService.RotateResult.class)))
                .thenAnswer(inv -> graceStore.get());
        // capture grace set
        // Cannot stub void set directly; use doAnswer instead
        org.mockito.Mockito.doAnswer(inv -> {
            graceStore.set(inv.getArgument(2));
            return null;
        }).when(redisService).set(eq(GRACE_NS), eq(oldToken), any(RefreshTokenService.RotateResult.class), any(Duration.class));

        // Second caller takes the consumed path: getAndDelete null -> CONSUMED_NS must also return null to reach grace
        // For grace hits to be ordered correctly, the CONSUMED_NS lookup must come after the grace check; our rotate flow does grace first, then getAndDelete
        // So the second caller: grace check (hits graceStore because the first caller already wrote it) -> returns directly
        // Never reaches getAndDelete/consumed

        int parties = 2;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch ready = new CountDownLatch(parties);
        CountDownLatch done = new CountDownLatch(parties);
        ExecutorService pool = Executors.newFixedThreadPool(parties);

        AtomicReference<RefreshTokenService.RotateResult> r1 = new AtomicReference<>();
        AtomicReference<RefreshTokenService.RotateResult> r2 = new AtomicReference<>();

        CompletableFuture<Void> f1 = CompletableFuture.runAsync(() -> {
            try {
                ready.countDown();
                start.await();
                r1.set(service.rotate(oldToken));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
            }
        }, pool);
        CompletableFuture<Void> f2 = CompletableFuture.runAsync(() -> {
            try {
                ready.countDown();
                start.await();
                // Small delay ensures f1 writes grace first
                Thread.sleep(20);
                r2.set(service.rotate(oldToken));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
            }
        }, pool);

        ready.await();
        start.countDown();
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        f1.get();
        f2.get();
        pool.shutdown();

        assertThat(r1.get()).isNotNull();
        assertThat(r2.get()).isNotNull();
        assertThat(r1.get().newAccessToken()).isEqualTo(r2.get().newAccessToken());
        assertThat(r1.get().newRefreshToken()).isEqualTo(r2.get().newRefreshToken());
    }

    // ── rotate: reuse after grace ─────────────────────────────────────

    @Test
    void rotate_afterGraceExpired_triggersReuseDetectionAndRevokesAll() {
        String oldToken = "replayed-token";
        when(redisService.get(GRACE_NS, oldToken, RefreshTokenService.RotateResult.class)).thenReturn(null);
        when(redisService.getAndDelete(NAMESPACE, oldToken, RefreshTokenService.RefreshTokenData.class)).thenReturn(null);
        when(redisService.get(CONSUMED_NS, oldToken, String.class)).thenReturn("user-victim");
        when(redisService.hGetAll(USER_IDX_NS, "user-victim")).thenReturn(Map.of("t1", "1", "t2", "1"));

        assertThatThrownBy(() -> service.rotate(oldToken))
                .isInstanceOf(RefreshTokenReuseException.class)
                .hasMessageContaining("user-victim");

        verify(redisService).delete(NAMESPACE, "t1");
        verify(redisService).delete(NAMESPACE, "t2");
        verify(redisService).delete(USER_IDX_NS, "user-victim");
    }

    // ── rotate: Step 4 reload User -- ban blocks ────────────────────────

    @Test
    void rotate_bannedUser_returnsNullAndRevokesAll() {
        String oldToken = "banned-rotate-token";
        String userId = UUID.randomUUID().toString();
        RefreshTokenService.RefreshTokenData data = new RefreshTokenService.RefreshTokenData(
                userId, "banned@example.com", List.of("admin"));

        when(redisService.get(GRACE_NS, oldToken, RefreshTokenService.RotateResult.class)).thenReturn(null);
        when(redisService.getAndDelete(NAMESPACE, oldToken, RefreshTokenService.RefreshTokenData.class)).thenReturn(data);
        when(redisService.hGetAll(USER_IDX_NS, userId)).thenReturn(Map.of("t1", "1"));
        // Reload returns a suspended User
        when(userRepository.findById(UUID.fromString(userId)))
                .thenReturn(Optional.of(userWithRoles(userId, true, "admin")));

        RefreshTokenService.RotateResult result = service.rotate(oldToken);

        assertThat(result).isNull();
        // Banned users must have all sessions revoked
        verify(redisService).delete(NAMESPACE, "t1");
        verify(redisService).delete(USER_IDX_NS, userId);
        // Must not issue a new access token
        verify(jwtUtil, never()).generateToken(anyString(), anyString(), any());
    }

    @Test
    void rotate_userNotFound_returnsNullAndRevokesAll() {
        String oldToken = "ghost-rotate-token";
        String userId = UUID.randomUUID().toString();
        RefreshTokenService.RefreshTokenData data = new RefreshTokenService.RefreshTokenData(
                userId, "ghost@example.com", List.of("user"));

        when(redisService.get(GRACE_NS, oldToken, RefreshTokenService.RotateResult.class)).thenReturn(null);
        when(redisService.getAndDelete(NAMESPACE, oldToken, RefreshTokenService.RefreshTokenData.class)).thenReturn(data);
        when(redisService.hGetAll(USER_IDX_NS, userId)).thenReturn(Map.of());
        when(userRepository.findById(UUID.fromString(userId))).thenReturn(Optional.empty());

        RefreshTokenService.RotateResult result = service.rotate(oldToken);

        assertThat(result).isNull();
        verify(redisService).delete(USER_IDX_NS, userId);
        verify(jwtUtil, never()).generateToken(anyString(), anyString(), any());
    }

    // ── rotate: Step 4 reload User -- latest roles win ────────────────

    @Test
    void rotate_rolesChanged_signsNewTokenWithFreshRoles() {
        String oldToken = "stale-roles-token";
        String userId = UUID.randomUUID().toString();
        // Old roles in the token are admin (issued before the role was revoked)
        RefreshTokenService.RefreshTokenData data = new RefreshTokenService.RefreshTokenData(
                userId, "demoted@example.com", List.of("admin"));

        when(redisService.get(GRACE_NS, oldToken, RefreshTokenService.RotateResult.class)).thenReturn(null);
        when(redisService.getAndDelete(NAMESPACE, oldToken, RefreshTokenService.RefreshTokenData.class)).thenReturn(data);
        // Current User roles have been downgraded to user
        when(userRepository.findById(UUID.fromString(userId)))
                .thenReturn(Optional.of(userWithRoles(userId, false, "user")));
        when(jwtUtil.generateToken(eq(userId), eq("demoted@example.com"), any())).thenReturn("fresh-access");

        RefreshTokenService.RotateResult result = service.rotate(oldToken);

        assertThat(result).isNotNull();
        assertThat(result.newAccessToken()).isEqualTo("fresh-access");

        // Access token must be issued with fresh roles (user), not old roles (admin)
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> rolesCaptor = ArgumentCaptor.forClass(List.class);
        verify(jwtUtil).generateToken(eq(userId), eq("demoted@example.com"), rolesCaptor.capture());
        assertThat(rolesCaptor.getValue()).containsExactly("user");

        // The new RefreshTokenData written to NAMESPACE must also use fresh roles (now passed via execInTransaction's RedisWrite)
        ArgumentCaptor<List<RedisService.RedisWrite>> writesCaptor = ArgumentCaptor.captor();
        verify(redisService).execInTransaction(writesCaptor.capture());
        RefreshTokenService.RefreshTokenData namespaceData = writesCaptor.getValue().stream()
                .filter(w -> w.op() == RedisService.RedisWrite.Op.SET && NAMESPACE.equals(w.cacheName()))
                .map(w -> (RefreshTokenService.RefreshTokenData) w.value())
                .findFirst().orElseThrow();
        assertThat(namespaceData.roles()).containsExactly("user");

        // RotateResult.data is fresh too
        assertThat(result.data().roles()).containsExactly("user");
    }

    // ── rotate: log line on cache hit ─────────────────────────────────

    @Test
    void rotate_cacheHit_emitsInfoLogWithShaPrefixAndNoRawToken() {
        String oldToken = "log-token";
        RefreshTokenService.RefreshTokenData data = new RefreshTokenService.RefreshTokenData(
                "user-4", "log@example.com", List.of("user"));
        RefreshTokenService.RotateResult cached = new RefreshTokenService.RotateResult(
                "log-access", "log-refresh", data);

        when(redisService.get(GRACE_NS, oldToken, RefreshTokenService.RotateResult.class)).thenReturn(cached);

        service.rotate(oldToken);

        List<ILoggingEvent> hits = logAppender.list.stream()
                .filter(e -> e.getLevel() == Level.INFO)
                .filter(e -> e.getFormattedMessage().contains("refresh.cache_hit"))
                .toList();
        assertThat(hits).hasSize(1);
        String msg = hits.get(0).getFormattedMessage();
        assertThat(msg).contains("refreshToken_sha=");
        assertThat(msg).doesNotContain(oldToken);
        assertThat(msg).doesNotContain("log-access");
        assertThat(msg).doesNotContain("log-refresh");
    }

    // ── generate / revoke / revokeAllForUser unchanged ────────────────

    @Test
    void generate_storesTokenAndUserIndex() {
        String token = service.generate("user-5", "gen@example.com", List.of("user"));

        assertThat(token).isNotBlank();
        verify(redisService).setOrThrow(eq(NAMESPACE), eq(token), any(RefreshTokenService.RefreshTokenData.class), eq(Duration.ofDays(30)));
        verify(redisService).hSetOrThrow(eq(USER_IDX_NS), eq("user-5"), eq(token), eq("1"), eq(Duration.ofDays(30)));
    }

    // ── Correctness-critical write failures must throw (P5-#3 / P5-#8) ─────────────────────────

    @Test
    void generate_whenUserIndexWriteFails_propagatesException() {
        doThrow(new RedisOperationException("redis down", null))
                .when(redisService).hSetOrThrow(eq(USER_IDX_NS), anyString(), anyString(), any(), any());

        assertThatThrownBy(() -> service.generate("user-9", "g@example.com", List.of("user")))
                .isInstanceOf(RedisOperationException.class);
    }

    @Test
    void rotate_whenTransactionFails_propagatesException() {
        // P1-#17: correctness-critical writes are combined into a single MULTI/EXEC; a transaction failure must throw for the whole batch (fail-fast).
        String oldToken = "old-token";
        String userId = UUID.randomUUID().toString();
        RefreshTokenService.RefreshTokenData data = new RefreshTokenService.RefreshTokenData(
                userId, "user@example.com", List.of("user"));
        when(redisService.get(GRACE_NS, oldToken, RefreshTokenService.RotateResult.class)).thenReturn(null);
        when(redisService.getAndDelete(NAMESPACE, oldToken, RefreshTokenService.RefreshTokenData.class)).thenReturn(data);
        when(userRepository.findById(UUID.fromString(userId)))
                .thenReturn(Optional.of(userWithRoles(userId, false, "user")));
        when(jwtUtil.generateToken(anyString(), anyString(), any())).thenReturn("new-access");
        doThrow(new RedisOperationException("redis down", null))
                .when(redisService).execInTransaction(any());

        assertThatThrownBy(() -> service.rotate(oldToken))
                .isInstanceOf(RedisOperationException.class);
    }

    @Test
    void revoke_existingToken_deletesNamespaceAndUserIndex() {
        String token = "revoke-token";
        RefreshTokenService.RefreshTokenData data = new RefreshTokenService.RefreshTokenData(
                "user-6", "rv@example.com", List.of("user"));
        when(redisService.getAndDelete(NAMESPACE, token, RefreshTokenService.RefreshTokenData.class)).thenReturn(data);

        service.revoke(token);

        verify(redisService).hDelete(USER_IDX_NS, "user-6", token);
    }

    @Test
    void revokeAllForUser_deletesEveryActiveToken() {
        when(redisService.hGetAll(USER_IDX_NS, "user-7")).thenReturn(Map.of("ta", "1", "tb", "1"));

        service.revokeAllForUser("user-7");

        verify(redisService).delete(NAMESPACE, "ta");
        verify(redisService).delete(NAMESPACE, "tb");
        verify(redisService).delete(USER_IDX_NS, "user-7");
    }
}
