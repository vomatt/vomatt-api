package com.vomattapi.service;

import com.fasterxml.jackson.databind.node.TextNode;
import com.vomattapi.application.dto.lookup.CreateLookupRequest;
import com.vomattapi.application.dto.lookup.LookupDto;
import com.vomattapi.application.dto.lookup.UpdateLookupRequest;
import com.vomattapi.application.exception.BusinessRuleViolationException;
import com.vomattapi.application.exception.EntityNotFoundException;
import com.vomattapi.application.mapper.LookupMapper;
import com.vomattapi.application.service.lookup.LookupServiceImpl;
import com.vomattapi.domain.common.Lookup;
import com.vomattapi.domain.common.repository.LookupRepository;
import com.vomattapi.infrastructure.redis.CacheUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("LookupService")
class LookupServiceTest {

    @Mock LookupRepository lookupRepository;
    @Mock LookupMapper lookupMapper;
    @Mock CacheUtil cacheUtil;

    @InjectMocks
    LookupServiceImpl lookupService;

    // ─── helper ───────────────────────────────────────────────────────────────

    private static final UUID LOOKUP_ID = UUID.randomUUID();

    private Lookup buildLookup(String type, String key, boolean active, boolean frontend) {
        Lookup l = new Lookup();
        l.setId(LOOKUP_ID);
        l.setLookupType(type);
        l.setLookupKey(key);
        l.setLookupValue(new TextNode("value"));
        l.setSeq(0);
        l.setIsActive(active);
        l.setFrontendUsing(frontend);
        return l;
    }

    private LookupDto buildDto(String type, String key) {
        return new LookupDto(LOOKUP_ID.toString(), type, key,
                new TextNode("value"), 0, null, null, true, null, false);
    }

    // ─── getActiveByType ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("getActiveByType")
    class GetActiveByTypeTests {

        @Test
        @DisplayName("Should return only isActive=true items")
        void shouldReturnActiveItemsByType() {
            Lookup active = buildLookup("CITY", "TPE", true, false);
            LookupDto dto = buildDto("CITY", "TPE");

            // CacheUtil directly executes supplier (simulate cache miss)
            when(cacheUtil.getOrSet(eq("lookup:type:CITY"), eq(Object.class), any(), any()))
                    .thenAnswer(inv -> inv.<java.util.function.Supplier<?>>getArgument(2).get());
            when(lookupRepository.findByLookupTypeAndIsActiveTrueOrderBySeqAsc("CITY"))
                    .thenReturn(List.of(active));
            when(lookupMapper.toDto(active)).thenReturn(dto);

            List<LookupDto> result = lookupService.getActiveByType("CITY");

            assertThat(result).hasSize(1);
            assertThat(result.get(0).lookupType()).isEqualTo("CITY");
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
        @DisplayName("Should throw EntityNotFoundException when not found")
        void shouldThrowWhenNotFound() {
            when(lookupRepository.findByLookupTypeAndLookupKey("CITY", "UNKNOWN"))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> lookupService.getByTypeAndKey("CITY", "UNKNOWN"))
                    .isInstanceOf(EntityNotFoundException.class);
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
        @DisplayName("Should return frontend dictionaries grouped by type")
        void shouldGroupByTypeForFrontend() {
            Lookup l1 = buildLookup("CITY", "TPE", true, true);
            Lookup l2 = buildLookup("CITY", "KHH", true, true);
            LookupDto d1 = buildDto("CITY", "TPE");
            LookupDto d2 = buildDto("CITY", "KHH");

            when(cacheUtil.getOrSet(eq("lookup:frontend"), eq(Object.class), any(), any()))
                    .thenAnswer(inv -> inv.<java.util.function.Supplier<?>>getArgument(2).get());
            when(lookupRepository.findByFrontendUsingTrueAndIsActiveTrueOrderByLookupTypeAscSeqAsc())
                    .thenReturn(List.of(l1, l2));
            when(lookupMapper.toDto(l1)).thenReturn(d1);
            when(lookupMapper.toDto(l2)).thenReturn(d2);

            Map<String, List<LookupDto>> result = lookupService.getFrontendLookups();

            assertThat(result).containsKey("CITY");
            assertThat(result.get("CITY")).hasSize(2);
        }
    }

    // ─── create ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("create")
    class CreateTests {

        @Test
        @DisplayName("Should successfully create new dictionary item")
        void shouldCreateLookup() {
            CreateLookupRequest req = new CreateLookupRequest();
            req.setLookupType("CITY");
            req.setLookupKey("KHH");
            req.setLookupValue(new TextNode("高雄"));

            Lookup saved = buildLookup("CITY", "KHH", true, false);
            LookupDto dto = buildDto("CITY", "KHH");

            when(lookupRepository.existsByLookupTypeAndLookupKey("CITY", "KHH")).thenReturn(false);
            when(lookupRepository.save(any(Lookup.class))).thenReturn(saved);
            when(lookupMapper.toDto(saved)).thenReturn(dto);

            LookupDto result = lookupService.create(req);

            assertThat(result.lookupKey()).isEqualTo("KHH");
            verify(lookupRepository).save(any(Lookup.class));
        }

        @Test
        @DisplayName("Should throw exception when type/key already exists")
        void shouldThrowWhenTypeKeyAlreadyExists() {
            CreateLookupRequest req = new CreateLookupRequest();
            req.setLookupType("CITY");
            req.setLookupKey("TPE");
            req.setLookupValue(new TextNode("台北"));

            when(lookupRepository.existsByLookupTypeAndLookupKey("CITY", "TPE")).thenReturn(true);

            assertThatThrownBy(() -> lookupService.create(req))
                    .isInstanceOf(BusinessRuleViolationException.class)
                    .hasMessageContaining("Dictionary item already exists");
        }
    }

    // ─── deactivate ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("deactivate")
    class DeactivateTests {

        @Test
        @DisplayName("Should only change isActive to false, not delete data")
        void shouldDeactivateWithoutDeleting() {
            Lookup lookup = buildLookup("CITY", "TPE", true, false);

            when(lookupRepository.findById(LOOKUP_ID)).thenReturn(Optional.of(lookup));
            when(lookupRepository.save(any(Lookup.class))).thenAnswer(inv -> inv.getArgument(0));

            lookupService.deactivate(LOOKUP_ID.toString());

            verify(lookupRepository).save(argThat(l -> !l.getIsActive()));
            verify(lookupRepository, never()).delete(any());
        }
    }

    // ─── delete ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("delete")
    class DeleteTests {

        @Test
        @DisplayName("Should throw exception when ID not found")
        void shouldThrowWhenIdNotFound() {
            UUID randomId = UUID.randomUUID();
            when(lookupRepository.findById(randomId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> lookupService.delete(randomId.toString()))
                    .isInstanceOf(EntityNotFoundException.class);
        }
    }
}
