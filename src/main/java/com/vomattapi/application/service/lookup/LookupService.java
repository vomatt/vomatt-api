package com.vomattapi.application.service.lookup;

import com.vomattapi.application.dto.lookup.CreateLookupRequest;
import com.vomattapi.application.dto.lookup.LookupDto;
import com.vomattapi.application.dto.lookup.UpdateLookupRequest;

import java.util.List;
import java.util.Map;

public interface LookupService {

    /**
     * 取得某 type 下所有 active 字典項目（公開）
     */
    List<LookupDto> getActiveByType(String lookupType);

    /**
     * 取得單筆字典項目
     */
    LookupDto getByTypeAndKey(String lookupType, String lookupKey);

    /**
     * 取得某 type 下特定父節點的子項目（公開）
     */
    List<LookupDto> getChildrenByParent(String lookupType, String parentType, String parentKey);

    /**
     * 取得所有前端用字典，依 type 分組回傳（公開）
     */
    Map<String, List<LookupDto>> getFrontendLookups();

    /**
     * 建立字典項目（Admin）
     */
    LookupDto create(CreateLookupRequest request);

    /**
     * 更新字典項目（Admin）
     */
    LookupDto update(String id, UpdateLookupRequest request);

    /**
     * 啟用字典項目（Admin）
     */
    void activate(String id);

    /**
     * 停用字典項目（Admin，軟刪除）
     */
    void deactivate(String id);

    /**
     * 刪除字典項目（Admin，硬刪除）
     */
    void delete(String id);
}
