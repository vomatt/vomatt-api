package com.vomattapi.application.dto.tag;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateTagRequest {

    @NotBlank(message = "標籤名稱不可為空")
    @Size(max = 30, message = "標籤名稱最長 30 字")
    private String name;

    @Size(max = 50, message = "Slug 最長 50 字")
    @Pattern(regexp = "^[a-z0-9\\-\\u4e00-\\u9fff\\u3040-\\u309f\\u30a0-\\u30ff]*$",
             message = "Slug 只允許小寫英文、數字、連字號和中日韓字符")
    private String slug;

    @Size(max = 200, message = "標籤說明最長 200 字")
    private String description;

    private int displayOrder = 0;
}
