package com.vomattapi.application.dto.lookup;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 更新字典項目請求（所有欄位為 Optional，只更新非 null 的欄位）
 */
@Data
public class UpdateLookupRequest {

    @Size(max = 50)
    private String lookupType;

    @Size(max = 50)
    private String lookupKey;

    private JsonNode lookupValue;

    private Integer seq;

    @Size(max = 50)
    private String parentType;

    @Size(max = 50)
    private String parentKey;

    @Size(max = 100)
    private String description;

    private Boolean frontendUsing;
}
