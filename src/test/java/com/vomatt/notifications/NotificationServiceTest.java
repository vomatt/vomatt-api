package com.vomatt.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.entity.NotificationKind;
import com.vomatt.entity.User;
import com.vomatt.entity.UserPreference;
import com.vomatt.entity.Vote;
import com.vomatt.notifications.dto.NotificationPreferencesResponse;
import com.vomatt.notifications.dto.UpdateNotificationPreferencesRequest;
import com.vomatt.repository.NotificationRepository;
import com.vomatt.repository.NotificationRepository.NotificationRow;
import com.vomatt.repository.UserPreferenceRepository;
import com.vomatt.repository.UserRepository;
import com.vomatt.repository.VoteCommentRepository;
import com.vomatt.repository.VoteRepository;
import com.vomatt.users.UserService;
import com.vomatt.votes.VoteService;

@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationService")
class NotificationServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final String USER = USER_ID.toString();

    @Mock NotificationRepository notificationRepository;
    @Mock UserPreferenceRepository preferenceRepository;
    @Mock UserRepository userRepository;
    @Mock VoteRepository voteRepository;
    @Mock VoteCommentRepository commentRepository;
    @Mock UserService userService;
    @Mock VoteService voteService;
    @InjectMocks NotificationService notificationService;

    private void stored(NotificationPreference preference, boolean on) {
        UserPreference row = new UserPreference();
        row.setKey(preference.preferenceKey());
        row.setValue(Boolean.toString(on));
        when(preferenceRepository.findByUserIdAndKeyStartingWith(USER_ID, NotificationPreference.KEY_PREFIX))
                .thenReturn(List.of(row));
    }

    @SuppressWarnings("unchecked")
    private Set<NotificationKind> kindsCounted() {
        ArgumentCaptor<Set<NotificationKind>> kinds = ArgumentCaptor.forClass(Set.class);
        notificationService.countUnread(USER);
        verify(notificationRepository).countUnread(eq(USER_ID), kinds.capture(), any());
        return kinds.getValue();
    }

    @SuppressWarnings("unchecked")
    private Set<NotificationKind> kindsListed() {
        ArgumentCaptor<Set<NotificationKind>> kinds = ArgumentCaptor.forClass(Set.class);
        notificationService.list(USER, null, 20);
        verify(notificationRepository).findPage(eq(USER_ID), kinds.capture(), any(), any(), anyInt());
        return kinds.getValue();
    }

    @Test
    @DisplayName("should list and count every kind but CLOSING_SOON when no preference is stored")
    void shouldUseDefaultKindsWhenNoPreferenceStored() {
        Set<NotificationKind> defaults = EnumSet.complementOf(EnumSet.of(NotificationKind.CLOSING_SOON));

        assertThat(kindsListed()).isEqualTo(defaults);
        assertThat(kindsCounted()).isEqualTo(defaults);
        assertThat(notificationService.getPreferences(USER))
                .isEqualTo(new NotificationPreferencesResponse(true, true, true, false));
    }

    @ParameterizedTest
    @EnumSource(value = NotificationPreference.class, names = { "RESULTS_IN", "INVITATIONS", "COMMENTS" })
    @DisplayName("should leave a switched-off kind out of the feed and the count, keeping FOLLOWED")
    void shouldLeaveKindOutWhenSwitchedOff(NotificationPreference preference) {
        stored(preference, false);
        Set<NotificationKind> off = switch (preference) {
            case RESULTS_IN -> EnumSet.of(NotificationKind.RESULT, NotificationKind.CANCELLED);
            case INVITATIONS -> EnumSet.of(NotificationKind.INVITATION);
            case COMMENTS -> EnumSet.of(NotificationKind.COMMENT);
            case CLOSING_SOON -> EnumSet.of(NotificationKind.CLOSING_SOON);
        };

        assertThat(kindsListed()).doesNotContainAnyElementsOf(off).contains(NotificationKind.FOLLOWED);
        assertThat(kindsCounted()).doesNotContainAnyElementsOf(off).contains(NotificationKind.FOLLOWED);
    }

    @Test
    @DisplayName("should include CLOSING_SOON when the user switched it on")
    void shouldIncludeClosingSoonWhenSwitchedOn() {
        stored(NotificationPreference.CLOSING_SOON, true);

        assertThat(kindsListed()).isEqualTo(EnumSet.allOf(NotificationKind.class));
    }

    @Test
    @DisplayName("should store only the switches sent and return all of them")
    void shouldUpsertOnlySentSwitchesWhenUpdating() {
        when(userRepository.existsById(USER_ID)).thenReturn(true);

        notificationService.updatePreferences(USER, new UpdateNotificationPreferencesRequest(null, null, false, null));

        verify(preferenceRepository).upsert(any(), eq(USER_ID), eq("notifications.comments"), eq("false"));
        verify(preferenceRepository, never()).upsert(any(), any(), eq("notifications.resultsIn"), any());
        verify(preferenceRepository, never()).upsert(any(), any(), eq("notifications.invitations"), any());
        verify(preferenceRepository, never()).upsert(any(), any(), eq("notifications.closingSoon"), any());
    }

    @Test
    @DisplayName("should return 404 when the account updating its preferences no longer exists")
    void shouldRejectUpdateWhenUserMissing() {
        assertThatThrownBy(() -> notificationService.updatePreferences(USER,
                new UpdateNotificationPreferencesRequest(true, null, null, null)))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getMessageKey()).isEqualTo(MessageKey.USER_NOT_FOUND));
    }

    @Test
    @DisplayName("should mark a notification read by its id when the user has it")
    void shouldMarkReadWhenNotificationExists() {
        UUID commentId = UUID.randomUUID();
        when(notificationRepository.exists(eq(USER_ID), eq(NotificationKind.COMMENT), eq(commentId), any())).thenReturn(true);

        notificationService.markRead(USER, "comment_" + commentId);

        verify(notificationRepository).markRead(eq(USER_ID), eq(NotificationKind.COMMENT), eq(commentId), any());
    }

    @Test
    @DisplayName("should return 404 and store nothing when the user does not have the notification")
    void shouldRejectMarkReadWhenNotificationMissing() {
        assertThatThrownBy(() -> notificationService.markRead(USER, "followed_" + UUID.randomUUID()))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getMessageKey()).isEqualTo(MessageKey.NOTIFICATION_NOT_FOUND));
        assertThatThrownBy(() -> notificationService.markRead(USER, "nonsense_" + UUID.randomUUID()))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getMessageKey()).isEqualTo(MessageKey.NOTIFICATION_NOT_FOUND));
        verify(notificationRepository, never()).markRead(any(), any(), any(), any());
    }

    @Test
    @DisplayName("should read the Poll's current notification when given a deprecated Poll id")
    void shouldMarkCurrentPollNotificationReadWhenGivenPollId() {
        UUID voteId = UUID.randomUUID();
        Vote vote = new Vote("Lunch?", null, User.builder().id(UUID.randomUUID()).build());
        ReflectionTestUtils.setField(vote, "id", voteId);
        when(voteService.findAccessibleVote(voteId, USER)).thenReturn(vote);
        when(notificationRepository.findCurrentForPoll(eq(USER_ID), eq(voteId), any())).thenReturn(Optional.of(
                new NotificationRow(NotificationKind.RESULT, voteId, OffsetDateTime.now(), null, voteId, null, false)));
        when(notificationRepository.exists(eq(USER_ID), eq(NotificationKind.RESULT), eq(voteId), any())).thenReturn(true);

        notificationService.markRead(USER, voteId.toString());

        verify(notificationRepository).markRead(eq(USER_ID), eq(NotificationKind.RESULT), eq(voteId), any());
    }

    @Test
    @DisplayName("should collapse whitespace and cut a long Comment at 120 characters with an ellipsis")
    void shouldTruncateExcerptWhenCommentLong() {
        assertThat(NotificationService.excerpt("  short\n\ttext ")).isEqualTo("short text");
        String excerpt = NotificationService.excerpt("投".repeat(130));
        assertThat(excerpt).hasSize(121).endsWith("…");
    }
}
