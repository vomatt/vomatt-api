package com.vomatt.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.vomatt.entity.Vote;

import jakarta.persistence.LockModeType;

/** List queries live in {@link VoteListRepository}. */
@Repository
public interface VoteRepository extends JpaRepository<Vote, UUID> {

    @Query("SELECT v FROM Vote v LEFT JOIN FETCH v.options WHERE v.id = :id")
    Optional<Vote> findByIdWithOptions(@Param("id") UUID id);

    /**
     * Shared Poll-row lock (FOR SHARE) for Ballot changes; they do not block one another but wait for Close / Edit.
     * Must be the first load of the Poll in the transaction, so its status is read from the locked row.
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("SELECT v FROM Vote v WHERE v.id = :id")
    Optional<Vote> findByIdForShare(@Param("id") UUID id);

    /** Exclusive Poll-row lock (FOR UPDATE) for Close and Edit; waits for in-flight Ballot changes. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT v FROM Vote v WHERE v.id = :id")
    Optional<Vote> findByIdForUpdate(@Param("id") UUID id);
}
