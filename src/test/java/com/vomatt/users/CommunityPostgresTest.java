package com.vomatt.users;

import com.vomatt.comments.CommentMapper;
import com.vomatt.comments.VoteCommentService;
import com.vomatt.comments.dto.CreateCommentRequest;
import com.vomatt.common.audit.AuditAspect;
import com.vomatt.common.audit.AuditService;
import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.common.security.ActingMember;
import com.vomatt.common.security.RefreshTokenService;
import com.vomatt.common.security.UserPrincipal;
import com.vomatt.entity.CommunityRole;
import com.vomatt.entity.Plan;
import com.vomatt.entity.User;
import com.vomatt.entity.Vote;
import com.vomatt.entity.VoteComment;
import com.vomatt.repository.CommunityMemberRepository;
import com.vomatt.repository.PostgresRepositoryTest;
import com.vomatt.repository.UserRepository;
import com.vomatt.users.dto.UpdateCommunityRequest;
import com.vomatt.users.dto.UpdateProfileRequest;
import com.vomatt.users.dto.UserDto;
import com.vomatt.votes.VoteServiceSlice;
import com.vomatt.votes.dto.CreateVoteRequest;
import com.vomatt.votes.dto.VoteResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.test.util.AopTestUtils;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@Import({ CommunityService.class, UserService.class, UserMapper.class, VoteCommentService.class, CommentMapper.class,
        VoteServiceSlice.class, com.vomatt.common.storage.StorageService.class, PlanService.class, AuditService.class })
class CommunityPostgresTest extends PostgresRepositoryTest {

    @Autowired CommunityService communityService;
    @Autowired CommunityMemberRepository memberRepository;
    @Autowired UserRepository userRepository;
    @Autowired com.vomatt.votes.VoteService voteService;
    @Autowired VoteCommentService commentService;
    @Autowired UserService userService;
    @MockitoBean RefreshTokenService refreshTokenService;
    @Autowired PlanService planService;

    private User owner;
    private UUID communityId;

