package com.vomatt.repository;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import com.vomatt.entity.ContactMessage;

@Repository
public interface ContactMessageRepository extends JpaRepository<ContactMessage, UUID> {

    /** Back-office list, newest first. */
    @Query("SELECT m FROM ContactMessage m ORDER BY m.createdAt DESC, m.id DESC")
    Page<ContactMessage> findPage(Pageable pageable);
}
