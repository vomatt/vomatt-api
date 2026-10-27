package com.vomatt.invitelists;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.lenient;
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
import com.vomatt.entity.InviteList;
import com.vomatt.entity.InviteListMember;
import com.vomatt.entity.User;
import com.vomatt.invitelists.dto.InviteListMembersRequest;
import com.vomatt.invitelists.dto.InviteListRequest;
import com.vomatt.lookups.LookupService;
import com.vomatt.repository.InviteListMemberRepository;
import com.vomatt.repository.InviteListRepository;
import com.vomatt.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("InviteListService")
class InviteListServiceTest {

    @Mock InviteListRepository inviteListRepository;
    @Mock InviteListMemberRepository inviteListMemberRepository;
    @Mock UserRepository userRepository;
    @Spy InviteListMapper inviteListMapper = new InviteListMapper();
    @Mock LookupService lookupService;

    @InjectMocks
    InviteListService inviteListService;

    private final UUID ownerId = UUID.randomUUID();
    private final UUID inviteListId = UUID.randomUUID();
    private User owner;
    private InviteList inviteList;

    @BeforeEach
    void setUp() {
        owner = User.builder().id(ownerId).username("alice").email("alice@example.com").build();
        inviteList = new InviteList(owner, "Book club");
        ReflectionTestUtils.setField(inviteList, "id", inviteListId);
        lenient().when(lookupService.configInt(anyString(), anyInt()))
                .thenAnswer(inv -> inv.getArgument(1));
    }

