package com.vomatt.repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.vomatt.entity.VoteBookmark;

@Repository
public interface VoteBookmarkRepository extends JpaRepository<VoteBookmark, UUID> {

    /**
     * Idempotent, also under concurrent requests; a repeat keeps the first bookmark's created_at. Returns 0 when the
     * bookmark already existed, the user does not exist or the Poll is gone or Deleted. The user and Poll rows are
     * locked, user first as in account deletion, so an account deletion or Poll delete still in flight is waited for
     * and yields 0 rather than a foreign-key failure or a bookmark on a Deleted Poll.
     */
    @Modifying
    @Query(value = """
            INSERT INTO vomatt.vote_bookmarks (id, user_id, vote_id)
            SELECT :id, :userId, :voteId
            WHERE EXISTS (SELECT 1 FROM vomatt.users WHERE id = :userId FOR KEY SHARE)
              AND EXISTS (SELECT 1 FROM vomatt.votes WHERE id = :voteId AND deleted_at IS NULL FOR SHARE)
            ON CONFLICT (user_id, vote_id) DO NOTHING""", nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id, @Param("userId") UUID userId, @Param("voteId") UUID voteId);

    /** The bookmark exists and its Poll is not Deleted. */
    boolean existsByUserIdAndVoteIdAndVoteDeletedAtIsNull(UUID userId, UUID voteId);

    /** @return 1 when a bookmark was removed, 0 when there was none */
    @Modifying
    @Query("DELETE FROM VoteBookmark b WHERE b.user.id = :userId AND b.vote.id = :voteId")
    int deleteByUserIdAndVoteId(@Param("userId") UUID userId, @Param("voteId") UUID voteId);

    /**
     * Keyset page of the user's bookmarked Polls, most recently bookmarked first. Deleted Polls, Invite-only Polls the
     * user can no longer reach (Invitee removed) and Polls whose owner blocked the user are left out, as in My Polls.
     * Null cursor values for the first page.
     */
    @Query("""
            SELECT b FROM VoteBookmark b JOIN FETCH b.vote v
            WHERE b.user.id = :userId AND v.deletedAt IS NULL
              AND (v.audience <> com.vomatt.entity.Audience.INVITE_ONLY OR v.creator.id = :userId
                   OR EXISTS (SELECT 1 FROM Invitee i WHERE i.vote = v AND i.user.id = :userId))
              AND NOT EXISTS (SELECT 1 FROM UserBlock x WHERE x.blocker = v.creator AND x.blocked.id = :userId)
              AND (CAST(:afterTime AS OffsetDateTime) IS NULL OR b.createdAt < :afterTime
                   OR (b.createdAt = :afterTime AND b.id < :afterId))
            ORDER BY b.createdAt DESC, b.id DESC""")
    List<VoteBookmark> findPage(@Param("userId") UUID userId, @Param("afterTime") OffsetDateTime afterTime,
                                @Param("afterId") UUID afterId, Limit limit);

    /** For the data export, oldest first. */
    List<VoteBookmark> findByUserIdOrderByCreatedAt(UUID userId);

    /** Of the given Polls, those the user bookmarked; one query for a page of Polls. */
    @Query("SELECT b.vote.id FROM VoteBookmark b WHERE b.user.id = :userId AND b.vote.id IN :voteIds")
    List<UUID> findVoteIds(@Param("userId") UUID userId, @Param("voteIds") Collection<UUID> voteIds);
}
