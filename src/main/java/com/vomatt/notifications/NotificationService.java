package com.vomatt.notifications;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.notifications.dto.UnreadCountResponse;
import com.vomatt.repository.NotificationReadRepository;
import com.vomatt.votes.VoteService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Invitations and Ended Notifications, derived on read (ADR 0003). The list itself is My Polls, whose items carry
 * {@code unread}; this service only counts and marks them read.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class NotificationService {

    private final NotificationReadRepository notificationReadRepository;
    private final VoteService voteService;

    @Transactional(readOnly = true)
    public UnreadCountResponse countUnread(String userId) {
        return new UnreadCountResponse(
                notificationReadRepository.countUnread(UUID.fromString(userId), OffsetDateTime.now()));
    }

    /** Marks the current notification (Invitation or Ended) of one Poll read; idempotent. 404 when the user has none. */
    public void markRead(String userId, String voteId) {
        UUID userUuid = UUID.fromString(userId);
        UUID voteUuid = voteService.findAccessibleVote(UUID.fromString(voteId), userId).getId();
        OffsetDateTime now = OffsetDateTime.now();
        if (!notificationReadRepository.isNotified(userUuid, voteUuid, now)) {
            throw ApiException.notFound(MessageKey.NOTIFICATION_NOT_FOUND);
        }
        notificationReadRepository.markRead(userUuid, voteUuid, now);
        log.info("User {} read notification of vote {}", userId, voteId);
    }
}
