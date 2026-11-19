package com.vomatt.common.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.common.redis.RedisService;
import com.vomatt.lookups.LookupService;
import com.vomatt.repository.InvitationEmailRepository;

@ExtendWith(MockitoExtension.class)
class InvitationEmailLimiterTest {

    @Mock RedisService redisService;
    @Mock LookupService lookupService;
    @Mock InvitationEmailRepository repository;

    @InjectMocks
    InvitationEmailLimiter limiter;

    private final UUID senderId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        lenient().when(lookupService.configInts(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void shouldSkipLockedAddressesAndRecordTheRestWhenClaiming() {
        when(repository.findLocked(eq(senderId), any(), any())).thenReturn(Map.of("a@example.com", OffsetDateTime.now().plusDays(7)));
        when(lookupService.configInt(anyString(), anyInt())).thenReturn(200);
        when(redisService.incrementAndExpire(eq("email:invitation"), anyString(), eq(1L), any())).thenReturn(1L);

        assertThat(limiter.claim(senderId, List.of("a@example.com", "b@example.com"))).containsExactly("b@example.com");
        verify(repository).recordSent(eq(senderId), eq(List.of("b@example.com")), any(), any(), eq(3), any());
    }

    private void assertRefused(MessageKey key) {
        assertThatThrownBy(() -> limiter.claimOrThrow(senderId, "a@example.com"))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getMessageKey()).isEqualTo(key));
    }

    @Test
    void shouldRefuseWithoutTakingQuotaWhenSenderIsLockedOut() {
        when(repository.findLocked(eq(senderId), any(), any())).thenReturn(Map.of("a@example.com", OffsetDateTime.now().plusDays(7)));

        assertRefused(MessageKey.INVITATION_EMAIL_TOO_SOON);
        verifyNoInteractions(redisService);
        verify(repository, never()).recordSent(any(), any(), any(), any(), anyInt(), any());
    }

    @Test
    void shouldRefuseAndRecordNothingWhenResendIsOverDailyLimit() {
        when(lookupService.configInt(anyString(), anyInt())).thenReturn(200);
        when(redisService.incrementAndExpire(eq("email:invitation"), anyString(), eq(1L), any())).thenReturn(201L);

        assertRefused(MessageKey.INVITATION_EMAIL_DAILY_LIMIT);
        verify(repository, never()).recordSent(any(), any(), any(), any(), anyInt(), any());
    }

    @Test
    void shouldCountAgainstRealMemberWhenActingAsCommunity() {
        UUID memberId = UUID.randomUUID();
        var auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(senderId.toString(), null, List.of());
        auth.setDetails(new com.vomatt.common.security.ActingMember(memberId, senderId, com.vomatt.entity.CommunityRole.EDITOR));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);
        when(lookupService.configInt(anyString(), anyInt())).thenReturn(200);
        when(redisService.incrementAndExpire(eq("email:invitation"), org.mockito.ArgumentMatchers.startsWith(memberId + ":"), eq(1L), any()))
                .thenReturn(1L);
        try {
            assertThat(limiter.claim(senderId, List.of("a@example.com"))).containsExactly("a@example.com");
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
        verify(repository).recordSent(eq(memberId), eq(List.of("a@example.com")), any(), any(), anyInt(), any());
    }

    @Test
    void shouldClaimOnlyWhatQuotaLeavesWhenLimitIsNearlyReached() {
        when(lookupService.configInt(anyString(), anyInt())).thenReturn(3);
        // 2 already sent today, then 2 more counted: only one fits under 3
        when(redisService.incrementAndExpire(eq("email:invitation"), anyString(), eq(2L), any())).thenReturn(4L);

        assertThat(limiter.claim(senderId, List.of("a@example.com", "b@example.com"))).hasSize(1);
    }

    @Test
    void shouldClaimNothingWhenRedisIsDown() {
        when(lookupService.configInt(anyString(), anyInt())).thenReturn(200);
        when(redisService.incrementAndExpire(anyString(), anyString(), anyLong(), any())).thenThrow(new RuntimeException("down"));

        assertThat(limiter.claim(senderId, List.of("a@example.com"))).isEmpty();
        verify(repository, never()).recordSent(any(), any(), any(), any(), anyInt(), any());
    }

    @Test
    void shouldTouchNothingWhenNoAddresses() {
        assertThat(limiter.claim(senderId, List.of())).isEmpty();
        verifyNoInteractions(repository, redisService);
    }
}
