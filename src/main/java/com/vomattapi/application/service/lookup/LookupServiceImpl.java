package com.vomattapi.application.service.lookup;

import com.vomattapi.application.dto.lookup.CreateLookupRequest;
import com.vomattapi.application.dto.lookup.LookupDto;
import com.vomattapi.application.dto.lookup.UpdateLookupRequest;
import com.vomattapi.application.exception.BusinessRuleViolationException;
import com.vomattapi.application.exception.EntityNotFoundException;
import com.vomattapi.application.mapper.LookupMapper;
import com.vomattapi.domain.common.Lookup;
import com.vomattapi.domain.common.repository.LookupRepository;
import com.vomattapi.infrastructure.redis.CacheUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class LookupServiceImpl implements LookupService {

    private final LookupRepository lookupRepository;
    private final LookupMapper lookupMapper;
    private final CacheUtil cacheUtil;

    private static final String CACHE_PREFIX_TYPE = "lookup:type:";
    private static final String CACHE_KEY_FRONTEND = "lookup:frontend";
    private static final Duration LOOKUP_CACHE_DURATION = Duration.ofHours(2);

    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<LookupDto> getActiveByType(String lookupType) {
        String cacheKey = CACHE_PREFIX_TYPE + lookupType;
        return (List<LookupDto>) cacheUtil.getOrSet(cacheKey, Object.class,
                () -> lookupRepository.findByLookupTypeAndIsActiveTrueOrderBySeqAsc(lookupType)
                        .stream()
                        .map(lookupMapper::toDto)
                        .collect(Collectors.toList()),
                LOOKUP_CACHE_DURATION);
    }

    @Override
    @Transactional(readOnly = true)
    public LookupDto getByTypeAndKey(String lookupType, String lookupKey) {
        return lookupRepository.findByLookupTypeAndLookupKey(lookupType, lookupKey)
                .map(lookupMapper::toDto)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Lookup", lookupType + "/" + lookupKey));
    }

    @Override
    @Transactional(readOnly = true)
    public List<LookupDto> getChildrenByParent(String lookupType, String parentType, String parentKey) {
        return lookupRepository
                .findByLookupTypeAndParentTypeAndParentKeyAndIsActiveTrueOrderBySeqAsc(
                        lookupType, parentType, parentKey)
                .stream()
                .map(lookupMapper::toDto)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public Map<String, List<LookupDto>> getFrontendLookups() {
        return (Map<String, List<LookupDto>>) cacheUtil.getOrSet(CACHE_KEY_FRONTEND, Object.class,
                () -> lookupRepository.findByFrontendUsingTrueAndIsActiveTrueOrderByLookupTypeAscSeqAsc()
                        .stream()
                        .map(lookupMapper::toDto)
                        .collect(Collectors.groupingBy(LookupDto::lookupType)),
                LOOKUP_CACHE_DURATION);
    }

    @Override
    @Transactional
    public LookupDto create(CreateLookupRequest request) {
        String type = request.getLookupType().trim();
        String key = request.getLookupKey().trim();

        if (lookupRepository.existsByLookupTypeAndLookupKey(type, key)) {
            throw new BusinessRuleViolationException(
                    "字典項目已存在: " + type + "/" + key);
        }

        Lookup lookup = new Lookup();
        lookup.setLookupType(type);
        lookup.setLookupKey(key);
        lookup.setLookupValue(request.getLookupValue());
        lookup.setSeq(request.getSeq() != null ? request.getSeq() : 0);
        lookup.setParentType(request.getParentType());
        lookup.setParentKey(request.getParentKey());
        lookup.setDescription(request.getDescription());
        lookup.setFrontendUsing(request.getFrontendUsing() != null ? request.getFrontendUsing() : false);
        lookup.setIsActive(true);

        Lookup saved = lookupRepository.save(lookup);
        evictCacheForType(type, Boolean.TRUE.equals(saved.getFrontendUsing()));
        log.info("字典項目已建立: {}/{}", type, key);
        return lookupMapper.toDto(saved);
    }

    @Override
    @Transactional
    public LookupDto update(String id, UpdateLookupRequest request) {
        Lookup lookup = findById(id);

        // 若要更換 type/key，確認不重複
        String newType = request.getLookupType() != null
                ? request.getLookupType().trim() : lookup.getLookupType();
        String newKey = request.getLookupKey() != null
                ? request.getLookupKey().trim() : lookup.getLookupKey();

        if (lookupRepository.existsByLookupTypeAndLookupKeyAndIdNot(newType, newKey, lookup.getId())) {
            throw new BusinessRuleViolationException(
                    "字典項目已存在: " + newType + "/" + newKey);
        }

        String oldType = lookup.getLookupType();
        boolean oldFrontend = Boolean.TRUE.equals(lookup.getFrontendUsing());

        if (request.getLookupType() != null) lookup.setLookupType(newType);
        if (request.getLookupKey() != null) lookup.setLookupKey(newKey);
        if (request.getLookupValue() != null) lookup.setLookupValue(request.getLookupValue());
        if (request.getSeq() != null) lookup.setSeq(request.getSeq());
        if (request.getParentType() != null) lookup.setParentType(request.getParentType());
        if (request.getParentKey() != null) lookup.setParentKey(request.getParentKey());
        if (request.getDescription() != null) lookup.setDescription(request.getDescription());
        if (request.getFrontendUsing() != null) lookup.setFrontendUsing(request.getFrontendUsing());

        Lookup saved = lookupRepository.save(lookup);

        // 清除可能影響到的快取
        evictCacheForType(oldType, oldFrontend);
        evictCacheForType(newType, Boolean.TRUE.equals(saved.getFrontendUsing()));
        log.info("字典項目已更新: id={}", id);
        return lookupMapper.toDto(saved);
    }

    @Override
    @Transactional
    public void activate(String id) {
        Lookup lookup = findById(id);
        lookup.setIsActive(true);
        lookupRepository.save(lookup);
        evictCacheForType(lookup.getLookupType(), Boolean.TRUE.equals(lookup.getFrontendUsing()));
        log.info("字典項目已啟用: id={}, type={}", id, lookup.getLookupType());
    }

    @Override
    @Transactional
    public void deactivate(String id) {
        Lookup lookup = findById(id);
        lookup.setIsActive(false);
        lookupRepository.save(lookup);
        evictCacheForType(lookup.getLookupType(), Boolean.TRUE.equals(lookup.getFrontendUsing()));
        log.info("字典項目已停用: id={}, type={}", id, lookup.getLookupType());
    }

    @Override
    @Transactional
    public void delete(String id) {
        Lookup lookup = findById(id);
        String type = lookup.getLookupType();
        boolean frontend = Boolean.TRUE.equals(lookup.getFrontendUsing());
        lookupRepository.delete(lookup);
        evictCacheForType(type, frontend);
        log.info("字典項目已刪除: id={}, type={}", id, type);
    }

    // ─── private helpers ──────────────────────────────────────────────────────

    private Lookup findById(String id) {
        return lookupRepository.findById(UUID.fromString(id))
                .orElseThrow(() -> new EntityNotFoundException("Lookup", id));
    }

    private void evictCacheForType(String lookupType, boolean isFrontend) {
        cacheUtil.evict(CACHE_PREFIX_TYPE + lookupType);
        if (isFrontend) {
            cacheUtil.evict(CACHE_KEY_FRONTEND);
        }
    }
}
