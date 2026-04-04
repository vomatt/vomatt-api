package com.vomattapi.application.dto.lookup;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Update dictionary item request (all fields are Optional, only update non-null fields)
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
