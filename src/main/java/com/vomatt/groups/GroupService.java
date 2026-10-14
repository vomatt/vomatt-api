package com.vomatt.groups;

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

import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.entity.Group;
import com.vomatt.entity.GroupMember;
import com.vomatt.entity.User;
import com.vomatt.groups.dto.GroupMemberResponse;
import com.vomatt.groups.dto.GroupMembersRequest;
import com.vomatt.groups.dto.GroupRequest;
import com.vomatt.groups.dto.GroupResponse;
import com.vomatt.repository.GroupMemberRepository;
import com.vomatt.repository.GroupRepository;
import com.vomatt.repository.IdCount;
import com.vomatt.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Groups are owner-only: another user's Group is {@code group.not_found} on every path, so its existence never leaks.
 * Members are never notified; they only matter once a Group is invited into a Poll.
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class GroupService {

    public static final int MAX_GROUPS_PER_OWNER = 50;
    public static final int MAX_MEMBERS_PER_GROUP = 100;

    private final GroupRepository groupRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final UserRepository userRepository;
    private final GroupMapper groupMapper;

    /** A Group's members as they are now: users added by ID and addresses added by email. */
    public record Members(List<UUID> userIds, List<String> emails) {
    }

    /** The caller's Groups by name. */
    @Transactional(readOnly = true)
    public List<GroupResponse> getGroups(String userId) {
        List<Group> groups = groupRepository.findByOwnerIdOrderByNameAscIdAsc(UUID.fromString(userId));
        if (groups.isEmpty()) {
            return List.of();
        }
        Map<UUID, Long> counts = groupMemberRepository.countByGroupIds(groups.stream().map(Group::getId).toList()).stream()
            .collect(Collectors.toMap(IdCount::getId, IdCount::getCount));
        return groups.stream().map(g -> groupMapper.toResponse(g, counts.getOrDefault(g.getId(), 0L))).toList();
    }

    public GroupResponse createGroup(GroupRequest request, String userId) {
        User owner = userRepository.findById(UUID.fromString(userId))
            .orElseThrow(() -> ApiException.notFound(MessageKey.USER_NOT_FOUND));
        // ponytail: count-then-insert can overshoot the cap under concurrent creates; lock the owner row if it matters
        if (groupRepository.countByOwnerId(owner.getId()) >= MAX_GROUPS_PER_OWNER) {
            throw ApiException.badRequest(MessageKey.GROUP_MAX_REACHED, MAX_GROUPS_PER_OWNER);
        }
        Group group = groupRepository.save(new Group(owner, request.getName()));
        log.info("Group {} created by user {}", group.getId(), userId);
        return groupMapper.toResponse(group, 0);
    }

    public GroupResponse renameGroup(String groupId, GroupRequest request, String userId) {
        Group group = requireOwned(groupId, userId);
        group.setName(request.getName().strip());
        group = groupRepository.saveAndFlush(group);
        return groupMapper.toResponse(group, groupMemberRepository.countByGroupId(group.getId()));
    }

    /** Polls the Group was already invited into keep their Invitees. */
    public void deleteGroup(String groupId, String userId) {
        groupRepository.delete(requireOwned(groupId, userId));
        log.info("Group {} deleted by user {}", groupId, userId);
    }

    @Transactional(readOnly = true)
    public List<GroupMemberResponse> getMembers(String groupId, String userId) {
        return members(requireOwned(groupId, userId).getId());
    }

    /**
     * Same rules as adding Invitees: duplicates, the owner and anyone already a member are skipped; an email row keeps
     * the address and also holds the user of an active account. Returns the full member list.
     */
    public List<GroupMemberResponse> addMembers(String groupId, GroupMembersRequest request, String userId) {
        // Exclusive lock: concurrent adds and email binding on this Group cannot collide
        Group group = groupRepository.findOwnedForUpdate(UUID.fromString(groupId), UUID.fromString(userId))
            .orElseThrow(() -> ApiException.notFound(MessageKey.GROUP_NOT_FOUND));
        UUID ownerId = group.getOwner().getId();
        Set<UUID> ids = new HashSet<>(orEmpty(request.getUserIds()));
        ids.remove(ownerId);
        Set<String> newEmails = orEmpty(request.getEmails()).stream().map(User::normalizeEmail)
            .collect(Collectors.toCollection(HashSet::new));
        // Suspended users cannot be added by ID, same as unknown ones
        List<User> users = ids.isEmpty() ? List.of() : userRepository.findAllById(ids).stream().filter(User::isActive).toList();
        if (users.size() != ids.size()) {
            throw ApiException.badRequest(MessageKey.GROUP_MEMBER_IDS_INVALID);
        }
        List<GroupMember> existing = groupMemberRepository.findByGroupId(group.getId());
        Set<UUID> memberUsers = existing.stream().filter(m -> m.getUser() != null)
            .map(m -> m.getUser().getId()).collect(Collectors.toCollection(HashSet::new));
        existing.forEach(m -> newEmails.remove(m.getEmail()));
        List<GroupMember> added = new ArrayList<>();
        users.stream().filter(u -> memberUsers.add(u.getId())).forEach(u -> added.add(new GroupMember(group, u)));
        Map<String, User> accounts = newEmails.isEmpty() ? Map.of() : userRepository.findByEmailIn(newEmails).stream()
            .filter(User::isActive).collect(Collectors.toMap(User::getEmail, u -> u));
        for (String email : newEmails) {
            User account = accounts.get(email);
            if (account == null) {
                added.add(new GroupMember(group, email));
            } else if (!account.getId().equals(ownerId) && memberUsers.add(account.getId())) {
                added.add(new GroupMember(group, account, email));
            }
        }
        if (existing.size() + added.size() > MAX_MEMBERS_PER_GROUP) {
            throw ApiException.badRequest(MessageKey.GROUP_MEMBERS_MAX, MAX_MEMBERS_PER_GROUP);
        }
        groupMemberRepository.saveAllAndFlush(added);
        log.info("{} member(s) added to group {} by user {}", added.size(), groupId, userId);
        return members(group.getId());
    }

    /** Removes the member added by this user ID; a no-op when there is none. */
    public List<GroupMemberResponse> removeMember(String groupId, String memberUserId, String userId) {
        Group group = groupRepository.findOwnedForUpdate(UUID.fromString(groupId), UUID.fromString(userId))
            .orElseThrow(() -> ApiException.notFound(MessageKey.GROUP_NOT_FOUND));
        groupMemberRepository.findByGroupIdAndUserIdAndEmailIsNull(group.getId(), UUID.fromString(memberUserId))
            .ifPresent(this::deleteMember);
        return members(group.getId());
    }

    /** Removes the member added by this address; a no-op when there is none. */
    public List<GroupMemberResponse> removeEmailMember(String groupId, String email, String userId) {
        Group group = groupRepository.findOwnedForUpdate(UUID.fromString(groupId), UUID.fromString(userId))
            .orElseThrow(() -> ApiException.notFound(MessageKey.GROUP_NOT_FOUND));
        groupMemberRepository.findByGroupIdAndEmail(group.getId(), User.normalizeEmail(email))
            .ifPresent(this::deleteMember);
        return members(group.getId());
    }

    /**
     * The members to invite when the owner passes {@code groupIds} to a Poll: users added by ID (suspended ones skipped,
     * so a Group never fails an invite with IDs the caller did not send) and addresses added by email, which become
     * email Invitees. 404 {@code group.not_found} when any Group is missing or someone else's.
     */
    @Transactional(readOnly = true)
    public Members expand(Collection<UUID> groupIds, UUID ownerId) {
        Set<UUID> ids = new HashSet<>(groupIds);
        if (ids.isEmpty()) {
            return new Members(List.of(), List.of());
        }
        if (groupRepository.countByIdInAndOwnerId(ids, ownerId) != ids.size()) {
            throw ApiException.notFound(MessageKey.GROUP_NOT_FOUND);
        }
        List<GroupMember> members = groupMemberRepository.findWithUserByGroupIdIn(ids);
        return new Members(
            members.stream().filter(m -> m.getEmail() == null && m.getUser().isActive()).map(m -> m.getUser().getId()).toList(),
            members.stream().map(GroupMember::getEmail).filter(e -> e != null).toList());
    }

    /**
     * Ties the pending email members of the user's verified email to the user; each keeps its address. Called right
     * after {@code VoteService.bindEmailInvitees} in the same transaction, so locks go Polls first, then Groups.
     */
    public void bindEmailMembers(User user) {
        String email = user.getEmail();
        List<UUID> groupIds = groupMemberRepository.lockGroupsWithPendingEmail(email);
        if (groupIds.isEmpty()) {
            return;
        }
        groupMemberRepository.bindEmail(user.getId(), email, groupIds);
        // Pending rows already covered by a user row (or the owner's own address) go
        groupMemberRepository.deleteUnbindableEmail(user.getId(), email, groupIds);
    }

    private Group requireOwned(String groupId, String userId) {
        return groupRepository.findByIdAndOwnerId(UUID.fromString(groupId), UUID.fromString(userId))
            .orElseThrow(() -> ApiException.notFound(MessageKey.GROUP_NOT_FOUND));
    }

    private void deleteMember(GroupMember member) {
        groupMemberRepository.delete(member);
        groupMemberRepository.flush();
    }

    private List<GroupMemberResponse> members(UUID groupId) {
        return groupMemberRepository.findWithUserByGroupIdIn(List.of(groupId)).stream()
            .map(groupMapper::toMemberResponse).toList();
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }
}
