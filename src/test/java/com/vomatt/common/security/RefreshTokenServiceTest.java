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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    private static final String NAMESPACE = "refresh";
    private static final String CONSUMED_NS = "refresh:consumed";
    private static final String GRACE_NS = "refresh:grace";
    private static final String USER_IDX_NS = "refresh:idx";
    private static final String REVOKED_SESSION_NS = "refresh:revoked-session";

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

    /** A grace pair of an active user's live session. */
    private RefreshTokenService.RotateResult standingPair(String access, String refresh) {
        String userId = UUID.randomUUID().toString();
        RefreshTokenService.RotateResult pair = new RefreshTokenService.RotateResult(access, refresh,
                new RefreshTokenService.RefreshTokenData(userId, List.of("user"), "session-1", false));
        when(userRepository.findById(UUID.fromString(userId))).thenReturn(Optional.of(userWithRoles(userId, false)));
        return pair;
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
        verify(jwtUtil, never()).generateToken(anyString(), any(), anyBoolean());
    }

    // ── rotate: happy path ────────────────────────────────────────────

    @Test
    void shouldKeepAdminScopeWhenRotatingAdminConsoleSession() {
        String oldToken = "admin-refresh-token";
        String userId = UUID.randomUUID().toString();
        when(redisService.getOrThrow(GRACE_NS, k(oldToken), RefreshTokenService.RotateResult.class)).thenReturn(null);
        when(redisService.getOrThrow(NAMESPACE, k(oldToken), RefreshTokenService.RefreshTokenData.class))
                .thenReturn(new RefreshTokenService.RefreshTokenData(userId, List.of("admin"), "session-1", true));
        when(userRepository.findById(UUID.fromString(userId))).thenReturn(Optional.of(userWithRoles(userId, false, "admin")));
        when(redisService.execScript(any(), anyList(), anyList())).thenReturn(1L);
        when(jwtUtil.generateToken(eq(userId), eq(List.of("admin")), eq(true))).thenReturn("admin-access");

        RefreshTokenService.RotateResult result = service.rotate(oldToken);

        assertThat(result.newAccessToken()).isEqualTo("admin-access");
        assertThat(result.data().grantsAdmin()).isTrue();
    }

    @Test
    void rotate_happyPath_writesGraceAndConsumedAndNewToken() {
        String oldToken = "old-refresh-token";
        String userId = UUID.randomUUID().toString();
        RefreshTokenService.RefreshTokenData data = new RefreshTokenService.RefreshTokenData(
                userId, List.of("user"), "session-1", false);

        when(redisService.getOrThrow(GRACE_NS, k(oldToken), RefreshTokenService.RotateResult.class)).thenReturn(null);
        when(redisService.getOrThrow(NAMESPACE, k(oldToken), RefreshTokenService.RefreshTokenData.class)).thenReturn(data);
        // Step 4 reload User: not banned, roles match the token
        when(userRepository.findById(UUID.fromString(userId)))
                .thenReturn(Optional.of(userWithRoles(userId, false, "user")));
        when(redisService.execScript(any(), anyList(), anyList())).thenReturn(1L);
        when(jwtUtil.generateToken(eq(userId), eq(List.of("user")), eq(false))).thenReturn("new-access-token");

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
        verify(jwtUtil, never()).generateToken(anyString(), any(), anyBoolean());
    }

    @Test
    void shouldRejectGracePairWhenItsSessionWasRevokedOnReuse() {
        String oldToken = "stolen-just-rotated";
        RefreshTokenService.RotateResult cached = new RefreshTokenService.RotateResult("access", "live-refresh",
                new RefreshTokenService.RefreshTokenData(UUID.randomUUID().toString(), List.of("user"), "session-1", false));
        when(redisService.getOrThrow(GRACE_NS, k(oldToken), RefreshTokenService.RotateResult.class)).thenReturn(cached);
        when(redisService.hasKeyOrThrow(REVOKED_SESSION_NS, "session-1")).thenReturn(true);

        assertThat(service.rotate(oldToken)).isNull();
    }

    @Test
    void shouldRejectGracePairWhenUserIsSuspended() {
        String oldToken = "stolen-just-rotated";
        String userId = UUID.randomUUID().toString();
        RefreshTokenService.RotateResult cached = new RefreshTokenService.RotateResult("access", "live-refresh",
                new RefreshTokenService.RefreshTokenData(userId, List.of("user"), "session-1", false));
        when(redisService.getOrThrow(GRACE_NS, k(oldToken), RefreshTokenService.RotateResult.class)).thenReturn(cached);
        when(userRepository.findById(UUID.fromString(userId))).thenReturn(Optional.of(userWithRoles(userId, true)));

        assertThat(service.rotate(oldToken)).isNull();
    }

    @Test
    void shouldReturnNullWhenLosingRaceToALogout() {
        // The winner's pair is in grace, but its session was logged out before this caller read it
        String oldToken = "lost-race-then-logout";
        String userId = UUID.randomUUID().toString();
        RefreshTokenService.RotateResult winner = new RefreshTokenService.RotateResult("access", "logged-out",
                new RefreshTokenService.RefreshTokenData(userId, List.of("user"), "session-1", false));
        when(redisService.getOrThrow(GRACE_NS, k(oldToken), RefreshTokenService.RotateResult.class)).thenReturn(null, winner);
        when(redisService.getOrThrow(NAMESPACE, k(oldToken), RefreshTokenService.RefreshTokenData.class))
                .thenReturn(new RefreshTokenService.RefreshTokenData(userId, List.of("user"), "session-1", false));
        when(userRepository.findById(UUID.fromString(userId))).thenReturn(Optional.of(userWithRoles(userId, false)));
        when(redisService.execScript(any(), anyList(), anyList())).thenReturn(0L);
        when(redisService.hasKeyOrThrow(REVOKED_SESSION_NS, "session-1")).thenReturn(true);

        assertThat(service.rotate(oldToken)).isNull();
        // The user loaded for the rotation is reused for the grace check
        verify(userRepository).findById(UUID.fromString(userId));
    }

    @Test
    void shouldPropagateExceptionWhenLegacyMarkerCheckFails() {
        String oldToken = "replayed-during-outage";
        when(redisService.getOrThrow(GRACE_NS, k(oldToken), RefreshTokenService.RotateResult.class)).thenReturn(null);
        when(redisService.getOrThrow(NAMESPACE, k(oldToken), RefreshTokenService.RefreshTokenData.class)).thenReturn(null);
        when(redisService.getOrThrow(CONSUMED_NS, k(oldToken), String.class)).thenReturn("session-1");
        when(redisService.hasKeyOrThrow(USER_IDX_NS, "session-1")).thenThrow(new RedisOperationException("redis timeout", null));

        // Read as "no legacy marker" it would revoke the wrong thing; a 503 revokes nothing
        assertThatThrownBy(() -> service.rotate(oldToken)).isInstanceOf(RedisOperationException.class);
        verify(redisService, never()).setOrThrow(anyString(), anyString(), any(), any());
        verify(redisService, never()).hGetAll(anyString(), anyString());
    }

    // ── rotate: concurrent race ───────────────────────────────────────

    @Test
    void shouldReturnSamePairWhenSecondCallerArrivesWhileFirstIsStillRotating() throws Exception {
        String oldToken = "mid-rotation-token";
        String userId = UUID.randomUUID().toString();
        RefreshTokenService.RefreshTokenData data = new RefreshTokenService.RefreshTokenData(
                userId, List.of("user"), "session-1", false);

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
        when(jwtUtil.generateToken(eq(userId), eq(List.of("user")), eq(false))).thenReturn("access");

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
                .thenReturn(new RefreshTokenService.RefreshTokenData(userId, List.of("user"), "session-1", false));
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
        when(redisService.hasKeyOrThrow(USER_IDX_NS, "session-1")).thenReturn(false);

        assertThatThrownBy(() -> service.rotate(oldToken)).isInstanceOf(RefreshTokenReuseException.class);

        verify(redisService).setOrThrow(REVOKED_SESSION_NS, "session-1", "1", Duration.ofDays(30));
        // The user's other sessions survive
        verify(redisService, never()).hGetAll(anyString(), anyString());
        verify(redisService, never()).delete(eq(USER_IDX_NS), anyString());
    }

    @Test
    void shouldRejectRotationWhenSessionRevokedAfterReuse() {
        String liveToken = "thief-live-token";
        String userId = UUID.randomUUID().toString();
        when(redisService.getOrThrow(GRACE_NS, k(liveToken), RefreshTokenService.RotateResult.class)).thenReturn(null);
        when(redisService.getOrThrow(NAMESPACE, k(liveToken), RefreshTokenService.RefreshTokenData.class))
                .thenReturn(new RefreshTokenService.RefreshTokenData(userId, List.of("user"), "session-1", false));
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
                .thenReturn(new RefreshTokenService.RefreshTokenData(userId, List.of("user"), null, false));
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
        when(redisService.hasKeyOrThrow(USER_IDX_NS, "user-legacy")).thenReturn(true);
        when(redisService.hGetAll(USER_IDX_NS, "user-legacy")).thenReturn(Map.of("t1", "1"));

        assertThatThrownBy(() -> service.rotate(oldToken)).isInstanceOf(RefreshTokenReuseException.class);

        verify(redisService).delete(NAMESPACE, "t1");
        verify(redisService).delete(USER_IDX_NS, "user-legacy");
    }

    // ── rotate: Step 4 reload User -- ban blocks ────────────────────────

    @Test
    void rotate_bannedUser_returnsNullAndRevokesAll() {
        String oldToken = "banned-rotate-token";
        String userId = UUID.randomUUID().toString();
        RefreshTokenService.RefreshTokenData data = new RefreshTokenService.RefreshTokenData(
                userId, List.of("admin"), "session-1", false);

        when(redisService.getOrThrow(GRACE_NS, k(oldToken), RefreshTokenService.RotateResult.class)).thenReturn(null);
        when(redisService.getOrThrow(NAMESPACE, k(oldToken), RefreshTokenService.RefreshTokenData.class)).thenReturn(data);
        when(redisService.hGetAll(USER_IDX_NS, userId)).thenReturn(Map.of("t1", "1"));
        // Reload returns a suspended User
        when(userRepository.findById(UUID.fromString(userId)))
                .thenReturn(Optional.of(userWithRoles(userId, true, "admin")));

        RefreshTokenService.RotateResult result = service.rotate(oldToken);

        assertThat(result).isNull();
        // The old token goes even if the user index doesn't list it
        verify(redisService).delete(NAMESPACE, k(oldToken));
        // Banned users must have all sessions revoked
        verify(redisService).delete(NAMESPACE, "t1");
        verify(redisService).delete(USER_IDX_NS, userId);
        // Must not issue a new access token
        verify(jwtUtil, never()).generateToken(anyString(), any(), anyBoolean());
    }

    @Test
    void rotate_userNotFound_returnsNullAndRevokesAll() {
        String oldToken = "ghost-rotate-token";
        String userId = UUID.randomUUID().toString();
        RefreshTokenService.RefreshTokenData data = new RefreshTokenService.RefreshTokenData(
                userId, List.of("user"), "session-1", false);

        when(redisService.getOrThrow(GRACE_NS, k(oldToken), RefreshTokenService.RotateResult.class)).thenReturn(null);
        when(redisService.getOrThrow(NAMESPACE, k(oldToken), RefreshTokenService.RefreshTokenData.class)).thenReturn(data);
        when(redisService.hGetAll(USER_IDX_NS, userId)).thenReturn(Map.of());
        when(userRepository.findById(UUID.fromString(userId))).thenReturn(Optional.empty());

        RefreshTokenService.RotateResult result = service.rotate(oldToken);

        assertThat(result).isNull();
        verify(redisService).delete(USER_IDX_NS, userId);
        verify(jwtUtil, never()).generateToken(anyString(), any(), anyBoolean());
    }

    // ── rotate: Step 4 reload User -- latest roles win ────────────────

    @Test
    void rotate_rolesChanged_signsNewTokenWithFreshRoles() {
        String oldToken = "stale-roles-token";
        String userId = UUID.randomUUID().toString();
        // Old roles in the token are admin (issued before the role was revoked)
        RefreshTokenService.RefreshTokenData data = new RefreshTokenService.RefreshTokenData(
                userId, List.of("admin"), "session-1", false);

        when(redisService.getOrThrow(GRACE_NS, k(oldToken), RefreshTokenService.RotateResult.class)).thenReturn(null);
        when(redisService.getOrThrow(NAMESPACE, k(oldToken), RefreshTokenService.RefreshTokenData.class)).thenReturn(data);
        // Current User roles have been downgraded to user
        when(userRepository.findById(UUID.fromString(userId)))
                .thenReturn(Optional.of(userWithRoles(userId, false, "user")));
        when(redisService.execScript(any(), anyList(), anyList())).thenReturn(1L);
        when(jwtUtil.generateToken(eq(userId), any(), eq(false))).thenReturn("fresh-access");

        RefreshTokenService.RotateResult result = service.rotate(oldToken);

        assertThat(result).isNotNull();
        assertThat(result.newAccessToken()).isEqualTo("fresh-access");

        // Access token must be issued with fresh roles (user), not old roles (admin)
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> rolesCaptor = ArgumentCaptor.forClass(List.class);
        verify(jwtUtil).generateToken(eq(userId), rolesCaptor.capture(), eq(false));
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

    // ── generate / revoke / revokeAllForUser unchanged ────────────────

    @Test
    @SuppressWarnings("unchecked")
    void shouldRenewUserIndexTtlWhenGeneratingToken() {
        String token = service.generate("user-5", List.of("user"), false);

        assertThat(token).isNotBlank();
        ArgumentCaptor<List<RedisService.RedisWrite>> writes = ArgumentCaptor.forClass(List.class);
        verify(redisService).execInTransaction(writes.capture());
        RefreshTokenService.RefreshTokenData stored = (RefreshTokenService.RefreshTokenData) writes.getValue().get(0).value();
        assertThat(stored.sessionId()).isNotBlank();
        assertThat(writes.getValue()).containsExactly(
                RedisService.RedisWrite.set(NAMESPACE, k(token),
                        new RefreshTokenService.RefreshTokenData("user-5", List.of("user"), stored.sessionId(), false), Duration.ofDays(30)),
                RedisService.RedisWrite.hput(USER_IDX_NS, "user-5", k(token), stored.sessionId()),
                // The index TTL is renewed on every issue, not only when the index is first created
                RedisService.RedisWrite.expire(USER_IDX_NS, "user-5", Duration.ofDays(30)));
    }

    // ── Correctness-critical write failures must throw (P5-#3 / P5-#8) ─────────────────────────

    @Test
    void generate_whenUserIndexWriteFails_propagatesException() {
        doThrow(new RedisOperationException("redis down", null))
                .when(redisService).execInTransaction(any());

        assertThatThrownBy(() -> service.generate("user-9", List.of("user"), false))
                .isInstanceOf(RedisOperationException.class);
    }

    @Test
    void shouldPropagateExceptionWhenCommitFails() {
        // Every rotation write is one atomic Lua step; a Redis failure must throw for the whole batch (fail-fast).
        String oldToken = "old-token";
        String userId = UUID.randomUUID().toString();
        RefreshTokenService.RefreshTokenData data = new RefreshTokenService.RefreshTokenData(
                userId, List.of("user"), "session-1", false);
        when(redisService.getOrThrow(GRACE_NS, k(oldToken), RefreshTokenService.RotateResult.class)).thenReturn(null);
        when(redisService.getOrThrow(NAMESPACE, k(oldToken), RefreshTokenService.RefreshTokenData.class)).thenReturn(data);
        when(userRepository.findById(UUID.fromString(userId)))
                .thenReturn(Optional.of(userWithRoles(userId, false, "user")));
        when(jwtUtil.generateToken(anyString(), any(), anyBoolean())).thenReturn("new-access");
        doThrow(new RedisOperationException("redis down", null))
                .when(redisService).execScript(any(), anyList(), anyList());

        assertThatThrownBy(() -> service.rotate(oldToken))
                .isInstanceOf(RedisOperationException.class);
    }

    @Test
    void revoke_existingToken_deletesNamespaceAndUserIndex() {
        String token = "revoke-token";
        RefreshTokenService.RefreshTokenData data = new RefreshTokenService.RefreshTokenData(
                "user-6", List.of("user"), "session-1", false);
        when(redisService.getAndDelete(NAMESPACE, k(token), RefreshTokenService.RefreshTokenData.class)).thenReturn(data);

        service.revoke(token);

        verify(redisService).hDelete(USER_IDX_NS, "user-6", k(token));
        // Ends the session for the grace window, so a grace pair of it is no longer handed out
        verify(redisService).setIfAbsent(REVOKED_SESSION_NS, "session-1", "1", Duration.ofSeconds(10));
    }

    @Test
    void revokeAllForUser_deletesEveryActiveToken() {
        // "1": an entry written before index values named the session
        when(redisService.hGetAll(USER_IDX_NS, "user-7")).thenReturn(Map.of("ta", "session-a", "tb", "1"));

        service.revokeAllForUser("user-7");

        verify(redisService).setIfAbsent(REVOKED_SESSION_NS, "session-a", "1", Duration.ofSeconds(10));
        verify(redisService, never()).setIfAbsent(REVOKED_SESSION_NS, "1", "1", Duration.ofSeconds(10));
        verify(redisService).delete(NAMESPACE, "ta");
        verify(redisService).delete(NAMESPACE, "tb");
        verify(redisService).delete(USER_IDX_NS, "user-7");
    }
}
