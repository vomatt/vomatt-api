package com.vomatt.repository;

import com.vomatt.entity.Tag;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Repository
public interface TagRepository extends JpaRepository<Tag, UUID> {

    boolean existsByName(String name);

    boolean existsBySlug(String slug);

    boolean existsByNameAndIdNot(String name, UUID id);

    boolean existsBySlugAndIdNot(String slug, UUID id);

    List<Tag> findAllByOrderByDisplayOrderAsc();

    /** Keyset page of tags by usage, most used first; null cursor for the first page. */
    @Query("""
            SELECT t FROM Tag t
            WHERE CAST(:afterCount AS Integer) IS NULL OR t.usageCount < :afterCount
               OR (t.usageCount = :afterCount AND t.id < :afterId)
            ORDER BY t.usageCount DESC, t.id DESC""")
    List<Tag> findPopularPage(@Param("afterCount") Integer afterCount, @Param("afterId") UUID afterId, Limit limit);

    // An empty set is fine: Hibernate renders the empty IN as 1=0 (TagUsagePostgresTest)
    @Modifying
    @Query("UPDATE Tag t SET t.usageCount = t.usageCount + 1 WHERE t.id IN :tagIds")
    void incrementUsageCount(@Param("tagIds") Set<UUID> tagIds);

    @Modifying
    @Query("UPDATE Tag t SET t.usageCount = t.usageCount - 1 WHERE t.id IN :tagIds AND t.usageCount > 0")
    void decrementUsageCount(@Param("tagIds") Set<UUID> tagIds);

    @Query("SELECT CASE WHEN COUNT(v) > 0 THEN true ELSE false END FROM Vote v JOIN v.tags t WHERE t.id = :tagId")
    boolean isTagReferencedByVotes(@Param("tagId") UUID tagId);

    /** Follows a tag; 0 when already followed or the tag does not exist. */
    @Modifying
    @Query(value = """
            INSERT INTO vomatt.tag_follows (user_id, tag_id)
            SELECT :userId, t.id FROM vomatt.tags t WHERE t.id = :tagId
            ON CONFLICT (user_id, tag_id) DO NOTHING""", nativeQuery = true)
    int follow(@Param("userId") UUID userId, @Param("tagId") UUID tagId);

    @Modifying
    @Query(value = "DELETE FROM vomatt.tag_follows WHERE user_id = :userId AND tag_id = :tagId", nativeQuery = true)
    int unfollow(@Param("userId") UUID userId, @Param("tagId") UUID tagId);

    /** Tags the user follows, most recently followed first. */
    @Query(value = """
            SELECT t.* FROM vomatt.tags t JOIN vomatt.tag_follows f ON f.tag_id = t.id
            WHERE f.user_id = :userId ORDER BY f.created_at DESC, t.id DESC""", nativeQuery = true)
    List<Tag> findFollowedBy(@Param("userId") UUID userId);

    /** A user's follow of a tag, for the data export. */
    interface FollowRow {
        UUID getTagId();

        Instant getCreatedAt();
    }

    /** The user's tag follows, oldest first. */
    @Query(value = """
            SELECT tag_id AS "tagId", created_at AS "createdAt" FROM vomatt.tag_follows
            WHERE user_id = :userId ORDER BY created_at, tag_id""", nativeQuery = true)
    List<FollowRow> findFollowsOf(@Param("userId") UUID userId);
}
