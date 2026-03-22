package com.vomattapi.application.service.impl;

import com.vomattapi.application.dto.request.CreateTagRequest;
import com.vomattapi.application.dto.request.UpdateTagRequest;
import com.vomattapi.application.dto.response.TagDto;
import com.vomattapi.application.exception.BusinessRuleViolationException;
import com.vomattapi.application.exception.EntityNotFoundException;
import com.vomattapi.application.exception.ResourceConflictException;
import com.vomattapi.application.service.TagService;
import com.vomattapi.domain.vote.Tag;
import com.vomattapi.domain.vote.repository.TagRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class TagServiceImpl implements TagService {

    private final TagRepository tagRepository;

    @Override
    @Transactional
    public TagDto createTag(CreateTagRequest request) {
        String name = request.getName().trim();

        if (tagRepository.existsByName(name)) {
            throw new BusinessRuleViolationException("標籤名稱已存在: " + name);
        }

        String slug = resolveSlug(request.getSlug(), name);

        if (tagRepository.existsBySlug(slug)) {
            throw new BusinessRuleViolationException("Slug 已存在: " + slug);
        }

        Tag tag = new Tag(name, slug, request.getDescription(), request.getDisplayOrder());
        tag = tagRepository.save(tag);
        log.info("Tag created: id={}, name={}, slug={}", tag.getId(), tag.getName(), tag.getSlug());

        return toDto(tag);
    }

    @Override
    @Transactional
    public TagDto updateTag(String tagId, UpdateTagRequest request) {
        UUID id = UUID.fromString(tagId);
        Tag tag = tagRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Tag", tagId));

        String name = request.getName().trim();

        if (tagRepository.existsByNameAndIdNot(name, id)) {
            throw new BusinessRuleViolationException("標籤名稱已存在: " + name);
        }

        String slug = resolveSlug(request.getSlug(), name);

        if (tagRepository.existsBySlugAndIdNot(slug, id)) {
            throw new BusinessRuleViolationException("Slug 已存在: " + slug);
        }

        tag.update(name, slug, request.getDescription(), request.getDisplayOrder());
        tag = tagRepository.save(tag);
        log.info("Tag updated: id={}, name={}, slug={}", tag.getId(), tag.getName(), tag.getSlug());

        return toDto(tag);
    }

    @Override
    @Transactional
    public void deleteTag(String tagId) {
        UUID id = UUID.fromString(tagId);
        Tag tag = tagRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Tag", tagId));

        if (tagRepository.isTagReferencedByVotes(id)) {
            throw new ResourceConflictException("無法刪除已被投票引用的標籤: " + tagId);
        }

        tagRepository.delete(tag);
        log.info("Tag deleted: id={}", tagId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TagDto> getAllTags() {
        return tagRepository.findAllByOrderByDisplayOrderAsc()
                .stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public Page<TagDto> getPopularTags(Pageable pageable) {
        return tagRepository.findAllByOrderByUsageCountDesc(pageable)
                .map(this::toDto);
    }

    private String resolveSlug(String providedSlug, String name) {
        if (providedSlug != null && !providedSlug.isBlank()) {
            return providedSlug;
        }
        return Tag.generateSlug(name);
    }

    private TagDto toDto(Tag tag) {
        return new TagDto(
                tag.getId().toString(),
                tag.getName(),
                tag.getSlug(),
                tag.getDescription(),
                tag.getDisplayOrder(),
                tag.getUsageCount()
        );
    }
}
