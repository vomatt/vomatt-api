package com.vomatt.repository;

import com.vomatt.entity.CommunityMember;
import com.vomatt.entity.CommunityRole;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CommunityMemberRepository extends JpaRepository<CommunityMember, CommunityMember.Key> {

    /** The member's role in an active (not suspended) community account; empty when not a member. */
    @Query("SELECT m.role FROM CommunityMember m WHERE m.communityUserId = :communityId AND m.userId = :userId AND m.community.active = true")
    Optional<CommunityRole> findActiveRole(@Param("communityId") UUID communityId, @Param("userId") UUID userId);

    Optional<CommunityMember> findByCommunityUserIdAndUserId(UUID communityUserId, UUID userId);

    @EntityGraph(attributePaths = "user")
    List<CommunityMember> findByCommunityUserIdOrderByCreatedAtAscUserIdAsc(UUID communityUserId);

    /** The account switcher: the active community accounts the user belongs to, oldest membership first. */
    @EntityGraph(attributePaths = "community")
    List<CommunityMember> findByUserIdAndCommunityActiveTrueOrderByCreatedAtAscCommunityUserIdAsc(UUID userId);

    /**
     * Locks the community account's OWNER rows and returns their user ids, so two OWNERs demoting or removing each
     * other concurrently cannot both pass the last-OWNER guard.
     */
    @Query(value = "SELECT user_id FROM vomatt.community_members WHERE community_user_id = :communityId AND role = 'OWNER' "
            + "ORDER BY user_id FOR UPDATE", nativeQuery = true)
    List<UUID> lockOwnerIds(@Param("communityId") UUID communityId);

    /** Whether the user is the only OWNER of some community account (their account deletion would leave it ownerless). */
    @Query(value = """
            SELECT EXISTS (SELECT 1 FROM vomatt.community_members m WHERE m.user_id = :userId AND m.role = 'OWNER'
              AND NOT EXISTS (SELECT 1 FROM vomatt.community_members o
                              WHERE o.community_user_id = m.community_user_id AND o.role = 'OWNER' AND o.user_id <> :userId))""",
            nativeQuery = true)
    boolean isSoleOwnerOfAny(@Param("userId") UUID userId);

    /** The active community accounts the user is an OWNER of, locked or not, earliest created first. */
    @Query(value = "SELECT c.id FROM vomatt.community_members m JOIN vomatt.users c ON c.id = m.community_user_id "
            + "WHERE m.user_id = :userId AND m.role = 'OWNER' AND c.active ORDER BY c.created_at, c.id", nativeQuery = true)
    List<UUID> findOwnedIds(@Param("userId") UUID userId);

    /** {@link #findOwnedIds} of only the locked ones, or only the unlocked ones. */
    @Query(value = "SELECT c.id FROM vomatt.community_members m JOIN vomatt.users c ON c.id = m.community_user_id "
            + "WHERE m.user_id = :userId AND m.role = 'OWNER' AND c.active AND (c.locked_at IS NOT NULL) = :locked "
            + "ORDER BY c.created_at, c.id", nativeQuery = true)
    List<UUID> findOwnedIds(@Param("userId") UUID userId, @Param("locked") boolean locked);

    @Query(value = "SELECT DISTINCT user_id FROM vomatt.community_members WHERE community_user_id IN (:communityIds) AND role = 'OWNER'",
           nativeQuery = true)
    List<UUID> findOwnerIds(@Param("communityIds") Collection<UUID> communityIds);
}
