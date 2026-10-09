package com.vomatt.entity.common;

import com.vomatt.common.util.UUIDv7Generator;
import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 所有 Entity 的基底類別，提供 id 與 created_at。 id 由應用層透過 UUIDv7Generator 自動產生（時序可排序）。
 */
@Getter
@SuperBuilder
@NoArgsConstructor
@MappedSuperclass
public abstract class BaseEntity {

    @UUIDv7Generator.UUIDv7
    @Id
    @Column(name = "id", columnDefinition = "uuid", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "created_at", updatable = false, nullable = false)
    private OffsetDateTime createdAt;

    // Single clock read per insert; AuditableEntity copies it into updated_at
    @PrePersist
    void stampCreatedAt() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }
}
