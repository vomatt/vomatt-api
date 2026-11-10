package com.vomatt.users;

import com.vomatt.common.audit.AuditService;
import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;
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

import java.util.Objects;
import java.util.UUID;

/**
 * Plans and the community accounts they let a user own. "Owned" counts the community accounts the user is an OWNER of,
 * so ownership moved to another account still counts there. Every check and change here starts by locking the
 * person's row ({@link UserRepository#lockPlan}).
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class PlanService {

    /** Who changed a plan, kept in the CHANGE_PLAN audit entry. */
    public enum Source { ADMIN, WEBHOOK }

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

    @Transactional(readOnly = true)
    public CommunityQuota quota(User user) {
        return new CommunityQuota(memberRepository.countOwned(user.getId()), limit(user.getPlan()));
    }

    /**
     * Run before the user becomes the OWNER of one more community account (creating one, receiving ownership, granted
     * OWNER): 403 {@code community.limit_reached} at the plan's limit. The row lock holds until the caller commits, so
     * two such changes for one user run one after the other and the second counts the first.
     */
    public void requireRoom(UUID userId) {
        int limit = limit(lockPlan(userId));
        int owned = memberRepository.countOwned(userId);
        if (owned >= limit) {
            throw ApiException.withData(HttpStatus.FORBIDDEN, MessageKey.COMMUNITY_LIMIT_REACHED, new LimitReached(limit, owned));
        }
    }

    /** The one way a plan changes, audit-logged as CHANGE_PLAN; a null actor is the system. The same plan changes nothing. */
    public void changePlan(UUID userId, Plan plan, Source source, UUID actorId) {
        Plan before = lockPlan(userId);
        if (before == plan) {
            return;
        }
        userRepository.setPlan(userId, plan.name());
        auditService.logActionNow(Objects.toString(actorId, null), "CHANGE_PLAN", "USER", userId.toString(), "from=" + before + "; to=" + plan + "; source=" + source);
        log.info("User {} plan changed from {} to {} by {}", userId, before, plan, source);
    }

    private Plan lockPlan(UUID userId) {
        return lockTimeout.within(() -> userRepository.lockPlan(userId)).map(Plan::valueOf)
                .orElseThrow(() -> ApiException.notFound(MessageKey.USER_NOT_FOUND));
    }
}
