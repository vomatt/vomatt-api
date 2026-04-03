package com.vomattapi.domain.common;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 字典表實體
 */
@Entity
@Table(name = "lookup")
@Data
@NoArgsConstructor
public class Lookup extends BaseEntity {

    @Column(name = "lookup_type", nullable = false, length = 50)
    private String lookupType;

    @Column(name = "lookup_key", nullable = false, length = 50)
    private String lookupKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "lookup_value", nullable = false, columnDefinition = "json")
    private JsonNode lookupValue;

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

    public List<JsonNode> getLookupValueAsList() {
        if (lookupValue == null || lookupValue.isNull()) {
            return Collections.emptyList();
        }

        if (lookupValue.isArray()) {
            List<JsonNode> list = new ArrayList<>();
            lookupValue.forEach(list::add);
            return Collections.unmodifiableList(list);
        }

        return Collections.singletonList(lookupValue);
    }
}
