package com.vomatt.notifications;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.notifications.dto.UnreadCountResponse;
import com.vomatt.repository.NotificationReadRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Ended Notifications, derived on read (ADR 0003). The list itself is My Polls {@code status=ended},
 * which carries {@code unread}; this service only counts and marks them read.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class NotificationService {

    private final NotificationReadRepository notificationReadRepository;

    @Transactional(readOnly = true)
    public UnreadCountResponse countUnread(String userId) {
        return new UnreadCountResponse(
                notificationReadRepository.countUnread(UUID.fromString(userId), OffsetDateTime.now()));
    }

    /** Marks the Ended Notification of one Poll read; idempotent. 404 when the user has no such notification. */
    public void markRead(String userId, String voteId) {
        UUID userUuid = UUID.fromString(userId);
        UUID voteUuid = UUID.fromString(voteId);
        if (!notificationReadRepository.isNotified(userUuid, voteUuid, OffsetDateTime.now())) {
            throw ApiException.notFound(MessageKey.NOTIFICATION_NOT_FOUND);
        }
        notificationReadRepository.markRead(userUuid, voteUuid);
        log.info("User {} read ended notification of vote {}", userId, voteId);
    }
}