    private static void assertApiException(Throwable ex, MessageKey key) {
        assertThat(ex).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getMessageKey()).isEqualTo(key));
    }

    private void lockInviteList() {
        when(inviteListRepository.findOwnedForUpdate(inviteListId, ownerId)).thenReturn(Optional.of(inviteList));
    }

    private InviteListMembersRequest members(List<UUID> userIds, List<String> emails) {
        InviteListMembersRequest request = new InviteListMembersRequest();
        request.setUserIds(userIds);
        request.setEmails(emails);
        return request;
    }

    private List<InviteListMember> saved() {
        var captor = org.mockito.ArgumentCaptor.forClass(Iterable.class);
        verify(inviteListMemberRepository).saveAllAndFlush(captor.capture());
        List<InviteListMember> rows = new ArrayList<>();
        ((Iterable<?>) captor.getValue()).forEach(m -> rows.add((InviteListMember) m));
        return rows;
    }

    @Test
    @DisplayName("should throw 400 INVITE_LIST_MAX_REACHED at the admin-set cap when one is set in lookups")
    void shouldRejectCreateWhenAdminCapReached() {
        when(lookupService.configInt(InviteListService.MAX_LISTS_KEY, InviteListService.MAX_INVITE_LISTS_PER_OWNER)).thenReturn(3);
        when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
        when(inviteListRepository.countByOwnerId(ownerId)).thenReturn(3L);
        InviteListRequest request = new InviteListRequest();
        request.setName("One more");

        assertThatThrownBy(() -> inviteListService.createInviteList(request, ownerId.toString()))
                .satisfies(ex -> {
                    assertApiException(ex, MessageKey.INVITE_LIST_MAX_REACHED);
                    assertThat(((ApiException) ex).getMessageArgs()).containsExactly(3);
                });
    }

    @Test
    @DisplayName("should throw 400 INVITE_LIST_MAX_REACHED when the owner already has 50 Invite Lists")
    void shouldRejectCreateWhenInviteListCapReached() {
        when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
        when(inviteListRepository.countByOwnerId(ownerId)).thenReturn((long) InviteListService.MAX_INVITE_LISTS_PER_OWNER);
        InviteListRequest request = new InviteListRequest();
        request.setName("One more");

        assertThatThrownBy(() -> inviteListService.createInviteList(request, ownerId.toString()))
                .satisfies(ex -> assertApiException(ex, MessageKey.INVITE_LIST_MAX_REACHED));
        verify(inviteListRepository, never()).save(any());
    }

    @Test
    @DisplayName("should skip the owner by ID and by address, and tie a registered address to its user while keeping the email")
    void shouldSkipOwnerWhenAddingMembers() {
        lockInviteList();
        User bob = User.builder().id(UUID.randomUUID()).email("bob@example.com").build();
        when(userRepository.findByEmailIn(Set.of("alice@example.com", "bob@example.com", "carol@example.com")))
                .thenReturn(List.of(owner, bob));

        inviteListService.addMembers(inviteListId.toString(),
                members(List.of(ownerId), List.of("Alice@Example.com", "bob@example.com", "carol@example.com")), ownerId.toString());

        assertThat(saved()).extracting(m -> m.getUser() == null ? null : m.getUser().getId(), InviteListMember::getEmail)
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple(bob.getId(), "bob@example.com"),
                        org.assertj.core.groups.Tuple.tuple(null, "carol@example.com"));
    }

    @Test
    @DisplayName("should throw 400 INVITE_LIST_MEMBER_IDS_INVALID when a user by ID is suspended or unknown")
    void shouldRejectAddWhenUserSuspended() {
        lockInviteList();
        User suspended = User.builder().id(UUID.randomUUID()).active(false).build();
        UUID unknown = UUID.randomUUID();
        when(userRepository.findAllById(Set.of(suspended.getId(), unknown))).thenReturn(List.of(suspended));

        assertThatThrownBy(() -> inviteListService.addMembers(inviteListId.toString(),
                members(List.of(suspended.getId(), unknown), null), ownerId.toString()))
                .satisfies(ex -> assertApiException(ex, MessageKey.INVITE_LIST_MEMBER_IDS_INVALID));
        verify(inviteListMemberRepository, never()).saveAllAndFlush(any());
    }

    @Test
    @DisplayName("should throw 400 INVITE_LIST_MEMBERS_MAX when the Invite List would exceed 100 members")
    void shouldRejectAddWhenMemberCapExceeded() {
        lockInviteList();
        List<InviteListMember> existing = IntStream.range(0, InviteListService.MAX_MEMBERS_PER_INVITE_LIST)
                .mapToObj(i -> new InviteListMember(inviteList, "m" + i + "@example.com")).toList();
        when(inviteListMemberRepository.findByInviteListId(inviteListId)).thenReturn(existing);

        assertThatThrownBy(() -> inviteListService.addMembers(inviteListId.toString(),
                members(null, List.of("new@example.com", "m1@example.com")), ownerId.toString()))
                .satisfies(ex -> assertApiException(ex, MessageKey.INVITE_LIST_MEMBERS_MAX));
        verify(inviteListMemberRepository, never()).saveAllAndFlush(any());
    }

    @Test
    @DisplayName("should throw 404 INVITE_LIST_NOT_FOUND on expansion when any Invite List is missing or someone else's, ignoring duplicates")
    void shouldRejectExpandWhenInviteListNotOwned() {
        UUID other = UUID.randomUUID();
        when(inviteListRepository.countByIdInAndOwnerId(Set.of(inviteListId, other), ownerId)).thenReturn(1L);
        when(inviteListRepository.countByIdInAndOwnerId(Set.of(inviteListId), ownerId)).thenReturn(1L);

        assertThatThrownBy(() -> inviteListService.expand(List.of(inviteListId, other), ownerId))
                .satisfies(ex -> assertApiException(ex, MessageKey.INVITE_LIST_NOT_FOUND));
        inviteListService.expand(List.of(inviteListId, inviteListId), ownerId);
        verify(inviteListMemberRepository).findWithUserByInviteListIdIn(argThat(ids -> ids.size() == 1));
    }

    @Test
    @DisplayName("should expand members by ID to active user IDs and email members to their stored address")
    void shouldSplitMembersWhenExpanding() {
        User bob = User.builder().id(UUID.randomUUID()).build();
        User suspended = User.builder().id(UUID.randomUUID()).active(false).build();
        User carol = User.builder().id(UUID.randomUUID()).email("carol@example.com").build();
        when(inviteListRepository.countByIdInAndOwnerId(Set.of(inviteListId), ownerId)).thenReturn(1L);
        when(inviteListMemberRepository.findWithUserByInviteListIdIn(Set.of(inviteListId))).thenReturn(List.of(
                new InviteListMember(inviteList, bob), new InviteListMember(inviteList, suspended), new InviteListMember(inviteList, carol, "carol@example.com")));

        InviteListService.Members members = inviteListService.expand(List.of(inviteListId), ownerId);

        assertThat(members.userIds()).containsExactly(bob.getId());
        assertThat(members.emails()).containsExactly("carol@example.com");
    }
}
