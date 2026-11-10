package com.vomatt.users;

import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.entity.CommunityMember;
import com.vomatt.entity.CommunityRole;
import com.vomatt.entity.User;
import com.vomatt.repository.LockTimeout;
import com.vomatt.repository.CommunityMemberRepository;
import com.vomatt.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.vomatt.entity.CommunityRole.ADMIN;
import static com.vomatt.entity.CommunityRole.ANALYST;
import static com.vomatt.entity.CommunityRole.EDITOR;
import static com.vomatt.entity.CommunityRole.OWNER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommunityServiceTest {

    @Mock CommunityMemberRepository memberRepository;
    @Mock UserRepository userRepository;
    @Spy UserMapper userMapper = new UserMapper();
    @Spy LockTimeout lockTimeout = new LockTimeout(mock(jakarta.persistence.EntityManager.class, org.mockito.Mockito.RETURNS_DEEP_STUBS));
    @InjectMocks CommunityService service;

    final UUID communityId = UUID.randomUUID();
    final UUID callerId = UUID.randomUUID();
    final UUID targetId = UUID.randomUUID();

    private void caller(CommunityRole role) {
        lenient().when(memberRepository.findActiveRole(communityId, callerId)).thenReturn(Optional.of(role));
    }

    private CommunityMember target(CommunityRole role) {
        CommunityMember member = new CommunityMember(communityId, targetId, role);
        lenient().when(userRepository.getReferenceById(targetId)).thenReturn(User.builder().id(targetId).username("bob").build());
        lenient().when(memberRepository.findByCommunityUserIdAndUserId(communityId, targetId)).thenReturn(Optional.of(member));
        return member;
    }

    private static void assertKey(Throwable ex, MessageKey key) {
        assertThat(ex).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getMessageKey()).isEqualTo(key));
    }

    @Test
    void shouldMatchCapabilityTableWhenCheckingRoles() {
        // OWNER, ADMIN, CHIEF_EDITOR, EDITOR, ANALYST
        assertThat(List.of(CommunityRole.values())).extracting(CommunityRole::canManageMembers).containsExactly(true, true, false, false, false);
        assertThat(List.of(CommunityRole.values())).extracting(CommunityRole::canEditProfile).containsExactly(true, true, false, false, false);
        assertThat(List.of(CommunityRole.values())).extracting(CommunityRole::canPublish).containsExactly(true, true, true, true, false);
        assertThat(List.of(CommunityRole.values())).extracting(CommunityRole::canEditAllContent).containsExactly(true, true, true, false, false);
        assertThat(List.of(CommunityRole.values())).extracting(CommunityRole::canReadReports).containsExactly(true, true, false, false, true);
    }

    // caller role, target's current role, new role, expected error (blank = allowed)
    @ParameterizedTest
    @CsvSource({
            "OWNER, EDITOR, OWNER, ",
            "OWNER, OWNER, EDITOR, ",
            "ADMIN, EDITOR, ANALYST, ",
            "ADMIN, EDITOR, CHIEF_EDITOR, ",
            "CHIEF_EDITOR, EDITOR, ANALYST, COMMUNITY_ROLE_FORBIDDEN",
            "ADMIN, EDITOR, OWNER, COMMUNITY_ROLE_FORBIDDEN",
            "ADMIN, OWNER, EDITOR, COMMUNITY_ROLE_FORBIDDEN",
            "EDITOR, ANALYST, EDITOR, COMMUNITY_ROLE_FORBIDDEN",
            "ANALYST, EDITOR, ANALYST, COMMUNITY_ROLE_FORBIDDEN",
    })
    void shouldFollowRoleMatrixWhenChangingRole(CommunityRole callerRole, CommunityRole current, CommunityRole next, MessageKey error) {
        caller(callerRole);
        CommunityMember member = target(current);
        // Another OWNER remains, so the last-OWNER guard never decides here
        lenient().when(memberRepository.lockOwnerIds(communityId)).thenReturn(List.of(callerId, targetId));

        if (error == null) {
            service.changeRole(communityId, targetId, next, callerId.toString());
            assertThat(member.getRole()).isEqualTo(next);
        } else {
            assertThatThrownBy(() -> service.changeRole(communityId, targetId, next, callerId.toString()))
                    .satisfies(ex -> assertKey(ex, error));
            assertThat(member.getRole()).isEqualTo(current);
        }
    }

    @Test
    void shouldLetMemberLeaveWhenAnalystRemovesThemself() {
        when(memberRepository.findActiveRole(communityId, targetId)).thenReturn(Optional.of(ANALYST));
        CommunityMember self = target(ANALYST);

        service.removeMember(communityId, targetId, targetId.toString());

        verify(memberRepository).delete(self);
    }

    @Test
    void shouldReturn409WhenLastOwnerLeaves() {
        when(memberRepository.findActiveRole(communityId, targetId)).thenReturn(Optional.of(OWNER));
        target(OWNER);
        when(memberRepository.lockOwnerIds(communityId)).thenReturn(List.of(targetId));

        assertThatThrownBy(() -> service.removeMember(communityId, targetId, targetId.toString()))
                .satisfies(ex -> assertKey(ex, MessageKey.COMMUNITY_LAST_OWNER));
        verify(memberRepository, never()).delete(any());
    }

    @Test
    void shouldRefuseRemovingOwnerWhenCallerIsAdmin() {
        caller(ADMIN);
        target(OWNER);

        assertThatThrownBy(() -> service.removeMember(communityId, targetId, callerId.toString()))
                .satisfies(ex -> assertKey(ex, MessageKey.COMMUNITY_ROLE_FORBIDDEN));
        verify(memberRepository, never()).delete(any());
    }

    @Test
    void shouldReturn409WhenDemotingLastOwner() {
        caller(OWNER);
        target(OWNER);
        when(memberRepository.lockOwnerIds(communityId)).thenReturn(List.of(targetId));

        assertThatThrownBy(() -> service.changeRole(communityId, targetId, ADMIN, callerId.toString()))
                .satisfies(ex -> assertKey(ex, MessageKey.COMMUNITY_LAST_OWNER));
        assertThatThrownBy(() -> service.removeMember(communityId, targetId, callerId.toString()))
                .satisfies(ex -> assertKey(ex, MessageKey.COMMUNITY_LAST_OWNER));
        verify(memberRepository, never()).delete(any());
    }

    @Test
    void shouldRefuseAddingOwnerWhenCallerIsAdmin() {
        caller(ADMIN);

        assertThatThrownBy(() -> service.addMember(communityId, targetId, OWNER, callerId.toString()))
                .satisfies(ex -> assertKey(ex, MessageKey.COMMUNITY_ROLE_FORBIDDEN));
    }

    @Test
    void shouldRefuseMemberWhenUserIsCommunityAccount() {
        caller(OWNER);
        when(userRepository.findById(targetId)).thenReturn(Optional.of(
                User.builder().id(targetId).username("other_community").roles(new String[] { "community" }).build()));

        assertThatThrownBy(() -> service.addMember(communityId, targetId, EDITOR, callerId.toString()))
                .satisfies(ex -> assertKey(ex, MessageKey.COMMUNITY_MEMBER_INVALID));
    }

    @Test
    void shouldReturn403WhenNonMemberListsMembers() {
        when(memberRepository.findActiveRole(communityId, callerId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.listMembers(communityId, callerId.toString()))
                .satisfies(ex -> assertKey(ex, MessageKey.COMMUNITY_NOT_MEMBER));
    }

    @Test
    void shouldRefuseTransferWhenCallerIsNotOwner() {
        caller(ADMIN);
        target(EDITOR);

        assertThatThrownBy(() -> service.transferOwner(communityId, targetId, callerId.toString()))
                .satisfies(ex -> assertKey(ex, MessageKey.COMMUNITY_ROLE_FORBIDDEN));
    }


    @Test
    void shouldRefuseTransferWhenOwnerWasDemotedBeforeTheLock() {
        caller(OWNER);
        // Read as OWNER, but another OWNER demoted the caller before the OWNER rows were locked
        when(memberRepository.lockOwnerIds(communityId)).thenReturn(List.of(targetId));
        assertThatThrownBy(() -> service.transferOwner(communityId, targetId, callerId.toString()))
                .satisfies(ex -> assertKey(ex, MessageKey.COMMUNITY_ROLE_FORBIDDEN));
        verify(memberRepository, never()).findByCommunityUserIdAndUserId(any(), any());
    }
}
