package com.vomatt.tags.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TagDto {
    private String id;
    private String name;
    private String slug;
    private String description;
    private int displayOrder;
    private int usageCount;
}
