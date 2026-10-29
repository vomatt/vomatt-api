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

import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    private static final String NAMESPACE = "refresh";
    private static final String CONSUMED_NS = "refresh:consumed";
    private static final String GRACE_NS = "refresh:grace";
    private static final String USER_IDX_NS = "refresh:idx";
    private static final String REVOKED_SESSION_NS = "refresh:revoked-session";
    private static final String ENDED_SESSION_NS = "refresh:ended-session";

    @Mock
    RedisService redisService;

    @Mock
    JwtUtil jwtUtil;

    @Mock
    UserRepository userRepository;

    RefreshTokenService service;

    /** Redis key of a token (the service never stores the token itself). */
    private static String k(String token) {
        return RefreshTokenService.keyOf(token);
    }

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

    /** A grace pair of a live session. */
    private RefreshTokenService.RotateResult standingPair(String access, String refresh) {
        return new RefreshTokenService.RotateResult(access, refresh,
                new RefreshTokenService.RefreshTokenData(UUID.randomUUID().toString(), List.of("user"), "session-1", false, null, null, null));
    }

    /** The writes that end a session: the grace-only marker, plus the long-lived revoked flag when revoked. */
    private static List<RedisService.RedisWrite> ending(String sessionId, boolean revoke) {
        RedisService.RedisWrite ended = RedisService.RedisWrite.set(ENDED_SESSION_NS, sessionId, "1", Duration.ofSeconds(60));
        return revoke
                ? List.of(ended, RedisService.RedisWrite.set(REVOKED_SESSION_NS, sessionId, "1", Duration.ofDays(30)))
                : List.of(ended);
    }

    /** Stubs the rotation script's reply; the captor holds its ARGV (session id, new token data, grace pair, ...). */
    private ArgumentCaptor<List<Object>> rotation(long reply) {
        ArgumentCaptor<List<Object>> args = ArgumentCaptor.captor();
        when(redisService.execScript(any(), anyList(), args.capture())).thenReturn(reply);
        return args;
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
        RequestContextHolder.resetRequestAttributes();
        serviceLogger.detachAppender(logAppender);
    }

    // ── rotate: unknown token ─────────────────────────────────────────

    @Test
    void rotate_unknownToken_returnsNull() {
        String oldToken = "unknown-token";
        when(redisService.getOrThrow(GRACE_NS, k(oldToken), RefreshTokenService.RotateResult.class)).thenReturn(null);
        when(redisService.getOrThrow(NAMESPACE, k(oldToken), RefreshTokenService.RefreshTokenData.class)).thenReturn(null);
        when(redisService.getOrThrow(CONSUMED_NS, k(oldToken), String.class)).thenReturn(null);

        RefreshTokenService.RotateResult result = service.rotate(oldToken);

        assertThat(result).isNull();
        verify(redisService, never()).execScript(any(), anyList(), anyList());
        verify(jwtUtil, never()).generateToken(anyString(), any(), anyBoolean(), any());
    }

    // ── rotate: happy path ────────────────────────────────────────────

    @Test
    void shouldKeepAdminScopeWhenRotatingAdminConsoleSession() {
        String oldToken = "admin-refresh-token";
        String userId = UUID.randomUUID().toString();
        when(redisService.getOrThrow(GRACE_NS, k(oldToken), RefreshTokenService.RotateResult.class)).thenReturn(null);
        when(redisService.getOrThrow(NAMESPACE, k(oldToken), RefreshTokenService.RefreshTokenData.class))
                .thenReturn(new RefreshTokenService.RefreshTokenData(userId, List.of("admin"), "session-1", true, null, null, null));
        when(userRepository.findById(UUID.fromString(userId))).thenReturn(Optional.of(userWithRoles(userId, false, "admin")));
        when(redisService.execScript(any(), anyList(), anyList())).thenReturn(1L);
        when(jwtUtil.generateToken(eq(userId), eq(List.of("admin")), eq(true), any())).thenReturn("admin-access");

        RefreshTokenService.RotateResult result = service.rotate(oldToken);

        assertThat(result.newAccessToken()).isEqualTo("admin-access");
        assertThat(result.data().grantsAdmin()).isTrue();
    }

    @Test
    void rotate_happyPath_writesGraceAndConsumedAndNewToken() {
        String oldToken = "old-refresh-token";
        String userId = UUID.randomUUID().toString();
        RefreshTokenService.RefreshTokenData data = new RefreshTokenService.RefreshTokenData(
                userId, List.of("user"), "session-1", false, null, null, null);

        when(redisService.getOrThrow(GRACE_NS, k(oldToken), RefreshTokenService.RotateResult.class)).thenReturn(null);
        when(redisService.getOrThrow(NAMESPACE, k(oldToken), RefreshTokenService.RefreshTokenData.class)).thenReturn(data);
        // Step 4 reload User: not banned, roles match the token
        when(userRepository.findById(UUID.fromString(userId)))
                .thenReturn(Optional.of(userWithRoles(userId, false, "user")));
        when(redisService.execScript(any(), anyList(), anyList())).thenReturn(1L);
        when(jwtUtil.generateToken(eq(userId), eq(List.of("user")), eq(false), any())).thenReturn("new-access-token");

        RefreshTokenService.RotateResult result = service.rotate(oldToken);

        assertThat(result).isNotNull();
        assertThat(result.newAccessToken()).isEqualTo("new-access-token");
        assertThat(result.newRefreshToken()).isNotBlank().isNotEqualTo(oldToken);
        assertThat(result.data().userId()).isEqualTo(userId);
        assertThat(result.data().roles()).isEqualTo(List.of("user"));

        // The old token is consumed by the script together with every write
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.captor();
        ArgumentCaptor<List<Object>> args = ArgumentCaptor.captor();
        verify(redisService).execScript(any(), keys.capture(), args.capture());
        String newKey = k(result.newRefreshToken());
        assertThat(keys.getValue()).containsExactly(NAMESPACE + ":" + k(oldToken),
                REVOKED_SESSION_NS + ":session-1", CONSUMED_NS + ":" + k(oldToken), NAMESPACE + ":" + newKey,
                USER_IDX_NS + ":" + userId, GRACE_NS + ":" + k(oldToken));
        assertThat(args.getValue().subList(0, 3)).containsExactly("session-1", result.data(), result);
        assertThat(args.getValue().subList(3, 7)).extracting(a -> new String((byte[]) a, java.nio.charset.StandardCharsets.UTF_8))
                .containsExactly(newKey, k(oldToken), Long.toString(Duration.ofDays(30).toMillis()), "10000");
    }

    // ── rotate: grace cache hit ───────────────────────────────────────

    @Test
    void rotate_withinGraceWindow_returnsSamePairWithoutRotating() {
        String oldToken = "racing-token";
        RefreshTokenService.RotateResult cached = standingPair("cached-access", "cached-refresh");

        when(redisService.getOrThrow(GRACE_NS, k(oldToken), RefreshTokenService.RotateResult.class)).thenReturn(cached);

        RefreshTokenService.RotateResult result = service.rotate(oldToken);

        assertThat(result).isSameAs(cached);
        verify(redisService, never()).getOrThrow(eq(NAMESPACE), anyString(), any());
        verify(redisService, never()).set(eq(GRACE_NS), anyString(), any(), any());
        verify(redisService, never()).setOrThrow(eq(CONSUMED_NS), anyString(), any(), any());
        verify(redisService, never()).setOrThrow(eq(NAMESPACE), anyString(), any(), any());
        verify(jwtUtil, never()).generateToken(anyString(), any(), anyBoolean(), any());
    }

    @Test
    void shouldRejectGracePairWhenItsSessionHasEnded() {
        String oldToken = "stolen-just-rotated";
        when(redisService.getOrThrow(GRACE_NS, k(oldToken), RefreshTokenService.RotateResult.class))
                .thenReturn(standingPair("access", "live-refresh"));
        when(redisService.hasKeyOrThrow(ENDED_SESSION_NS, "session-1")).thenReturn(true);

        assertThat(service.rotate(oldToken)).isNull();
    }

    @Test
    void shouldServeGracePairWithoutLoadingUserWhenSessionStands() {
        String oldToken = "just-rotated";
        RefreshTokenService.RotateResult cached = standingPair("access", "live-refresh");
        when(redisService.getOrThrow(GRACE_NS, k(oldToken), RefreshTokenService.RotateResult.class)).thenReturn(cached);

        assertThat(service.rotate(oldToken)).isSameAs(cached);
        // Suspension ends the session through revokeAllForUser, so the marker alone decides
        verifyNoInteractions(userRepository);
    }

    @Test
    void shouldReturnNullWhenLosingRaceToALogout() {
        // The winner's pair is in grace, but its session was logged out before this caller read it
        String oldToken = "lost-race-then-logout";
        String userId = UUID.randomUUID().toString();
        RefreshTokenService.RotateResult winner = new RefreshTokenService.RotateResult("access", "logged-out",
                new RefreshTokenService.RefreshTokenData(userId, List.of("user"), "session-1", false, null, null, null));
        when(redisService.getOrThrow(GRACE_NS, k(oldToken), RefreshTokenService.RotateResult.class)).thenReturn(null, winner);
        when(redisService.getOrThrow(NAMESPACE, k(oldToken), RefreshTokenService.RefreshTokenData.class))
                .thenReturn(new RefreshTokenService.RefreshTokenData(userId, List.of("user"), "session-1", false, null, null, null));
        when(userRepository.findById(UUID.fromString(userId))).thenReturn(Optional.of(userWithRoles(userId, false)));
        when(redisService.execScript(any(), anyList(), anyList())).thenReturn(0L);
        when(redisService.hasKeyOrThrow(ENDED_SESSION_NS, "session-1")).thenReturn(true);

        assertThat(service.rotate(oldToken)).isNull();
    }

    @Test
    void shouldPropagateExceptionWhenLegacyMarkerCheckFails() {
        String oldToken = "replayed-during-outage";
        when(redisService.getOrThrow(GRACE_NS, k(oldToken), RefreshTokenService.RotateResult.class)).thenReturn(null);
        when(redisService.getOrThrow(NAMESPACE, k(oldToken), RefreshTokenService.RefreshTokenData.class)).thenReturn(null);
        when(redisService.getOrThrow(CONSUMED_NS, k(oldToken), String.class)).thenReturn("session-1");
        when(redisService.hasKeyOrThrow(GRACE_NS, k(oldToken))).thenReturn(false);
        when(redisService.hasKeyOrThrow(USER_IDX_NS, "session-1")).thenThrow(new RedisOperationException("redis timeout", null));

        // Read as "no legacy marker" it would revoke the wrong thing; a 503 revokes nothing
        assertThatThrownBy(() -> service.rotate(oldToken)).isInstanceOf(RedisOperationException.class);
        verify(redisService, never()).execInTransaction(any());
        verify(redisService, never()).hGetAllOrThrow(anyString(), anyString());
    }

    // ── rotate: concurrent race ───────────────────────────────────────

    @Test
    void shouldReturnSamePairWhenSecondCallerArrivesWhileFirstIsStillRotating() throws Exception {
        String oldToken = "mid-rotation-token";
        String userId = UUID.randomUUID().toString();
        RefreshTokenService.RefreshTokenData data = new RefreshTokenService.RefreshTokenData(
                userId, List.of("user"), "session-1", false, null, null, null);

        // Redis as one shared store: the token stays readable until a commit consumes it, which writes grace in the same step
        AtomicBoolean consumed = new AtomicBoolean(false);
        AtomicReference<RefreshTokenService.RotateResult> graceStore = new AtomicReference<>();
        when(redisService.getOrThrow(NAMESPACE, k(oldToken), RefreshTokenService.RefreshTokenData.class))
                .thenAnswer(inv -> consumed.get() ? null : data);
        when(redisService.getOrThrow(eq(GRACE_NS), eq(k(oldToken)), eq(RefreshTokenService.RotateResult.class)))
                .thenAnswer(inv -> graceStore.get());
        when(redisService.execScript(any(), anyList(), anyList())).thenAnswer(inv -> {
            if (!consumed.compareAndSet(false, true)) return 0L;
            List<Object> args = inv.getArgument(2);
            graceStore.set((RefreshTokenService.RotateResult) args.get(2));
            return 1L;
        });
        when(jwtUtil.generateToken(eq(userId), eq(List.of("user")), eq(false), any())).thenReturn("access");

        // Both callers are in the user lookup (a slow DB round trip after idle) before either commits
        CyclicBarrier bothRead = new CyclicBarrier(2);
        when(userRepository.findById(UUID.fromString(userId))).thenAnswer(inv -> {
            bothRead.await(5, TimeUnit.SECONDS);
            return Optional.of(userWithRoles(userId, false, "user"));
        });

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CompletableFuture<RefreshTokenService.RotateResult> first =
                CompletableFuture.supplyAsync(() -> service.rotate(oldToken), pool);
        CompletableFuture<RefreshTokenService.RotateResult> second =
                CompletableFuture.supplyAsync(() -> service.rotate(oldToken), pool);
        RefreshTokenService.RotateResult r1 = first.get(5, TimeUnit.SECONDS);
        RefreshTokenService.RotateResult r2 = second.get(5, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(r1).isNotNull();
        assertThat(r2).isNotNull();
        assertThat(r2.newRefreshToken()).isEqualTo(r1.newRefreshToken());
    }

    @Test
    void shouldReturnGracePairWhenTokenConsumedBetweenGraceCheckAndRead() {
        String oldToken = "just-rotated-token";
        RefreshTokenService.RotateResult cached = standingPair("access", "refresh");
        // A concurrent caller commits right after this one's first grace lookup
        when(redisService.getOrThrow(GRACE_NS, k(oldToken), RefreshTokenService.RotateResult.class)).thenReturn(null, cached);
        when(redisService.getOrThrow(NAMESPACE, k(oldToken), RefreshTokenService.RefreshTokenData.class)).thenReturn(null);

        assertThat(service.rotate(oldToken)).isSameAs(cached);
        // Not mistaken for reuse, although the consumed marker exists by now
        verify(redisService, never()).getOrThrow(eq(CONSUMED_NS), anyString(), eq(String.class));
    }

    @Test
    void shouldPropagateExceptionWhenLosingRaceAndGraceReadFails() {
        String oldToken = "lost-race-token";
        String userId = UUID.randomUUID().toString();
        when(redisService.getOrThrow(GRACE_NS, k(oldToken), RefreshTokenService.RotateResult.class))
                .thenReturn(null)
                .thenThrow(new RedisOperationException("redis timeout", null));
        when(redisService.getOrThrow(NAMESPACE, k(oldToken), RefreshTokenService.RefreshTokenData.class))
                .thenReturn(new RefreshTokenService.RefreshTokenData(userId, List.of("user"), "session-1", false, null, null, null));
        when(userRepository.findById(UUID.fromString(userId))).thenReturn(Optional.of(userWithRoles(userId, false, "user")));
        when(redisService.execScript(any(), anyList(), anyList())).thenReturn(0L);

        // A 503 keeps the session; a null here would answer 401 and sign the user out
        assertThatThrownBy(() -> service.rotate(oldToken)).isInstanceOf(RedisOperationException.class);
    }

    @Test
    void shouldPropagateExceptionWhenReuseCheckReadFails() {
        String oldToken = "replayed-during-outage";
        when(redisService.getOrThrow(GRACE_NS, k(oldToken), RefreshTokenService.RotateResult.class)).thenReturn(null);
        when(redisService.getOrThrow(NAMESPACE, k(oldToken), RefreshTokenService.RefreshTokenData.class)).thenReturn(null);
        when(redisService.getOrThrow(CONSUMED_NS, k(oldToken), String.class))
                .thenThrow(new RedisOperationException("redis timeout", null));

        // A 503 rather than a plain 401 that would let a replayed token skip the session revocation
        assertThatThrownBy(() -> service.rotate(oldToken)).isInstanceOf(RedisOperationException.class);
    }

    @Test
    void shouldRefuseToStartWhenGraceWindowIsBelowOneSecond() {
        ReflectionTestUtils.setField(service, "refreshGraceSeconds", 0L);
        assertThatThrownBy(() -> service.checkGraceWindow()).isInstanceOf(IllegalStateException.class);

        ReflectionTestUtils.setField(service, "refreshGraceSeconds", 1L);
        service.checkGraceWindow();
    }

    // ── rotate: reuse after grace ─────────────────────────────────────

    @Test
    void shouldRevokeOnlyItsSessionWhenTokenReusedAfterGrace() {
        String oldToken = "replayed-token";
        when(redisService.getOrThrow(GRACE_NS, k(oldToken), RefreshTokenService.RotateResult.class)).thenReturn(null);
        when(redisService.getOrThrow(NAMESPACE, k(oldToken), RefreshTokenService.RefreshTokenData.class)).thenReturn(null);
        when(redisService.getOrThrow(CONSUMED_NS, k(oldToken), String.class)).thenReturn("session-1");
        when(redisService.hasKeyOrThrow(GRACE_NS, k(oldToken))).thenReturn(false);
        when(redisService.hasKeyOrThrow(USER_IDX_NS, "session-1")).thenReturn(false);

        assertThatThrownBy(() -> service.rotate(oldToken)).isInstanceOf(RefreshTokenReuseException.class);

        // Revoked, and its grace pairs (of tokens rotated within the window) are no longer served either
        verify(redisService).execInTransaction(ending("session-1", true));
        // The user's other sessions survive
        verify(redisService, never()).hGetAllOrThrow(anyString(), anyString());
        verify(redisService, never()).delete(eq(USER_IDX_NS), anyString());
    }

    @Test
    void shouldRejectRotationWhenSessionRevokedAfterReuse() {
        String liveToken = "thief-live-token";
        String userId = UUID.randomUUID().toString();
        when(redisService.getOrThrow(GRACE_NS, k(liveToken), RefreshTokenService.RotateResult.class)).thenReturn(null);
        when(redisService.getOrThrow(NAMESPACE, k(liveToken), RefreshTokenService.RefreshTokenData.class))
                .thenReturn(new RefreshTokenService.RefreshTokenData(userId, List.of("user"), "session-1", false, null, null, null));
        when(userRepository.findById(UUID.fromString(userId))).thenReturn(Optional.of(userWithRoles(userId, false)));
        // The script finds the flag, also when reuse detection set it after this caller read the token
        ArgumentCaptor<List<Object>> captor = rotation(2L);

        assertThat(service.rotate(liveToken)).isNull();

        assertThat(captor.getValue().get(0)).isEqualTo("session-1");
        // No grace read after the script: the pair it would have written was never stored
        verify(redisService).getOrThrow(GRACE_NS, k(liveToken), RefreshTokenService.RotateResult.class);
    }

    @Test
    void shouldStartSessionWhenRotatingTokenIssuedBeforeSessionIds() {
        String oldToken = "pre-deploy-live-token";
        String userId = UUID.randomUUID().toString();
        when(redisService.getOrThrow(GRACE_NS, k(oldToken), RefreshTokenService.RotateResult.class)).thenReturn(null);
        when(redisService.getOrThrow(NAMESPACE, k(oldToken), RefreshTokenService.RefreshTokenData.class))
                .thenReturn(new RefreshTokenService.RefreshTokenData(userId, List.of("user"), null, false, null, null, null));
        when(userRepository.findById(UUID.fromString(userId))).thenReturn(Optional.of(userWithRoles(userId, false, "user")));

        when(redisService.execScript(any(), anyList(), anyList())).thenReturn(1L);

        RefreshTokenService.RotateResult result = service.rotate(oldToken);

        assertThat(result.data().sessionId()).isNotBlank();
    }

    @Test
    void shouldRevokeAllSessionsWhenLegacyMarkerHoldsUserId() {
        String oldToken = "pre-deploy-token";
        when(redisService.getOrThrow(GRACE_NS, k(oldToken), RefreshTokenService.RotateResult.class)).thenReturn(null);
        when(redisService.getOrThrow(NAMESPACE, k(oldToken), RefreshTokenService.RefreshTokenData.class)).thenReturn(null);
        when(redisService.getOrThrow(CONSUMED_NS, k(oldToken), String.class)).thenReturn("user-legacy");
        when(redisService.hasKeyOrThrow(GRACE_NS, k(oldToken))).thenReturn(false);
        when(redisService.hasKeyOrThrow(USER_IDX_NS, "user-legacy")).thenReturn(true);
        when(redisService.hGetAllOrThrow(USER_IDX_NS, "user-legacy")).thenReturn(Map.of("t1", "session-1"));

        assertThatThrownBy(() -> service.rotate(oldToken)).isInstanceOf(RefreshTokenReuseException.class);

        verify(redisService).execInTransaction(ending("session-1", true));
        verify(redisService).deleteAll(NAMESPACE, Set.of("t1"));
        verify(redisService).hDelete(USER_IDX_NS, "user-legacy", "t1");
    }

    // ── rotate: Step 4 reload User -- ban blocks ────────────────────────

    @Test
    void rotate_bannedUser_returnsNullAndRevokesAll() {
        String oldToken = "banned-rotate-token";
        String userId = UUID.randomUUID().toString();
        RefreshTokenService.RefreshTokenData data = new RefreshTokenService.RefreshTokenData(
                userId, List.of("admin"), "session-1", false, null, null, null);

        when(redisService.getOrThrow(GRACE_NS, k(oldToken), RefreshTokenService.RotateResult.class)).thenReturn(null);
        when(redisService.getOrThrow(NAMESPACE, k(oldToken), RefreshTokenService.RefreshTokenData.class)).thenReturn(data);
        when(redisService.hGetAllOrThrow(USER_IDX_NS, userId)).thenReturn(Map.of("t1", "session-1"));
        // Reload returns a suspended User
        when(userRepository.findById(UUID.fromString(userId)))
                .thenReturn(Optional.of(userWithRoles(userId, true, "admin")));

        RefreshTokenService.RotateResult result = service.rotate(oldToken);

        assertThat(result).isNull();
        // The old token goes even if the user index doesn't list it
        verify(redisService).delete(NAMESPACE, k(oldToken));
        // Banned users must have all sessions revoked
        verify(redisService).execInTransaction(ending("session-1", true));
        verify(redisService).deleteAll(NAMESPACE, Set.of("t1"));
        // Must not issue a new access token
        verify(jwtUtil, never()).generateToken(anyString(), any(), anyBoolean(), any());
    }

    @Test
    void rotate_userNotFound_returnsNullAndRevokesAll() {
        String oldToken = "ghost-rotate-token";
        String userId = UUID.randomUUID().toString();
        RefreshTokenService.RefreshTokenData data = new RefreshTokenService.RefreshTokenData(
                userId, List.of("user"), "session-1", false, null, null, null);

        when(redisService.getOrThrow(GRACE_NS, k(oldToken), RefreshTokenService.RotateResult.class)).thenReturn(null);
        when(redisService.getOrThrow(NAMESPACE, k(oldToken), RefreshTokenService.RefreshTokenData.class)).thenReturn(data);
        when(redisService.hGetAllOrThrow(USER_IDX_NS, userId)).thenReturn(Map.of());
        when(userRepository.findById(UUID.fromString(userId))).thenReturn(Optional.empty());

        RefreshTokenService.RotateResult result = service.rotate(oldToken);

        assertThat(result).isNull();
        verify(redisService).delete(NAMESPACE, k(oldToken));
        // Nothing listed, nothing more to delete (an HDEL of no fields is a Redis error)
        verify(redisService, never()).hDelete(eq(USER_IDX_NS), anyString(), any(String[].class));
        verify(jwtUtil, never()).generateToken(anyString(), any(), anyBoolean(), any());
    }

    // ── rotate: Step 4 reload User -- latest roles win ────────────────

    @Test
    void rotate_rolesChanged_signsNewTokenWithFreshRoles() {
        String oldToken = "stale-roles-token";
        String userId = UUID.randomUUID().toString();
        // Old roles in the token are admin (issued before the role was revoked)
        RefreshTokenService.RefreshTokenData data = new RefreshTokenService.RefreshTokenData(
                userId, List.of("admin"), "session-1", false, null, null, null);

        when(redisService.getOrThrow(GRACE_NS, k(oldToken), RefreshTokenService.RotateResult.class)).thenReturn(null);
        when(redisService.getOrThrow(NAMESPACE, k(oldToken), RefreshTokenService.RefreshTokenData.class)).thenReturn(data);
        // Current User roles have been downgraded to user
        when(userRepository.findById(UUID.fromString(userId)))
                .thenReturn(Optional.of(userWithRoles(userId, false, "user")));
        when(redisService.execScript(any(), anyList(), anyList())).thenReturn(1L);
        when(jwtUtil.generateToken(eq(userId), any(), eq(false), any())).thenReturn("fresh-access");

        RefreshTokenService.RotateResult result = service.rotate(oldToken);

        assertThat(result).isNotNull();
        assertThat(result.newAccessToken()).isEqualTo("fresh-access");

        // Access token must be issued with fresh roles (user), not old roles (admin)
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> rolesCaptor = ArgumentCaptor.forClass(List.class);
        verify(jwtUtil).generateToken(eq(userId), rolesCaptor.capture(), eq(false), any());
        assertThat(rolesCaptor.getValue()).containsExactly("user");

        // The new RefreshTokenData written to NAMESPACE must also use fresh roles
        ArgumentCaptor<List<Object>> args = ArgumentCaptor.captor();
        verify(redisService).execScript(any(), anyList(), args.capture());
        assertThat(((RefreshTokenService.RefreshTokenData) args.getValue().get(1)).roles()).containsExactly("user");

        // RotateResult.data is fresh too
        assertThat(result.data().roles()).containsExactly("user");
    }

    // ── rotate: log line on cache hit ─────────────────────────────────

    @Test
    void rotate_cacheHit_emitsInfoLogWithShaPrefixAndNoRawToken() {
        String oldToken = "log-token";
        RefreshTokenService.RotateResult cached = standingPair("log-access", "log-refresh");

        when(redisService.getOrThrow(GRACE_NS, k(oldToken), RefreshTokenService.RotateResult.class)).thenReturn(cached);

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

    // ── issue / revoke / revokeAllForUser ─────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void shouldNameTheSameSessionInBothTokensWhenIssuing() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("User-Agent", "x".repeat(600));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        JwtUtil realJwt = new JwtUtil("unit-test-secret-unit-test-secret-1234", 3600);
        RefreshTokenService issuing = new RefreshTokenService(redisService, realJwt, userRepository);
        ReflectionTestUtils.setField(issuing, "refreshExpirationDays", 30L);

        RefreshTokenService.IssuedTokens tokens = issuing.issue("user-5", List.of("user"), false);

        ArgumentCaptor<List<RedisService.RedisWrite>> writes = ArgumentCaptor.forClass(List.class);
        verify(redisService).execInTransaction(writes.capture());
        RefreshTokenService.RefreshTokenData stored = (RefreshTokenService.RefreshTokenData) writes.getValue().get(0).value();
        assertThat(realJwt.parseToken(tokens.accessToken()).sessionId()).isEqualTo(stored.sessionId()).isNotBlank();
        assertThat(stored.userAgent()).hasSize(512);
        assertThat(stored.createdAt()).isNotNull().isEqualTo(stored.lastSeenAt());
    }

    @Test
    void shouldKeepCreatedAtAndTakeCurrentDeviceWhenRotating() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("User-Agent", "NewBrowser/2.0");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        String oldToken = "device-token";
        String userId = UUID.randomUUID().toString();
        Instant signedIn = Instant.parse("2026-10-20T08:12:00Z");
        when(redisService.getOrThrow(GRACE_NS, k(oldToken), RefreshTokenService.RotateResult.class)).thenReturn(null);
        when(redisService.getOrThrow(NAMESPACE, k(oldToken), RefreshTokenService.RefreshTokenData.class)).thenReturn(
                new RefreshTokenService.RefreshTokenData(userId, List.of("user"), "session-1", false,
                        "OldBrowser/1.0", signedIn, signedIn));
        when(userRepository.findById(UUID.fromString(userId))).thenReturn(Optional.of(userWithRoles(userId, false, "user")));
        when(redisService.execScript(any(), anyList(), anyList())).thenReturn(1L);
        when(jwtUtil.generateToken(userId, List.of("user"), false, "session-1")).thenReturn("access");

        RefreshTokenService.RefreshTokenData rotated = service.rotate(oldToken).data();

        assertThat(rotated.userAgent()).isEqualTo("NewBrowser/2.0");
        assertThat(rotated.createdAt()).isEqualTo(signedIn);
        assertThat(rotated.lastSeenAt()).isAfter(signedIn);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldRenewUserIndexTtlWhenGeneratingToken() {
        String token = service.issue("user-5", List.of("user"), false).refreshToken();

        assertThat(token).isNotBlank();
        ArgumentCaptor<List<RedisService.RedisWrite>> writes = ArgumentCaptor.forClass(List.class);
        verify(redisService).execInTransaction(writes.capture());
        RefreshTokenService.RefreshTokenData stored = (RefreshTokenService.RefreshTokenData) writes.getValue().get(0).value();
        assertThat(stored.sessionId()).isNotBlank();
        assertThat(writes.getValue()).containsExactly(
                RedisService.RedisWrite.set(NAMESPACE, k(token),
                        stored, Duration.ofDays(30)),
                RedisService.RedisWrite.hput(USER_IDX_NS, "user-5", k(token), stored.sessionId()),
                // The index TTL is renewed on every issue, not only when the index is first created
                RedisService.RedisWrite.expire(USER_IDX_NS, "user-5", Duration.ofDays(30)));
    }

    // ── Correctness-critical write failures must throw (P5-#3 / P5-#8) ─────────────────────────

    @Test
    void generate_whenUserIndexWriteFails_propagatesException() {
        doThrow(new RedisOperationException("redis down", null))
                .when(redisService).execInTransaction(any());

        assertThatThrownBy(() -> service.issue("user-9", List.of("user"), false).refreshToken())
                .isInstanceOf(RedisOperationException.class);
    }

    @Test
    void shouldPropagateExceptionWhenCommitFails() {
        // Every rotation write is one atomic Lua step; a Redis failure must throw for the whole batch (fail-fast).
        String oldToken = "old-token";
        String userId = UUID.randomUUID().toString();
        RefreshTokenService.RefreshTokenData data = new RefreshTokenService.RefreshTokenData(
                userId, List.of("user"), "session-1", false, null, null, null);
        when(redisService.getOrThrow(GRACE_NS, k(oldToken), RefreshTokenService.RotateResult.class)).thenReturn(null);
        when(redisService.getOrThrow(NAMESPACE, k(oldToken), RefreshTokenService.RefreshTokenData.class)).thenReturn(data);
        when(userRepository.findById(UUID.fromString(userId)))
                .thenReturn(Optional.of(userWithRoles(userId, false, "user")));
        when(jwtUtil.generateToken(anyString(), any(), anyBoolean(), any())).thenReturn("new-access");
        doThrow(new RedisOperationException("redis down", null))
                .when(redisService).execScript(any(), anyList(), anyList());

        assertThatThrownBy(() -> service.rotate(oldToken))
                .isInstanceOf(RedisOperationException.class);
    }

    @Test
    void revoke_existingToken_deletesNamespaceAndUserIndex() {
        String token = "revoke-token";
        RefreshTokenService.RefreshTokenData data = new RefreshTokenService.RefreshTokenData(
                "user-6", List.of("user"), "session-1", false, null, null, null);
        when(redisService.getOrThrow(NAMESPACE, k(token), RefreshTokenService.RefreshTokenData.class)).thenReturn(data);
        when(redisService.delete(NAMESPACE, k(token))).thenReturn(true);

        service.revoke(token);

        // Session ended and index field removed before the token goes, so a grace replay in between gets nothing
        InOrder order = inOrder(redisService);
        order.verify(redisService).execInTransaction(ending("session-1", false));
        order.verify(redisService).hDelete(USER_IDX_NS, "user-6", k(token));
        order.verify(redisService).delete(NAMESPACE, k(token));
        verify(redisService, never()).getOrThrow(eq(CONSUMED_NS), anyString(), any());
    }

    @Test
    void shouldKeepTokenForRetryWhenLogoutFailsBeforeDelete() {
        String token = "revoke-fails";
        when(redisService.getOrThrow(NAMESPACE, k(token), RefreshTokenService.RefreshTokenData.class)).thenReturn(
                new RefreshTokenService.RefreshTokenData("user-6", List.of("user"), "session-1", false, null, null, null));
        doThrow(new RedisOperationException("redis down", null)).when(redisService).execInTransaction(anyList());

        assertThatThrownBy(() -> service.revoke(token)).isInstanceOf(RedisOperationException.class);
        verify(redisService, never()).delete(eq(NAMESPACE), anyString());
    }

    @Test
    void shouldRevokeSessionWhenRotationConsumesTokenDuringLogout() {
        String token = "revoke-races-rotation";
        when(redisService.getOrThrow(NAMESPACE, k(token), RefreshTokenService.RefreshTokenData.class)).thenReturn(
                new RefreshTokenService.RefreshTokenData("user-6", List.of("user"), "session-1", false, null, null, null));

        service.revoke(token);

        // The successor that rotation wrote can no longer rotate
        verify(redisService).execInTransaction(ending("session-1", true));
    }

    @Test
    void shouldDeleteOnlyTokenWhenLoggingOutWithUndecodableToken() {
        String token = "revoke-undecodable";
        when(redisService.delete(NAMESPACE, k(token))).thenReturn(true);

        service.revoke(token);

        verify(redisService, never()).getOrThrow(eq(CONSUMED_NS), anyString(), any());
        verify(redisService, never()).execInTransaction(any());
    }

    @Test
    void shouldRevokeAllSessionsWhenLoggingOutWithTokenWhoseLegacyMarkerHoldsUserId() {
        String token = "pre-deploy-rotated-then-logout";
        when(redisService.getOrThrow(NAMESPACE, k(token), RefreshTokenService.RefreshTokenData.class)).thenReturn(null);
        when(redisService.getOrThrow(CONSUMED_NS, k(token), String.class)).thenReturn("user-legacy");
        when(redisService.hasKeyOrThrow(USER_IDX_NS, "user-legacy")).thenReturn(true);
        when(redisService.hGetAllOrThrow(USER_IDX_NS, "user-legacy")).thenReturn(Map.of("t1", "session-1"));

        service.revoke(token);

        verify(redisService).execInTransaction(ending("session-1", true));
        verify(redisService).deleteAll(NAMESPACE, Set.of("t1"));
    }

    @Test
    void shouldRevokeSessionWhenLoggingOutWithAlreadyRotatedToken() {
        String token = "rotated-then-logout";
        when(redisService.getOrThrow(NAMESPACE, k(token), RefreshTokenService.RefreshTokenData.class)).thenReturn(null);
        when(redisService.getOrThrow(CONSUMED_NS, k(token), String.class)).thenReturn("session-1");

        service.revoke(token);

        // Its successor can no longer rotate, and no grace pair of the session is served
        verify(redisService).execInTransaction(ending("session-1", true));
    }

    @Test
    void shouldFlagSessionsBeforeDeletingOnlySnapshotTokensWhenRevokingAll() {
        // "1": an entry written before index values named the session; its token names it
        when(redisService.hGetAllOrThrow(USER_IDX_NS, "user-7")).thenReturn(Map.of("ta", "session-a", "tb", "1"));
        when(redisService.multiGetOrThrow(NAMESPACE, List.of("tb"), RefreshTokenService.RefreshTokenData.class))
                .thenReturn(List.of(new RefreshTokenService.RefreshTokenData("user-7", List.of("user"), "session-b", false, null, null, null)));
        when(redisService.multiGetOrThrow(CONSUMED_NS, List.of("tb"), String.class)).thenReturn(java.util.Arrays.asList((String) null));

        service.revokeAllForUser("user-7");

        InOrder order = inOrder(redisService);
        ArgumentCaptor<List<RedisService.RedisWrite>> writes = ArgumentCaptor.captor();
        order.verify(redisService).execInTransaction(writes.capture());
        order.verify(redisService).deleteAll(NAMESPACE, Set.of("ta", "tb"));
        order.verify(redisService).hDelete(eq(USER_IDX_NS), eq("user-7"), any(String[].class));
        assertThat(writes.getValue()).containsExactlyInAnyOrderElementsOf(
                java.util.stream.Stream.concat(ending("session-a", true).stream(), ending("session-b", true).stream()).toList());
        // An entry a concurrent rotation wrote after the snapshot is kept
        verify(redisService, never()).delete(eq(USER_IDX_NS), anyString());
    }

    @Test
    void shouldNameLegacySessionsAfterTheirKeyWhenRevokingAll() {
        // A null value: undecodable. "tc" has a live token issued before session ids, "td" rotated since the snapshot
        Map<String, Object> index = new java.util.HashMap<>();
        index.put("tc", null);
        index.put("td", "1");
        when(redisService.hGetAllOrThrow(USER_IDX_NS, "user-9")).thenReturn(index);
        when(redisService.multiGetOrThrow(eq(NAMESPACE), anyList(), eq(RefreshTokenService.RefreshTokenData.class)))
                .thenAnswer(inv -> ((List<String>) inv.getArgument(1)).stream().map(key -> "tc".equals(key)
                        ? new RefreshTokenService.RefreshTokenData("user-9", List.of("user"), null, false, null, null, null) : null).toList());
        when(redisService.multiGetOrThrow(eq(CONSUMED_NS), anyList(), eq(String.class)))
                .thenAnswer(inv -> ((List<String>) inv.getArgument(1)).stream().map(key -> "td".equals(key) ? "session-d" : null).toList());

        service.revokeAllForUser("user-9");

        // One MGET per namespace; the legacy live token's session is named after its key, as rotate names it
        ArgumentCaptor<List<RedisService.RedisWrite>> writes = ArgumentCaptor.captor();
        verify(redisService).execInTransaction(writes.capture());
        assertThat(writes.getValue()).containsExactlyInAnyOrderElementsOf(
                java.util.stream.Stream.concat(ending("tc", true).stream(), ending("session-d", true).stream()).toList());
    }

    @Test
    void shouldRejectWithoutRevokingWhenGracePairIsUndecodable() {
        String oldToken = "rotated-pair-undecodable";
        // The grace read answers null although the key is there: the pair no longer decodes
        when(redisService.getOrThrow(GRACE_NS, k(oldToken), RefreshTokenService.RotateResult.class)).thenReturn(null);
        when(redisService.getOrThrow(NAMESPACE, k(oldToken), RefreshTokenService.RefreshTokenData.class)).thenReturn(null);
        when(redisService.getOrThrow(CONSUMED_NS, k(oldToken), String.class)).thenReturn("session-1");
        when(redisService.hasKeyOrThrow(GRACE_NS, k(oldToken))).thenReturn(true);

        // Plain 401, not a reuse alert: the live successor stays
        assertThat(service.rotate(oldToken)).isNull();
        verify(redisService, never()).execInTransaction(any());
    }

    @Test
    void shouldSkipSessionWritesWhenRevokeAllNamesNoSession() {
        // A legacy entry whose token and consumed marker are both gone
        when(redisService.hGetAllOrThrow(USER_IDX_NS, "user-10")).thenReturn(Map.of("te", "1"));
        when(redisService.multiGetOrThrow(NAMESPACE, List.of("te"), RefreshTokenService.RefreshTokenData.class))
                .thenReturn(java.util.Arrays.asList((RefreshTokenService.RefreshTokenData) null));
        when(redisService.multiGetOrThrow(CONSUMED_NS, List.of("te"), String.class)).thenReturn(java.util.Arrays.asList((String) null));

        service.revokeAllForUser("user-10");

        verify(redisService, never()).execInTransaction(any());
        verify(redisService).hDelete(USER_IDX_NS, "user-10", "te");
    }

    // ── sessions ──────────────────────────────────────────────────────

    @Test
    void shouldSkipExpiredTokensAndSortByLastSeenWhenListingSessions() {
        Instant t = Instant.parse("2026-10-29T00:00:00Z");
        Map<String, Object> index = new java.util.LinkedHashMap<>();
        index.put("k-old", "s-old");
        index.put("k-gone", "s-gone");
        index.put("k-legacy", "1");
        index.put("k-new", "s-new");
        when(redisService.hGetAllOrThrow(USER_IDX_NS, "u1")).thenReturn(index);
        when(redisService.multiGetOrThrow(NAMESPACE, List.of("k-old", "k-gone", "k-legacy", "k-new"),
                RefreshTokenService.RefreshTokenData.class)).thenReturn(java.util.Arrays.asList(
                new RefreshTokenService.RefreshTokenData("u1", List.of("user"), "s-old", false, "A", t, t),
                null,
                new RefreshTokenService.RefreshTokenData("u1", List.of("user"), null, null, null, null, null),
                new RefreshTokenService.RefreshTokenData("u1", List.of("user"), "s-new", true, "B", t, t.plusSeconds(60))));

        // The legacy token has no session id or lastSeenAt: named after its key, listed last
        assertThat(service.listSessions("u1")).extracting(RefreshTokenService.RefreshTokenData::sessionId)
                .containsExactly("s-new", "s-old", "k-legacy");
    }

    @Test
    void shouldRevokeNothingWhenSessionIsNotTheUsers() {
        when(redisService.hGetAllOrThrow(USER_IDX_NS, "u1")).thenReturn(Map.of("k1", "s1"));

        assertThat(service.revokeSessions("u1", "someone-elses"::equals)).isZero();
        verify(redisService, never()).execInTransaction(any());
        verify(redisService, never()).deleteAll(anyString(), any());
    }

    @Test
    void shouldKeepTheCurrentSessionWhenRevokingOthers() {
        when(redisService.hGetAllOrThrow(USER_IDX_NS, "u1")).thenReturn(Map.of("k-here", "s-here", "k-a", "s-a", "k-b", "s-b"));

        assertThat(service.revokeSessions("u1", id -> !id.equals("s-here"))).isEqualTo(2);

        ArgumentCaptor<List<RedisService.RedisWrite>> writes = ArgumentCaptor.captor();
        InOrder order = inOrder(redisService);
        order.verify(redisService).execInTransaction(writes.capture());
        order.verify(redisService).deleteAll(NAMESPACE, Set.of("k-a", "k-b"));
        assertThat(writes.getValue()).containsExactlyInAnyOrderElementsOf(
                java.util.stream.Stream.concat(ending("s-a", true).stream(), ending("s-b", true).stream()).toList());
    }

    @Test
    void shouldPropagateExceptionWhenRevokeAllCannotReadIndex() {
        when(redisService.hGetAllOrThrow(USER_IDX_NS, "user-8")).thenThrow(new RedisOperationException("redis timeout", null));

        // An empty answer would revoke nothing and report success
        assertThatThrownBy(() -> service.revokeAllForUser("user-8")).isInstanceOf(RedisOperationException.class);
        verify(redisService, never()).deleteAll(anyString(), any());
    }
}
