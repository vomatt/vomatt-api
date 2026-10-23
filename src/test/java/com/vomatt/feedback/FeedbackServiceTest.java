package com.vomatt.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import com.vomatt.common.exception.ApiException;
import com.vomatt.common.redis.RedisService;
import com.vomatt.entity.Feedback;
import com.vomatt.entity.User;
import com.vomatt.lookups.LookupService;
import com.vomatt.lookups.dto.LookupDto;
import com.vomatt.repository.FeedbackRepository;
import com.vomatt.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("FeedbackService")
class FeedbackServiceTest {

    private final UUID userId = UUID.randomUUID();

    @Mock FeedbackRepository feedbackRepository;
    @Mock UserRepository userRepository;
    @Mock RedisService redisService;
    @Mock LookupService lookupService;
    @InjectMocks FeedbackService feedbackService;

    @Test
    void shouldRejectWhenDailyLimitExceeded() {
        when(redisService.incrementAndExpire(eq("feedback:daily"), anyString(), any()))
                .thenReturn((long) FeedbackService.DEFAULT_DAILY_LIMIT + 1);
        when(lookupService.getActiveByType("config")).thenReturn(List.of());

        assertThatThrownBy(() -> feedbackService.createFeedback(userId.toString(), "hi"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
        verifyNoInteractions(feedbackRepository);
    }

    @Test
    void shouldUseAdminLimitWhenLookupSet() {
        when(lookupService.getActiveByType("config")).thenReturn(List.of(
                new LookupDto("l1", "config", "feedback_daily_limit", 3, 0, null, null, true, null, false)));
        assertThat(feedbackService.dailyLimit()).isEqualTo(3);
    }

    @Test
    void shouldUseDefaultLimitWhenLookupInvalid() {
        when(lookupService.getActiveByType("config")).thenReturn(List.of(
                new LookupDto("l1", "config", "feedback_daily_limit", "lots", 0, null, null, true, null, false)));
        assertThat(feedbackService.dailyLimit()).isEqualTo(FeedbackService.DEFAULT_DAILY_LIMIT);
    }

    @Test
    void shouldStoreFeedbackWhenRedisIsDown() {
        when(redisService.incrementAndExpire(eq("feedback:daily"), anyString(), any())).thenThrow(new RuntimeException("down"));
        User user = User.builder().id(userId).username("alice").build();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(feedbackRepository.save(any(Feedback.class))).thenAnswer(inv -> inv.getArgument(0));

        feedbackService.createFeedback(userId.toString(), "hi");

        org.mockito.Mockito.verify(feedbackRepository).save(any(Feedback.class));
    }
}
