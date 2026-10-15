package com.vomatt.groups;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.entity.Audience;
import com.vomatt.entity.Invitee;
import com.vomatt.entity.User;
import com.vomatt.entity.Vote;
import com.vomatt.groups.dto.GroupMemberResponse;
import com.vomatt.groups.dto.GroupMembersRequest;
import com.vomatt.groups.dto.GroupRequest;
import com.vomatt.repository.GroupMemberRepository;
import com.vomatt.repository.InviteeRepository;
import com.vomatt.repository.NotificationReadRepository;
import com.vomatt.repository.PostgresRepositoryTest;
import com.vomatt.votes.VoteService;
import com.vomatt.votes.VoteServiceSlice;
import com.vomatt.votes.dto.CreateVoteRequest;
import com.vomatt.votes.dto.InviteeResponse;
import com.vomatt.votes.dto.InviteesRequest;

@Import(VoteServiceSlice.class)
@DisplayName("Groups (Postgres)")
class GroupPostgresTest extends PostgresRepositoryTest {

    @Autowired GroupService groupService;
    @Autowired VoteService voteService;
    @Autowired GroupMemberRepository groupMemberRepository;
    @Autowired InviteeRepository inviteeRepository;
    @Autowired NotificationReadRepository notificationReadRepository;

    private final OffsetDateTime now = OffsetDateTime.now();
    private final String carolEmail = "gr-carol-" + System.nanoTime() + "@example.com";
    private User owner;
    private User bob;
    private String ownerId;
    private String groupId;

    @BeforeEach
    void setUp() {
        owner = persistUser("gr-owner-" + System.nanoTime());
        bob = persistUser("gr-bob-" + System.nanoTime());
        bob.setAvatarUrl("bot:boba");
        em.flush();
        ownerId = owner.getId().toString();
        groupId = groupService.createGroup(name("Book club"), ownerId).id();
    }

    private static GroupRequest name(String name) {
        GroupRequest request = new GroupRequest();
        request.setName(name);
        return request;
    }

    private List<GroupMemberResponse> addMembers(String id, List<UUID> userIds, List<String> emails, String callerId) {
        GroupMembersRequest request = new GroupMembersRequest();
        request.setUserIds(userIds);
        request.setEmails(emails);
        return groupService.addMembers(id, request, callerId);
    }

    private Vote inviteOnlyPoll(OffsetDateTime start, OffsetDateTime end) {
        Vote poll = persistPoll(owner, start, end, "A", "B");
        poll.setAudience(Audience.INVITE_ONLY);
        em.flush();
        return poll;
    }

    private List<InviteeResponse> inviteGroup(Vote poll, String id, String callerId) {
        InviteesRequest request = new InviteesRequest();
        request.setGroupIds(List.of(UUID.fromString(id)));
        return voteService.addInvitees(poll.getId().toString(), request, callerId);
    }

