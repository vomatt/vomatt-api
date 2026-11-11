package com.vomatt.common.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

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
        ReflectionTestUtils.setField(limiter, "dailyLimit", 200);
    }

    @Test
    void shouldSkipAddressesMailedThisWeekAndRecordTheRestWhenClaiming() {
        when(repository.findSentSince(eq(senderId), any(), any())).thenReturn(List.of("a@example.com"));
        when(lookupService.configInt(anyString(), anyInt())).thenReturn(200);
        when(redisService.incrementAndExpire(eq("email:invitation"), anyString(), eq(1L), any())).thenReturn(1L);

        assertThat(limiter.claim(senderId, List.of("a@example.com", "b@example.com"))).containsExactly("b@example.com");
        verify(repository).recordSent(eq(senderId), eq(List.of("b@example.com")), any());
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
        verify(repository, never()).recordSent(any(), any(), any());
    }

    @Test
    void shouldTouchNothingWhenNoAddresses() {
        assertThat(limiter.claim(senderId, List.of())).isEmpty();
        verifyNoInteractions(repository, redisService);
    }
}
