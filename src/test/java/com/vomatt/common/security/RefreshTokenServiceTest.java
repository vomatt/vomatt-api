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

    /** 建立帶指定 roles 的 User（id 由 userId 解析）；banned=true 對應 active=false */
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
        // Step 4 重查 User：未封禁、roles 與 token 內一致
        when(userRepository.findById(UUID.fromString(userId)))
                .thenReturn(Optional.of(userWithRoles(userId, false, "user")));
        when(jwtUtil.generateToken(eq(userId), eq("user@example.com"), eq(List.of("user")))).thenReturn("new-access-token");

        RefreshTokenService.RotateResult result = service.rotate(oldToken);

        assertThat(result).isNotNull();
        assertThat(result.newAccessToken()).isEqualTo("new-access-token");
        assertThat(result.newRefreshToken()).isNotBlank().isNotEqualTo(oldToken);
        assertThat(result.data().userId()).isEqualTo(userId);
        assertThat(result.data().roles()).isEqualTo(List.of("user"));

        // GRACE 仍為單獨 fail-soft 寫入（race 碰面點），其餘正確性關鍵寫入收斂為單一 MULTI/EXEC（P1-#17）。
        InOrder inOrder = inOrder(redisService);
        inOrder.verify(redisService).set(eq(GRACE_NS), eq(oldToken), eq(result), eq(Duration.ofSeconds(10)));

        ArgumentCaptor<List<RedisService.RedisWrite>> writesCaptor = ArgumentCaptor.captor();
        inOrder.verify(redisService).execInTransaction(writesCaptor.capture());
        List<RedisService.RedisWrite> writes = writesCaptor.getValue();
        assertThat(writes).containsExactly(
                RedisService.RedisWrite.set(CONSUMED_NS, oldToken, userId, Duration.ofSeconds(60)),
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

        // 第一個進入的 thread 拿到 data；第二個進入的 thread 拿到 null（GETDEL 已被消耗）
        AtomicBoolean getAndDeleteConsumed = new AtomicBoolean(false);
        when(redisService.getAndDelete(NAMESPACE, oldToken, RefreshTokenService.RefreshTokenData.class))
                .thenAnswer(inv -> getAndDeleteConsumed.compareAndSet(false, true) ? data : null);

        // Step 4 重查 User：未封禁
        when(userRepository.findById(UUID.fromString(userId)))
                .thenReturn(Optional.of(userWithRoles(userId, false, "user")));

        // jwtUtil 對同 data 回固定 token（模擬決定性 access token；實際 JWT 因 iat 不同會有差，但 grace cache 解決這個）
        when(jwtUtil.generateToken(eq(userId), eq("race@example.com"), eq(List.of("user"))))
                .thenReturn("concurrent-access-token");

        // grace cache 行為：第一次寫入後，後續查詢回該結果
        AtomicReference<RefreshTokenService.RotateResult> graceStore = new AtomicReference<>();
        when(redisService.get(eq(GRACE_NS), eq(oldToken), eq(RefreshTokenService.RotateResult.class)))
                .thenAnswer(inv -> graceStore.get());
        // capture grace set
        // 不能直接 stub void set，改用 doAnswer
        org.mockito.Mockito.doAnswer(inv -> {
            graceStore.set(inv.getArgument(2));
            return null;
        }).when(redisService).set(eq(GRACE_NS), eq(oldToken), any(RefreshTokenService.RotateResult.class), any(Duration.class));

        // 第二 caller 走 consumed 路徑：getAndDelete null → CONSUMED_NS 也回 null 才能走 grace
        // 為求 grace 命中順序正確，CONSUMED_NS 查詢必須在 grace check 之後；但我們的 rotate 流程是先 grace 後 getAndDelete
        // 所以第二 caller：grace check（命中 graceStore，因為第一 caller 已寫入）→ 直接 return
        // 不會走到 getAndDelete／consumed

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
                // 小延遲確保 f1 先寫入 grace
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

    // ── rotate: Step 4 重查 User —— 封禁阻擋 ────────────────────────

    @Test
    void rotate_bannedUser_returnsNullAndRevokesAll() {
        String oldToken = "banned-rotate-token";
        String userId = UUID.randomUUID().toString();
        RefreshTokenService.RefreshTokenData data = new RefreshTokenService.RefreshTokenData(
                userId, "banned@example.com", List.of("admin"));

        when(redisService.get(GRACE_NS, oldToken, RefreshTokenService.RotateResult.class)).thenReturn(null);
        when(redisService.getAndDelete(NAMESPACE, oldToken, RefreshTokenService.RefreshTokenData.class)).thenReturn(data);
        when(redisService.hGetAll(USER_IDX_NS, userId)).thenReturn(Map.of("t1", "1"));
        // 重查回停權 User
        when(userRepository.findById(UUID.fromString(userId)))
                .thenReturn(Optional.of(userWithRoles(userId, true, "admin")));

        RefreshTokenService.RotateResult result = service.rotate(oldToken);

        assertThat(result).isNull();
        // 封禁者須撤銷所有 session
        verify(redisService).delete(NAMESPACE, "t1");
        verify(redisService).delete(USER_IDX_NS, userId);
        // 不得簽發新 access token
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

    // ── rotate: Step 4 重查 User —— roles 以最新為準 ────────────────

    @Test
    void rotate_rolesChanged_signsNewTokenWithFreshRoles() {
        String oldToken = "stale-roles-token";
        String userId = UUID.randomUUID().toString();
        // token 內舊 roles 為 admin（被拔權前簽發）
        RefreshTokenService.RefreshTokenData data = new RefreshTokenService.RefreshTokenData(
                userId, "demoted@example.com", List.of("admin"));

        when(redisService.get(GRACE_NS, oldToken, RefreshTokenService.RotateResult.class)).thenReturn(null);
        when(redisService.getAndDelete(NAMESPACE, oldToken, RefreshTokenService.RefreshTokenData.class)).thenReturn(data);
        // 現行 User roles 已降為 user
        when(userRepository.findById(UUID.fromString(userId)))
                .thenReturn(Optional.of(userWithRoles(userId, false, "user")));
        when(jwtUtil.generateToken(eq(userId), eq("demoted@example.com"), any())).thenReturn("fresh-access");

        RefreshTokenService.RotateResult result = service.rotate(oldToken);

        assertThat(result).isNotNull();
        assertThat(result.newAccessToken()).isEqualTo("fresh-access");

        // access token 必須以 fresh roles（user）簽發，而非舊 roles（admin）
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> rolesCaptor = ArgumentCaptor.forClass(List.class);
        verify(jwtUtil).generateToken(eq(userId), eq("demoted@example.com"), rolesCaptor.capture());
        assertThat(rolesCaptor.getValue()).containsExactly("user");

        // 寫入 NAMESPACE 的新 RefreshTokenData 也須用 fresh roles（現由 execInTransaction 的 RedisWrite 帶入）
        ArgumentCaptor<List<RedisService.RedisWrite>> writesCaptor = ArgumentCaptor.captor();
        verify(redisService).execInTransaction(writesCaptor.capture());
        RefreshTokenService.RefreshTokenData namespaceData = writesCaptor.getValue().stream()
                .filter(w -> w.op() == RedisService.RedisWrite.Op.SET && NAMESPACE.equals(w.cacheName()))
                .map(w -> (RefreshTokenService.RefreshTokenData) w.value())
                .findFirst().orElseThrow();
        assertThat(namespaceData.roles()).containsExactly("user");

        // RotateResult.data 亦為 fresh
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

    // ── 正確性關鍵寫入失敗須拋出（P5-#3 / P5-#8）─────────────────────────

    @Test
    void generate_whenUserIndexWriteFails_propagatesException() {
        doThrow(new RedisOperationException("redis down", null))
                .when(redisService).hSetOrThrow(eq(USER_IDX_NS), anyString(), anyString(), any(), any());

        assertThatThrownBy(() -> service.generate("user-9", "g@example.com", List.of("user")))
                .isInstanceOf(RedisOperationException.class);
    }

    @Test
    void rotate_whenTransactionFails_propagatesException() {
        // P1-#17：正確性關鍵寫入收斂為單一 MULTI/EXEC；交易失敗須整批拋出（fail-fast）。
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
        when(redisService.get(NAMESPACE, token, RefreshTokenService.RefreshTokenData.class)).thenReturn(data);
        when(redisService.delete(NAMESPACE, token)).thenReturn(true);

        service.revoke(token);

        verify(redisService).delete(NAMESPACE, token);
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