    private static void assertGroupNotFound(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                ex -> assertThat(ex.getMessageKey()).isEqualTo(MessageKey.GROUP_NOT_FOUND));
    }

    @Test
    @DisplayName("should copy the Group's current members into the Poll, untouched by later Group edits or deletion")
    void shouldCopyMembersWhenGroupInvitedToPoll() {
        addMembers(groupId, List.of(bob.getId(), owner.getId()), List.of(carolEmail.toUpperCase(), owner.getEmail()), ownerId);
        Vote poll = inviteOnlyPoll(now.minusHours(1), now.plusDays(1));

        List<InviteeResponse> invitees = inviteGroup(poll, groupId, ownerId);

        assertThat(invitees).extracting(InviteeResponse::userId, InviteeResponse::avatarUrl, InviteeResponse::email).containsExactlyInAnyOrder(
                Tuple.tuple(bob.getId().toString(), "bot:boba", null), Tuple.tuple(null, null, carolEmail));
        groupService.removeMember(groupId, bob.getId().toString(), ownerId);
        // A fresh request: the database cascades the members, none are loaded in this session
        em.flush();
        em.clear();
        groupService.deleteGroup(groupId, ownerId);
        em.flush();
        em.clear();
        assertThat(voteService.getInvitees(poll.getId().toString(), ownerId)).hasSize(2);
        assertThat(groupService.getGroups(ownerId)).isEmpty();
        assertThat(groupMemberRepository.findByGroupId(UUID.fromString(groupId))).isEmpty();
    }

    @Test
    @DisplayName("should list, count and rename the owner's Group, skipping duplicates and existing members")
    void shouldListAndRenameGroupWhenOwner() {
        addMembers(groupId, List.of(bob.getId()), List.of(carolEmail), ownerId);
        List<GroupMemberResponse> members = addMembers(groupId, List.of(bob.getId(), bob.getId()), List.of(carolEmail), ownerId);

        assertThat(members).extracting(GroupMemberResponse::username, GroupMemberResponse::avatarUrl, GroupMemberResponse::email)
                .containsExactly(Tuple.tuple(bob.getUsername(), "bot:boba", null), Tuple.tuple(null, null, carolEmail));
        assertThat(groupService.renameGroup(groupId, name("  Chess club "), ownerId).name()).isEqualTo("Chess club");
        assertThat(groupService.getGroups(ownerId)).singleElement().satisfies(g -> {
            assertThat(g.name()).isEqualTo("Chess club");
            assertThat(g.memberCount()).isEqualTo(2);
        });
    }

    @Test
    @DisplayName("should return group.not_found to anyone but the owner on every Group path, including groupIds on a Poll")
    void shouldHideGroupWhenCallerNotOwner() {
        String strangerId = bob.getId().toString();
        Vote strangerPoll = persistPoll(bob, now.minusHours(1), now.plusDays(1), "A", "B");
        strangerPoll.setAudience(Audience.INVITE_ONLY);
        em.flush();

        assertThat(groupService.getGroups(strangerId)).isEmpty();
        assertGroupNotFound(() -> groupService.getMembers(groupId, strangerId));
        assertGroupNotFound(() -> groupService.renameGroup(groupId, name("Mine"), strangerId));
        assertGroupNotFound(() -> groupService.deleteGroup(groupId, strangerId));
        assertGroupNotFound(() -> addMembers(groupId, List.of(), List.of(carolEmail), strangerId));
        assertGroupNotFound(() -> groupService.removeEmailMember(groupId, carolEmail, strangerId));
        assertGroupNotFound(() -> inviteGroup(strangerPoll, groupId, strangerId));
        assertGroupNotFound(() -> groupService.getMembers(UUID.randomUUID().toString(), ownerId));
    }

    @Test
    @DisplayName("should tie an email member to the user on verification, still listing it by address, also after a rerun")
    void shouldBindEmailMemberWhenEmailVerified() {
        addMembers(groupId, List.of(), List.of(carolEmail), ownerId);
        User carol = User.builder().username("gr-carol-" + System.nanoTime()).email(carolEmail).avatarUrl("bot:boba").build();
        em.persist(carol);

        groupService.bindEmailMembers(carol);
        groupService.bindEmailMembers(carol);
        em.clear();

        assertThat(groupMemberRepository.findByGroupId(UUID.fromString(groupId))).singleElement().satisfies(m -> {
            assertThat(m.getUser().getId()).isEqualTo(carol.getId());
            assertThat(m.getEmail()).isEqualTo(carolEmail);
        });
        assertThat(groupService.getMembers(groupId, ownerId)).singleElement().satisfies(m -> {
            assertThat(m.userId()).isNull();
            assertThat(m.avatarUrl()).isNull();
            assertThat(m.email()).isEqualTo(carolEmail);
        });
        // Expanded by its stored address, so the Poll lists the address too
        Vote poll = inviteOnlyPoll(now.minusHours(1), now.plusDays(1));
        assertThat(inviteGroup(poll, groupId, ownerId)).extracting(InviteeResponse::userId, InviteeResponse::email)
                .containsExactly(Tuple.tuple(null, carolEmail));
    }

    @Test
    @DisplayName("should drop the pending email member when the verified user already is a member by ID")
    void shouldDropEmailMemberWhenUserAlreadyMember() {
        User carol = User.builder().username("gr-carol-" + System.nanoTime()).email(carolEmail).active(false).build();
        em.persist(carol);
        em.flush();
        // Suspended at add time, so the address stays pending; then carol is added by ID once reinstated
        addMembers(groupId, List.of(), List.of(carolEmail), ownerId);
        carol.setActive(true);
        em.flush();
        addMembers(groupId, List.of(carol.getId()), List.of(), ownerId);

        groupService.bindEmailMembers(carol);
        em.clear();

        assertThat(groupService.getMembers(groupId, ownerId)).singleElement()
                .satisfies(m -> assertThat(m.userId()).isEqualTo(carol.getId().toString()));
    }

    @Test
    @DisplayName("should skip a suspended member by ID when the Group is invited, instead of failing the invite")
    void shouldSkipSuspendedMemberWhenGroupInvited() {
        addMembers(groupId, List.of(bob.getId()), List.of(), ownerId);
        bob.setActive(false);
        em.flush();
        Vote poll = inviteOnlyPoll(now.minusHours(1), now.plusDays(1));

        assertThat(inviteGroup(poll, groupId, ownerId)).isEmpty();
    }

    @Test
    @DisplayName("should keep already-invited members as they are when an edit merges a Group into an emptied list")
    void shouldKeepInvitedMembersWhenEditMergesGroup() {
        addMembers(groupId, List.of(bob.getId()), List.of(), ownerId);
        Vote scheduled = inviteOnlyPoll(now.plusDays(1), now.plusDays(2));
        em.persist(new Invitee(scheduled, bob));
        em.flush();
        notificationReadRepository.markRead(bob.getId(), scheduled.getId(), now);
        UUID before = inviteeRepository.findByVoteId(scheduled.getId()).getFirst().getId();
        em.clear();

        CreateVoteRequest edit = new CreateVoteRequest();
        edit.setTitle("Edited");
        CreateVoteRequest.VoteOptionRequest a = new CreateVoteRequest.VoteOptionRequest();
        a.setText("A");
        CreateVoteRequest.VoteOptionRequest b = new CreateVoteRequest.VoteOptionRequest();
        b.setText("B");
        edit.setOptions(List.of(a, b));
        edit.setStartTime(now.plusDays(1));
        edit.setEndTime(now.plusDays(2));
        edit.setInviteeIds(List.of());
        edit.setGroupIds(List.of(UUID.fromString(groupId)));
        voteService.updateVote(scheduled.getId().toString(), edit, ownerId);
        em.flush();
        em.clear();

        assertThat(inviteeRepository.findByVoteId(scheduled.getId())).singleElement()
                .satisfies(i -> assertThat(i.getId()).isEqualTo(before));
        assertThat(notificationReadRepository.isNotified(bob.getId(), scheduled.getId(), now)).isTrue();
        assertThat(notificationReadRepository.countUnread(bob.getId(), now)).isZero();
    }
}
