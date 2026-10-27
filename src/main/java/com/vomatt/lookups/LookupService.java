package com.vomatt.lookups;

import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.lookups.dto.CreateLookupRequest;
import com.vomatt.lookups.dto.LookupDto;
import com.vomatt.lookups.dto.UpdateLookupRequest;
import com.vomatt.entity.Lookup;
import com.vomatt.repository.LookupRepository;
import com.vomatt.common.redis.CacheNamespaces;
import com.vomatt.common.redis.RedisService;
import com.vomatt.common.util.AfterCommit;
import tools.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class LookupService {

    private final LookupRepository lookupRepository;
    private final LookupMapper lookupMapper;
    private final RedisService redisService;

    private static final String CACHE_KEY_FRONTEND = "frontend";
    private static final Duration LOOKUP_CACHE_DURATION = Duration.ofHours(2);

    @Transactional(readOnly = true)
    public List<LookupDto> getActiveByType(String lookupType) {
        List<LookupDto> cached = redisService.get(CacheNamespaces.LOOKUP_BY_TYPE, lookupType, new TypeReference<>() {});
        if (cached != null) return cached;
        List<LookupDto> result = lookupRepository.findByLookupTypeAndIsActiveTrueOrderBySeqAsc(lookupType)
                .stream()
                .map(lookupMapper::toDto)
                .toList();
        redisService.set(CacheNamespaces.LOOKUP_BY_TYPE, lookupType, result, LOOKUP_CACHE_DURATION);
        return result;
    }

    /** Admin listing: one type (or every type when null), inactive items included on request; never cached. */
    @Transactional(readOnly = true)
    public List<LookupDto> listForAdmin(String lookupType, boolean includeInactive) {
        return lookupRepository.findForAdmin(lookupType, includeInactive).stream().map(lookupMapper::toDto).toList();
    }

    /** Lookup type holding admin-tunable backend settings; never set frontendUsing on these. */
    public static final String CONFIG_TYPE = "config";

    /**
     * Admin override of a positive int setting: the active {@code config / key} item, as a JSON number or numeric string.
     * Missing, inactive, non-numeric, non-positive or out-of-int-range values fall back to {@code defaultValue}.
     */
    public int configInt(String key, int defaultValue) {
        Integer value = configOverride(key);
        return value != null ? value : defaultValue;
    }

    /** {@link #configInt} for a duration stored as a count of {@code unit}; without a valid override, {@code defaultValue} as is. */
    public Duration configDuration(String key, ChronoUnit unit, Duration defaultValue) {
        Integer value = configOverride(key);
        return value != null ? Duration.of(value, unit) : defaultValue;
    }

    private Integer configOverride(String key) {
        return getActiveByType(CONFIG_TYPE).stream()
                .filter(l -> key.equals(l.lookupKey()) && l.lookupValue() != null)
                .findFirst()
                .map(l -> {
                    try {
                        long v = new BigDecimal(l.lookupValue().toString().trim()).longValueExact();
                        return v > 0 && v <= Integer.MAX_VALUE ? (Integer) (int) v : null;
                    } catch (NumberFormatException | ArithmeticException e) {
                        log.warn("Ignoring invalid config lookup {}: {}", key, l.lookupValue());
                        return null;
                    }
                })
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public LookupDto getByTypeAndKey(String lookupType, String lookupKey) {
        return lookupRepository.findByLookupTypeAndLookupKey(lookupType, lookupKey)
                .map(lookupMapper::toDto)
                .orElseThrow(() -> ApiException.notFound(MessageKey.LOOKUP_NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public List<LookupDto> getChildrenByParent(String lookupType, String parentType, String parentKey) {
        return lookupRepository
                .findByLookupTypeAndParentTypeAndParentKeyAndIsActiveTrueOrderBySeqAsc(
                        lookupType, parentType, parentKey)
                .stream()
                .map(lookupMapper::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public Map<String, List<LookupDto>> getFrontendLookups() {
        Map<String, List<LookupDto>> cached =
                redisService.get(CacheNamespaces.LOOKUP_FRONTEND, CACHE_KEY_FRONTEND, new TypeReference<>() {});
        if (cached != null) return cached;
        Map<String, List<LookupDto>> result = lookupRepository.findByFrontendUsingTrueAndIsActiveTrueOrderByLookupTypeAscSeqAsc()
                .stream()
                .map(lookupMapper::toDto)
                .collect(Collectors.groupingBy(LookupDto::lookupType));
        redisService.set(CacheNamespaces.LOOKUP_FRONTEND, CACHE_KEY_FRONTEND, result, LOOKUP_CACHE_DURATION);
        return result;
    }

    @Transactional
    public LookupDto create(CreateLookupRequest request) {
        String type = request.getLookupType().trim();
        String key = request.getLookupKey().trim();

        if (lookupRepository.existsByLookupTypeAndLookupKey(type, key)) {
            throw ApiException.conflict(MessageKey.LOOKUP_EXISTS, type, key);
        }

        Lookup lookup = new Lookup();
        lookup.setLookupType(type);
        lookup.setLookupKey(key);
        lookup.setLookupValue(request.getLookupValue());
        lookup.setSeq(Objects.requireNonNullElse(request.getSeq(), 0));
        lookup.setParentType(request.getParentType());
        lookup.setParentKey(request.getParentKey());
        lookup.setDescription(request.getDescription());
        lookup.setFrontendUsing(Boolean.TRUE.equals(request.getFrontendUsing()));
        lookup.setIsActive(true);

        Lookup saved = lookupRepository.save(lookup);
        evictCacheForType(type, Boolean.TRUE.equals(saved.getFrontendUsing()));
        log.info("Dictionary item created: {}/{}", type, key);
        return lookupMapper.toDto(saved);
    }

    @Transactional
    public LookupDto update(String id, UpdateLookupRequest request) {
        Lookup lookup = findById(id);

        // If changing type/key, confirm no duplicates
        String newType = request.getLookupType() != null
                ? request.getLookupType().trim() : lookup.getLookupType();
        String newKey = request.getLookupKey() != null
                ? request.getLookupKey().trim() : lookup.getLookupKey();

        if (lookupRepository.existsByLookupTypeAndLookupKeyAndIdNot(newType, newKey, lookup.getId())) {
            throw ApiException.conflict(MessageKey.LOOKUP_EXISTS, newType, newKey);
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

        // Evict caches that may be affected
        evictCacheForType(oldType, oldFrontend);
        evictCacheForType(newType, Boolean.TRUE.equals(saved.getFrontendUsing()));
        log.info("Dictionary item updated: id={}", id);
        return lookupMapper.toDto(saved);
    }

    @Transactional
    public void activate(String id) {
        setActive(id, true);
    }

    @Transactional
    public void deactivate(String id) {
        setActive(id, false);
    }

    @Transactional
    public void delete(String id) {
        Lookup lookup = findById(id);
        String type = lookup.getLookupType();
        boolean frontend = Boolean.TRUE.equals(lookup.getFrontendUsing());
        lookupRepository.delete(lookup);
        evictCacheForType(type, frontend);
        log.info("Dictionary item deleted: id={}, type={}", id, type);
    }

    // ─── private helpers ──────────────────────────────────────────────────────

    private Lookup findById(String id) {
        return lookupRepository.findById(UUID.fromString(id))
                .orElseThrow(() -> ApiException.notFound(MessageKey.LOOKUP_NOT_FOUND));
    }

    private void setActive(String id, boolean active) {
        Lookup lookup = findById(id);
        // Unchanged: no write, but still evict, so toggling again clears a stale cache
        if (!Boolean.valueOf(active).equals(lookup.getIsActive())) {
            lookup.setIsActive(active);
            lookupRepository.save(lookup);
        }
        evictCacheForType(lookup.getLookupType(), Boolean.TRUE.equals(lookup.getFrontendUsing()));
        log.info("Dictionary item {}: id={}, type={}", active ? "activated" : "deactivated", id, lookup.getLookupType());
    }

    // After commit: evicting earlier lets a concurrent read re-cache the old rows for LOOKUP_CACHE_DURATION
    private void evictCacheForType(String lookupType, boolean isFrontend) {
        AfterCommit.run(() -> {
            redisService.delete(CacheNamespaces.LOOKUP_BY_TYPE, lookupType);
            if (isFrontend) {
                redisService.delete(CacheNamespaces.LOOKUP_FRONTEND, CACHE_KEY_FRONTEND);
            }
        });
    }
}
