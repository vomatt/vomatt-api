package com.vomatt.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.vomatt.entity.Invitee;

@Repository
public interface InviteeRepository extends JpaRepository<Invitee, UUID> {

    boolean existsByVoteIdAndUserId(UUID voteId, UUID userId);

    Optional<Invitee> findByVoteIdAndUserId(UUID voteId, UUID userId);

    List<Invitee> findByVoteId(UUID voteId);

    @Query("SELECT i.vote.id AS id, COUNT(i) AS count FROM Invitee i WHERE i.vote.id IN :voteIds GROUP BY i.vote.id")
    List<IdCount> countByVoteIds(@Param("voteIds") Collection<UUID> voteIds);

    /** Invitees of a Poll with whether each holds a Ballot, earliest invited first. */
    @Query("""
            SELECT i.user.id AS userId, i.user.username AS username, i.createdAt AS invitedAt,
                   CASE WHEN EXISTS (SELECT 1 FROM UserVote uv WHERE uv.vote.id = i.vote.id AND uv.user.id = i.user.id)
                        THEN true ELSE false END AS hasBallot
            FROM Invitee i WHERE i.vote.id = :voteId ORDER BY i.createdAt, i.id""")
    List<InviteeStatus> findStatuses(@Param("voteId") UUID voteId);
}