    @BeforeEach
    void setUp() {
        owner = persistUser("of-" + UUID.randomUUID().toString().substring(0, 8));
        em.flush();
        UserDto community = communityService.createCommunity(
                "community_" + UUID.randomUUID().toString().substring(0, 8), "Sunrise Tower", owner.getId());
        communityId = UUID.fromString(community.id());
        em.flush();
        em.clear();
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    /** The controller as a request reaches it: signed in as {@code memberId}, with the audit aspect around it. */
    private CommunityController auditedController(AuditService auditService, UUID memberId) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new UserPrincipal(memberId.toString(), List.of("user"), null), null, List.of()));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        AspectJProxyFactory factory = new AspectJProxyFactory(new CommunityController(auditedService(auditService)));
        factory.addAspect(new AuditAspect(auditService));
        return factory.getProxy();
    }

    private CommunityService auditedService(AuditService auditService) {
        AspectJProxyFactory factory = new AspectJProxyFactory((Object) AopTestUtils.getUltimateTargetObject(communityService));
        factory.addAspect(new AuditAspect(auditService));
        return factory.getProxy();
    }

    private UserController actingUserController(AuditService auditService, UUID memberId, CommunityRole role) {
        var auth = new UsernamePasswordAuthenticationToken(principal(communityId), null, List.of());
        auth.setDetails(new ActingMember(memberId, communityId, role));
        SecurityContextHolder.getContext().setAuthentication(auth);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        return new UserController(userService, null, null, null, null, planService, auditedService(auditService));
    }

    private User addAdmin() {
        User admin = persistUser("of-admin-" + UUID.randomUUID().toString().substring(0, 8));
        em.flush();
        communityService.addMember(communityId, admin.getId(), CommunityRole.ADMIN, owner.getId().toString());
        em.flush();
        em.clear();
        return admin;
    }

    @Test
    void shouldRenameAndAuditAsCommunityUpdateWhenAdminEditsProfileWhileActing() {
        User admin = addAdmin();
        AuditService auditService = mock(AuditService.class);
        UpdateProfileRequest rename = new UpdateProfileRequest();
        rename.setDisplayName(" Sunrise Club ");

        var response = actingUserController(auditService, admin.getId(), CommunityRole.ADMIN).updateMyProfile(rename, principal(communityId));

        assertThat(response.getBody().data().displayName()).isEqualTo("Sunrise Club");
        verify(auditService).logAction(eq(admin.getId().toString()), eq("UPDATE"), eq("COMMUNITY"), eq(communityId.toString()),
                any(), any(), any(), any(), eq("actingAs=" + communityId + "; displayName: \"Sunrise Tower\" -> \"Sunrise Club\""));
    }

    @Test
    void shouldRefuseUsernameChangeAndPersonFieldsWhenEditingProfileWhileActing() {
        User admin = addAdmin();
        String username = userRepository.findById(communityId).orElseThrow().getUsername();
        UserController controller = actingUserController(mock(AuditService.class), admin.getId(), CommunityRole.ADMIN);
        UpdateProfileRequest rename = new UpdateProfileRequest();
        rename.setUsername("renamed_" + UUID.randomUUID().toString().substring(0, 8));
        UpdateProfileRequest birthday = new UpdateProfileRequest();
        birthday.setBirthday(java.time.LocalDate.of(2000, 1, 1));
        UpdateProfileRequest sameName = new UpdateProfileRequest();
        sameName.setUsername(username);
        sameName.setBio("Residents");

        assertThatThrownBy(() -> controller.updateMyProfile(rename, principal(communityId))).isInstanceOfSatisfying(ApiException.class,
                ex -> assertThat(ex.getMessageKey()).isEqualTo(MessageKey.COMMUNITY_USERNAME_IMMUTABLE));
        assertThatThrownBy(() -> controller.updateMyProfile(birthday, principal(communityId))).isInstanceOfSatisfying(ApiException.class,
                ex -> assertThat(ex.getMessageKey()).isEqualTo(MessageKey.COMMON_BAD_REQUEST));
        controller.updateMyProfile(sameName, principal(communityId));
        em.flush();
        em.clear();

        User community = userRepository.findById(communityId).orElseThrow();
        assertThat(community.getUsername()).isEqualTo(username);
        assertThat(community.getBio()).isEqualTo("Residents");
        assertThat(community.getBirthday()).isNull();
    }

    private static UserPrincipal principal(UUID id) {
        return new UserPrincipal(id.toString(), List.of("user"), null);
    }

    private void actAs(UUID memberId, CommunityRole role) {
        var auth = new UsernamePasswordAuthenticationToken(communityId.toString(), null, List.of());
        auth.setDetails(new ActingMember(memberId, communityId, role));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    void shouldCreateCommunityWithoutEmailOrPhoneWhenAdminCreates() {
        User community = userRepository.findById(communityId).orElseThrow();

        assertThat(community.isCommunity()).isTrue();
        assertThat(community.getEmail()).isNull();
        assertThat(community.getPhoneNumber()).isNull();
        assertThat(memberRepository.findActiveRole(communityId, owner.getId())).contains(CommunityRole.OWNER);
        assertThat(communityService.listMine(owner.getId().toString()))
                .singleElement().satisfies(m -> assertThat(m.role()).isEqualTo(CommunityRole.OWNER));
        // The public profile badge comes from the profile projection
        assertThat(userRepository.findProfileByUsername(community.getUsername()).orElseThrow().getRoles()).contains("community");
    }

    @Test
    void shouldShowNewNameAndAuditBeforeAndAfterWhenAdminRenames() {
        User admin = persistUser("of-admin-" + UUID.randomUUID().toString().substring(0, 8));
        em.flush();
        communityService.addMember(communityId, admin.getId(), CommunityRole.ADMIN, owner.getId().toString());
        String username = userRepository.findById(communityId).orElseThrow().getUsername();
        em.flush();
        em.clear();
        AuditService auditService = mock(AuditService.class);

        var response = auditedController(auditService, admin.getId()).updateProfile(communityId,
                new UpdateCommunityRequest("  Sunrise Tower Committee ", "Residents"), principal(admin.getId()));
        em.flush();
        em.clear();

        assertThat(response.getBody().data().displayName()).isEqualTo("Sunrise Tower Committee");
        var profile = userService.getUserProfile(username, null);
        assertThat(profile.displayName()).isEqualTo("Sunrise Tower Committee");
        assertThat(profile.bio()).isEqualTo("Residents");
        assertThat(profile.community()).isTrue();
        verify(auditService).logAction(eq(admin.getId().toString()), eq("UPDATE"), eq("COMMUNITY"), eq(communityId.toString()),
                any(), any(), any(), any(),
                eq("displayName: \"Sunrise Tower\" -> \"Sunrise Tower Committee\"; bio: null -> \"Residents\""));
    }

    @Test
    void shouldLeaveProfileUnchangedWhenEditorUpdates() {
        User editor = persistUser("of-ed-" + UUID.randomUUID().toString().substring(0, 8));
        em.flush();
        communityService.addMember(communityId, editor.getId(), CommunityRole.EDITOR, owner.getId().toString());
        em.flush();

        assertThatThrownBy(() -> communityService.updateProfile(communityId, new UpdateCommunityRequest("Hijacked", null).toProfileUpdate(),
                editor.getId().toString()))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getMessageKey()).isEqualTo(MessageKey.COMMUNITY_ROLE_FORBIDDEN));
        em.clear();
        assertThat(userRepository.findById(communityId).orElseThrow().getDisplayName()).isEqualTo("Sunrise Tower");
    }

    @Test
    void shouldDeleteCommunityWithItsPollsAndMembershipsWhenOwnerDeletes() {
        User editor = persistUser("of-del-" + UUID.randomUUID().toString().substring(0, 8));
        em.flush();
        communityService.addMember(communityId, editor.getId(), CommunityRole.EDITOR, owner.getId().toString());
        User community = userRepository.findById(communityId).orElseThrow();
        String username = community.getUsername();
        Vote poll = persistPoll(community, OffsetDateTime.now().minusHours(1), OffsetDateTime.now().plusDays(1), "A", "B");
        em.flush();
        em.clear();
        AuditService auditService = mock(AuditService.class);

        auditedController(auditService, owner.getId()).deleteCommunity(communityId, principal(owner.getId()));
        em.flush();
        em.clear();

        assertThatThrownBy(() -> userService.getUserProfile(username, null))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getMessageKey()).isEqualTo(MessageKey.USER_NOT_FOUND));
        assertThat(communityService.listMine(owner.getId().toString())).isEmpty();
        assertThat(communityService.listMine(editor.getId().toString())).isEmpty();
        assertThat(em.find(Vote.class, poll.getId())).isNull();
        assertThat(userRepository.findById(owner.getId())).isPresent();
    }

    @Test
    void shouldKeepCommunityWhenAdminDeletes() {
        User admin = persistUser("of-deladm-" + UUID.randomUUID().toString().substring(0, 8));
        em.flush();
        communityService.addMember(communityId, admin.getId(), CommunityRole.ADMIN, owner.getId().toString());
        em.flush();

        assertThatThrownBy(() -> communityService.deleteCommunity(communityId, admin.getId().toString()))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getMessageKey()).isEqualTo(MessageKey.COMMUNITY_ROLE_FORBIDDEN));
        em.clear();
        assertThat(userRepository.findById(communityId)).isPresent();
        assertThat(communityService.listMine(admin.getId().toString())).singleElement()
                .satisfies(m -> assertThat(m.communityId()).isEqualTo(communityId.toString()));
    }

    @Test
    void shouldRefuseUsernameTakenWhenAnotherAccountHasIt() {
        String taken = userRepository.findById(communityId).orElseThrow().getUsername();

        assertThatThrownBy(() -> communityService.createCommunity(taken, "Copycat", owner.getId()))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getMessageKey()).isEqualTo(MessageKey.USER_USERNAME_TAKEN));
    }

    @Test
    void shouldRefuseOwnerWhenOwnerIsCommunityAccount() {
        assertThatThrownBy(() -> communityService.createCommunity("community_child", "Child", communityId))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getMessageKey()).isEqualTo(MessageKey.COMMUNITY_MEMBER_INVALID));
        assertThat(userRepository.existsByUsername("community_child")).isFalse();
    }

    @Test
    void shouldHideMembershipWhenCommunityIsSuspended() {
        em.createNativeQuery("UPDATE vomatt.users SET active = false WHERE id = :id").setParameter("id", communityId).executeUpdate();

        assertThat(memberRepository.findActiveRole(communityId, owner.getId())).isEmpty();
        assertThat(communityService.listMine(owner.getId().toString())).isEmpty();
    }

    @Test
    void shouldPersistActedByWhenMemberPublishesAsCommunity() {
        actAs(owner.getId(), CommunityRole.OWNER);
        CreateVoteRequest request = new CreateVoteRequest();
        request.setTitle("Elevator repair");
        request.setOptions(List.of(option("Yes"), option("No")));
        request.setEndTime(OffsetDateTime.now().plusDays(1));

        VoteResponse poll = voteService.createVote(request, communityId.toString());
        CreateCommentRequest comment = new CreateCommentRequest();
        comment.setText("Vote by Friday");
        String commentId = commentService.createComment(poll.getId(), communityId.toString(), comment).getId();
        em.flush();
        em.clear();

        assertThat(poll.isCreatorCommunity()).isTrue();
        assertThat(em.find(Vote.class, UUID.fromString(poll.getId())).getActedBy()).isEqualTo(owner.getId());
        assertThat(em.find(VoteComment.class, UUID.fromString(commentId)).getActedBy()).isEqualTo(owner.getId());
    }

    @Test
    void shouldLeaveActedByNullWhenUserPublishesAsThemself() {
        CreateVoteRequest request = new CreateVoteRequest();
        request.setTitle("Lunch");
        request.setOptions(List.of(option("Rice"), option("Noodles")));
        request.setEndTime(OffsetDateTime.now().plusDays(1));

        VoteResponse poll = voteService.createVote(request, owner.getId().toString());
        em.flush();
        em.clear();

        assertThat(poll.isCreatorCommunity()).isFalse();
        assertThat(em.find(Vote.class, UUID.fromString(poll.getId())).getActedBy()).isNull();
    }

    @Test
    void shouldRefuseRemovingLastOwnerWhenSoleOwner() {
        String ownerId = owner.getId().toString();

        assertThatThrownBy(() -> communityService.changeRole(communityId, owner.getId(), CommunityRole.ADMIN, ownerId))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getMessageKey()).isEqualTo(MessageKey.COMMUNITY_LAST_OWNER));
        assertThatThrownBy(() -> communityService.removeMember(communityId, owner.getId(), ownerId))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getMessageKey()).isEqualTo(MessageKey.COMMUNITY_LAST_OWNER));
        assertThat(memberRepository.isSoleOwnerOfAny(owner.getId())).isTrue();
    }

    @Test
    void shouldStepDownWhenAnotherOwnerRemains() {
        User second = persistUser("of2-" + UUID.randomUUID().toString().substring(0, 8));
        em.flush();
        String ownerId = owner.getId().toString();
        communityService.addMember(communityId, second.getId(), CommunityRole.OWNER, ownerId);
        em.flush();

        communityService.changeRole(communityId, owner.getId(), CommunityRole.ADMIN, ownerId);
        em.flush();
        // A new request: members are read fresh with their users
        em.clear();

        assertThat(memberRepository.findActiveRole(communityId, owner.getId())).contains(CommunityRole.ADMIN);
        assertThat(memberRepository.isSoleOwnerOfAny(second.getId())).isTrue();
        assertThat(communityService.listMembers(communityId, ownerId)).hasSize(2);
    }

    @Test
    void shouldTransferOwnershipWhenOwnerTransfers() {
        User editor = persistUser("of3-" + UUID.randomUUID().toString().substring(0, 8));
        em.flush();
        String ownerId = owner.getId().toString();
        communityService.addMember(communityId, editor.getId(), CommunityRole.EDITOR, ownerId);
        em.flush();

        communityService.transferOwner(communityId, editor.getId(), ownerId);
        em.flush();

        assertThat(memberRepository.findActiveRole(communityId, editor.getId())).contains(CommunityRole.OWNER);
        assertThat(memberRepository.findActiveRole(communityId, owner.getId())).contains(CommunityRole.ADMIN);
    }

    @Test
    void shouldRefuseSecondCommunityWhenFreeUserOwnsOne() {
        assertLimitReached(() -> createFor(owner), 1, 1);
        assertThat(planService.quota(userRepository.findById(owner.getId()).orElseThrow())).satisfies(q -> {
            assertThat(q.owned()).isEqualTo(1);
            assertThat(q.limit()).isEqualTo(1);
        });
    }

    @Test
    void shouldNotCountSuspendedCommunityWhenFreeUserCreatesAnother() {
        em.createNativeQuery("UPDATE vomatt.users SET active = false WHERE id = :id").setParameter("id", communityId).executeUpdate();

        createFor(owner);
        em.flush();
        em.clear();

        assertThat(planService.quota(userRepository.findById(owner.getId()).orElseThrow()).owned()).isEqualTo(1);
        assertThat(communityService.listMine(owner.getId().toString())).hasSize(1);
    }

    @Test
    void shouldAllowThreeCommunitiesWhenProAndRefuseFourth() {
        planService.changePlan(owner.getId(), Plan.PRO, PlanService.Source.ADMIN, null);
        createFor(owner);
        createFor(owner);

        assertLimitReached(() -> createFor(owner), 3, 3);
        em.flush();
        em.clear();
        assertThat(userRepository.findById(owner.getId()).orElseThrow().getPlan()).isEqualTo(Plan.PRO);
        assertThat(em.createNativeQuery("SELECT details FROM vomatt.audit_logs WHERE action = 'CHANGE_PLAN' AND resource_id = :id")
                .setParameter("id", owner.getId().toString()).getResultList())
                .containsExactly("from=FREE; to=PRO; source=ADMIN");
    }

    @Test
    void shouldChangeNothingWhenPlanIsUnchanged() {
        planService.changePlan(owner.getId(), Plan.FREE, PlanService.Source.ADMIN, null);
        em.flush();

        assertThat(em.createNativeQuery("SELECT count(*) FROM vomatt.audit_logs WHERE resource_id = :id")
                .setParameter("id", owner.getId().toString()).getSingleResult()).isEqualTo(0L);
    }

    @Test
    void shouldRefuseTransferAndOwnerGrantWhenRecipientIsFull() {
        User full = persistUser("full-" + UUID.randomUUID().toString().substring(0, 8));
        User fullToo = persistUser("full2-" + UUID.randomUUID().toString().substring(0, 8));
        em.flush();
        createFor(full);
        createFor(fullToo);
        String ownerId = owner.getId().toString();
        communityService.addMember(communityId, full.getId(), CommunityRole.EDITOR, ownerId);
        em.flush();

        assertLimitReached(() -> communityService.transferOwner(communityId, full.getId(), ownerId), 1, 1);
        assertLimitReached(() -> communityService.changeRole(communityId, full.getId(), CommunityRole.OWNER, ownerId), 1, 1);
        assertLimitReached(() -> communityService.addMember(communityId, fullToo.getId(), CommunityRole.OWNER, ownerId), 1, 1);
        assertThat(memberRepository.findActiveRole(communityId, full.getId())).contains(CommunityRole.EDITOR);
        assertThat(memberRepository.findActiveRole(communityId, fullToo.getId())).isEmpty();
    }

    @Test
    void shouldTransferWhenRecipientIsAlreadyOwner() {
        User coOwner = persistUser("co-" + UUID.randomUUID().toString().substring(0, 8));
        em.flush();
        String ownerId = owner.getId().toString();
        // The co-owner is at the FREE limit through this very community account
        communityService.addMember(communityId, coOwner.getId(), CommunityRole.OWNER, ownerId);
        em.flush();

        communityService.transferOwner(communityId, coOwner.getId(), ownerId);
        em.flush();

        assertThat(memberRepository.findActiveRole(communityId, owner.getId())).contains(CommunityRole.ADMIN);
    }

    private UUID createFor(User user) {
        return UUID.fromString(communityService.createCommunity("community_" + UUID.randomUUID().toString().substring(0, 8),
                "Club", user.getId()).id());
    }

    private static void assertLimitReached(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, int limit, int owned) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, ex -> {
            assertThat(ex.getMessageKey()).isEqualTo(MessageKey.COMMUNITY_LIMIT_REACHED);
            assertThat(ex.getData()).isEqualTo(new PlanService.LimitReached(limit, owned));
        });
    }

    private static CreateVoteRequest.VoteOptionRequest option(String text) {
        CreateVoteRequest.VoteOptionRequest option = new CreateVoteRequest.VoteOptionRequest();
        option.setText(text);
        return option;
    }
}
