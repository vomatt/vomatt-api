package com.vomattapi.application.service.lookup;

import com.vomattapi.application.dto.lookup.CreateLookupRequest;
import com.vomattapi.application.dto.lookup.LookupDto;
import com.vomattapi.application.dto.lookup.UpdateLookupRequest;

import java.util.List;
import java.util.Map;

public interface LookupService {

    /**
     * Get all active dictionary items of a specific type (Public)
     */
    List<LookupDto> getActiveByType(String lookupType);

    /**
     * Get a single dictionary item
     */
    LookupDto getByTypeAndKey(String lookupType, String lookupKey);

    /**
     * Get child items of a specific parent node under a type (Public)
     */
    List<LookupDto> getChildrenByParent(String lookupType, String parentType, String parentKey);

    /**
     * Get all frontend dictionaries, return grouped by type (Public)
     */
    Map<String, List<LookupDto>> getFrontendLookups();

    /**
     * Create dictionary item (Admin)
     */
    LookupDto create(CreateLookupRequest request);

    /**
     * Update dictionary item (Admin)
     */
    LookupDto update(String id, UpdateLookupRequest request);

    /**
     * Activate dictionary item (Admin)
     */
    void activate(String id);

    /**
     * Deactivate dictionary item (Admin, soft delete)
     */
    void deactivate(String id);

    /**
     * Delete dictionary item (Admin, hard delete)
     */
    void delete(String id);
}
