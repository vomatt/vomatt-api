package com.vomatt.users;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.core.context.SecurityContextHolder;

import com.vomatt.admin.AdminMapper;
import com.vomatt.admin.AdminUserService;
import com.vomatt.auth.AuthService;
import com.vomatt.common.audit.AuditService;
import com.vomatt.common.constant.UserRole;
import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.common.security.ActingMember;
import com.vomatt.common.security.RefreshTokenService;
import com.vomatt.common.storage.StorageService;
import com.vomatt.entity.CommunityMember;
import com.vomatt.entity.CommunityRole;
import com.vomatt.entity.NotificationKind;
import com.vomatt.entity.Plan;
import com.vomatt.entity.User;
import com.vomatt.repository.AdminQueryRepository;
import com.vomatt.repository.CommunityMemberRepository;
import com.vomatt.repository.NotificationRepository;
import com.vomatt.repository.PostgresRepositoryTest;
import com.vomatt.repository.UserRepository;
import com.vomatt.users.dto.CommunityQuota;
import com.vomatt.users.dto.MyCommunityResponse;
import com.vomatt.votes.VoteService;
import com.vomatt.votes.VoteServiceSlice;
import com.vomatt.votes.dto.CreateVoteRequest;

/** Downgrade, choosing what to keep, the deadline job, unlocking and upgrading, on a PRO owner of three community accounts. */
@Import({ CommunityService.class, UserService.class, UserMapper.class, PlanService.class, AuditService.class,
        CommunitySelectionJob.class, VoteServiceSlice.class, StorageService.class, NotificationRepository.class,
        AdminUserService.class, AdminQueryRepository.class, AdminMapper.class })
class CommunityLockPostgresTest extends PostgresRepositoryTest {

    @Autowired PlanService planService;
    @Autowired CommunityService communityService;
    @Autowired CommunitySelectionJob job;
    @Autowired UserRepository userRepository;
    @Autowired CommunityMemberRepository memberRepository;
    @Autowired VoteService voteService;
    @Autowired NotificationRepository notificationRepository;
    @Autowired AdminUserService adminUserService;
    @MockitoBean RefreshTokenService refreshTokenService;
    @MockitoBean AuthService authService;

    private User owner;
    private UUID first;
    private UUID second;
    private UUID third;

