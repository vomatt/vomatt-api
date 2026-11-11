package com.vomatt.users;

import com.vomatt.common.audit.AuditService;
import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.entity.CommunityRole;
import com.vomatt.entity.Plan;
import com.vomatt.entity.User;
import com.vomatt.lookups.LookupService;
import com.vomatt.repository.CommunityMemberRepository;
import com.vomatt.repository.LockTimeout;
import com.vomatt.repository.UserRepository;
import com.vomatt.users.dto.CommunityQuota;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Plans and the community accounts they let a user own. "Owned" counts the active, unlocked community accounts the user
 * is an OWNER of, so ownership moved to another account still counts there, and locking one frees its place. A person
 * has a selection deadline exactly while they own more than their limit: every change to who owns what, or to what is
 * locked, ends with {@link #reconcile} of each OWNER it touched. Every check and change here starts by locking the
 * person's row ({@link UserRepository#lockPlan}), then any community account rows in id order.
 * ponytail: reconcile then writes the other OWNERs' person rows after those, so two co-owners changing shared accounts
 * at once can deadlock (Postgres aborts one, a 500); lock every OWNER up front if that shows up.
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class PlanService {

    /** How long a downgraded user has to choose which community accounts to keep. */
    static final Duration SELECTION_PERIOD = Duration.ofDays(7);

    /** Who changed a plan, kept in the CHANGE_PLAN audit entry. */
    public enum Source { ADMIN, WEBHOOK }

    /** Why a community account was locked or unlocked, kept in the LOCK / UNLOCK audit entry. */
    enum LockReason { USER_CHOICE, SELECTION_DEADLINE, UPGRADE, OWNER_UNLOCK }

    /** The {@code data} of 403 {@code community.limit_reached}. */
    public record LimitReached(int limit, int owned) {}

    private final UserRepository userRepository;
    private final CommunityMemberRepository memberRepository;
    private final LookupService lookupService;
    private final LockTimeout lockTimeout;
    private final AuditService auditService;

    public int limit(Plan plan) {
        return lookupService.configInt(plan.limitKey(), plan.defaultLimit());
    }

    /** A selection is required while the user owns more than the limit; {@link #reconcile} keeps the deadline in step. */
    @Transactional(readOnly = true)
    public CommunityQuota quota(User user) {
        int owned = memberRepository.countOwned(user.getId());
        int limit = limit(user.getPlan());
        OffsetDateTime deadline = owned > limit ? user.getCommunitySelectionDeadline() : null;
        return new CommunityQuota(owned, limit, deadline != null, deadline);
    }

    /**
     * Run before the user becomes the OWNER of one more community account (creating one, receiving ownership, granted
     * OWNER, unlocking one): 403 {@code community.limit_reached} at the plan's limit. The row lock holds until the
     * caller commits, so two such changes for one user run one after the other and the second counts the first.
     */
    public void requireRoom(UUID userId) {
        int limit = limit(lockPlan(userId));
        int owned = memberRepository.countOwned(userId);
        if (owned >= limit) {
            throw ApiException.withData(HttpStatus.FORBIDDEN, MessageKey.COMMUNITY_LIMIT_REACHED, new LimitReached(limit, owned));
        }
    }

    /**
     * The one way a plan changes, audit-logged as CHANGE_PLAN; a null actor is the system. The same plan changes nothing.
     * An upgrade unlocks the earliest created locked community accounts the user owns, as many as the new limit has
     * room for. A downgrade that leaves the user over the new limit gives them {@link #SELECTION_PERIOD} to
     * {@link #keep} some; until then their community accounts work as before.
     */
    public void changePlan(UUID userId, Plan plan, Source source, UUID actorId) {
        Plan before = lockPlan(userId);
        if (before == plan) {
            return;
        }
        userRepository.setPlan(userId, plan);
        List<UUID> unlocking = List.of();
        if (plan.compareTo(before) > 0) {
            List<UUID> locked = memberRepository.findOwnedIds(userId, true);
            int room = Math.max(0, limit(plan) - memberRepository.countOwned(userId));
            unlocking = locked.subList(0, Math.min(room, locked.size()));
            setLocked(unlocking, false, actorId, LockReason.UPGRADE);
        }
        reconcile(userId, unlocking);
        auditService.logActionNow(Objects.toString(actorId, null), "CHANGE_PLAN", "USER", userId.toString(), "from=" + before + "; to=" + plan + "; source=" + source);
        log.info("User {} plan changed from {} to {} by {}", userId, before, plan, source);
    }

    /**
     * The user keeps exactly {@code keep} of the active community accounts they own unlocked: the others are locked,
     * kept ones that were locked are unlocked, and any selection deadline ends. Sending the same set again changes nothing.
     * 400 {@code community.keep.invalid} unless the set names as many as the limit allows (all of them when the user
     * owns fewer), each one the user owns.
     */
    public void keep(UUID userId, Set<UUID> keep) {
        int limit = limit(lockPlan(userId));
        List<UUID> owned = memberRepository.findOwnedIds(userId);
        if (keep.isEmpty() || keep.size() != Math.min(limit, owned.size()) || !owned.containsAll(keep)) {
            throw ApiException.badRequest(MessageKey.COMMUNITY_KEEP_INVALID);
        }
        // Both passes below lock rows; taking them all first keeps the id order across the two
        lockTimeout.within(() -> userRepository.lockIds(owned));
        setLocked(owned.stream().filter(id -> !keep.contains(id)).toList(), true, userId, LockReason.USER_CHOICE);
        setLocked(keep, false, userId, LockReason.USER_CHOICE);
        reconcile(userId, owned);
    }

    /** An OWNER with room in their plan unlocks a locked community account, which then counts toward their limit. */
    public void unlock(UUID communityId, UUID callerId) {
        CommunityRole role = memberRepository.findActiveRole(communityId, callerId)
                .orElseThrow(() -> ApiException.forbidden(MessageKey.COMMUNITY_NOT_MEMBER));
        if (role != CommunityRole.OWNER) {
            throw ApiException.forbidden(MessageKey.COMMUNITY_ROLE_FORBIDDEN);
        }
        if (!userRepository.existsByIdAndLockedAtIsNotNull(communityId)) {
            return;
        }
        requireRoom(callerId);
        setLocked(List.of(communityId), false, callerId, LockReason.OWNER_UNLOCK);
        reconcile(callerId, List.of(communityId));
    }

    /**
     * The selection job's step for one user: once the deadline has passed without a choice, keeps the earliest created
     * community accounts up to the limit and locks the rest. The deadline is re-read under the user's row lock, so a
     * second run (another instance) or a choice made meanwhile changes nothing. Returns how many it locked.
     */
    public int expireSelection(UUID userId, OffsetDateTime now) {
        int limit = limit(lockPlan(userId));
        OffsetDateTime deadline = userRepository.findSelectionDeadline(userId).orElse(null);
        if (deadline == null || deadline.isAfter(now)) {
            return 0;
        }
        List<UUID> unlocked = memberRepository.findOwnedIds(userId, false);
        List<UUID> locking = unlocked.subList(Math.min(limit, unlocked.size()), unlocked.size());
        int locked = setLocked(locking, true, null, LockReason.SELECTION_DEADLINE);
        reconcile(userId, locking);
        return locked;
    }

    /** {@link #reconcile(Collection)} of the person and every OWNER of the community accounts. */
    private void reconcile(UUID userId, Collection<UUID> communityIds) {
        Set<UUID> people = new HashSet<>(communityIds.isEmpty() ? List.of() : memberRepository.findOwnerIds(communityIds));
        people.add(userId);
        reconcile(people);
    }

    /** {@link #reconcile(Collection)} of every OWNER of the community account, after it was suspended or restored. */
    public void reconcileOwners(UUID communityId) {
        reconcile(memberRepository.findOwnerIds(List.of(communityId)));
    }

    /**
     * Sets or ends each person's selection deadline so it runs exactly while they own more than their limit. Call it
     * last, after the change to who owns or what is locked, for every person whose count it may have moved.
     */
    public void reconcile(Collection<UUID> userIds) {
        if (!userIds.isEmpty()) {
            userRepository.reconcileSelectionDeadlines(userIds, limit(Plan.FREE), limit(Plan.PRO),
                    OffsetDateTime.now().plus(SELECTION_PERIOD));
        }
    }

    /** Locks or unlocks those of the community accounts not already so, with one audit entry each; returns how many. */
    private int setLocked(Collection<UUID> ids, boolean lock, UUID actorId, LockReason reason) {
        if (ids.isEmpty()) {
            return 0;
        }
        List<UUID> changing = lockTimeout.within(() -> userRepository.lockToChange(ids, lock));
        if (changing.isEmpty()) {
            return 0;
        }
        if (lock) {
            userRepository.markLocked(changing, OffsetDateTime.now());
        } else {
            userRepository.markUnlocked(changing);
        }
        changing.forEach(id -> auditService.logActionNow(Objects.toString(actorId, null), lock ? "LOCK" : "UNLOCK", "COMMUNITY",
                id.toString(), "reason=" + reason));
        log.info("Community accounts {} {} ({})", changing, lock ? "locked" : "unlocked", reason);
        return changing.size();
    }

    private Plan lockPlan(UUID userId) {
        return lockTimeout.within(() -> userRepository.lockPlan(userId)).map(Plan::valueOf)
                .orElseThrow(() -> ApiException.notFound(MessageKey.USER_NOT_FOUND));
    }
}
