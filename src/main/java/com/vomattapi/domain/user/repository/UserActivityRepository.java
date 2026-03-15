package com.vomattapi.domain.user.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.vomattapi.domain.user.User;
import com.vomattapi.domain.user.UserActivity;

@Repository
public interface UserActivityRepository extends JpaRepository<UserActivity, UUID> {
    Page<UserActivity> findByUser(User user, Pageable pageable);

    List<UserActivity> findByUserAndActivityTypeAndCreatedAtAfter(User user, String activityType, LocalDateTime after);

    Page<UserActivity> findByActivityTypeAndCreatedAtBetween(String activityType, LocalDateTime start, LocalDateTime end, Pageable pageable);
}
