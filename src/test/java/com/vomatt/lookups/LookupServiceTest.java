package com.vomatt.lookups;

import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.common.redis.CacheNamespaces;
import com.vomatt.common.redis.RedisService;
import com.vomatt.entity.Lookup;
import com.vomatt.lookups.dto.CreateLookupRequest;
import com.vomatt.lookups.dto.LookupDto;
import com.vomatt.repository.LookupRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.node.StringNode;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("LookupService")
class LookupServiceTest {

    @Mock LookupRepository lookupRepository;
    @Mock LookupMapper lookupMapper;
    @Mock RedisService redisService;

    @InjectMocks
    LookupService lookupService;

    // ─── helper ───────────────────────────────────────────────────────────────

    private static final UUID LOOKUP_ID = UUID.randomUUID();

    private Lookup buildLookup(String type, String key, boolean active, boolean frontend) {
        Lookup l = new Lookup();
        ReflectionTestUtils.setField(l, "id", LOOKUP_ID);
        l.setLookupType(type);
        l.setLookupKey(key);
        l.setLookupValue(StringNode.valueOf("value"));
        l.setSeq(0);
        l.setIsActive(active);
        l.setFrontendUsing(frontend);
        return l;
    }

    private LookupDto buildDto(String type, String key) {
        return new LookupDto(LOOKUP_ID.toString(), type, key,
                StringNode.valueOf("value"), 0, null, null, true, null, false);
    }

    private static void assertApiException(Throwable ex, HttpStatus status, MessageKey key) {
        assertThat(ex).isInstanceOf(ApiException.class);
        ApiException apiEx = (ApiException) ex;
        assertThat(apiEx.getStatus()).isEqualTo(status);
        assertThat(apiEx.getMessageKey()).isEqualTo(key);
    }

    // ─── getActiveByType ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("getActiveByType")
    class GetActiveByTypeTests {

        @Test
        @DisplayName("Cache miss: should load only isActive=true items and populate cache")
        void shouldReturnActiveItemsByType() {
            Lookup active = buildLookup("CITY", "TPE", true, false);
            LookupDto dto = buildDto("CITY", "TPE");

            when(redisService.get(eq(CacheNamespaces.LOOKUP_BY_TYPE), eq("CITY"), any(TypeReference.class)))
                    .thenReturn(null);
            when(lookupRepository.findByLookupTypeAndIsActiveTrueOrderBySeqAsc("CITY"))
                    .thenReturn(List.of(active));
            when(lookupMapper.toDto(active)).thenReturn(dto);

            List<LookupDto> result = lookupService.getActiveByType("CITY");

            assertThat(result).hasSize(1);
            assertThat(result.get(0).lookupType()).isEqualTo("CITY");
            verify(redisService).set(eq(CacheNamespaces.LOOKUP_BY_TYPE), eq("CITY"), eq(List.of(dto)), any(Duration.class));
        }

        @Test
        @DisplayName("Cache hit: should return cached list without querying DB")
        void shouldReturnCachedItems() {
            LookupDto dto = buildDto("CITY", "TPE");
            when(redisService.get(eq(CacheNamespaces.LOOKUP_BY_TYPE), eq("CITY"), any(TypeReference.class)))
                    .thenReturn(List.of(dto));

            List<LookupDto> result = lookupService.getActiveByType("CITY");

            assertThat(result).containsExactly(dto);
            verifyNoInteractions(lookupRepository);
            verify(redisService, never()).set(anyString(), anyString(), any(), any());
        }
    }

    // ─── getByTypeAndKey ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("getByTypeAndKey")
    class GetByTypeAndKeyTests {

        @Test
        @DisplayName("Should return item for specific type/key")
        void shouldReturnItemByTypeAndKey() {
            Lookup lookup = buildLookup("CITY", "TPE", true, false);
            LookupDto dto = buildDto("CITY", "TPE");

            when(lookupRepository.findByLookupTypeAndLookupKey("CITY", "TPE"))
                    .thenReturn(Optional.of(lookup));
            when(lookupMapper.toDto(lookup)).thenReturn(dto);

            LookupDto result = lookupService.getByTypeAndKey("CITY", "TPE");

            assertThat(result.lookupKey()).isEqualTo("TPE");
        }

