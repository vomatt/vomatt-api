package com.vomatt.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.vomatt.entity.InviteList;

import jakarta.persistence.LockModeType;

@Repository
public interface InviteListRepository extends JpaRepository<InviteList, UUID> {

    Optional<InviteList> findByIdAndOwnerId(UUID id, UUID ownerId);

    /** Member changes take this lock, so they serialize with each other and with email binding. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT g FROM InviteList g WHERE g.id = :id AND g.owner.id = :ownerId")
    Optional<InviteList> findOwnedForUpdate(@Param("id") UUID id, @Param("ownerId") UUID ownerId);

    List<InviteList> findByOwnerIdOrderByNameAscIdAsc(UUID ownerId);

    long countByOwnerId(UUID ownerId);

    long countByIdInAndOwnerId(Collection<UUID> ids, UUID ownerId);
}
