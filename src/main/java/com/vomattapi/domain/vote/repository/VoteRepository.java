package com.vomattapi.domain.vote.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.vomattapi.domain.vote.Vote;

@Repository
public interface VoteRepository extends JpaRepository<Vote, UUID> {

    List<Vote> findByCreatorIdOrderByCreatedAtDesc(UUID creatorId);

    Page<Vote> findByCreatorIdOrderByCreatedAtDesc(UUID creatorId, Pageable pageable);

    long countByCreatorId(UUID creatorId);

    List<Vote> findByIsActiveTrueOrderByCreatedAtDesc();

    Page<Vote> findByIsActiveTrueOrderByCreatedAtDesc(Pageable pageable);

    @Query("SELECT v FROM Vote v WHERE v.isActive = true AND " +
           "(v.startTime IS NULL OR v.startTime <= :now) AND " +
           "(v.endTime IS NULL OR v.endTime > :now)")
    List<Vote> findActiveVotesAtTime(@Param("now") LocalDateTime now);

    @Query("SELECT v FROM Vote v WHERE v.isActive = true AND " +
           "(v.startTime IS NULL OR v.startTime <= :now) AND " +
           "(v.endTime IS NULL OR v.endTime > :now)")
    Page<Vote> findActiveVotesAtTime(@Param("now") LocalDateTime now, Pageable pageable);

    @Query("SELECT v FROM Vote v WHERE v.endTime IS NOT NULL AND v.endTime < :now AND v.isActive = true")
    List<Vote> findExpiredActiveVotes(@Param("now") LocalDateTime now);

    Optional<Vote> findByIdAndIsActiveTrue(UUID id);
}
