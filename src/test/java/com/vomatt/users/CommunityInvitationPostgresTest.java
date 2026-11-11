package com.vomatt.users;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.vomatt.comments.CommentMapper;
import com.vomatt.comments.VoteCommentService;
import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.common.security.RefreshTokenService;
import com.vomatt.entity.CommunityRole;
import com.vomatt.entity.NotificationKind;
import com.vomatt.entity.User;
import com.vomatt.repository.CommunityInvitationRepository;
import com.vomatt.repository.CommunityMemberRepository;
import com.vomatt.repository.NotificationRepository;
import com.vomatt.repository.PostgresRepositoryTest;
import com.vomatt.users.dto.CommunityInvitationResponse;
import com.vomatt.votes.VoteServiceSlice;

@Import({ CommunityService.class, UserService.class, UserMapper.class, VoteCommentService.class, CommentMapper.class,
        VoteServiceSlice.class, com.vomatt.common.storage.StorageService.class })
class CommunityInvitationPostgresTest extends PostgresRepositoryTest {

    @Autowired CommunityService communityService;
    @Autowired CommunityMemberRepository memberRepository;
    @Autowired CommunityInvitationRepository invitationRepository;
    @Autowired NotificationRepository notificationRepository;
    @MockitoBean RefreshTokenService refreshTokenService;

    private User owner;
    private User dave;
    private UUID communityId;

    @BeforeEach
    void setUp() {
        owner = persistUser("ci-o-" + UUID.randomUUID().toString().substring(0, 8));
        dave = persistUser("ci-d-" + UUID.randomUUID().toString().substring(0, 8));
        em.flush();
        communityId = UUID.fromString(communityService.createCommunity(
                "ci_" + UUID.randomUUID().toString().substring(0, 8), "Sunrise Tower", owner.getId()).id());
        em.flush();
    }

    private CommunityInvitationResponse invite(String email, CommunityRole role) {
        CommunityInvitationResponse invitation = communityService.invite(communityId, email, role, owner.getId().toString());
        em.flush();
        return invitation;
    }

    private boolean hasNotification(User user, String invitationId) {
        return notificationRepository.exists(user.getId(), NotificationKind.COMMUNITY_INVITATION, UUID.fromString(invitationId),
                EnumSet.allOf(NotificationKind.class), OffsetDateTime.now());
    }

    private static void assertKey(ThrowingRunnable call, MessageKey key) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getMessageKey()).isEqualTo(key));
    }

    private interface ThrowingRunnable {
        void run();
    }

    @Test
    void shouldNotifyAccountAndJoinWithRoleWhenInviteeAccepts() {
        CommunityInvitationResponse invitation = invite(" " + dave.getEmail().toUpperCase(), CommunityRole.EDITOR);

        assertThat(invitation.email()).isEqualTo(dave.getEmail());
        assertThat(hasNotification(dave, invitation.id())).isTrue();
        assertThat(hasNotification(owner, invitation.id())).isFalse();

        assertThat(communityService.acceptInvitation(UUID.fromString(invitation.id()), dave.getId().toString()).role())
                .isEqualTo(CommunityRole.EDITOR);
        em.flush();
        assertThat(memberRepository.findActiveRole(communityId, dave.getId())).contains(CommunityRole.EDITOR);
        assertThat(invitationRepository.findAll()).noneMatch(i -> i.getId().toString().equals(invitation.id()));
    }

    @Test
    void shouldRefuseAcceptWhenCallerIsNotTheInvitedAddress() {
        CommunityInvitationResponse invitation = invite(dave.getEmail(), CommunityRole.EDITOR);

        assertKey(() -> communityService.acceptInvitation(UUID.fromString(invitation.id()), owner.getId().toString()),
                MessageKey.COMMUNITY_INVITATION_NOT_FOUND);
        assertKey(() -> communityService.declineInvitation(UUID.fromString(invitation.id()), owner.getId().toString()),
                MessageKey.COMMUNITY_INVITATION_NOT_FOUND);
    }

    @Test
    void shouldRemoveNotificationWhenInviteeDeclines() {
        CommunityInvitationResponse invitation = invite(dave.getEmail(), CommunityRole.ANALYST);

        communityService.declineInvitation(UUID.fromString(invitation.id()), dave.getId().toString());
        em.flush();

        assertThat(hasNotification(dave, invitation.id())).isFalse();
        assertThat(memberRepository.findActiveRole(communityId, dave.getId())).isEmpty();
    }

    @Test
    void shouldHideNotificationWhenInviteeIsAlreadyMember() {
        memberRepository.save(new com.vomatt.entity.CommunityMember(communityId, dave.getId(), CommunityRole.ANALYST));
        CommunityInvitationResponse invitation = invite(dave.getEmail(), CommunityRole.ADMIN);

        assertThat(hasNotification(dave, invitation.id())).isFalse();
        // Accepting keeps the current role
        assertThat(communityService.acceptInvitation(UUID.fromString(invitation.id()), dave.getId().toString()).role())
                .isEqualTo(CommunityRole.ANALYST);
    }

    @Test
    void shouldConflictWhenAddressAlreadyInvitedAndReplaceWhenExpired() {
        CommunityInvitationResponse first = invite("erin@example.com", CommunityRole.EDITOR);
        assertKey(() -> invite("erin@example.com", CommunityRole.ADMIN), MessageKey.COMMUNITY_INVITATION_EXISTS);

        em.createNativeQuery("UPDATE vomatt.community_invitations SET expires_at = now() - INTERVAL '1 minute' WHERE id = :id")
                .setParameter("id", UUID.fromString(first.id())).executeUpdate();
        em.clear();
        CommunityInvitationResponse second = invite("erin@example.com", CommunityRole.ADMIN);

        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(communityService.listInvitations(communityId, owner.getId().toString()))
                .extracting(CommunityInvitationResponse::role).containsExactly(CommunityRole.ADMIN);
    }

    @Test
    void shouldJoinInvitedCommunitiesWhenAddressSignsUp() {
        invite("frank@example.com", CommunityRole.CHIEF_EDITOR);
        User frank = User.builder().username("ci-f-" + UUID.randomUUID().toString().substring(0, 8)).email("frank@example.com").build();
        em.persist(frank);

        communityService.joinInvitedCommunities(frank);
        em.flush();

        assertThat(memberRepository.findActiveRole(communityId, frank.getId())).contains(CommunityRole.CHIEF_EDITOR);
        assertThat(invitationRepository.findByEmailAndExpiresAtAfter("frank@example.com", OffsetDateTime.now())).isEmpty();
    }

    @Test
    void shouldRefuseAdminWhenInvitingOrRevokingOwner() {
        memberRepository.save(new com.vomatt.entity.CommunityMember(communityId, dave.getId(), CommunityRole.ADMIN));
        CommunityInvitationResponse ownerInvite = invite("gina@example.com", CommunityRole.OWNER);

        assertKey(() -> communityService.invite(communityId, "hank@example.com", CommunityRole.OWNER, dave.getId().toString()),
                MessageKey.COMMUNITY_ROLE_FORBIDDEN);
        assertKey(() -> communityService.revokeInvitation(communityId, UUID.fromString(ownerInvite.id()), dave.getId().toString()),
                MessageKey.COMMUNITY_ROLE_FORBIDDEN);
        communityService.revokeInvitation(communityId, UUID.fromString(ownerInvite.id()), owner.getId().toString());
    }
}
