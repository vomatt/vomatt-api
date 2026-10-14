package com.vomatt.groups;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.entity.Group;
import com.vomatt.entity.GroupMember;
import com.vomatt.entity.User;
import com.vomatt.groups.dto.GroupMembersRequest;
import com.vomatt.groups.dto.GroupRequest;
import com.vomatt.repository.GroupMemberRepository;
import com.vomatt.repository.GroupRepository;
import com.vomatt.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("GroupService")
class GroupServiceTest {

    @Mock GroupRepository groupRepository;
    @Mock GroupMemberRepository groupMemberRepository;
    @Mock UserRepository userRepository;
    @Spy GroupMapper groupMapper = new GroupMapper();

    @InjectMocks
    GroupService groupService;

    private final UUID ownerId = UUID.randomUUID();
    private final UUID groupId = UUID.randomUUID();
    private User owner;
    private Group group;

    @BeforeEach
    void setUp() {
        owner = User.builder().id(ownerId).username("alice").email("alice@example.com").build();
        group = new Group(owner, "Book club");
        ReflectionTestUtils.setField(group, "id", groupId);
    }

    private static void assertApiException(Throwable ex, MessageKey key) {
        assertThat(ex).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getMessageKey()).isEqualTo(key));
    }

    private void lockGroup() {
        when(groupRepository.findOwnedForUpdate(groupId, ownerId)).thenReturn(Optional.of(group));
    }

    private GroupMembersRequest members(List<UUID> userIds, List<String> emails) {
        GroupMembersRequest request = new GroupMembersRequest();
        request.setUserIds(userIds);
        request.setEmails(emails);
        return request;
    }

    private List<GroupMember> saved() {
        var captor = org.mockito.ArgumentCaptor.forClass(Iterable.class);
        verify(groupMemberRepository).saveAllAndFlush(captor.capture());
        List<GroupMember> rows = new ArrayList<>();
        ((Iterable<?>) captor.getValue()).forEach(m -> rows.add((GroupMember) m));
        return rows;
    }

    @Test
    @DisplayName("should throw 400 GROUP_MAX_REACHED when the owner already has 50 Groups")
    void shouldRejectCreateWhenGroupCapReached() {
        when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
        when(groupRepository.countByOwnerId(ownerId)).thenReturn((long) GroupService.MAX_GROUPS_PER_OWNER);
        GroupRequest request = new GroupRequest();
        request.setName("One more");

        assertThatThrownBy(() -> groupService.createGroup(request, ownerId.toString()))
                .satisfies(ex -> assertApiException(ex, MessageKey.GROUP_MAX_REACHED));
        verify(groupRepository, never()).save(any());
    }

    @Test
    @DisplayName("should skip the owner by ID and by address, and tie a registered address to its user while keeping the email")
    void shouldSkipOwnerWhenAddingMembers() {
        lockGroup();
        User bob = User.builder().id(UUID.randomUUID()).email("bob@example.com").build();
        when(userRepository.findByEmailIn(Set.of("alice@example.com", "bob@example.com", "carol@example.com")))
                .thenReturn(List.of(owner, bob));

        groupService.addMembers(groupId.toString(),
                members(List.of(ownerId), List.of("Alice@Example.com", "bob@example.com", "carol@example.com")), ownerId.toString());

        assertThat(saved()).extracting(m -> m.getUser() == null ? null : m.getUser().getId(), GroupMember::getEmail)
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple(bob.getId(), "bob@example.com"),
                        org.assertj.core.groups.Tuple.tuple(null, "carol@example.com"));
    }

    @Test
    @DisplayName("should throw 400 GROUP_MEMBER_IDS_INVALID when a user by ID is suspended or unknown")
    void shouldRejectAddWhenUserSuspended() {
        lockGroup();
        User suspended = User.builder().id(UUID.randomUUID()).active(false).build();
        UUID unknown = UUID.randomUUID();
        when(userRepository.findAllById(Set.of(suspended.getId(), unknown))).thenReturn(List.of(suspended));

        assertThatThrownBy(() -> groupService.addMembers(groupId.toString(),
                members(List.of(suspended.getId(), unknown), null), ownerId.toString()))
                .satisfies(ex -> assertApiException(ex, MessageKey.GROUP_MEMBER_IDS_INVALID));
        verify(groupMemberRepository, never()).saveAllAndFlush(any());
    }

    @Test
    @DisplayName("should throw 400 GROUP_MEMBERS_MAX when the Group would exceed 100 members")
    void shouldRejectAddWhenMemberCapExceeded() {
        lockGroup();
        List<GroupMember> existing = IntStream.range(0, GroupService.MAX_MEMBERS_PER_GROUP)
                .mapToObj(i -> new GroupMember(group, "m" + i + "@example.com")).toList();
        when(groupMemberRepository.findByGroupId(groupId)).thenReturn(existing);

        assertThatThrownBy(() -> groupService.addMembers(groupId.toString(),
                members(null, List.of("new@example.com", "m1@example.com")), ownerId.toString()))
                .satisfies(ex -> assertApiException(ex, MessageKey.GROUP_MEMBERS_MAX));
        verify(groupMemberRepository, never()).saveAllAndFlush(any());
    }

    @Test
    @DisplayName("should throw 404 GROUP_NOT_FOUND on expansion when any Group is missing or someone else's, ignoring duplicates")
    void shouldRejectExpandWhenGroupNotOwned() {
        UUID other = UUID.randomUUID();
        when(groupRepository.countByIdInAndOwnerId(Set.of(groupId, other), ownerId)).thenReturn(1L);
        when(groupRepository.countByIdInAndOwnerId(Set.of(groupId), ownerId)).thenReturn(1L);

        assertThatThrownBy(() -> groupService.expand(List.of(groupId, other), ownerId))
                .satisfies(ex -> assertApiException(ex, MessageKey.GROUP_NOT_FOUND));
        groupService.expand(List.of(groupId, groupId), ownerId);
        verify(groupMemberRepository).findWithUserByGroupIdIn(argThat(ids -> ids.size() == 1));
    }

    @Test
    @DisplayName("should expand members by ID to active user IDs and email members to their stored address")
    void shouldSplitMembersWhenExpanding() {
        User bob = User.builder().id(UUID.randomUUID()).build();
        User suspended = User.builder().id(UUID.randomUUID()).active(false).build();
        User carol = User.builder().id(UUID.randomUUID()).email("carol@example.com").build();
        when(groupRepository.countByIdInAndOwnerId(Set.of(groupId), ownerId)).thenReturn(1L);
        when(groupMemberRepository.findWithUserByGroupIdIn(Set.of(groupId))).thenReturn(List.of(
                new GroupMember(group, bob), new GroupMember(group, suspended), new GroupMember(group, carol, "carol@example.com")));

        GroupService.Members members = groupService.expand(List.of(groupId), ownerId);

        assertThat(members.userIds()).containsExactly(bob.getId());
        assertThat(members.emails()).containsExactly("carol@example.com");
    }
}
