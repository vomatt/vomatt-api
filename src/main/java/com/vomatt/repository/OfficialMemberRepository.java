package com.vomatt.repository;

import com.vomatt.entity.OfficialMember;
import com.vomatt.entity.OfficialRole;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OfficialMemberRepository extends JpaRepository<OfficialMember, OfficialMember.Key> {

    /** The member's role in an active (not suspended) official account; empty when not a member. */
    @Query("SELECT m.role FROM OfficialMember m WHERE m.officialUserId = :officialId AND m.userId = :userId AND m.official.active = true")
    Optional<OfficialRole> findActiveRole(@Param("officialId") UUID officialId, @Param("userId") UUID userId);

    Optional<OfficialMember> findByOfficialUserIdAndUserId(UUID officialUserId, UUID userId);

    @EntityGraph(attributePaths = "user")
    List<OfficialMember> findByOfficialUserIdOrderByCreatedAtAscUserIdAsc(UUID officialUserId);

    /** The account switcher: the active official accounts the user belongs to, oldest membership first. */
    @EntityGraph(attributePaths = "official")
    List<OfficialMember> findByUserIdAndOfficialActiveTrueOrderByCreatedAtAscOfficialUserIdAsc(UUID userId);

    /**
     * Locks the official account's OWNER rows and returns their user ids, so two OWNERs demoting or removing each
     * other concurrently cannot both pass the last-OWNER guard.
     */
    @Query(value = "SELECT user_id FROM vomatt.official_members WHERE official_user_id = :officialId AND role = 'OWNER' "
            + "ORDER BY user_id FOR UPDATE", nativeQuery = true)
    List<UUID> lockOwnerIds(@Param("officialId") UUID officialId);

    /** Whether the user is the only OWNER of some official account (their account deletion would leave it ownerless). */
    @Query(value = """
            SELECT EXISTS (SELECT 1 FROM vomatt.official_members m WHERE m.user_id = :userId AND m.role = 'OWNER'
              AND NOT EXISTS (SELECT 1 FROM vomatt.official_members o
                              WHERE o.official_user_id = m.official_user_id AND o.role = 'OWNER' AND o.user_id <> :userId))""",
            nativeQuery = true)
    boolean isSoleOwnerOfAny(@Param("userId") UUID userId);
}
