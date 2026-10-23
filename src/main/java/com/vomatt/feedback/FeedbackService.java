package com.vomatt.feedback;

import java.time.Duration;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.common.redis.RedisService;
import com.vomatt.common.util.TextUtil;
import com.vomatt.entity.Feedback;
import com.vomatt.entity.User;
import com.vomatt.lookups.LookupService;
import com.vomatt.repository.FeedbackRepository;
import com.vomatt.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class FeedbackService {

    static final int DEFAULT_DAILY_LIMIT = 10;
    /** Admins override the default with lookup {@code config / feedback_daily_limit} via /api/admin/lookups. */
    static final String LIMIT_LOOKUP_KEY = "feedback_daily_limit";
    private static final String DAILY_COUNTER_NS = "feedback:daily";

    private final FeedbackRepository feedbackRepository;
    private final UserRepository userRepository;
    private final RedisService redisService;
    private final LookupService lookupService;

    public UUID createFeedback(String userId, String content) {
        String counterKey = userId + ":" + TextUtil.utcDay();
        // Checked first and counted only after the row is written, so a failed save does not spend the quota
        // (as the data export does). Concurrent requests may overshoot by a few; Redis down fails open.
        Long sent = redisService.get(DAILY_COUNTER_NS, counterKey, Long.class);
        if (sent != null && sent >= lookupService.configInt(LIMIT_LOOKUP_KEY, DEFAULT_DAILY_LIMIT)) {
            throw ApiException.tooManyRequests(MessageKey.COMMON_RATE_LIMITED);
        }
        User user = userRepository.findById(UUID.fromString(userId))
            .orElseThrow(() -> ApiException.notFound(MessageKey.USER_NOT_FOUND));
        UUID id = feedbackRepository.saveAndFlush(new Feedback(user, content)).getId();
        try {
            redisService.incrementAndExpire(DAILY_COUNTER_NS, counterKey, Duration.ofDays(1));
        } catch (RuntimeException e) {
            log.warn("Feedback rate limit count failed for user {}: {}", userId, e.getMessage());
        }
        return id;
    }
}
