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
     * The one binding predicate, for a pending email row aliased {@code i}: the user is neither already an Invitee of
     * that Poll nor its owner. Shared by every binding statement so they agree on which rows bind.
     */
    String BINDABLE = """
            (NOT EXISTS (SELECT 1 FROM vomatt.vote_invitees u WHERE u.vote_id = i.vote_id AND u.user_id = :userId)
             AND NOT EXISTS (SELECT 1 FROM vomatt.votes v WHERE v.id = i.vote_id AND v.creator_id = :userId))""";

    @Query(value = "SELECT i.id FROM vomatt.vote_invitees i WHERE i.email = :email", nativeQuery = true)
    List<UUID> findIdsByEmail(@Param("email") String email);

    /**
     * Binding step 2, per pending row: adds the user Invitee in its place. An INSERT rather than an UPDATE of the email
     * row, because ON CONFLICT DO NOTHING absorbs an owner inviting the same user concurrently: an UPDATE would hit
     * UNIQUE (vote_id, user_id) and roll back the whole sign-up. Step 3 then drops the email row either way.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO vomatt.vote_invitees (id, vote_id, user_id, created_at, updated_at)
            SELECT :id, i.vote_id, :userId, i.created_at, now() FROM vomatt.vote_invitees i
            WHERE i.id = :rowId AND """ + BINDABLE + """

            ON CONFLICT (vote_id, user_id) DO NOTHING""", nativeQuery = true)
    int bindEmailRow(@Param("id") UUID id, @Param("rowId") UUID rowId, @Param("userId") UUID userId);

    /**
     * Binding step 3: drops the email rows that are no longer bindable, i.e. now covered by a user row (bound in step 2
     * or invited before) or the owner's own address. Rows added after step 2 stay pending and bind at the next sign-in.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "DELETE FROM vomatt.vote_invitees i WHERE i.email = :email AND NOT " + BINDABLE, nativeQuery = true)
    int deleteUnbindableEmail(@Param("userId") UUID userId, @Param("email") String email);

    @Query(value = "SELECT EXISTS (SELECT 1 FROM vomatt.vote_invitation_emails WHERE vote_id = :voteId AND email = :email)",
            nativeQuery = true)
    boolean isInvitationSent(@Param("voteId") UUID voteId, @Param("email") String email);

    /** Records that the Poll's invitation went to this address, so it is never sent there again. */
    @Modifying
    @Query(value = "INSERT INTO vomatt.vote_invitation_emails (vote_id, email) VALUES (:voteId, :email) ON CONFLICT DO NOTHING",
            nativeQuery = true)
    void recordInvitationSent(@Param("voteId") UUID voteId, @Param("email") String email);
}
