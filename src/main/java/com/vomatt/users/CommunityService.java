package com.vomatt.users;

import com.vomatt.common.audit.AuditAspect;
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
import com.vomatt.users.dto.UpdateCommunityRequest;
import com.vomatt.users.dto.UpdateProfileRequest;
import com.vomatt.users.dto.UserDto;
import com.vomatt.users.dto.UserProfileResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.vomatt.common.email.EmailService;
import com.vomatt.common.email.InvitationEmailLimiter;
import com.vomatt.common.util.AfterCommit;
import com.vomatt.entity.CommunityInvitation;
import com.vomatt.repository.CommunityInvitationRepository;
import com.vomatt.users.dto.CommunityInvitationResponse;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.dao.DataIntegrityViolationException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.ArrayList;
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
    private final UserService userService;
    private final CommunityInvitationRepository invitationRepository;
    private final InvitationEmailLimiter invitationEmailLimiter;
    private final EmailService emailService;

    /** How long an invitation can be accepted, or joined by signing up. */
    static final Duration INVITATION_TTL = Duration.ofDays(7);

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

    /**
     * OWNER or ADMIN invites a user picked by id, or an email address, with a role; an ADMIN never grants OWNER. Nobody
     * joins without accepting: a user by id, or the account of an address, sees a COMMUNITY_INVITATION notification and
     * decides. An address with no account at all is mailed, once committed, within the inviting member's invitation email
     * limits, and signing up with it joins. For an address the response never tells whether it has an account. An
     * unexpired invitation of the same user or address is a conflict; an expired one is replaced.
     */
    public CommunityInvitationResponse invite(UUID communityId, UUID userId, String email, CommunityRole role, String callerId) {
        requireGrant(requireManager(communityId, callerId), role);
        User user = null;
        String address = null;
        if (userId != null) {
            // Picked by id, so who they are is no secret: an ineligible user or a member says so
            user = requireEligible(userId);
            if (memberRepository.existsById(new CommunityMember.Key(communityId, userId))) {
                throw ApiException.conflict(MessageKey.COMMUNITY_MEMBER_EXISTS);
            }
        } else {
            address = User.normalizeEmail(email);
        }
        OffsetDateTime now = OffsetDateTime.now();
        (user != null ? invitationRepository.findByCommunityUserIdAndUserId(communityId, userId)
                : invitationRepository.findByCommunityUserIdAndEmail(communityId, address)).ifPresent(existing -> {
            if (!existing.isExpiredAt(now)) {
                throw ApiException.conflict(MessageKey.COMMUNITY_INVITATION_EXISTS);
            }
            invitationRepository.delete(existing);
            // Hibernate flushes inserts before deletes; flush now so the replacement cannot hit the unique key
            invitationRepository.flush();
        });
        CommunityInvitation invitation;
        try {
            invitation = invitationRepository.saveAndFlush(new CommunityInvitation(communityId, userId, address, role,
                    UUID.fromString(callerId), now.plus(INVITATION_TTL)));
        } catch (DataIntegrityViolationException e) {
            // A concurrent invite of the same user or address won the unique key
            throw ApiException.conflict(MessageKey.COMMUNITY_INVITATION_EXISTS);
        }
        if (address != null && !userRepository.existsByEmail(address)) {
            mailInvitation(communityId, address, role, callerId);
        }
        log.info("Community account {}: {} invited {} as {} ({})", communityId, callerId, user != null ? userId : "an address",
                role, invitation.getId());
        return toResponse(invitation, user);
    }

    private void mailInvitation(UUID communityId, String address, CommunityRole role, String callerId) {
        // Counted against the inviting person, not the community account: anyone can create community accounts, so a
        // per-community budget would multiply with each one they make
        if (invitationEmailLimiter.claim(UUID.fromString(callerId), List.of(address)).isEmpty()) {
            return;
        }
        User community = userRepository.getReferenceById(communityId);
        User inviter = userRepository.getReferenceById(UUID.fromString(callerId));
        String communityName = community.getDisplayName() != null ? community.getDisplayName() : community.getUsername();
        String inviterName = inviter.getDisplayName() != null ? inviter.getDisplayName() : inviter.getUsername();
        String inviterUsername = inviter.getUsername();
        Locale locale = LocaleContextHolder.getLocale();
        AfterCommit.run(() -> emailService.sendCommunityInvitationEmail(address, inviterName, inviterUsername, communityName,
                role.name(), INVITATION_TTL, locale));
    }

    /** OWNER or ADMIN: the community account's invitations, expired ones included, oldest first. */
    @Transactional(readOnly = true)
    public List<CommunityInvitationResponse> listInvitations(UUID communityId, String callerId) {
        requireManager(communityId, callerId);
        List<CommunityInvitation> invitations = invitationRepository.findByCommunityUserIdOrderByCreatedAtAscIdAsc(communityId);
        Map<UUID, User> users = userRepository.findAllById(invitations.stream().map(CommunityInvitation::getUserId)
                .filter(Objects::nonNull).toList()).stream().collect(Collectors.toMap(User::getId, u -> u));
        return invitations.stream().map(i -> toResponse(i, i.getUserId() == null ? null : users.get(i.getUserId()))).toList();
    }

    /** OWNER or ADMIN withdraws an invitation; only an OWNER withdraws one that grants OWNER. */
    public void revokeInvitation(UUID communityId, UUID invitationId, String callerId) {
        CommunityRole caller = requireManager(communityId, callerId);
        CommunityInvitation invitation = invitationRepository.findByIdAndCommunityUserId(invitationId, communityId)
                .orElseThrow(() -> ApiException.notFound(MessageKey.COMMUNITY_INVITATION_NOT_FOUND));
        requireGrant(caller, invitation.getRole());
        invitationRepository.delete(invitation);
        log.info("Community account {}: {} revoked invitation {}", communityId, callerId, invitationId);
    }

    /**
     * The invitee joins with the invitation's role. Only the user it was sent to, or the account whose (always verified)
     * email it was sent to, can see it; any other caller, an expired invitation or a suspended community account is not found. Already a
     * member: the membership is kept as it is and the invitation is used up.
     */
    public MyCommunityResponse acceptInvitation(UUID invitationId, String callerId) {
        CommunityInvitation invitation = findOwnInvitation(invitationId, callerId);
        User community = userRepository.findById(invitation.getCommunityUserId()).filter(User::isActive)
                .orElseThrow(() -> ApiException.notFound(MessageKey.COMMUNITY_INVITATION_NOT_FOUND));
        UUID userId = requireEligible(UUID.fromString(callerId)).getId();
        CommunityMember member = memberRepository.findByCommunityUserIdAndUserId(community.getId(), userId)
                .orElseGet(() -> memberRepository.save(new CommunityMember(community.getId(), userId, invitation.getRole())));
        invitationRepository.delete(invitation);
        log.info("Community account {}: {} accepted invitation {} as {}", community.getId(), callerId, invitationId, member.getRole());
        return new MyCommunityResponse(community.getId().toString(), community.getUsername(), community.getDisplayName(),
                community.getAvatarUrl(), member.getRole());
    }

    /** The invitee turns the invitation down; it is deleted, and its notification goes with it. */
    public void declineInvitation(UUID invitationId, String callerId) {
        invitationRepository.delete(findOwnInvitation(invitationId, callerId));
        log.info("User {} declined community invitation {}", callerId, invitationId);
    }

    /**
     * Sign-up with an invited address joins every active community account that invited it, with the invitation's role,
     * and uses the address's invitations up. Called only from find-or-create by verified email in auth, for a new account.
     */
    public void joinInvitedCommunities(User user) {
        List<CommunityInvitation> invitations = invitationRepository.findByEmailAndExpiresAtAfter(user.getEmail(), OffsetDateTime.now());
        Set<UUID> active = userRepository.findAllById(invitations.stream().map(CommunityInvitation::getCommunityUserId).toList())
                .stream().filter(User::isActive).map(User::getId).collect(Collectors.toSet());
        invitations.stream().filter(i -> active.contains(i.getCommunityUserId())).forEach(i -> {
            memberRepository.save(new CommunityMember(i.getCommunityUserId(), user.getId(), i.getRole()));
            log.info("Community account {}: new user {} joined by invitation {} as {}", i.getCommunityUserId(), user.getId(), i.getId(), i.getRole());
        });
        invitationRepository.deleteByEmail(user.getEmail());
    }

    private CommunityInvitation findOwnInvitation(UUID invitationId, String callerId) {
        UUID userId = UUID.fromString(callerId);
        String email = userRepository.findById(userId).map(User::getEmail).orElse(null);
        return invitationRepository.findById(invitationId)
                .filter(i -> (userId.equals(i.getUserId()) || i.getEmail() != null && i.getEmail().equals(email))
                        && !i.isExpiredAt(OffsetDateTime.now()))
                .orElseThrow(() -> ApiException.notFound(MessageKey.COMMUNITY_INVITATION_NOT_FOUND));
    }

    private static CommunityInvitationResponse toResponse(CommunityInvitation i, User user) {
        return new CommunityInvitationResponse(i.getId().toString(), user == null ? null : user.getId().toString(),
                user == null ? null : user.getUsername(), user == null ? null : user.getDisplayName(),
                user == null ? null : user.getAvatarUrl(), i.getEmail(), i.getRole(), i.getCreatedAt(), i.getExpiresAt());
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

    /** OWNER or ADMIN: display name and bio; the audit details hold each changed field before and after. */
    public UserProfileResponse updateProfile(UUID communityId, UpdateCommunityRequest request, String callerId) {
        requireProfileEditor(communityId, callerId);
        User community = userRepository.findById(communityId).orElseThrow(() -> ApiException.notFound(MessageKey.USER_NOT_FOUND));
        String displayName = request.displayName() == null ? null : request.displayName().trim();
        List<String> changes = new ArrayList<>();
        change(changes, "displayName", community.getDisplayName(), displayName);
        change(changes, "bio", community.getBio(), request.bio());
        UpdateProfileRequest update = new UpdateProfileRequest();
        update.setDisplayName(displayName);
        update.setBio(request.bio());
        UserProfileResponse profile = userService.updateMyProfile(communityId.toString(), update);
        AuditAspect.recordDetails(changes.isEmpty() ? null : String.join("; ", changes));
        log.info("Community account {}: profile updated by {}", communityId, callerId);
        return profile;
    }

    /**
     * OWNER or ADMIN: replaces the avatar as {@link UserService#uploadAvatar} does, which decodes and stores the image
     * outside any transaction; the daily upload limit counts per community account.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public UserProfileResponse uploadAvatar(UUID communityId, MultipartFile file, String callerId) {
        requireProfileEditor(communityId, callerId);
        String before = userRepository.findById(communityId).map(User::getAvatarUrl).orElse(null);
        UserProfileResponse profile = userService.uploadAvatar(communityId.toString(), file);
        List<String> changes = new ArrayList<>();
        change(changes, "avatarUrl", before, profile.avatarUrl());
        AuditAspect.recordDetails(String.join("; ", changes));
        log.info("Community account {}: avatar uploaded by {}", communityId, callerId);
        return profile;
    }

    private static void change(List<String> changes, String field, String before, String after) {
        if (after != null && !after.equals(before)) {
            changes.add(field + ": " + quote(before) + " -> " + quote(after));
        }
    }

    private static String quote(String value) {
        return value == null ? "null" : '"' + value + '"';
    }

    private void requireProfileEditor(UUID communityId, String callerId) {
        if (!requireRole(communityId, callerId).canEditProfile()) {
            throw ApiException.forbidden(MessageKey.COMMUNITY_ROLE_FORBIDDEN);
        }
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
