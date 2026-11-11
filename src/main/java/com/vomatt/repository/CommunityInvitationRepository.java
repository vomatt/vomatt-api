package com.vomatt.repository;

import java.time.OffsetDateTime;
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

    /** Joining at sign-up: the address's unexpired invitations. */
    List<CommunityInvitation> findByEmailAndExpiresAtAfter(String email, OffsetDateTime now);

    /** Uses up every invitation of the address, expired ones included, in one statement. */
    @Modifying
    @Query("DELETE FROM CommunityInvitation i WHERE i.email = :email")
    void deleteByEmail(@Param("email") String email);
}
