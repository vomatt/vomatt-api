package com.vomatt.entity.common;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;

/**
 * 需要 updated_at 的 Entity 基底類別，繼承 BaseEntity。 updated_at 由 Hibernate @UpdateTimestamp 自動維護。
 */
@Getter
@SuperBuilder
@NoArgsConstructor
@MappedSuperclass
public abstract class AuditableEntity extends BaseEntity {

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
