package com.vomattapi.domain.member.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.vomattapi.domain.member.Member;
import com.vomattapi.domain.member.MemberActivity;

@Repository
public interface MemberActivityRepository extends JpaRepository<MemberActivity, Long> {
    Page<MemberActivity> findByMember(Member member, Pageable pageable);
    
    List<MemberActivity> findByMemberAndActivityTypeAndTimestampAfter(Member member, String activityType, LocalDateTime after);
    
    Page<MemberActivity> findByActivityTypeAndTimestampBetween(String activityType, LocalDateTime start, LocalDateTime end, Pageable pageable);
}
