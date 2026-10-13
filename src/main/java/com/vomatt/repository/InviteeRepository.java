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

import com.vomatt.entity.Invitee;

@Repository
public interface InviteeRepository extends JpaRepository<Invitee, UUID> {

    boolean existsByVoteIdAndUserId(UUID voteId, UUID userId);

    Optional<Invitee> findByVoteIdAndUserId(UUID voteId, UUID userId);

    Optional<Invitee> findByVoteIdAndEmail(UUID voteId, String email);

    List<Invitee> findByVoteId(UUID voteId);

    @Query("SELECT i.vote.id AS id, COUNT(i) AS count FROM Invitee i WHERE i.vote.id IN :voteIds GROUP BY i.vote.id")
    List<IdCount> countByVoteIds(@Param("voteIds") Collection<UUID> voteIds);

    /** Invitees of a Poll with whether each holds a Ballot, earliest invited first. */
    @Query("""
            SELECT u.id AS userId, u.username AS username, i.email AS email, i.createdAt AS invitedAt,
                   CASE WHEN EXISTS (SELECT 1 FROM UserVote uv WHERE uv.vote.id = i.vote.id AND uv.user.id = u.id)
                        THEN true ELSE false END AS hasBallot
            FROM Invitee i LEFT JOIN i.user u WHERE i.vote.id = :voteId ORDER BY i.createdAt, i.id""")
    List<InviteeStatus> findStatuses(@Param("voteId") UUID voteId);

    /**
     * Binding step 1: turns email Invitees into user Invitees, except on Polls where the user already is an
     * Invitee or the owner. Only for an email the user has verified.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE vomatt.vote_invitees i SET user_id = :userId, email = NULL, updated_at = now()
            WHERE i.email = :email
              AND NOT EXISTS (SELECT 1 FROM vomatt.vote_invitees u WHERE u.vote_id = i.vote_id AND u.user_id = :userId)
              AND NOT EXISTS (SELECT 1 FROM vomatt.votes v WHERE v.id = i.vote_id AND v.creator_id = :userId)""",
            nativeQuery = true)
    int bindEmail(@Param("userId") UUID userId, @Param("email") String email);

    /**
     * Binding step 2: drops the email rows step 1 skipped, as duplicates of the user (or the owner). Rows added after
     * step 1 stay pending and bind at the next verified sign-in.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            DELETE FROM vomatt.vote_invitees i
            WHERE i.email = :email
              AND (EXISTS (SELECT 1 FROM vomatt.vote_invitees u WHERE u.vote_id = i.vote_id AND u.user_id = :userId)
                   OR EXISTS (SELECT 1 FROM vomatt.votes v WHERE v.id = i.vote_id AND v.creator_id = :userId))""",
            nativeQuery = true)
    int deleteDuplicateEmail(@Param("userId") UUID userId, @Param("email") String email);
}
