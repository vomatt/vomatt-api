package com.vomatt.entity;

import com.vomatt.entity.common.AuditableEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;


/**
 * Dictionary table entity
 */
@Entity
@Table(name = "lookup")
@Data
@NoArgsConstructor
public class Lookup extends AuditableEntity {

    @Column(name = "lookup_type", nullable = false, length = 50)
    private String lookupType;

    @Column(name = "lookup_key", nullable = false, length = 50)
    private String lookupKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "lookup_value", nullable = false, columnDefinition = "json")
    /**
     * JSON 值（物件 / 陣列 / 純量）。刻意宣告為 Object：Hibernate 7.2 的 JSON mapper 只支援 Jackson 2，
     * 無法產生 Jackson 3 的 JsonNode；Object 讓兩邊都以 Map / List / 純量往返。
     */
    private Object lookupValue;

    @Column(name = "seq", nullable = false)
    private Integer seq = 0;

    @Column(name = "parent_type", length = 50)
    private String parentType;

    @Column(name = "parent_key", length = 50)
    private String parentKey;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "description", length = 100)
    private String description;

    @Column(name = "frontend_using", nullable = false)
    private Boolean frontendUsing = false;
}
