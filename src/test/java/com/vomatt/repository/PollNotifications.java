package com.vomatt.repository;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.vomatt.notifications.NotificationPreference;

/** A user's notifications about one Poll (its Invitation, then its Result), for tests of the Poll lifecycle. */
public record PollNotifications(NotificationRepository repository) {

    /** Unread notifications with every preference at its default. */
    public long countUnread(UUID userId, OffsetDateTime now) {
        return repository.countUnread(userId, NotificationPreference.enabledKinds(NotificationPreference.defaults(), null), now);
    }

    public boolean isNotified(UUID userId, UUID voteId, OffsetDateTime now) {
        return repository.findCurrentForPoll(userId, voteId, now).isPresent();
    }

    /** Marks the Poll's current notification read. */
    public void markRead(UUID userId, UUID voteId, OffsetDateTime now) {
        var current = repository.findCurrentForPoll(userId, voteId, now).orElseThrow();
        repository.markRead(userId, current.kind(), current.sourceId(), now);
    }
}