        @Test
        @DisplayName("Should throw 404 LOOKUP_NOT_FOUND when not found")
        void shouldThrowWhenNotFound() {
            when(lookupRepository.findByLookupTypeAndLookupKey("CITY", "UNKNOWN"))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> lookupService.getByTypeAndKey("CITY", "UNKNOWN"))
                    .satisfies(ex -> assertApiException(ex, HttpStatus.NOT_FOUND, MessageKey.LOOKUP_NOT_FOUND));
        }
    }

    // ─── getChildrenByParent ──────────────────────────────────────────────────

    @Nested
    @DisplayName("getChildrenByParent")
    class GetChildrenByParentTests {

        @Test
        @DisplayName("Should return child items of specified parent node")
        void shouldReturnChildrenByParent() {
            Lookup child = buildLookup("DISTRICT", "ZHONGSHAN", true, false);
            child.setParentType("CITY");
            child.setParentKey("TPE");
            LookupDto dto = buildDto("DISTRICT", "ZHONGSHAN");

            when(lookupRepository
                    .findByLookupTypeAndParentTypeAndParentKeyAndIsActiveTrueOrderBySeqAsc(
                            "DISTRICT", "CITY", "TPE"))
                    .thenReturn(List.of(child));
            when(lookupMapper.toDto(child)).thenReturn(dto);

            List<LookupDto> result = lookupService.getChildrenByParent("DISTRICT", "CITY", "TPE");

            assertThat(result).hasSize(1);
            assertThat(result.get(0).lookupType()).isEqualTo("DISTRICT");
        }
    }

    // ─── getFrontendLookups ───────────────────────────────────────────────────

    @Nested
    @DisplayName("getFrontendLookups")
    class GetFrontendLookupsTests {

        @Test
        @DisplayName("Should return frontend dictionaries grouped by type and populate cache")
        void shouldGroupByTypeForFrontend() {
            Lookup l1 = buildLookup("CITY", "TPE", true, true);
            Lookup l2 = buildLookup("CITY", "KHH", true, true);
            LookupDto d1 = buildDto("CITY", "TPE");
            LookupDto d2 = buildDto("CITY", "KHH");

            when(redisService.get(eq(CacheNamespaces.LOOKUP_FRONTEND), anyString(), any(TypeReference.class)))
                    .thenReturn(null);
            when(lookupRepository.findByFrontendUsingTrueAndIsActiveTrueOrderByLookupTypeAscSeqAsc())
                    .thenReturn(List.of(l1, l2));
            when(lookupMapper.toDto(l1)).thenReturn(d1);
            when(lookupMapper.toDto(l2)).thenReturn(d2);

            Map<String, List<LookupDto>> result = lookupService.getFrontendLookups();

            assertThat(result).containsKey("CITY");
            assertThat(result.get("CITY")).hasSize(2);
            verify(redisService).set(eq(CacheNamespaces.LOOKUP_FRONTEND), anyString(), eq(result), any(Duration.class));
        }
    }

    // ─── create ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("create")
    class CreateTests {

        @Test
        @DisplayName("Should successfully create new dictionary item and evict type cache")
        void shouldCreateLookup() {
            CreateLookupRequest req = new CreateLookupRequest();
            req.setLookupType("CITY");
            req.setLookupKey("KHH");
            req.setLookupValue(StringNode.valueOf("高雄"));

            Lookup saved = buildLookup("CITY", "KHH", true, false);
            LookupDto dto = buildDto("CITY", "KHH");

            when(lookupRepository.existsByLookupTypeAndLookupKey("CITY", "KHH")).thenReturn(false);
            when(lookupRepository.save(any(Lookup.class))).thenReturn(saved);
            when(lookupMapper.toDto(saved)).thenReturn(dto);

            LookupDto result = lookupService.create(req);

            assertThat(result.lookupKey()).isEqualTo("KHH");
            verify(lookupRepository).save(any(Lookup.class));
            verify(redisService).delete(CacheNamespaces.LOOKUP_BY_TYPE, "CITY");
            // 非前端字典不需清前端快取
            verify(redisService, never()).delete(eq(CacheNamespaces.LOOKUP_FRONTEND), anyString());
        }

        @Test
        @DisplayName("Should throw 409 LOOKUP_EXISTS when type/key already exists")
        void shouldThrowWhenTypeKeyAlreadyExists() {
            CreateLookupRequest req = new CreateLookupRequest();
            req.setLookupType("CITY");
            req.setLookupKey("TPE");
            req.setLookupValue(StringNode.valueOf("台北"));

            when(lookupRepository.existsByLookupTypeAndLookupKey("CITY", "TPE")).thenReturn(true);

            assertThatThrownBy(() -> lookupService.create(req))
                    .satisfies(ex -> {
                        assertApiException(ex, HttpStatus.CONFLICT, MessageKey.LOOKUP_EXISTS);
                        assertThat(((ApiException) ex).getMessageArgs()).containsExactly("CITY", "TPE");
                    });
            verify(lookupRepository, never()).save(any());
        }
    }

    // ─── deactivate ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("deactivate")
    class DeactivateTests {

        @Test
        @DisplayName("Should only change isActive to false, not delete data")
        void shouldDeactivateWithoutDeleting() {
            Lookup lookup = buildLookup("CITY", "TPE", true, true);

            when(lookupRepository.findById(LOOKUP_ID)).thenReturn(Optional.of(lookup));
            when(lookupRepository.save(any(Lookup.class))).thenAnswer(inv -> inv.getArgument(0));

            lookupService.deactivate(LOOKUP_ID.toString());

            verify(lookupRepository).save(argThat(l -> !l.getIsActive()));
            verify(lookupRepository, never()).delete(any());
            // 前端字典須同時清 type 與 frontend 快取
            verify(redisService).delete(CacheNamespaces.LOOKUP_BY_TYPE, "CITY");
            verify(redisService).delete(eq(CacheNamespaces.LOOKUP_FRONTEND), anyString());
        }
    }

    // ─── delete ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("delete")
    class DeleteTests {

        @Test
        @DisplayName("Should throw 404 LOOKUP_NOT_FOUND when ID not found")
        void shouldThrowWhenIdNotFound() {
            UUID randomId = UUID.randomUUID();
            when(lookupRepository.findById(randomId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> lookupService.delete(randomId.toString()))
                    .satisfies(ex -> assertApiException(ex, HttpStatus.NOT_FOUND, MessageKey.LOOKUP_NOT_FOUND));
            verifyNoInteractions(redisService);
        }
    }
}
