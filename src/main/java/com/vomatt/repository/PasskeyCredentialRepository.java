package com.vomatt.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.vomatt.entity.PasskeyCredential;

@Repository
public interface PasskeyCredentialRepository extends JpaRepository<PasskeyCredential, UUID> {

    List<PasskeyCredential> findByUserIdOrderByCreatedAt(UUID userId);

    /** With its user, which sign-in hands to AuthService after the transaction ends. */
    @EntityGraph(attributePaths = "user")
    Optional<PasskeyCredential> findByCredentialId(byte[] credentialId);

    Optional<PasskeyCredential> findByIdAndUserId(UUID id, UUID userId);
}
