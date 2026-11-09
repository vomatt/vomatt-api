package com.vomatt.users;

import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.entity.OfficialMember;
import com.vomatt.entity.OfficialRole;
import com.vomatt.entity.User;
import com.vomatt.repository.LockTimeout;
import com.vomatt.repository.OfficialMemberRepository;
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

import static com.vomatt.entity.OfficialRole.ADMIN;
import static com.vomatt.entity.OfficialRole.ANALYST;
import static com.vomatt.entity.OfficialRole.EDITOR;
import static com.vomatt.entity.OfficialRole.OWNER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OfficialServiceTest {

    @Mock OfficialMemberRepository memberRepository;
    @Mock UserRepository userRepository;
    @Spy UserMapper userMapper = new UserMapper();
    @Spy LockTimeout lockTimeout = new LockTimeout(mock(jakarta.persistence.EntityManager.class, org.mockito.Mockito.RETURNS_DEEP_STUBS));
    @InjectMocks OfficialService service;

    final UUID officialId = UUID.randomUUID();
    final UUID callerId = UUID.randomUUID();
    final UUID targetId = UUID.randomUUID();

    private void caller(OfficialRole role) {
        lenient().when(memberRepository.findActiveRole(officialId, callerId)).thenReturn(Optional.of(role));
    }

    private OfficialMember target(OfficialRole role) {
        OfficialMember member = new OfficialMember(officialId, targetId, role);
        lenient().when(userRepository.getReferenceById(targetId)).thenReturn(User.builder().id(targetId).username("bob").build());
        lenient().when(memberRepository.findByOfficialUserIdAndUserId(officialId, targetId)).thenReturn(Optional.of(member));
        return member;
    }

    private static void assertKey(Throwable ex, MessageKey key) {
        assertThat(ex).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getMessageKey()).isEqualTo(key));
    }

    @Test
    void shouldMatchCapabilityTableWhenCheckingRoles() {
        assertThat(List.of(OfficialRole.values())).extracting(OfficialRole::canManageMembers).containsExactly(true, true, false, false);
        assertThat(List.of(OfficialRole.values())).extracting(OfficialRole::canEditProfile).containsExactly(true, true, false, false);
        assertThat(List.of(OfficialRole.values())).extracting(OfficialRole::canPublish).containsExactly(true, true, true, false);
        assertThat(List.of(OfficialRole.values())).extracting(OfficialRole::canEditAllPolls).containsExactly(true, true, false, false);
        assertThat(List.of(OfficialRole.values())).extracting(OfficialRole::canReadReports).containsExactly(true, true, false, true);
    }

    // caller role, target's current role, new role, expected error (blank = allowed)
    @ParameterizedTest
    @CsvSource({
            "OWNER, EDITOR, OWNER, ",
            "OWNER, OWNER, EDITOR, ",
            "ADMIN, EDITOR, ANALYST, ",
            "ADMIN, EDITOR, OWNER, OFFICIAL_ROLE_FORBIDDEN",
            "ADMIN, OWNER, EDITOR, OFFICIAL_ROLE_FORBIDDEN",
            "EDITOR, ANALYST, EDITOR, OFFICIAL_ROLE_FORBIDDEN",
            "ANALYST, EDITOR, ANALYST, OFFICIAL_ROLE_FORBIDDEN",
    })
    void shouldFollowRoleMatrixWhenChangingRole(OfficialRole callerRole, OfficialRole current, OfficialRole next, MessageKey error) {
        caller(callerRole);
        OfficialMember member = target(current);
        // Another OWNER remains, so the last-OWNER guard never decides here
        lenient().when(memberRepository.lockOwnerIds(officialId)).thenReturn(List.of(callerId, targetId));

        if (error == null) {
            service.changeRole(officialId, targetId, next, callerId.toString());
            assertThat(member.getRole()).isEqualTo(next);
        } else {
            assertThatThrownBy(() -> service.changeRole(officialId, targetId, next, callerId.toString()))
                    .satisfies(ex -> assertKey(ex, error));
            assertThat(member.getRole()).isEqualTo(current);
        }
    }

    @Test
    void shouldLetMemberLeaveWhenAnalystRemovesThemself() {
        when(memberRepository.findActiveRole(officialId, targetId)).thenReturn(Optional.of(ANALYST));
        OfficialMember self = target(ANALYST);

        service.removeMember(officialId, targetId, targetId.toString());

        verify(memberRepository).delete(self);
    }

    @Test
    void shouldReturn409WhenLastOwnerLeaves() {
        when(memberRepository.findActiveRole(officialId, targetId)).thenReturn(Optional.of(OWNER));
        target(OWNER);
        when(memberRepository.lockOwnerIds(officialId)).thenReturn(List.of(targetId));

        assertThatThrownBy(() -> service.removeMember(officialId, targetId, targetId.toString()))
                .satisfies(ex -> assertKey(ex, MessageKey.OFFICIAL_LAST_OWNER));
        verify(memberRepository, never()).delete(any());
    }

    @Test
    void shouldRefuseRemovingOwnerWhenCallerIsAdmin() {
        caller(ADMIN);
        target(OWNER);

        assertThatThrownBy(() -> service.removeMember(officialId, targetId, callerId.toString()))
                .satisfies(ex -> assertKey(ex, MessageKey.OFFICIAL_ROLE_FORBIDDEN));
        verify(memberRepository, never()).delete(any());
    }

    @Test
    void shouldReturn409WhenDemotingLastOwner() {
        caller(OWNER);
        target(OWNER);
        when(memberRepository.lockOwnerIds(officialId)).thenReturn(List.of(targetId));

        assertThatThrownBy(() -> service.changeRole(officialId, targetId, ADMIN, callerId.toString()))
                .satisfies(ex -> assertKey(ex, MessageKey.OFFICIAL_LAST_OWNER));
        assertThatThrownBy(() -> service.removeMember(officialId, targetId, callerId.toString()))
                .satisfies(ex -> assertKey(ex, MessageKey.OFFICIAL_LAST_OWNER));
        verify(memberRepository, never()).delete(any());
    }

    @Test
    void shouldRefuseAddingOwnerWhenCallerIsAdmin() {
        caller(ADMIN);

        assertThatThrownBy(() -> service.addMember(officialId, targetId, OWNER, callerId.toString()))
                .satisfies(ex -> assertKey(ex, MessageKey.OFFICIAL_ROLE_FORBIDDEN));
    }

    @Test
    void shouldRefuseMemberWhenUserIsOfficialAccount() {
        caller(OWNER);
        when(userRepository.findById(targetId)).thenReturn(Optional.of(
                User.builder().id(targetId).username("other_official").roles(new String[] { "official" }).build()));

        assertThatThrownBy(() -> service.addMember(officialId, targetId, EDITOR, callerId.toString()))
                .satisfies(ex -> assertKey(ex, MessageKey.OFFICIAL_MEMBER_INVALID));
    }

    @Test
    void shouldReturn403WhenNonMemberListsMembers() {
        when(memberRepository.findActiveRole(officialId, callerId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.listMembers(officialId, callerId.toString()))
                .satisfies(ex -> assertKey(ex, MessageKey.OFFICIAL_NOT_MEMBER));
    }

    @Test
    void shouldRefuseTransferWhenCallerIsNotOwner() {
        caller(ADMIN);
        target(EDITOR);

        assertThatThrownBy(() -> service.transferOwner(officialId, targetId, callerId.toString()))
                .satisfies(ex -> assertKey(ex, MessageKey.OFFICIAL_ROLE_FORBIDDEN));
    }

    @Test
    void shouldAllowReportsWhenRoleIsOwnerAdminOrAnalyst() {
        for (OfficialRole role : OfficialRole.values()) {
            when(memberRepository.findActiveRole(officialId, callerId)).thenReturn(Optional.of(role));
            assertThat(service.canReadReports(officialId, callerId)).as(role.name()).isEqualTo(role != EDITOR);
        }
        when(memberRepository.findActiveRole(officialId, callerId)).thenReturn(Optional.empty());
        assertThat(service.canReadReports(officialId, callerId)).isFalse();
        assertThat(ANALYST.canPublish()).isFalse();
    }
}
