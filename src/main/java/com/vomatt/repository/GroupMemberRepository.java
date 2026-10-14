package com.vomatt.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.vomatt.entity.GroupMember;

@Repository
public interface GroupMemberRepository extends JpaRepository<GroupMember, UUID> {

    List<GroupMember> findByGroupId(UUID groupId);

    long countByGroupId(UUID groupId);

    /** Members of the Groups with their users, earliest added first. */
    @Query("SELECT m FROM GroupMember m LEFT JOIN FETCH m.user WHERE m.group.id IN :groupIds ORDER BY m.createdAt, m.id")
    List<GroupMember> findWithUserByGroupIdIn(@Param("groupIds") Collection<UUID> groupIds);

    /** The member added by ID, never one added by email (that one is reached only by its address). */
    Optional<GroupMember> findByGroupIdAndUserIdAndEmailIsNull(UUID groupId, UUID userId);

    Optional<GroupMember> findByGroupIdAndEmail(UUID groupId, String email);

    @Query("SELECT m.group.id AS id, COUNT(m) AS count FROM GroupMember m WHERE m.group.id IN :groupIds GROUP BY m.group.id")
    List<IdCount> countByGroupIds(@Param("groupIds") Collection<UUID> groupIds);

    /** Same rule as {@link InviteeRepository#BINDABLE}: the user is neither already a member of that Group nor its owner. */
    String BINDABLE = """
            (NOT EXISTS (SELECT 1 FROM vomatt.group_members u WHERE u.group_id = m.group_id AND u.user_id = :userId)
             AND NOT EXISTS (SELECT 1 FROM vomatt.groups g WHERE g.id = m.group_id AND g.owner_id = :userId))""";

    /**
     * Binding step 1: share-locks the Groups holding a pending row of this email, in id order. Member changes take the
     * Group's write lock, so binding and a concurrent add of the same user serialize and cannot both add that user.
     */
    @Query(value = """
            SELECT g.id FROM vomatt.groups g
            WHERE g.id IN (SELECT m.group_id FROM vomatt.group_members m WHERE m.email = :email AND m.user_id IS NULL)
            ORDER BY g.id FOR SHARE""", nativeQuery = true)
    List<UUID> lockGroupsWithPendingEmail(@Param("email") String email);

    /** Binding step 2: ties the bindable pending rows of the locked Groups to the user; each keeps its email. */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE vomatt.group_members m SET user_id = :userId, updated_at = now()
            WHERE m.email = :email AND m.user_id IS NULL AND m.group_id IN (:groupIds) AND """ + BINDABLE,
            nativeQuery = true)
    int bindEmail(@Param("userId") UUID userId, @Param("email") String email, @Param("groupIds") Collection<UUID> groupIds);

    /** Binding step 3: drops the pending rows left unbound, i.e. already covered by a user row or the owner's own address. */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            DELETE FROM vomatt.group_members m
            WHERE m.email = :email AND m.user_id IS NULL AND m.group_id IN (:groupIds) AND NOT """ + BINDABLE,
            nativeQuery = true)
    int deleteUnbindableEmail(@Param("userId") UUID userId, @Param("email") String email,
            @Param("groupIds") Collection<UUID> groupIds);
}
