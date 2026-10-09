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
 * 需要 updated_at 的 Entity 基底類別，繼承 BaseEntity。 updated_at 建立時等於 created_at，之後每次更新時刷新。
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
