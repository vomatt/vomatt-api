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
    @Mock UserService userService;
    @Mock com.vomatt.common.audit.AuditService auditService;
    @Spy LockTimeout lockTimeout = new LockTimeout(mock(jakarta.persistence.EntityManager.class, org.mockito.Mockito.RETURNS_DEEP_STUBS));
    @Mock com.vomatt.repository.CommunityInvitationRepository invitationRepository;
    @Mock com.vomatt.common.email.InvitationEmailLimiter invitationEmailLimiter;
    @Mock com.vomatt.common.email.EmailService emailService;
    @Mock PlanService planService;
    @Mock com.vomatt.repository.UserPreferenceRepository preferenceRepository;
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
    void shouldRefuseInvitingOwnerWhenCallerIsAdmin() {
        caller(ADMIN);

        assertThatThrownBy(() -> service.invite(communityId, targetId, null, OWNER, callerId.toString()))
                .satisfies(ex -> assertKey(ex, MessageKey.COMMUNITY_ROLE_FORBIDDEN));
    }

    @Test
    void shouldRefuseInviteWhenUserIsCommunityAccount() {
        caller(OWNER);
        when(userRepository.findById(targetId)).thenReturn(Optional.of(
                User.builder().id(targetId).username("other_community").roles(new String[] { "community" }).build()));

        assertThatThrownBy(() -> service.invite(communityId, targetId, null, EDITOR, callerId.toString()))
                .satisfies(ex -> assertKey(ex, MessageKey.COMMUNITY_MEMBER_INVALID));
    }

    @Test
    void shouldReturn403WhenNonMemberListsMembers() {
        when(memberRepository.findActiveRole(communityId, callerId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.listMembers(communityId, callerId.toString()))
                .satisfies(ex -> assertKey(ex, MessageKey.COMMUNITY_NOT_MEMBER));
    }

    @Test
    void shouldMaskMemberDisplayNameWhenTheMemberHidesIt() {
        caller(EDITOR);
        User bob = User.builder().id(targetId).username("bob").displayName("Bob").build();
        CommunityMember member = mock(CommunityMember.class);
        when(member.getUser()).thenReturn(bob);
        when(member.getUserId()).thenReturn(targetId);
        when(memberRepository.findByCommunityUserIdOrderByCreatedAtAscUserIdAsc(communityId)).thenReturn(List.of(member));
        com.vomatt.entity.UserPreference hidden = new com.vomatt.entity.UserPreference();
        hidden.setUser(bob);
        hidden.setKey("visibility.displayName");
        hidden.setValue("false");
        when(preferenceRepository.findByUserIdInAndKeyIn(any(), any())).thenReturn(List.of(hidden));

        assertThat(service.listMembers(communityId, callerId.toString()))
                .singleElement().satisfies(m -> {
                    assertThat(m.username()).isEqualTo("bob");
                    assertThat(m.displayName()).isNull();
                });
    }

    @Test
    void shouldSkipRoomCheckWhenTransferringLockedCommunity() {
        caller(OWNER);
        CommunityMember target = target(EDITOR);
        when(memberRepository.findByCommunityUserIdAndUserId(communityId, callerId))
                .thenReturn(Optional.of(new CommunityMember(communityId, callerId, OWNER)));
        when(memberRepository.lockOwnerIds(communityId)).thenReturn(List.of(callerId));
        when(userRepository.existsByIdAndLockedAtIsNotNull(communityId)).thenReturn(true);

        service.transferOwner(communityId, targetId, callerId.toString());

        verify(planService, never()).requireRoom(any());
        assertThat(target.getRole()).isEqualTo(OWNER);
    }

    @Test
    void shouldRecordNewAccountInAuditDetailsWhenCreatingCommunity() {
        UUID newId = UUID.randomUUID();
        when(userRepository.findById(callerId)).thenReturn(Optional.of(User.builder().id(callerId).username("alice").build()));
        when(userRepository.saveAndFlush(any())).thenAnswer(inv -> User.builder().id(newId)
                .username(inv.<User>getArgument(0).getUsername()).displayName("Sunrise").build());
        var request = new org.springframework.mock.web.MockHttpServletRequest("POST", "/api/communities");
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                new org.springframework.web.context.request.ServletRequestAttributes(request));
        try {
            service.createCommunity("sunrise", "Sunrise", callerId);
        } finally {
            org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
        }

        assertThat(request.getAttribute(com.vomatt.common.audit.AuditAspect.class.getName() + ".details"))
                .isEqualTo("id=" + newId + "; username=sunrise");
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
        CommunityMember target = target(EDITOR);
        when(memberRepository.lockOwnerIds(communityId)).thenReturn(List.of(targetId));
        assertThatThrownBy(() -> service.transferOwner(communityId, targetId, callerId.toString()))
                .satisfies(ex -> assertKey(ex, MessageKey.COMMUNITY_ROLE_FORBIDDEN));
        assertThat(target.getRole()).isEqualTo(EDITOR);
    }

    // caller role (blank = not a member), expected error (blank = allowed)
    @ParameterizedTest
    @CsvSource({
            "OWNER, ",
            "ADMIN, ",
            "CHIEF_EDITOR, COMMUNITY_ROLE_FORBIDDEN",
            "EDITOR, COMMUNITY_ROLE_FORBIDDEN",
            "ANALYST, COMMUNITY_ROLE_FORBIDDEN",
            ", COMMUNITY_NOT_MEMBER",
    })
    void shouldAllowOnlyOwnerAndAdminWhenEditingProfile(CommunityRole role, MessageKey error) {
        when(memberRepository.findActiveRole(communityId, callerId)).thenReturn(Optional.ofNullable(role));
        lenient().when(userRepository.findById(communityId)).thenReturn(Optional.of(User.builder().id(communityId).username("sunrise").build()));
        var update = new com.vomatt.users.dto.UpdateCommunityRequest("Sunrise", null).toProfileUpdate();
        var file = mock(org.springframework.web.multipart.MultipartFile.class);
        lenient().when(userService.uploadAvatar(communityId.toString(), file)).thenReturn(mock(com.vomatt.users.dto.UserProfileResponse.class));

        if (error == null) {
            service.updateProfile(communityId, update, callerId.toString());
            service.uploadAvatar(communityId, file, callerId.toString());
            verify(userService).updateMyProfile(org.mockito.ArgumentMatchers.eq(communityId.toString()),
                    org.mockito.ArgumentMatchers.argThat(r -> "Sunrise".equals(r.getDisplayName()) && r.getUsername() == null));
            verify(userService).uploadAvatar(communityId.toString(), file);
        } else {
            assertThatThrownBy(() -> service.updateProfile(communityId, update, callerId.toString())).satisfies(ex -> assertKey(ex, error));
            assertThatThrownBy(() -> service.uploadAvatar(communityId, file, callerId.toString())).satisfies(ex -> assertKey(ex, error));
            org.mockito.Mockito.verifyNoInteractions(userService);
        }
    }

    private void savesInvitation() {
        when(invitationRepository.saveAndFlush(org.mockito.ArgumentMatchers.any())).thenAnswer(inv -> {
            com.vomatt.entity.CommunityInvitation saved = inv.getArgument(0);
            org.springframework.test.util.ReflectionTestUtils.setField(saved, "id", UUID.randomUUID());
            return saved;
        });
    }

    @Test
    void shouldMailOnInviterQuotaWhenInvitedAddressHasNoAccount() {
        caller(CommunityRole.ADMIN);
        savesInvitation();
        when(userRepository.existsByEmail("dave@example.com")).thenReturn(false);
        when(invitationEmailLimiter.claim(callerId, communityId, List.of("dave@example.com"))).thenReturn(List.of("dave@example.com"));
        when(userRepository.getReferenceById(communityId)).thenReturn(User.builder().id(communityId).username("tower").displayName("Sunrise Tower").build());
        when(userRepository.getReferenceById(callerId)).thenReturn(User.builder().id(callerId).username("amy").build());

        com.vomatt.users.dto.CommunityInvitationResponse invitation = service.invite(communityId, null, "dave@example.com", CommunityRole.EDITOR, callerId.toString());

        verify(emailService).sendCommunityInvitationEmail(org.mockito.ArgumentMatchers.eq("dave@example.com"),
                org.mockito.ArgumentMatchers.eq(UUID.fromString(invitation.id())), org.mockito.ArgumentMatchers.eq("amy"),
                org.mockito.ArgumentMatchers.eq("amy"), org.mockito.ArgumentMatchers.eq("Sunrise Tower"), org.mockito.ArgumentMatchers.eq("EDITOR"),
                org.mockito.ArgumentMatchers.eq(CommunityService.INVITATION_TTL), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void shouldNotMailWhenInvitedAddressHasAccount() {
        caller(CommunityRole.OWNER);
        savesInvitation();
        when(userRepository.existsByEmail("dave@example.com")).thenReturn(true);

        service.invite(communityId, null, "dave@example.com", CommunityRole.EDITOR, callerId.toString());

        org.mockito.Mockito.verifyNoInteractions(invitationEmailLimiter, emailService);
    }

    @Test
    void shouldRefuseDeleteWhenCallerIsNotMember() {
        when(memberRepository.findActiveRole(communityId, callerId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteCommunity(communityId, callerId.toString()))
                .satisfies(ex -> assertKey(ex, MessageKey.COMMUNITY_NOT_MEMBER));
        org.mockito.Mockito.verifyNoInteractions(userService);
    }
}
