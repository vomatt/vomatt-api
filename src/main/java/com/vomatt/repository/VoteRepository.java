package com.vomatt.repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.vomatt.entity.Vote;

@Repository
public interface VoteRepository extends JpaRepository<Vote, UUID> {

    List<Vote> findByCreatorIdOrderByCreatedAtDesc(UUID creatorId);

    Page<Vote> findByCreatorIdOrderByCreatedAtDesc(UUID creatorId, Pageable pageable);

    long countByCreatorId(UUID creatorId);

    List<Vote> findByIsActiveTrueOrderByCreatedAtDesc();

    Page<Vote> findByIsActiveTrueOrderByCreatedAtDesc(Pageable pageable);

    /** Started at :now (votes without a start time count as started). */
    String STARTED_AT_NOW = "(v.startTime IS NULL OR v.startTime <= :now)";

    /** Not yet ended at :now (votes without an end time never end). */
    String NOT_ENDED_AT_NOW = "(v.endTime IS NULL OR v.endTime > :now)";

    @Query("SELECT v FROM Vote v WHERE v.isActive = true AND " + STARTED_AT_NOW + " AND " + NOT_ENDED_AT_NOW)
    List<Vote> findActiveVotesAtTime(@Param("now") OffsetDateTime now);

    @Query("SELECT v FROM Vote v WHERE v.isActive = true AND " + STARTED_AT_NOW + " AND " + NOT_ENDED_AT_NOW)
    Page<Vote> findActiveVotesAtTime(@Param("now") OffsetDateTime now, Pageable pageable);

    @Query("SELECT v FROM Vote v WHERE v.endTime IS NOT NULL AND v.endTime < :now AND v.isActive = true")
    List<Vote> findExpiredActiveVotes(@Param("now") OffsetDateTime now);

    @Query("SELECT v FROM Vote v LEFT JOIN FETCH v.tags WHERE v.id = :id")
    Optional<Vote> findByIdWithTags(@Param("id") UUID id);

    /** A page of votes with creator and options in one query; tags follow in one batch (Vote.tags) */
    @Query("SELECT DISTINCT v FROM Vote v JOIN FETCH v.creator LEFT JOIN FETCH v.options WHERE v.id IN :ids")
    List<Vote> findAllForResponseByIdIn(@Param("ids") Collection<UUID> ids);

    /** 某使用者建立、未被取消的投票（含已結束），供公開個人頁使用 */
    @Query("SELECT v FROM Vote v WHERE v.creator.username = :username AND v.isActive = true ORDER BY v.createdAt DESC")
    Page<Vote> findByCreatorUsername(@Param("username") String username, Pageable pageable);

    /** 某使用者投過票的投票（帳號頁「你的投票」），依最近一次投票時間排序 */
    @Query(value = "SELECT v FROM Vote v WHERE v.id IN "
            + "(SELECT uv.vote.id FROM UserVote uv WHERE uv.user.id = :userId) ORDER BY "
            + "(SELECT MAX(uv2.createdAt) FROM UserVote uv2 WHERE uv2.vote = v AND uv2.user.id = :userId) DESC",
            countQuery = "SELECT COUNT(DISTINCT uv.vote.id) FROM UserVote uv WHERE uv.user.id = :userId")
    Page<Vote> findParticipatedByUserId(@Param("userId") UUID userId, Pageable pageable);

    @Query("SELECT v FROM Vote v JOIN v.tags t WHERE t.slug = :tagSlug AND v.isActive = true ORDER BY v.createdAt DESC")
    Page<Vote> findByTagSlugAndIsActiveTrue(@Param("tagSlug") String tagSlug, Pageable pageable);

    /**
     * Public list filter: started, not cancelled, open and/or ended at :now, and :pattern (a LIKE pattern,
     * lower case, '!' escapes) in the title, description or an option.
     */
    String PUBLIC_SEARCH_WHERE = " WHERE v.isActive = true"
            + " AND " + STARTED_AT_NOW
            + " AND ((:includeOpen = true AND " + NOT_ENDED_AT_NOW + ")"
            + " OR (:includeEnded = true AND v.endTime IS NOT NULL AND v.endTime <= :now))"
            + " AND (LOWER(v.title) LIKE :pattern ESCAPE '!'"
            + " OR LOWER(COALESCE(v.description, '')) LIKE :pattern ESCAPE '!'"
            + " OR EXISTS (SELECT 1 FROM VoteOption o WHERE o.vote = v AND LOWER(o.text) LIKE :pattern ESCAPE '!'))";

    /** Public list ordered by the pageable's sort. */
    @Query(value = "SELECT v FROM Vote v" + PUBLIC_SEARCH_WHERE,
            countQuery = "SELECT COUNT(v) FROM Vote v" + PUBLIC_SEARCH_WHERE)
    Page<Vote> searchPublic(@Param("now") OffsetDateTime now, @Param("includeOpen") boolean includeOpen,
            @Param("includeEnded") boolean includeEnded, @Param("pattern") String pattern, Pageable pageable);

    /** Public list, most participants (distinct voters) first; pass an unsorted pageable. */
    @Query(value = "SELECT v FROM Vote v" + PUBLIC_SEARCH_WHERE + " ORDER BY "
            + "(SELECT COUNT(DISTINCT uv.user.id) FROM UserVote uv WHERE uv.vote = v) DESC, v.createdAt DESC",
            countQuery = "SELECT COUNT(v) FROM Vote v" + PUBLIC_SEARCH_WHERE)
    Page<Vote> searchPublicByParticipants(@Param("now") OffsetDateTime now,
            @Param("includeOpen") boolean includeOpen, @Param("includeEnded") boolean includeEnded,
            @Param("pattern") String pattern, Pageable pageable);
}
