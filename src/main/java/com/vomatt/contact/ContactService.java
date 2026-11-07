package com.vomatt.contact;

import java.time.Duration;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.common.redis.RedisService;
import com.vomatt.common.util.TextUtil;
import com.vomatt.contact.dto.CreateContactMessageRequest;
import com.vomatt.entity.ContactMessage;
import com.vomatt.lookups.LookupService;
import com.vomatt.repository.ContactMessageRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ContactService {

    static final int DEFAULT_DAILY_LIMIT = 5;
    /** Admins override the default with lookup {@code config / contact_daily_limit} via /api/admin/lookups. */
    static final String LIMIT_LOOKUP_KEY = "contact_daily_limit";
    private static final String DAILY_COUNTER_NS = "contact:daily";

    private final ContactMessageRepository contactMessageRepository;
    private final RedisService redisService;
    private final LookupService lookupService;

    /** Senders are anonymous, so the daily limit is per client IP. */
    public UUID createMessage(String clientIp, CreateContactMessageRequest request) {
        String counterKey = clientIp + ":" + TextUtil.utcDay();
        // Checked first and counted only after the row is written, as feedback does. Concurrent requests may
        // overshoot by a few; Redis down fails open.
        Long sent = redisService.get(DAILY_COUNTER_NS, counterKey, Long.class);
        if (sent != null && sent >= lookupService.configInt(LIMIT_LOOKUP_KEY, DEFAULT_DAILY_LIMIT)) {
            throw ApiException.tooManyRequests(MessageKey.COMMON_RATE_LIMITED);
        }
        ContactMessage message = new ContactMessage(request.getName().trim(), request.getEmail().trim(),
                request.getMessage().trim(), request.getLocale());
        UUID id = contactMessageRepository.saveAndFlush(message).getId();
        try {
            redisService.incrementAndExpire(DAILY_COUNTER_NS, counterKey, Duration.ofDays(1));
        } catch (RuntimeException e) {
            log.warn("Contact rate limit count failed for {}: {}", clientIp, e.getMessage());
        }
        return id;
    }
}
