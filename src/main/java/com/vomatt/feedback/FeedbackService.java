package com.vomatt.feedback;

import java.time.Duration;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vomatt.admin.dto.AdminFeedbackResponse;
import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.common.redis.RedisService;
import com.vomatt.common.response.PageResponse;
import com.vomatt.common.util.TextUtil;
import com.vomatt.entity.Feedback;
import com.vomatt.entity.User;
import com.vomatt.lookups.LookupService;
import com.vomatt.lookups.dto.LookupDto;
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
    /** Admins override the default with lookup {@code config / feedback_daily_limit} (a positive number) via /api/admin/lookups. */
    static final String LIMIT_LOOKUP_TYPE = "config";
    static final String LIMIT_LOOKUP_KEY = "feedback_daily_limit";
    private static final String DAILY_COUNTER_NS = "feedback:daily";

    private final FeedbackRepository feedbackRepository;
    private final UserRepository userRepository;
    private final RedisService redisService;
    private final LookupService lookupService;

    public UUID createFeedback(String userId, String content) {
        checkDailyLimit(userId);
        User user = userRepository.findById(UUID.fromString(userId))
            .orElseThrow(() -> ApiException.notFound(MessageKey.USER_NOT_FOUND));
        return feedbackRepository.save(new Feedback(user, content)).getId();
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminFeedbackResponse> listFeedback(UUID userId, Integer page, Integer limit) {
        return PageResponse.from(feedbackRepository.findPage(userId, PageResponse.pageable(page, limit)),
                f -> new AdminFeedbackResponse(f.getId().toString(), f.getUser().getId().toString(),
                        f.getUser().getUsername(), f.getContent(), f.getCreatedAt()));
    }

    // Fails open like the Ballot rate limit: losing Redis should not lose feedback
    private void checkDailyLimit(String userId) {
        long count;
        try {
            count = redisService.incrementAndExpire(DAILY_COUNTER_NS, userId + ":" + TextUtil.utcDay(), Duration.ofDays(1));
        } catch (RuntimeException e) {
            log.warn("Feedback rate limit check failed for user {}: {}", userId, e.getMessage());
            return;
        }
        if (count > dailyLimit()) {
            throw ApiException.tooManyRequests(MessageKey.COMMON_RATE_LIMITED);
        }
    }

    // Missing, inactive or non-positive override falls back to the default
    int dailyLimit() {
        return lookupService.getActiveByType(LIMIT_LOOKUP_TYPE).stream()
                .filter(l -> LIMIT_LOOKUP_KEY.equals(l.lookupKey()))
                .map(LookupDto::lookupValue)
                .filter(v -> v instanceof Number n && n.intValue() > 0)
                .map(v -> ((Number) v).intValue())
                .findFirst()
                .orElse(DEFAULT_DAILY_LIMIT);
    }
}