    @BeforeEach
    void setUp() {
        owner = persistUser("lock-" + UUID.randomUUID().toString().substring(0, 8));
        em.flush();
        planService.changePlan(owner.getId(), Plan.PRO, PlanService.Source.ADMIN, null);
        first = create("2026-01-01T00:00:00Z");
        second = create("2026-02-01T00:00:00Z");
        third = create("2026-03-01T00:00:00Z");
        em.flush();
        em.clear();
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private UUID create(String createdAt) {
        UUID id = UUID.fromString(communityService.createCommunity("lock_" + UUID.randomUUID().toString().substring(0, 8),
                "Club", owner.getId()).id());
        em.flush();
        em.createNativeQuery("UPDATE vomatt.users SET created_at = CAST(:at AS timestamptz) WHERE id = :id")
                .setParameter("at", createdAt).setParameter("id", id).executeUpdate();
        return id;
    }

    private void downgrade() {
        planService.changePlan(owner.getId(), Plan.FREE, PlanService.Source.ADMIN, null);
        em.flush();
        em.clear();
    }

    /** A community account the person owns, made without the limit check, as a plan the limit no longer covers leaves. */
    private UUID adopt(User person, String createdAt) {
        User community = User.builder().username("lock_" + UUID.randomUUID().toString().substring(0, 8)).displayName("Club")
                .roles(new String[] { UserRole.COMMUNITY }).build();
        em.persist(community);
        em.persist(new CommunityMember(community.getId(), person.getId(), CommunityRole.OWNER));
        em.flush();
        em.createNativeQuery("UPDATE vomatt.users SET created_at = CAST(:at AS timestamptz) WHERE id = :id")
                .setParameter("at", createdAt).setParameter("id", community.getId()).executeUpdate();
        return community.getId();
    }

    private void addOwner(UUID communityId, User person) {
        em.persist(new CommunityMember(communityId, person.getId(), CommunityRole.OWNER));
        em.flush();
    }

    private OffsetDateTime storedDeadline(User person) {
        em.flush();
        em.clear();
        return userRepository.findSelectionDeadline(person.getId()).orElse(null);
    }

    private CommunityQuota quota(User person) {
        em.flush();
        em.clear();
        return planService.quota(userRepository.findById(person.getId()).orElseThrow());
    }

    /** The actors of the person's COMMUNITY_LOCKED notifications. */
    private List<UUID> lockNotifications(User person) {
        em.flush();
        return notificationRepository.findPage(person.getId(), EnumSet.allOf(NotificationKind.class), null, OffsetDateTime.now(), 50)
                .stream().filter(n -> n.kind().name().equals("COMMUNITY_LOCKED")).map(NotificationRepository.NotificationRow::actorId).toList();
    }

    private CommunityQuota quota() {
        em.flush();
        em.clear();
        return planService.quota(userRepository.findById(owner.getId()).orElseThrow());
    }

    private Map<UUID, Boolean> lockedById() {
        em.flush();
        em.clear();
        return communityService.listMine(owner.getId().toString()).stream()
                .collect(Collectors.toMap(m -> UUID.fromString(m.communityId()), MyCommunityResponse::locked));
    }

    /** The LOCK / UNLOCK audit entries of the owner's three community accounts, as "action reason". */
    @SuppressWarnings("unchecked")
    private List<String> lockAudits() {
        em.flush();
        return em.createNativeQuery("SELECT action || ' ' || details FROM vomatt.audit_logs WHERE resource_id IN (:ids) "
                + "AND action IN ('LOCK', 'UNLOCK') ORDER BY action, details")
                .setParameter("ids", List.of(first.toString(), second.toString(), third.toString())).getResultList();
    }

    private static void assertKey(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, MessageKey key) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getMessageKey()).isEqualTo(key));
    }

    @Test
    void shouldRequireSelectionAndKeepCommunitiesWorkingWhenProDowngrades() {
        downgrade();

        CommunityQuota quota = quota();
        assertThat(quota.owned()).isEqualTo(3);
        assertThat(quota.limit()).isEqualTo(1);
        assertThat(quota.selectionRequired()).isTrue();
        assertThat(quota.selectionDeadline()).isBetween(OffsetDateTime.now().plusDays(7).minusMinutes(1), OffsetDateTime.now().plusDays(7));
        assertThat(lockedById()).containsOnly(Map.entry(first, false), Map.entry(second, false), Map.entry(third, false));
    }

    @Test
    void shouldNotRequireSelectionWhenDowngradeStaysWithinLimit() {
        communityService.deleteCommunity(second, owner.getId().toString());
        communityService.deleteCommunity(third, owner.getId().toString());
        downgrade();

        assertThat(quota().selectionRequired()).isFalse();
        assertThat(quota().selectionDeadline()).isNull();
    }

    @Test
    void shouldLockTheRestWhenKeepingAndChangeNothingWhenResent() {
        downgrade();

        planService.keep(owner.getId(), Set.of(second));
        List<String> audits = lockAudits();
        planService.keep(owner.getId(), Set.of(second));

        assertThat(lockedById()).containsOnly(Map.entry(first, true), Map.entry(second, false), Map.entry(third, true));
        assertThat(lockAudits()).isEqualTo(audits).containsExactly("LOCK reason=USER_CHOICE", "LOCK reason=USER_CHOICE");
        CommunityQuota quota = quota();
        assertThat(quota.owned()).isEqualTo(1);
        assertThat(quota.selectionRequired()).isFalse();
        assertThat(quota.selectionDeadline()).isNull();
    }

    @Test
    void shouldSwapWhichStaysUnlockedWhenKeepingAnother() {
        downgrade();
        planService.keep(owner.getId(), Set.of(second));

        planService.keep(owner.getId(), Set.of(third));

        assertThat(lockedById()).containsOnly(Map.entry(first, true), Map.entry(second, true), Map.entry(third, false));
    }

    @Test
    void shouldRefuseKeepWhenOverLimitOrNotOwned() {
        downgrade();

        assertKey(() -> planService.keep(owner.getId(), Set.of(first, second)), MessageKey.COMMUNITY_KEEP_INVALID);
        assertKey(() -> planService.keep(owner.getId(), Set.of(UUID.randomUUID())), MessageKey.COMMUNITY_KEEP_INVALID);
        assertKey(() -> planService.keep(owner.getId(), Set.of()), MessageKey.COMMUNITY_KEEP_INVALID);
        assertThat(lockedById()).doesNotContainValue(true);
    }

    @Test
    void shouldKeepEarliestAndLockTheRestOnceWhenDeadlinePasses() {
        downgrade();
        OffsetDateTime now = OffsetDateTime.now();
        assertThat(job.run(now)).as("before the deadline").isZero();
        em.createNativeQuery("UPDATE vomatt.users SET community_selection_deadline = :at WHERE id = :id")
                .setParameter("at", now.minusMinutes(1)).setParameter("id", owner.getId()).executeUpdate();

        assertThat(job.run(now)).isEqualTo(2);
        assertThat(job.run(now)).as("second run").isZero();

        assertThat(lockedById()).containsOnly(Map.entry(first, false), Map.entry(second, true), Map.entry(third, true));
        assertThat(lockAudits()).containsExactly("LOCK reason=SELECTION_DEADLINE", "LOCK reason=SELECTION_DEADLINE");
        assertThat(em.createNativeQuery("SELECT count(*) FROM vomatt.audit_logs WHERE action = 'LOCK' AND user_id IS NULL "
                + "AND resource_id IN (:ids)").setParameter("ids", List.of(second.toString(), third.toString())).getSingleResult())
                .isEqualTo(2L);
        assertThat(quota().selectionRequired()).isFalse();
    }

    @Test
    void shouldUnlockAllWhenUpgradedAgain() {
        downgrade();
        planService.keep(owner.getId(), Set.of(first));

        planService.changePlan(owner.getId(), Plan.PRO, PlanService.Source.ADMIN, null);

        assertThat(lockedById()).doesNotContainValue(true);
        assertThat(lockAudits()).containsExactly("LOCK reason=USER_CHOICE", "LOCK reason=USER_CHOICE",
                "UNLOCK reason=UPGRADE", "UNLOCK reason=UPGRADE");
        assertThat(quota().owned()).isEqualTo(3);
    }

    @Test
    void shouldUnlockOnlyWithRoomWhenOwnerUnlocks() {
        User friend = persistUser("friend-" + UUID.randomUUID().toString().substring(0, 8));
        em.flush();
        communityService.addMember(first, friend.getId(), CommunityRole.EDITOR, owner.getId().toString());
        downgrade();
        planService.keep(owner.getId(), Set.of(first));

        assertKey(() -> planService.unlock(second, owner.getId()), MessageKey.COMMUNITY_LIMIT_REACHED);
        assertKey(() -> planService.unlock(second, friend.getId()), MessageKey.COMMUNITY_NOT_MEMBER);
        // Handing the kept one over makes room
        communityService.transferOwner(first, friend.getId(), owner.getId().toString());
        planService.unlock(second, owner.getId());
        planService.unlock(second, owner.getId());

        assertThat(lockedById()).containsOnly(Map.entry(first, false), Map.entry(second, false), Map.entry(third, true));
        assertThat(lockAudits()).filteredOn(a -> a.startsWith("UNLOCK")).containsExactly("UNLOCK reason=OWNER_UNLOCK");
    }

    @Test
    void shouldTransferLeaveAndReadWhenCommunityIsLocked() {
        User friend = persistUser("heir-" + UUID.randomUUID().toString().substring(0, 8));
        em.flush();
        communityService.addMember(third, friend.getId(), CommunityRole.ADMIN, owner.getId().toString());
        actAs(third);
        CreateVoteRequest request = new CreateVoteRequest();
        request.setTitle("Before the lock");
        request.setOptions(List.of(option("Yes"), option("No")));
        request.setEndTime(OffsetDateTime.now().plusDays(1));
        String pollId = voteService.createVote(request, third.toString()).getId();
        SecurityContextHolder.clearContext();
        downgrade();
        planService.keep(owner.getId(), Set.of(first));

        communityService.transferOwner(third, friend.getId(), owner.getId().toString());
        communityService.removeMember(third, owner.getId(), owner.getId().toString());
        em.flush();
        em.clear();

        assertThat(memberRepository.findActiveRole(third, friend.getId())).contains(CommunityRole.OWNER);
        assertThat(memberRepository.findActiveRole(third, owner.getId())).isEmpty();
        assertThat(userRepository.existsByIdAndLockedAtIsNotNull(third)).isTrue();
        assertThat(voteService.getVote(pollId, friend.getId().toString()).getTitle()).isEqualTo("Before the lock");
    }

    @Test
    void shouldUnlockOnlyTheEarliestUpToTheLimitWhenUpgraded() {
        UUID fourth = adopt(owner, "2026-04-01T00:00:00Z");
        UUID fifth = adopt(owner, "2026-05-01T00:00:00Z");
        downgrade();
        planService.keep(owner.getId(), Set.of(first));

        planService.changePlan(owner.getId(), Plan.PRO, PlanService.Source.ADMIN, null);

        assertThat(lockedById()).containsOnly(Map.entry(first, false), Map.entry(second, false), Map.entry(third, false),
                Map.entry(fourth, true), Map.entry(fifth, true));
        CommunityQuota quota = quota();
        assertThat(quota.owned()).isEqualTo(3);
        assertThat(quota.selectionRequired()).isFalse();
    }

    @Test
    void shouldLeaveSuspendedCommunityAccountsOutOfKeepAndUpgrade() {
        UUID fourth = adopt(owner, "2026-04-01T00:00:00Z");
        downgrade();
        planService.keep(owner.getId(), Set.of(first));
        em.createNativeQuery("UPDATE vomatt.users SET active = false WHERE id = :id").setParameter("id", second).executeUpdate();

        assertKey(() -> planService.keep(owner.getId(), Set.of(second)), MessageKey.COMMUNITY_KEEP_INVALID);
        planService.changePlan(owner.getId(), Plan.PRO, PlanService.Source.ADMIN, null);

        assertThat(lockedById()).containsOnly(Map.entry(first, false), Map.entry(third, false), Map.entry(fourth, false));
        assertThat(userRepository.existsByIdAndLockedAtIsNotNull(second)).isTrue();
    }

    @Test
    void shouldRequireKeepingExactlyAsManyAsTheLimitAllows() {
        assertKey(() -> planService.keep(owner.getId(), Set.of(first, second)), MessageKey.COMMUNITY_KEEP_INVALID);
        planService.keep(owner.getId(), Set.of(first, second, third));

        adopt(owner, "2026-04-01T00:00:00Z");
        adopt(owner, "2026-05-01T00:00:00Z");
        assertKey(() -> planService.keep(owner.getId(), Set.of(first, second)), MessageKey.COMMUNITY_KEEP_INVALID);
        assertThat(lockedById()).doesNotContainValue(true);
    }

    enum Unlocking { UPGRADE, KEEP, OWNER_UNLOCK }

    @ParameterizedTest
    @EnumSource(Unlocking.class)
    void shouldRequireSelectionFromCoOwnerPushedOverTheirLimitWhenUnlocked(Unlocking unlocking) {
        User coOwner = persistUser("co-" + UUID.randomUUID().toString().substring(0, 8));
        em.flush();
        communityService.createCommunity("co_" + UUID.randomUUID().toString().substring(0, 8), "Own", coOwner.getId());
        downgrade();
        planService.keep(owner.getId(), Set.of(first));
        addOwner(second, coOwner);
        assertThat(quota(coOwner).selectionRequired()).as("a locked one does not count").isFalse();

        switch (unlocking) {
            case UPGRADE -> planService.changePlan(owner.getId(), Plan.PRO, PlanService.Source.ADMIN, null);
            case KEEP -> planService.keep(owner.getId(), Set.of(second));
            case OWNER_UNLOCK -> {
                communityService.deleteCommunity(first, owner.getId().toString());
                planService.unlock(second, owner.getId());
            }
        }

        CommunityQuota quota = quota(coOwner);
        assertThat(quota.owned()).isEqualTo(2);
        assertThat(quota.selectionRequired()).isTrue();
        assertThat(quota.selectionDeadline()).isAfter(OffsetDateTime.now().plusDays(6));
    }

    enum Release { TRANSFER, DEMOTE, LEAVE, DELETE, SUSPEND }

    @ParameterizedTest
    @EnumSource(Release.class)
    void shouldEndSelectionWhenOwnerDropsBackToTheLimit(Release release) {
        User partner = persistUser("partner-" + UUID.randomUUID().toString().substring(0, 8));
        em.flush();
        planService.changePlan(partner.getId(), Plan.PRO, PlanService.Source.ADMIN, null);
        addOwner(second, partner);
        addOwner(third, partner);
        downgrade();
        assertThat(storedDeadline(owner)).isNotNull();
        String self = owner.getId().toString();

        for (UUID community : List.of(second, third)) {
            switch (release) {
                case TRANSFER -> communityService.transferOwner(community, partner.getId(), self);
                case DEMOTE -> communityService.changeRole(community, owner.getId(), CommunityRole.ADMIN, partner.getId().toString());
                case LEAVE -> communityService.removeMember(community, owner.getId(), self);
                case DELETE -> communityService.deleteCommunity(community, self);
                case SUSPEND -> adminUserService.suspend(community, partner.getId().toString());
            }
        }

        assertThat(quota().owned()).isEqualTo(1);
        assertThat(storedDeadline(owner)).isNull();
    }

    @Test
    void shouldRequireSelectionWhenAdminRestoresASuspendedCommunityAccount() {
        downgrade();
        planService.keep(owner.getId(), Set.of(first));
        planService.changePlan(owner.getId(), Plan.PRO, PlanService.Source.ADMIN, null);
        adminUserService.suspend(third, UUID.randomUUID().toString());
        planService.changePlan(owner.getId(), Plan.FREE, PlanService.Source.ADMIN, null);
        planService.keep(owner.getId(), Set.of(first));
        assertThat(storedDeadline(owner)).isNull();

        adminUserService.unsuspend(third, UUID.randomUUID().toString());

        assertThat(quota().owned()).isEqualTo(2);
        assertThat(storedDeadline(owner)).isNotNull();
    }

    @Test
    void shouldNotifyEveryOwnerAndEndCoOwnerSelectionWhenTheDeadlineLocks() {
        User coOwner = persistUser("co-" + UUID.randomUUID().toString().substring(0, 8));
        em.flush();
        communityService.createCommunity("co_" + UUID.randomUUID().toString().substring(0, 8), "Own", coOwner.getId());
        addOwner(third, coOwner);
        downgrade();
        OffsetDateTime now = OffsetDateTime.now();
        em.createNativeQuery("UPDATE vomatt.users SET community_selection_deadline = :at WHERE id IN (:ids)")
                .setParameter("at", now.minusMinutes(1)).setParameter("ids", List.of(owner.getId())).executeUpdate();
        em.createNativeQuery("UPDATE vomatt.users SET community_selection_deadline = :at WHERE id IN (:ids)")
                .setParameter("at", now.plusDays(3)).setParameter("ids", List.of(coOwner.getId())).executeUpdate();

        assertThat(job.run(now)).isEqualTo(2);

        assertThat(lockNotifications(owner)).containsExactlyInAnyOrder(second, third);
        assertThat(lockNotifications(coOwner)).containsExactly(third);
        assertThat(storedDeadline(coOwner)).as("back within the limit").isNull();
    }

    @Test
    void shouldNotNotifyWhenOwnerChoosesWhatToLock() {
        downgrade();

        planService.keep(owner.getId(), Set.of(first));

        assertThat(lockNotifications(owner)).isEmpty();
    }

    private void actAs(UUID communityId) {
        var auth = new UsernamePasswordAuthenticationToken(communityId.toString(), null, List.of());
        auth.setDetails(new ActingMember(owner.getId(), communityId, CommunityRole.OWNER));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private static CreateVoteRequest.VoteOptionRequest option(String text) {
        CreateVoteRequest.VoteOptionRequest option = new CreateVoteRequest.VoteOptionRequest();
        option.setText(text);
        return option;
    }
}
