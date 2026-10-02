package com.vomatt.lookups.dto;


/**
 * Dictionary table response DTO
 */
public record LookupDto(
        String id,
        String lookupType,
        String lookupKey,
        Object lookupValue,
        Integer seq,
        String parentType,
        String parentKey,
        Boolean isActive,
        String description,
        Boolean frontendUsing
) {}
