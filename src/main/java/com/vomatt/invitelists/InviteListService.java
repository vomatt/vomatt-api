package com.vomatt.invitelists;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vomatt.lookups.LookupService;
import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.entity.InviteList;
import com.vomatt.entity.InviteListMember;
import com.vomatt.entity.User;
import com.vomatt.invitelists.dto.InviteListMemberResponse;
import com.vomatt.invitelists.dto.InviteListMembersRequest;
import com.vomatt.invitelists.dto.InviteListRequest;
import com.vomatt.invitelists.dto.InviteListResponse;
import com.vomatt.repository.InviteListMemberRepository;
import com.vomatt.repository.InviteListRepository;
import com.vomatt.repository.IdCount;
import com.vomatt.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Invite Lists are owner-only: another user's Invite List is {@code invite_list.not_found} on every path, so its existence never leaks.
 * Members are never notified; they only matter once an Invite List is invited into a Poll.
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class InviteListService {

    /** Defaults; admins override them with lookups {@code config / invite_list_max_per_owner} and {@code invite_list_max_members}. */
    public static final int MAX_INVITE_LISTS_PER_OWNER = 50;
    public static final int MAX_MEMBERS_PER_INVITE_LIST = 100;

    private final InviteListRepository inviteListRepository;
    private final InviteListMemberRepository inviteListMemberRepository;
    private final UserRepository userRepository;
    private final InviteListMapper inviteListMapper;
    private final LookupService lookupService;

    /** An Invite List's members as they are now: users added by ID and addresses added by email. */
    public record Members(List<UUID> userIds, List<String> emails) {
    }

    /** The caller's Invite Lists by name. */
    @Transactional(readOnly = true)
    public List<InviteListResponse> getInviteLists(String userId) {
        List<InviteList> inviteLists = inviteListRepository.findByOwnerIdOrderByNameAscIdAsc(UUID.fromString(userId));
        if (inviteLists.isEmpty()) {
            return List.of();
        }
        Map<UUID, Long> counts = inviteListMemberRepository.countByInviteListIds(inviteLists.stream().map(InviteList::getId).toList()).stream()
            .collect(Collectors.toMap(IdCount::getId, IdCount::getCount));
        return inviteLists.stream().map(g -> inviteListMapper.toResponse(g, counts.getOrDefault(g.getId(), 0L))).toList();
    }

    public InviteListResponse createInviteList(InviteListRequest request, String userId) {
        User owner = userRepository.findById(UUID.fromString(userId))
            .orElseThrow(() -> ApiException.notFound(MessageKey.USER_NOT_FOUND));
        // ponytail: count-then-insert can overshoot the cap under concurrent creates; lock the owner row if it matters
        int maxLists = lookupService.configInt("invite_list_max_per_owner", MAX_INVITE_LISTS_PER_OWNER);
        if (inviteListRepository.countByOwnerId(owner.getId()) >= maxLists) {
            throw ApiException.badRequest(MessageKey.INVITE_LIST_MAX_REACHED, maxLists);
        }
        InviteList inviteList = inviteListRepository.save(new InviteList(owner, request.getName()));
        log.info("Invite List {} created by user {}", inviteList.getId(), userId);
        return inviteListMapper.toResponse(inviteList, 0);
    }

    public InviteListResponse renameInviteList(String inviteListId, InviteListRequest request, String userId) {
        InviteList inviteList = requireOwned(inviteListId, userId);
        inviteList.rename(request.getName());
        inviteList = inviteListRepository.saveAndFlush(inviteList);
        return inviteListMapper.toResponse(inviteList, inviteListMemberRepository.countByInviteListId(inviteList.getId()));
    }

    /** Polls the Invite List was already invited into keep their Invitees. */
    public void deleteInviteList(String inviteListId, String userId) {
        inviteListRepository.delete(requireOwned(inviteListId, userId));
        log.info("Invite List {} deleted by user {}", inviteListId, userId);
    }

    @Transactional(readOnly = true)
    public List<InviteListMemberResponse> getMembers(String inviteListId, String userId) {
        return members(requireOwned(inviteListId, userId).getId());
    }

    /**
     * Same rules as adding Invitees: duplicates, the owner and anyone already a member are skipped; an email row keeps
     * the address and also holds the user of an active account. Returns the full member list.
     */
    public List<InviteListMemberResponse> addMembers(String inviteListId, InviteListMembersRequest request, String userId) {
        // Exclusive lock: concurrent adds and email binding on this Invite List cannot collide
        InviteList inviteList = requireOwnedForUpdate(inviteListId, userId);
        UUID ownerId = inviteList.getOwner().getId();
        Set<UUID> ids = new HashSet<>(orEmpty(request.getUserIds()));
        ids.remove(ownerId);
        // The DTO setter already normalized the addresses
        Set<String> newEmails = new HashSet<>(orEmpty(request.getEmails()));
        // Suspended users cannot be added by ID, same as unknown ones
        List<User> users = ids.isEmpty() ? List.of() : userRepository.findAllById(ids).stream().filter(User::isActive).toList();
        if (users.size() != ids.size()) {
            throw ApiException.badRequest(MessageKey.INVITE_LIST_MEMBER_IDS_INVALID);
        }
        List<InviteListMember> existing = inviteListMemberRepository.findByInviteListId(inviteList.getId());
        Set<UUID> memberUsers = existing.stream().filter(m -> m.getUser() != null)
            .map(m -> m.getUser().getId()).collect(Collectors.toCollection(HashSet::new));
        existing.forEach(m -> newEmails.remove(m.getEmail()));
        List<InviteListMember> added = new ArrayList<>();
        users.stream().filter(u -> memberUsers.add(u.getId())).forEach(u -> added.add(new InviteListMember(inviteList, u)));
        Map<String, User> accounts = newEmails.isEmpty() ? Map.of() : userRepository.findByEmailIn(newEmails).stream()
            .filter(User::isActive).collect(Collectors.toMap(User::getEmail, u -> u));
        for (String email : newEmails) {
            User account = accounts.get(email);
            if (account == null) {
                added.add(new InviteListMember(inviteList, email));
            } else if (!account.getId().equals(ownerId) && memberUsers.add(account.getId())) {
                added.add(new InviteListMember(inviteList, account, email));
            }
        }
        int maxMembers = lookupService.configInt("invite_list_max_members", MAX_MEMBERS_PER_INVITE_LIST);
        if (existing.size() + added.size() > maxMembers) {
            throw ApiException.badRequest(MessageKey.INVITE_LIST_MEMBERS_MAX, maxMembers);
        }
        inviteListMemberRepository.saveAllAndFlush(added);
        log.info("{} member(s) added to Invite List {} by user {}", added.size(), inviteListId, userId);
        return members(inviteList.getId());
    }

    /** Removes the member added by this user ID; a no-op when there is none. */
    public List<InviteListMemberResponse> removeMember(String inviteListId, String memberUserId, String userId) {
        InviteList inviteList = requireOwnedForUpdate(inviteListId, userId);
        inviteListMemberRepository.findByInviteListIdAndUserIdAndEmailIsNull(inviteList.getId(), UUID.fromString(memberUserId))
            .ifPresent(this::deleteMember);
        return members(inviteList.getId());
    }

    /** Removes the member added by this address; a no-op when there is none. */
    public List<InviteListMemberResponse> removeEmailMember(String inviteListId, String email, String userId) {
        InviteList inviteList = requireOwnedForUpdate(inviteListId, userId);
        inviteListMemberRepository.findByInviteListIdAndEmail(inviteList.getId(), User.normalizeEmail(email))
            .ifPresent(this::deleteMember);
        return members(inviteList.getId());
    }

    /**
     * The members to invite when the owner passes {@code inviteListIds} to a Poll: users added by ID (suspended ones skipped,
     * so an Invite List never fails an invite with IDs the caller did not send) and addresses added by email, which become
     * email Invitees. 404 {@code invite_list.not_found} when any Invite List is missing or someone else's.
     */
    @Transactional(readOnly = true)
    public Members expand(Collection<UUID> inviteListIds, UUID ownerId) {
        Set<UUID> ids = new HashSet<>(inviteListIds);
        if (ids.isEmpty()) {
            return new Members(List.of(), List.of());
        }
        if (inviteListRepository.countByIdInAndOwnerId(ids, ownerId) != ids.size()) {
            throw ApiException.notFound(MessageKey.INVITE_LIST_NOT_FOUND);
        }
        List<InviteListMember> members = inviteListMemberRepository.findWithUserByInviteListIdIn(ids);
        return new Members(
            members.stream().filter(m -> m.getEmail() == null && m.getUser().isActive()).map(m -> m.getUser().getId()).toList(),
            members.stream().map(InviteListMember::getEmail).filter(e -> e != null).toList());
    }

    /**
     * Ties the pending email members of the user's verified email to the user; each keeps its address. Called right
     * after {@code VoteService.bindEmailInvitees} in the same transaction, so locks go Polls first, then Invite Lists.
     */
    public void bindEmailMembers(User user) {
        String email = user.getEmail();
        List<UUID> inviteListIds = inviteListMemberRepository.lockInviteListsWithPendingEmail(email);
        if (inviteListIds.isEmpty()) {
            return;
        }
        inviteListMemberRepository.bindEmail(user.getId(), email, inviteListIds);
        // Pending rows already covered by a user row (or the owner's own address) go
        inviteListMemberRepository.deleteUnbindableEmail(user.getId(), email, inviteListIds);
    }

    private InviteList requireOwned(String inviteListId, String userId) {
        return inviteListRepository.findByIdAndOwnerId(UUID.fromString(inviteListId), UUID.fromString(userId))
            .orElseThrow(() -> ApiException.notFound(MessageKey.INVITE_LIST_NOT_FOUND));
    }

    private InviteList requireOwnedForUpdate(String inviteListId, String userId) {
        return inviteListRepository.findOwnedForUpdate(UUID.fromString(inviteListId), UUID.fromString(userId))
            .orElseThrow(() -> ApiException.notFound(MessageKey.INVITE_LIST_NOT_FOUND));
    }

    private void deleteMember(InviteListMember member) {
        inviteListMemberRepository.delete(member);
        inviteListMemberRepository.flush();
    }

    private List<InviteListMemberResponse> members(UUID inviteListId) {
        return inviteListMemberRepository.findWithUserByInviteListIdIn(List.of(inviteListId)).stream()
            .map(inviteListMapper::toMemberResponse).toList();
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }
}
