package com.vomattapi.application.dto.lookup;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 字典表回應 DTO
 */
public record LookupDto(
        String id,
        String lookupType,
        String lookupKey,
        JsonNode lookupValue,
        Integer seq,
        String parentType,
        String parentKey,
        Boolean isActive,
        String description,
        Boolean frontendUsing
) {}
