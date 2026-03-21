package com.vomattapi.domain.vote.repository;

import com.vomattapi.domain.vote.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public interface TagRepository extends JpaRepository<Tag, UUID> {

    Optional<Tag> findBySlug(String slug);

    Optional<Tag> findByName(String name);

    boolean existsByName(String name);

    boolean existsBySlug(String slug);

    boolean existsByNameAndIdNot(String name, UUID id);

    boolean existsBySlugAndIdNot(String slug, UUID id);

    List<Tag> findAllByOrderByDisplayOrderAsc();

    Page<Tag> findAllByOrderByUsageCountDesc(Pageable pageable);

    List<Tag> findAllByIdIn(Set<UUID> ids);

    @Modifying
    @Query("UPDATE Tag t SET t.usageCount = t.usageCount + 1 WHERE t.id IN :tagIds")
    void incrementUsageCount(@Param("tagIds") Set<UUID> tagIds);

    @Modifying
    @Query("UPDATE Tag t SET t.usageCount = t.usageCount - 1 WHERE t.id IN :tagIds AND t.usageCount > 0")
    void decrementUsageCount(@Param("tagIds") Set<UUID> tagIds);

    @Query("SELECT CASE WHEN COUNT(v) > 0 THEN true ELSE false END FROM Vote v JOIN v.tags t WHERE t.id = :tagId")
    boolean isTagReferencedByVotes(@Param("tagId") UUID tagId);
}
