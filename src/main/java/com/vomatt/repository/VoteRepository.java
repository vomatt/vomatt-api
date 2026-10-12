package com.vomatt.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.vomatt.entity.Vote;

/** List queries live in {@link VoteListRepository}. */
@Repository
public interface VoteRepository extends JpaRepository<Vote, UUID> {

    @Query("SELECT v FROM Vote v LEFT JOIN FETCH v.options WHERE v.id = :id")
    Optional<Vote> findByIdWithOptions(@Param("id") UUID id);
}
