package com.vomatt.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
    void shouldRejectWhenDailyLimitReached() {
        when(redisService.get(eq("feedback:daily"), anyString(), eq(Long.class))).thenReturn(3L);
        when(lookupService.configInt("feedback_daily_limit", FeedbackService.DEFAULT_DAILY_LIMIT)).thenReturn(3);

        assertThatThrownBy(() -> feedbackService.createFeedback(userId.toString(), "hi"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
        verifyNoInteractions(feedbackRepository);
    }

    @Test
    void shouldNotCountWhenSaveFails() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(User.builder().id(userId).username("alice").build()));
        when(feedbackRepository.saveAndFlush(any(Feedback.class))).thenThrow(new RuntimeException("db down"));

        assertThatThrownBy(() -> feedbackService.createFeedback(userId.toString(), "hi")).hasMessage("db down");
        verify(redisService, never()).incrementAndExpire(anyString(), anyString(), any());
    }

    @Test
    void shouldStoreFeedbackWhenRedisIsDown() {
        when(redisService.incrementAndExpire(eq("feedback:daily"), anyString(), any())).thenThrow(new RuntimeException("down"));
        when(userRepository.findById(userId)).thenReturn(Optional.of(User.builder().id(userId).username("alice").build()));
        when(feedbackRepository.saveAndFlush(any(Feedback.class))).thenAnswer(inv -> inv.getArgument(0));

        feedbackService.createFeedback(userId.toString(), "hi");

        verify(feedbackRepository).saveAndFlush(any(Feedback.class));
    }
}
