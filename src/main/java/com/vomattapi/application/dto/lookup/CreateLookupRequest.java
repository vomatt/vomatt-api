package com.vomattapi.application.dto.lookup;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Create dictionary item request
 */
@Data
public class CreateLookupRequest {

    @NotBlank
    @Size(max = 50)
    private String lookupType;

    @NotBlank
    @Size(max = 50)
    private String lookupKey;

    @NotNull
    private JsonNode lookupValue;

    private Integer seq = 0;

    @Size(max = 50)
    private String parentType;

    @Size(max = 50)
    private String parentKey;

    @Size(max = 100)
    private String description;

    private Boolean frontendUsing = false;
}
