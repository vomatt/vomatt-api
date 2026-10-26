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

    /** The Invitee added by ID, never one added by email (that one is reached only by its address). */
    Optional<Invitee> findByVoteIdAndUserIdAndEmailIsNull(UUID voteId, UUID userId);

    Optional<Invitee> findByVoteIdAndEmail(UUID voteId, String email);

    List<Invitee> findByVoteId(UUID voteId);

    @Query("SELECT i.vote.id AS id, COUNT(i) AS count FROM Invitee i WHERE i.vote.id IN :voteIds GROUP BY i.vote.id")
    List<IdCount> countByVoteIds(@Param("voteIds") Collection<UUID> voteIds);

    /** Invitees of a Poll with whether each holds a Ballot, earliest invited first. */
    @Query("""
            SELECT u.id AS userId, u.username AS username, u.avatarUrl AS avatarUrl, i.email AS email, i.createdAt AS invitedAt,
                   CASE WHEN EXISTS (SELECT 1 FROM UserVote uv WHERE uv.vote.id = i.vote.id AND uv.user.id = u.id)
                        THEN true ELSE false END AS hasBallot
            FROM Invitee i LEFT JOIN i.user u WHERE i.vote.id = :voteId ORDER BY i.createdAt, i.id""")
    List<InviteeStatus> findStatuses(@Param("voteId") UUID voteId);

    /**
     * The one binding predicate, for a pending email row aliased {@code i}: the user is neither already an Invitee of
     * that Poll nor its owner. Shared by every binding statement so they agree on which rows bind.
     */
    String BINDABLE = """
            (NOT EXISTS (SELECT 1 FROM vomatt.vote_invitees u WHERE u.vote_id = i.vote_id AND u.user_id = :userId)
             AND NOT EXISTS (SELECT 1 FROM vomatt.votes v WHERE v.id = i.vote_id AND v.creator_id = :userId))""";

    /**
     * Binding step 1: write-locks the Polls holding a pending row of this email, in id order. The owner changes
     * Invitees under the same lock, so binding and a concurrent invite of the same user serialize and cannot both
     * add that user; the lock also lets binding auto-end a Poll whose last pending row it drops. A Ballot on such a
     * Poll waits for the sign-in (and vice versa); both are short. Every other path locks a single Poll, so the id
     * order rules out a cycle. FOR UPDATE also blocks FOR KEY SHARE, so inserts that reference the Poll by foreign key
     * without taking its lock (Comments) briefly wait behind a binding; they lock nothing else
     * first, so this adds no cycle either.
     */
    @Query(value = """
            SELECT v.id FROM vomatt.votes v
            WHERE v.id IN (SELECT i.vote_id FROM vomatt.vote_invitees i WHERE i.email = :email AND i.user_id IS NULL)
            ORDER BY v.id FOR UPDATE""", nativeQuery = true)
    List<UUID> lockVotesWithPendingEmail(@Param("email") String email);

    /** Binding step 2: ties the bindable pending rows of the locked Polls to the user; each keeps its email. */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE vomatt.vote_invitees i SET user_id = :userId, updated_at = now()
            WHERE i.email = :email AND i.user_id IS NULL AND i.vote_id IN (:voteIds) AND """ + BINDABLE,
            nativeQuery = true)
    int bindEmail(@Param("userId") UUID userId, @Param("email") String email, @Param("voteIds") Collection<UUID> voteIds);

    /**
     * Binding step 3: drops the pending rows of the locked Polls left unbound, i.e. already covered by a user row
     * (an Invitee added by ID) or the owner's own address.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            DELETE FROM vomatt.vote_invitees i
            WHERE i.email = :email AND i.user_id IS NULL AND i.vote_id IN (:voteIds) AND NOT """ + BINDABLE,
            nativeQuery = true)
    int deleteUnbindableEmail(@Param("userId") UUID userId, @Param("email") String email,
            @Param("voteIds") Collection<UUID> voteIds);

    /** The given addresses the Poll's invitation already went to. */
    @Query(value = "SELECT email FROM vomatt.vote_invitation_emails WHERE vote_id = :voteId AND email IN (:emails)",
            nativeQuery = true)
    List<String> findInvitationsSent(@Param("voteId") UUID voteId, @Param("emails") Collection<String> emails);

    /** Records that the Poll's invitation went to these addresses, so it is never sent there again. */
    @Modifying
    @Query(value = """
            INSERT INTO vomatt.vote_invitation_emails (vote_id, email)
            SELECT :voteId, unnest(CAST(:emails AS text[])) ON CONFLICT DO NOTHING""", nativeQuery = true)
    void recordInvitationsSent(@Param("voteId") UUID voteId, @Param("emails") String[] emails);

    /**
     * Retention: the "already sent" records keep one Poll from mailing the same address twice, so they live as long as
     * the Poll can still invite; they go with the unregistered Invitees once the Poll has been over for the grace period.
     */
    @Modifying
    @Query(value = """
            DELETE FROM vomatt.vote_invitation_emails e
              WHERE EXISTS (SELECT 1 FROM vomatt.votes v WHERE v.id = e.vote_id AND v.end_time < :cutoff)""",
            nativeQuery = true)
    int deleteInvitationEmailsOfPollsEndedBefore(@Param("cutoff") java.time.OffsetDateTime cutoff);

    /**
     * Retention: an email Invitee who never signed up is a third party's address kept only so the Poll can reach them;
     * once the Poll has been over for the grace period there is nothing left to reach them for.
     */
    @Modifying
    @Query(value = """
            DELETE FROM vomatt.vote_invitees i WHERE i.user_id IS NULL
              AND EXISTS (SELECT 1 FROM vomatt.votes v WHERE v.id = i.vote_id AND v.end_time < :cutoff)""",
            nativeQuery = true)
    int deleteUnregisteredOfPollsEndedBefore(@Param("cutoff") java.time.OffsetDateTime cutoff);

    /** At least one Invitee, and none without a Ballot; an Invitee not tied to a user (NULL user_id) counts as without. */
    @Query(value = """
            SELECT EXISTS (SELECT 1 FROM vomatt.vote_invitees WHERE vote_id = :voteId)
               AND NOT EXISTS (SELECT 1 FROM vomatt.vote_invitees i WHERE i.vote_id = :voteId
                   AND (i.user_id IS NULL OR NOT EXISTS (
                       SELECT 1 FROM vomatt.user_votes uv WHERE uv.vote_id = i.vote_id AND uv.user_id = i.user_id)))""",
           nativeQuery = true)
    boolean allHoldBallot(@Param("voteId") UUID voteId);
}
