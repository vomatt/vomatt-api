package com.vomattapi.domain.vote;

import com.vomattapi.domain.common.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "tags")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class Tag extends BaseEntity {

    @Column(name = "name", nullable = false, length = 30, unique = true)
    private String name;

    @Column(name = "slug", nullable = false, length = 50, unique = true)
    private String slug;

    @Column(name = "description", length = 200)
    private String description;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Column(name = "usage_count", nullable = false)
    private int usageCount;

    public Tag(String name, String slug, String description, int displayOrder) {
        this.name = name.trim();
        this.slug = slug;
        this.description = description;
        this.displayOrder = displayOrder;
        this.usageCount = 0;
    }

    public void update(String name, String slug, String description, int displayOrder) {
        this.name = name.trim();
        this.slug = slug;
        this.description = description;
        this.displayOrder = displayOrder;
    }

    /**
     * Slug 自動生成：英文轉小寫 + 空格轉連字號，中日韓字符保留
     */
    public static String generateSlug(String name) {
        String slug = name.trim()
                .toLowerCase()
                .replaceAll("\\s+", "-")
                .replaceAll("[^a-z0-9\\-\\u4e00-\\u9fff\\u3040-\\u309f\\u30a0-\\u30ff]", "");
        if (slug.isBlank()) {
            throw new IllegalArgumentException("無法從名稱生成有效的 slug: " + name);
        }
        return slug;
    }
}
