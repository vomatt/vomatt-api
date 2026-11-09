package com.vomatt.users;

import com.vomatt.common.constant.ReservedUsernames;
import com.vomatt.common.constant.UserRole;
import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.entity.OfficialMember;
import com.vomatt.entity.OfficialRole;
import com.vomatt.entity.User;
import com.vomatt.repository.LockTimeout;
import com.vomatt.repository.OfficialMemberRepository;
import com.vomatt.repository.UserRepository;
import com.vomatt.users.dto.CreateOfficialRequest;
import com.vomatt.users.dto.MyOfficialResponse;
import com.vomatt.users.dto.OfficialMemberResponse;
import com.vomatt.users.dto.UserDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static com.vomatt.entity.OfficialRole.OWNER;

/**
 * Official accounts and their members. Every caller id here is the real person (see
 * {@code ActingMember#realUserId}), never the official account a request acts as.
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class OfficialService {

    private final OfficialMemberRepository memberRepository;
    private final UserRepository userRepository;
    private final UserMapper userMapper;
    private final LockTimeout lockTimeout;

    /** Admin only: creates the official account (no email or phone, so it can never sign in) and its first OWNER. */
    public UserDto createOfficial(CreateOfficialRequest request) {
        if (ReservedUsernames.contains(request.username()) || userRepository.existsByUsername(request.username())) {
            throw ApiException.conflict(MessageKey.USER_USERNAME_TAKEN);
        }
        requireEligible(request.ownerUserId());
        User official = userRepository.saveAndFlush(User.builder()
                .username(request.username())
                .displayName(request.displayName().trim())
                .roles(new String[] { UserRole.OFFICIAL })
                .build());
        memberRepository.save(new OfficialMember(official.getId(), request.ownerUserId(), OWNER));
        log.info("Official account {} created with owner {}", official.getId(), request.ownerUserId());
        return userMapper.toSearchResult(official, true);
    }

    /** Any member: everyone in the official account with their role, earliest first. */
    @Transactional(readOnly = true)
    public List<OfficialMemberResponse> listMembers(UUID officialId, String callerId) {
        requireRole(officialId, callerId);
        return memberRepository.findByOfficialUserIdOrderByCreatedAtAscUserIdAsc(officialId).stream()
                .map(m -> toResponse(m, m.getUser())).toList();
    }

    /** The official accounts the caller belongs to, for the account switcher. */
    @Transactional(readOnly = true)
    public List<MyOfficialResponse> listMine(String callerId) {
        return memberRepository.findByUserIdAndOfficialActiveTrueOrderByCreatedAtAscOfficialUserIdAsc(UUID.fromString(callerId))
                .stream()
                .map(m -> new MyOfficialResponse(m.getOfficialUserId().toString(), m.getOfficial().getUsername(),
                        m.getOfficial().getDisplayName(), m.getOfficial().getAvatarUrl(), m.getRole()))
                .toList();
    }

    public OfficialMemberResponse addMember(UUID officialId, UUID userId, OfficialRole role, String callerId) {
        requireGrant(requireManager(officialId, callerId), role);
        User user = requireEligible(userId);
        if (memberRepository.existsById(new OfficialMember.Key(officialId, userId))) {
            throw ApiException.conflict(MessageKey.OFFICIAL_MEMBER_EXISTS);
        }
        OfficialMember member = memberRepository.saveAndFlush(new OfficialMember(officialId, userId, role));
        log.info("Official account {}: {} added {} as {}", officialId, callerId, userId, role);
        return toResponse(member, user);
    }

    public OfficialMemberResponse changeRole(UUID officialId, UUID userId, OfficialRole role, String callerId) {
        OfficialRole caller = requireManager(officialId, callerId);
        OfficialMember member = findMember(officialId, userId);
        requireTouch(caller, member);
        requireGrant(caller, role);
        if (member.getRole() == OWNER && role != OWNER) {
            requireAnotherOwner(officialId, userId);
        }
        member.setRole(role);
        log.info("Official account {}: {} set {} to {}", officialId, callerId, userId, role);
        return toResponse(member, userRepository.getReferenceById(userId));
    }

    /** OWNER or ADMIN removes a member; any member may leave on their own. The last OWNER can do neither. */
    public void removeMember(UUID officialId, UUID userId, String callerId) {
        boolean leaving = userId.toString().equals(callerId);
        OfficialRole caller = leaving ? requireRole(officialId, callerId) : requireManager(officialId, callerId);
        OfficialMember member = findMember(officialId, userId);
        if (!leaving) {
            requireTouch(caller, member);
        }
        if (member.getRole() == OWNER) {
            requireAnotherOwner(officialId, userId);
        }
        memberRepository.delete(member);
        log.info("Official account {}: {} removed {}", officialId, callerId, userId);
    }

    /** OWNER only: the member becomes an OWNER and the caller an ADMIN. Transferring to oneself changes nothing. */
    public void transferOwner(UUID officialId, UUID userId, String callerId) {
        if (requireRole(officialId, callerId) != OWNER) {
            throw ApiException.forbidden(MessageKey.OFFICIAL_ROLE_FORBIDDEN);
        }
        OfficialMember target = findMember(officialId, userId);
        if (target.getUserId().toString().equals(callerId)) {
            return;
        }
        target.setRole(OWNER);
        findMember(officialId, UUID.fromString(callerId)).setRole(OfficialRole.ADMIN);
        log.info("Official account {}: ownership transferred from {} to {}", officialId, callerId, userId);
    }

    private OfficialRole requireRole(UUID officialId, String callerId) {
        return memberRepository.findActiveRole(officialId, UUID.fromString(callerId))
                .orElseThrow(() -> ApiException.forbidden(MessageKey.OFFICIAL_NOT_MEMBER));
    }

    private OfficialRole requireManager(UUID officialId, String callerId) {
        OfficialRole role = requireRole(officialId, callerId);
        if (!role.canManageMembers()) {
            throw ApiException.forbidden(MessageKey.OFFICIAL_ROLE_FORBIDDEN);
        }
        return role;
    }

    // Only an OWNER grants OWNER
    private static void requireGrant(OfficialRole caller, OfficialRole granted) {
        if (granted == OWNER && caller != OWNER) {
            throw ApiException.forbidden(MessageKey.OFFICIAL_ROLE_FORBIDDEN);
        }
    }

    // Only an OWNER changes or removes an OWNER
    private static void requireTouch(OfficialRole caller, OfficialMember member) {
        if (member.getRole() == OWNER && caller != OWNER) {
            throw ApiException.forbidden(MessageKey.OFFICIAL_ROLE_FORBIDDEN);
        }
    }

    /**
     * The last-OWNER guard, run before an OWNER is demoted or removed. Locks the OWNER rows first, so two OWNERs
     * removing each other concurrently cannot both pass; a waiter past 3 s gets 409 {@code common.conflict}.
     */
    private void requireAnotherOwner(UUID officialId, UUID userId) {
        List<UUID> owners = lockTimeout.within(() -> memberRepository.lockOwnerIds(officialId));
        if (owners.stream().allMatch(userId::equals)) {
            throw ApiException.conflict(MessageKey.OFFICIAL_LAST_OWNER);
        }
    }

    private OfficialMember findMember(UUID officialId, UUID userId) {
        return memberRepository.findByOfficialUserIdAndUserId(officialId, userId)
                .orElseThrow(() -> ApiException.notFound(MessageKey.OFFICIAL_MEMBER_NOT_FOUND));
    }

    // A member is a person: an active account that is not itself an official account
    private User requireEligible(UUID userId) {
        User user = userRepository.findById(userId).orElseThrow(() -> ApiException.notFound(MessageKey.USER_NOT_FOUND));
        if (!user.isActive() || user.isOfficial()) {
            throw ApiException.badRequest(MessageKey.OFFICIAL_MEMBER_INVALID);
        }
        return user;
    }

    private static OfficialMemberResponse toResponse(OfficialMember m, User user) {
        return new OfficialMemberResponse(m.getUserId().toString(), user.getUsername(), user.getDisplayName(),
                user.getAvatarUrl(), m.getRole(), m.getCreatedAt());
    }
}
