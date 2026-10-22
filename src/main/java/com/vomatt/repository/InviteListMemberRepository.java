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

import com.vomatt.entity.InviteListMember;

@Repository
public interface InviteListMemberRepository extends JpaRepository<InviteListMember, UUID> {

    List<InviteListMember> findByInviteListId(UUID inviteListId);

    long countByInviteListId(UUID inviteListId);

    /** Members of the Invite Lists with their users, earliest added first. */
    @Query("SELECT m FROM InviteListMember m LEFT JOIN FETCH m.user WHERE m.inviteList.id IN :inviteListIds ORDER BY m.createdAt, m.id")
    List<InviteListMember> findWithUserByInviteListIdIn(@Param("inviteListIds") Collection<UUID> inviteListIds);

    /** The member added by ID, never one added by email (that one is reached only by its address). */
    Optional<InviteListMember> findByInviteListIdAndUserIdAndEmailIsNull(UUID inviteListId, UUID userId);

    Optional<InviteListMember> findByInviteListIdAndEmail(UUID inviteListId, String email);

    @Query("SELECT m.inviteList.id AS id, COUNT(m) AS count FROM InviteListMember m WHERE m.inviteList.id IN :inviteListIds GROUP BY m.inviteList.id")
    List<IdCount> countByInviteListIds(@Param("inviteListIds") Collection<UUID> inviteListIds);

    /** Same rule as {@link InviteeRepository#BINDABLE}: the user is neither already a member of that Invite List nor its owner. */
    String BINDABLE = """
            (NOT EXISTS (SELECT 1 FROM vomatt.invite_list_members u WHERE u.invite_list_id = m.invite_list_id AND u.user_id = :userId)
             AND NOT EXISTS (SELECT 1 FROM vomatt.invite_lists l WHERE l.id = m.invite_list_id AND l.owner_id = :userId))""";

    /**
     * Binding step 1: share-locks the Invite Lists holding a pending row of this email, in id order. Member changes take the
     * Invite List's write lock, so binding and a concurrent add of the same user serialize and cannot both add that user.
     */
    @Query(value = """
            SELECT l.id FROM vomatt.invite_lists l
            WHERE l.id IN (SELECT m.invite_list_id FROM vomatt.invite_list_members m WHERE m.email = :email AND m.user_id IS NULL)
            ORDER BY l.id FOR SHARE""", nativeQuery = true)
    List<UUID> lockInviteListsWithPendingEmail(@Param("email") String email);

    /** Binding step 2: ties the bindable pending rows of the locked Invite Lists to the user; each keeps its email. */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE vomatt.invite_list_members m SET user_id = :userId, updated_at = now()
            WHERE m.email = :email AND m.user_id IS NULL AND m.invite_list_id IN (:inviteListIds) AND """ + BINDABLE,
            nativeQuery = true)
    int bindEmail(@Param("userId") UUID userId, @Param("email") String email, @Param("inviteListIds") Collection<UUID> inviteListIds);

    /** Binding step 3: drops the pending rows left unbound, i.e. already covered by a user row or the owner's own address. */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            DELETE FROM vomatt.invite_list_members m
            WHERE m.email = :email AND m.user_id IS NULL AND m.invite_list_id IN (:inviteListIds) AND NOT """ + BINDABLE,
            nativeQuery = true)
    int deleteUnbindableEmail(@Param("userId") UUID userId, @Param("email") String email,
            @Param("inviteListIds") Collection<UUID> inviteListIds);
}
