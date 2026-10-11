package com.vomatt.entity.common;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.OffsetDateTime;

/**
 * Base class for entities that need updated_at; extends BaseEntity. updated_at equals created_at on creation and is refreshed on every update.
 */
@Getter
@SuperBuilder
@NoArgsConstructor
@MappedSuperclass
public abstract class AuditableEntity extends BaseEntity {

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    // Runs after BaseEntity's @PrePersist, so a new row has updated_at == created_at exactly
    @PrePersist
    void stampUpdatedAtOnInsert() {
        updatedAt = getCreatedAt();
    }

    @PreUpdate
    void stampUpdatedAt() {
        updatedAt = OffsetDateTime.now();
    }
}
