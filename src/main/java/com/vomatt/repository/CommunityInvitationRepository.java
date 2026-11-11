package com.vomatt.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.vomatt.entity.CommunityInvitation;

public interface CommunityInvitationRepository extends JpaRepository<CommunityInvitation, UUID> {

    Optional<CommunityInvitation> findByCommunityUserIdAndEmail(UUID communityUserId, String email);

    Optional<CommunityInvitation> findByCommunityUserIdAndUserId(UUID communityUserId, UUID userId);

    Optional<CommunityInvitation> findByIdAndCommunityUserId(UUID id, UUID communityUserId);

    /** A community account's invitations, expired ones included, oldest first. */
    List<CommunityInvitation> findByCommunityUserIdOrderByCreatedAtAscIdAsc(UUID communityUserId);

    /**
     * Uses up every invitation of the community account to the user, by id or to their address, so a second one cannot
     * bring them back after they leave.
     */
    @Modifying
    @Query("DELETE FROM CommunityInvitation i WHERE i.communityUserId = :communityId AND (i.userId = :userId OR i.email = :email)")
    void deleteForInvitee(@Param("communityId") UUID communityId, @Param("userId") UUID userId, @Param("email") String email);
}
