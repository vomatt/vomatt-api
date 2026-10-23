package com.vomatt.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.vomatt.entity.Feedback;

@Repository
public interface FeedbackRepository extends JpaRepository<Feedback, UUID> {

    List<Feedback> findByUserIdOrderByCreatedAt(UUID userId);

    /** Back-office list, newest first; {@code userId} null means every user. */
    @EntityGraph(attributePaths = "user")
    @Query("SELECT f FROM Feedback f WHERE (:userId IS NULL OR f.user.id = :userId) ORDER BY f.createdAt DESC, f.id DESC")
    Page<Feedback> findPage(@Param("userId") UUID userId, Pageable pageable);
}
