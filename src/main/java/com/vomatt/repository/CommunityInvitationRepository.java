package com.vomatt.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.vomatt.entity.CommunityInvitation;

import jakarta.persistence.LockModeType;

public interface CommunityInvitationRepository extends JpaRepository<CommunityInvitation, UUID> {

    Optional<CommunityInvitation> findByCommunityUserIdAndEmail(UUID communityUserId, String email);

    Optional<CommunityInvitation> findByCommunityUserIdAndUserId(UUID communityUserId, UUID userId);

    Optional<CommunityInvitation> findByIdAndCommunityUserId(UUID id, UUID communityUserId);

    /** The invitation, locked so two resends of it cannot both pass the resend window. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM CommunityInvitation i WHERE i.id = :id AND i.communityUserId = :communityId")
    Optional<CommunityInvitation> lockByIdAndCommunityUserId(@Param("id") UUID id, @Param("communityId") UUID communityId);

    /** A community account's invitations, expired ones included, oldest first. */
    List<CommunityInvitation> findByCommunityUserIdOrderByCreatedAtAscIdAsc(UUID communityUserId);

    /**
     * Deletes the invitation in one statement, so a concurrent delete of the same row is a miss (0), not a failure.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM CommunityInvitation i WHERE i.id = :id")
    int removeById(@Param("id") UUID id);

    /**
     * Uses up every invitation of the community account to the user, by id or to their address, so a second one cannot
     * bring them back after they leave.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM CommunityInvitation i WHERE i.communityUserId = :communityId AND (i.userId = :userId OR i.email = :email)")
    void deleteForInvitee(@Param("communityId") UUID communityId, @Param("userId") UUID userId, @Param("email") String email);
}
