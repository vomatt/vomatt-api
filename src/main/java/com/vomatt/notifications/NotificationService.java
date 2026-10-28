package com.vomatt.notifications;

import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.common.response.Cursor;
import com.vomatt.common.response.CursorResponse;
import com.vomatt.common.util.UUIDv7Generator;
import com.vomatt.entity.UserPreference;
import com.vomatt.entity.Vote;
import com.vomatt.entity.VoteComment;
import com.vomatt.entity.VoteOption;
import com.vomatt.notifications.dto.NotificationPreferencesResponse;
import com.vomatt.notifications.dto.NotificationResponse;
import com.vomatt.notifications.dto.UnreadCountResponse;
import com.vomatt.notifications.dto.UpdateNotificationPreferencesRequest;
import com.vomatt.repository.BallotSelection;
import com.vomatt.repository.NotificationRepository;
import com.vomatt.repository.NotificationRepository.NotificationRow;
import com.vomatt.repository.UserPreferenceRepository;
import com.vomatt.repository.UserRepository;
import com.vomatt.repository.UserVoteRepository;
import com.vomatt.repository.VoteCommentRepository;
import com.vomatt.repository.VoteRepository;
import com.vomatt.users.UserService;
import com.vomatt.users.dto.FollowUserDto;
import com.vomatt.votes.VoteMapper;
import com.vomatt.votes.VoteService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The notification feed, its read state and the caller's switches. Notifications are derived on read (ADR 0003,
 * ADR 0005): {@link NotificationRepository} finds them, this service applies the preferences and fills in the actor,
 * Poll and Comment of each.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class NotificationService {

    static final int EXCERPT_LENGTH = 120;

    private final NotificationRepository notificationRepository;
    private final UserPreferenceRepository preferenceRepository;
    private final UserRepository userRepository;
    private final VoteRepository voteRepository;
    private final VoteCommentRepository commentRepository;
    private final UserVoteRepository userVoteRepository;
    private final UserService userService;
    private final VoteService voteService;
    private final VoteMapper voteMapper;

    /** The caller's feed, newest first, of the kinds their preferences leave on. */
    @Transactional(readOnly = true)
    public CursorResponse<NotificationResponse> list(String userId, String cursor, Integer limit) {
        UUID userUuid = UUID.fromString(userId);
        int size = CursorResponse.limit(limit);
        List<NotificationRow> rows = notificationRepository.findPage(userUuid,
                NotificationPreference.enabledKinds(preferences(userUuid)), Cursor.decode(cursor, Cursor.Scope.NOTIFICATIONS),
                OffsetDateTime.now(), size + 1);
        return CursorResponse.of(rows, size, Cursor.Scope.NOTIFICATIONS, row -> Cursor.of(row.createdAt(), row.sourceId()),
                page -> toResponses(userUuid, page));
    }

    @Transactional(readOnly = true)
    public UnreadCountResponse countUnread(String userId) {
        UUID userUuid = UUID.fromString(userId);
        return new UnreadCountResponse(notificationRepository.countUnread(userUuid,
                NotificationPreference.enabledKinds(preferences(userUuid)), OffsetDateTime.now()));
    }

    /**
     * Marks one notification read; idempotent. {@code id} is a notification id, or (deprecated) a Poll id, which reads
     * that Poll's current notification: the Invitation while it has not Ended, then its Result.
     */
    public void markRead(String userId, String id) {
        UUID userUuid = UUID.fromString(userId);
        OffsetDateTime now = OffsetDateTime.now();
        NotificationId notification = isUuid(id) ? currentForPoll(userId, id, now) : NotificationId.parse(id);
        if (!notificationRepository.exists(userUuid, notification.kind(), notification.sourceId(),
                NotificationPreference.enabledKinds(preferences(userUuid)), now)) {
            throw ApiException.notFound(MessageKey.NOTIFICATION_NOT_FOUND);
        }
        notificationRepository.markRead(userUuid, notification.kind(), notification.sourceId(), now);
        log.info("User {} read notification {}", userId, notification);
    }

    /** Marks every notification up to now read, those of switched-off kinds included. */
    public void markAllRead(String userId) {
        notificationRepository.markAllRead(UUID.fromString(userId), OffsetDateTime.now());
        log.info("User {} read all notifications", userId);
    }

    @Transactional(readOnly = true)
    public NotificationPreferencesResponse getPreferences(String userId) {
        return toResponse(preferences(UUID.fromString(userId)));
    }

    /** Sets the given switches; null fields keep their value. Returns every switch after the change. */
    public NotificationPreferencesResponse updatePreferences(String userId, UpdateNotificationPreferencesRequest request) {
        UUID userUuid = UUID.fromString(userId);
        if (!userRepository.existsById(userUuid)) {
            throw ApiException.notFound(MessageKey.USER_NOT_FOUND);
        }
        Map<NotificationPreference, Boolean> changes = new EnumMap<>(NotificationPreference.class);
        changes.put(NotificationPreference.RESULTS_IN, request.resultsIn());
        changes.put(NotificationPreference.INVITATIONS, request.invitations());
        changes.put(NotificationPreference.COMMENTS, request.comments());
        changes.put(NotificationPreference.CLOSING_SOON, request.closingSoon());
        changes.put(NotificationPreference.MENTIONS, request.mentions());
        changes.forEach((preference, on) -> {
            if (on != null) {
                preferenceRepository.upsert(UUIDv7Generator.generateId(), userUuid, preference.preferenceKey(), on.toString());
            }
        });
        log.info("User {} updated notification preferences", userId);
        return toResponse(preferences(userUuid));
    }

    /** Every switch, the stored value or its default. */
    private Map<NotificationPreference, Boolean> preferences(UUID userId) {
        Map<String, String> stored = preferenceRepository
                .findByUserIdAndKeyStartingWith(userId, NotificationPreference.KEY_PREFIX).stream()
                .collect(Collectors.toMap(UserPreference::getKey, UserPreference::getValue, (a, b) -> a));
        Map<NotificationPreference, Boolean> settings = NotificationPreference.defaults();
        settings.replaceAll((preference, on) -> {
            String value = stored.get(preference.preferenceKey());
            return value == null ? on : Boolean.parseBoolean(value);
        });
        return settings;
    }

    private static NotificationPreferencesResponse toResponse(Map<NotificationPreference, Boolean> settings) {
        return new NotificationPreferencesResponse(settings.get(NotificationPreference.RESULTS_IN),
                settings.get(NotificationPreference.INVITATIONS), settings.get(NotificationPreference.COMMENTS),
                settings.get(NotificationPreference.CLOSING_SOON), settings.get(NotificationPreference.MENTIONS));
    }

    private NotificationId currentForPoll(String userId, String voteId, OffsetDateTime now) {
        UUID voteUuid = voteService.findAccessibleVote(UUID.fromString(voteId), userId).getId();
        return notificationRepository.findCurrentForPoll(UUID.fromString(userId), voteUuid, now)
                .map(row -> new NotificationId(row.kind(), row.sourceId()))
                .orElseThrow(() -> ApiException.notFound(MessageKey.NOTIFICATION_NOT_FOUND));
    }

    private static boolean isUuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Fills in the page's actors (with the caller's follows both ways), Polls (with the caller's Selection and, once
     * Ended, the winners) and Comments, a fixed number of queries per page.
     */
    private List<NotificationResponse> toResponses(UUID userId, List<NotificationRow> rows) {
        Map<UUID, FollowUserDto> actors = userService.toFollowItems(
                        userRepository.findAllById(ids(rows, NotificationRow::actorId)), userId).stream()
                .collect(Collectors.toMap(user -> UUID.fromString(user.id()), Function.identity()));
        List<UUID> voteIds = ids(rows, NotificationRow::voteId);
        Map<UUID, Vote> polls = voteRepository.findAllById(voteIds).stream()
                .collect(Collectors.toMap(Vote::getId, Function.identity()));
        // A sealed Anonymous Ballot has no option any more, so it is left out like no Ballot
        Map<UUID, UUID> myOptions = voteIds.isEmpty() ? Map.of() : userVoteRepository.findSelections(userId, voteIds).stream()
                .filter(selection -> selection.getOptionId() != null)
                .collect(Collectors.toMap(BallotSelection::getVoteId, BallotSelection::getOptionId, (a, b) -> a));
        Map<UUID, VoteComment> comments = commentRepository.findAllById(ids(rows, NotificationRow::commentId)).stream()
                .collect(Collectors.toMap(VoteComment::getId, Function.identity()));
        OffsetDateTime now = OffsetDateTime.now();
        return rows.stream().map(row -> new NotificationResponse(
                new NotificationId(row.kind(), row.sourceId()).toString(),
                row.kind(),
                row.createdAt(),
                row.read(),
                row.actorId() == null ? null : actors.get(row.actorId()),
                row.voteId() == null ? null : toPollSummary(polls.get(row.voteId()), myOptions.get(row.voteId()), now),
                row.commentId() == null ? null : toCommentSummary(comments.get(row.commentId()))))
                .toList();
    }

    private static List<UUID> ids(List<NotificationRow> rows, Function<NotificationRow, UUID> id) {
        return rows.stream().map(id).filter(Objects::nonNull).distinct().toList();
    }

    // No per-option counts; the winners come from VoteMapper, which keeps them null while Sealed
    private NotificationResponse.PollSummary toPollSummary(Vote poll, UUID myOptionId, OffsetDateTime now) {
        List<VoteOption> winners = voteMapper.toWinners(poll);
        return new NotificationResponse.PollSummary(poll.getId().toString(), poll.getTitle(), poll.getStartTime(),
                poll.getEndTime(), poll.statusAt(now), poll.getTurnout(),
                myOptionId == null ? null : myOptionId.toString(),
                winners == null ? null : winners.stream().map(option -> option.getId().toString()).toList(),
                winners == null ? null : winners.stream().map(VoteOption::getText).toList(),
                winners == null ? null : winners.size() > 1);
    }

    private static NotificationResponse.CommentSummary toCommentSummary(VoteComment comment) {
        VoteComment parent = comment.getParent();
        return new NotificationResponse.CommentSummary(comment.getId().toString(),
                parent == null ? null : parent.getId().toString(), excerpt(comment.getContent()));
    }

    static String excerpt(String content) {
        String text = content.strip().replaceAll("\\s+", " ");
        if (text.codePointCount(0, text.length()) <= EXCERPT_LENGTH) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, EXCERPT_LENGTH)).stripTrailing() + "…";
    }
}
