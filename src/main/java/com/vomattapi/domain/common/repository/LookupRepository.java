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
     * 查詢某 type 下所有 active 項目，依 seq 排序
     */
    List<Lookup> findByLookupTypeAndIsActiveTrueOrderBySeqAsc(String lookupType);

    /**
     * 查詢特定 type + key 的項目
     */
    Optional<Lookup> findByLookupTypeAndLookupKey(String lookupType, String lookupKey);

    /**
     * 查詢某 type 下特定父節點的子項目（active only），依 seq 排序
     */
    List<Lookup> findByLookupTypeAndParentTypeAndParentKeyAndIsActiveTrueOrderBySeqAsc(
            String lookupType, String parentType, String parentKey);

    /**
     * 查詢所有標記為前端使用的 active 項目，依 type 和 seq 排序
     */
    List<Lookup> findByFrontendUsingTrueAndIsActiveTrueOrderByLookupTypeAscSeqAsc();

    /**
     * 確認 type + key 組合是否已存在（新增時用）
     */
    boolean existsByLookupTypeAndLookupKey(String lookupType, String lookupKey);

    /**
     * 確認 type + key 組合是否被其他筆記錄使用（更新時用）
     */
    boolean existsByLookupTypeAndLookupKeyAndIdNot(String lookupType, String lookupKey, UUID id);
}
