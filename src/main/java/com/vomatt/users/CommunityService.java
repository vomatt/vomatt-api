package com.vomatt.users;

import com.vomatt.common.constant.ReservedUsernames;
import com.vomatt.common.constant.UserRole;
import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.entity.CommunityMember;
import com.vomatt.entity.CommunityRole;
import com.vomatt.entity.User;
import com.vomatt.repository.LockTimeout;
import com.vomatt.repository.CommunityMemberRepository;
import com.vomatt.repository.UserRepository;
import com.vomatt.users.dto.MyCommunityResponse;
import com.vomatt.users.dto.CommunityMemberResponse;
import com.vomatt.users.dto.UserDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static com.vomatt.entity.CommunityRole.OWNER;

/**
 * Community accounts and their members. Every caller id here is the real person (see
 * {@code ActingMember#realUserId}), never the community account a request acts as.
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class CommunityService {

    private final CommunityMemberRepository memberRepository;
    private final UserRepository userRepository;
    private final UserMapper userMapper;
    private final LockTimeout lockTimeout;

    /**
     * Creates the community account (no email or phone, so it can never sign in) with {@code ownerUserId} as its first
     * OWNER: an admin names the owner, any other user creates one for themself.
     */
    public UserDto createCommunity(String username, String displayName, UUID ownerUserId) {
        if (ReservedUsernames.contains(username) || userRepository.existsByUsername(username)) {
            throw ApiException.conflict(MessageKey.USER_USERNAME_TAKEN);
        }
        requireEligible(ownerUserId);
        User community = userRepository.saveAndFlush(User.builder()
                .username(username)
                .displayName(displayName.trim())
                .roles(new String[] { UserRole.COMMUNITY })
                .build());
        memberRepository.save(new CommunityMember(community.getId(), ownerUserId, OWNER));
        log.info("Community account {} created with owner {}", community.getId(), ownerUserId);
        return userMapper.toSearchResult(community, true);
    }

    /** Any member: everyone in the community account with their role, earliest first. */
    @Transactional(readOnly = true)
    public List<CommunityMemberResponse> listMembers(UUID communityId, String callerId) {
        requireRole(communityId, callerId);
        return memberRepository.findByCommunityUserIdOrderByCreatedAtAscUserIdAsc(communityId).stream()
                .map(m -> toResponse(m, m.getUser())).toList();
    }

    /** The community accounts the caller belongs to, for the account switcher. */
    @Transactional(readOnly = true)
    public List<MyCommunityResponse> listMine(String callerId) {
        return memberRepository.findByUserIdAndCommunityActiveTrueOrderByCreatedAtAscCommunityUserIdAsc(UUID.fromString(callerId))
                .stream()
                .map(m -> new MyCommunityResponse(m.getCommunityUserId().toString(), m.getCommunity().getUsername(),
                        m.getCommunity().getDisplayName(), m.getCommunity().getAvatarUrl(), m.getRole()))
                .toList();
    }

    public CommunityMemberResponse addMember(UUID communityId, UUID userId, CommunityRole role, String callerId) {
        requireGrant(requireManager(communityId, callerId), role);
        User user = requireEligible(userId);
        if (memberRepository.existsById(new CommunityMember.Key(communityId, userId))) {
            throw ApiException.conflict(MessageKey.COMMUNITY_MEMBER_EXISTS);
        }
        CommunityMember member = memberRepository.saveAndFlush(new CommunityMember(communityId, userId, role));
        log.info("Community account {}: {} added {} as {}", communityId, callerId, userId, role);
        return toResponse(member, user);
    }

    public CommunityMemberResponse changeRole(UUID communityId, UUID userId, CommunityRole role, String callerId) {
        CommunityRole caller = requireManager(communityId, callerId);
        CommunityMember member = findMember(communityId, userId);
        requireTouch(caller, member);
        requireGrant(caller, role);
        if (member.getRole() == OWNER && role != OWNER) {
            requireAnotherOwner(communityId, userId);
        }
        member.setRole(role);
        log.info("Community account {}: {} set {} to {}", communityId, callerId, userId, role);
        return toResponse(member, userRepository.getReferenceById(userId));
    }

    /** OWNER or ADMIN removes a member; any member may leave on their own. The last OWNER can do neither. */
    public void removeMember(UUID communityId, UUID userId, String callerId) {
        boolean leaving = userId.toString().equals(callerId);
        CommunityRole caller = leaving ? requireRole(communityId, callerId) : requireManager(communityId, callerId);
        CommunityMember member = findMember(communityId, userId);
        if (!leaving) {
            requireTouch(caller, member);
        }
        if (member.getRole() == OWNER) {
            requireAnotherOwner(communityId, userId);
        }
        memberRepository.delete(member);
        log.info("Community account {}: {} removed {}", communityId, callerId, userId);
    }

    /**
     * OWNER only: the member becomes an OWNER and the caller an ADMIN. Transferring to oneself changes nothing. Takes
     * the OWNER row lock and checks the caller's role under it, so an OWNER demoted concurrently cannot still transfer.
     */
    public void transferOwner(UUID communityId, UUID userId, String callerId) {
        requireRole(communityId, callerId);
        if (!lockTimeout.within(() -> memberRepository.lockOwnerIds(communityId)).contains(UUID.fromString(callerId))) {
            throw ApiException.forbidden(MessageKey.COMMUNITY_ROLE_FORBIDDEN);
        }
        CommunityMember target = findMember(communityId, userId);
        if (target.getUserId().toString().equals(callerId)) {
            return;
        }
        target.setRole(OWNER);
        findMember(communityId, UUID.fromString(callerId)).setRole(CommunityRole.ADMIN);
        log.info("Community account {}: ownership transferred from {} to {}", communityId, callerId, userId);
    }

    private CommunityRole requireRole(UUID communityId, String callerId) {
        return memberRepository.findActiveRole(communityId, UUID.fromString(callerId))
                .orElseThrow(() -> ApiException.forbidden(MessageKey.COMMUNITY_NOT_MEMBER));
    }

    private CommunityRole requireManager(UUID communityId, String callerId) {
        CommunityRole role = requireRole(communityId, callerId);
        if (!role.canManageMembers()) {
            throw ApiException.forbidden(MessageKey.COMMUNITY_ROLE_FORBIDDEN);
        }
        return role;
    }

    // Only an OWNER grants OWNER
    private static void requireGrant(CommunityRole caller, CommunityRole granted) {
        if (granted == OWNER && caller != OWNER) {
            throw ApiException.forbidden(MessageKey.COMMUNITY_ROLE_FORBIDDEN);
        }
    }

    // Only an OWNER changes or removes an OWNER
    private static void requireTouch(CommunityRole caller, CommunityMember member) {
        if (member.getRole() == OWNER && caller != OWNER) {
            throw ApiException.forbidden(MessageKey.COMMUNITY_ROLE_FORBIDDEN);
        }
    }

    /**
     * The last-OWNER guard, run before an OWNER is demoted or removed. Locks the OWNER rows first, so two OWNERs
     * removing each other concurrently cannot both pass; a waiter past 3 s gets 409 {@code common.conflict}.
     */
    private void requireAnotherOwner(UUID communityId, UUID userId) {
        List<UUID> owners = lockTimeout.within(() -> memberRepository.lockOwnerIds(communityId));
        if (owners.stream().allMatch(userId::equals)) {
            throw ApiException.conflict(MessageKey.COMMUNITY_LAST_OWNER);
        }
    }

    private CommunityMember findMember(UUID communityId, UUID userId) {
        return memberRepository.findByCommunityUserIdAndUserId(communityId, userId)
                .orElseThrow(() -> ApiException.notFound(MessageKey.COMMUNITY_MEMBER_NOT_FOUND));
    }

    // A member is a person: an active account that is not itself a community account
    private User requireEligible(UUID userId) {
        User user = userRepository.findById(userId).orElseThrow(() -> ApiException.notFound(MessageKey.USER_NOT_FOUND));
        if (!user.isActive() || user.isCommunity()) {
            throw ApiException.badRequest(MessageKey.COMMUNITY_MEMBER_INVALID);
        }
        return user;
    }

    private static CommunityMemberResponse toResponse(CommunityMember m, User user) {
        return new CommunityMemberResponse(m.getUserId().toString(), user.getUsername(), user.getDisplayName(),
                user.getAvatarUrl(), m.getRole(), m.getCreatedAt());
    }
}
