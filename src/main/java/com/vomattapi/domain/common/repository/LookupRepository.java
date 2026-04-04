package com.vomattapi.domain.common.repository;

import com.vomattapi.domain.common.Lookup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LookupRepository extends JpaRepository<Lookup, UUID> {

    /**
     * Query all active items of a specific type, sorted by seq
     */
    List<Lookup> findByLookupTypeAndIsActiveTrueOrderBySeqAsc(String lookupType);

    /**
     * Query item for specific type + key combination
     */
    Optional<Lookup> findByLookupTypeAndLookupKey(String lookupType, String lookupKey);

    /**
     * Query child items of a specific parent node under a type (active only), sorted by seq
     */
    List<Lookup> findByLookupTypeAndParentTypeAndParentKeyAndIsActiveTrueOrderBySeqAsc(
            String lookupType, String parentType, String parentKey);

    /**
     * Query all active items marked for frontend usage, sorted by type and seq
     */
    List<Lookup> findByFrontendUsingTrueAndIsActiveTrueOrderByLookupTypeAscSeqAsc();

    /**
     * Check if type + key combination already exists (used for creation)
     */
    boolean existsByLookupTypeAndLookupKey(String lookupType, String lookupKey);

    /**
     * Check if type + key combination is used by other records (used for update)
     */
    boolean existsByLookupTypeAndLookupKeyAndIdNot(String lookupType, String lookupKey, UUID id);
}
