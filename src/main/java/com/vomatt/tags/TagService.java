package com.vomatt.tags;

import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.tags.dto.CreateTagRequest;
import com.vomatt.tags.dto.UpdateTagRequest;
import com.vomatt.tags.dto.TagDto;
import com.vomatt.entity.Tag;
import com.vomatt.common.response.Cursor;
import com.vomatt.common.response.CursorResponse;
import com.vomatt.repository.TagRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class TagService {

    private final TagRepository tagRepository;

    @Transactional
    public TagDto createTag(CreateTagRequest request) {
        String name = request.getName().trim();

        if (tagRepository.existsByName(name)) {
            throw ApiException.conflict(MessageKey.TAG_NAME_EXISTS, name);
        }

        String slug = resolveSlug(request.getSlug(), name);

        if (tagRepository.existsBySlug(slug)) {
            throw ApiException.conflict(MessageKey.TAG_SLUG_EXISTS, slug);
        }

        Tag tag = new Tag(name, slug, request.getDescription(), request.getDisplayOrder());
        tag = tagRepository.save(tag);
        log.info("Tag created: id={}, name={}, slug={}", tag.getId(), tag.getName(), tag.getSlug());

        return toDto(tag);
    }

    @Transactional
    public TagDto updateTag(String tagId, UpdateTagRequest request) {
        UUID id = UUID.fromString(tagId);
        Tag tag = tagRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound(MessageKey.TAG_NOT_FOUND));

        String name = request.getName().trim();

        if (tagRepository.existsByNameAndIdNot(name, id)) {
            throw ApiException.conflict(MessageKey.TAG_NAME_EXISTS, name);
        }

        String slug = resolveSlug(request.getSlug(), name);

        if (tagRepository.existsBySlugAndIdNot(slug, id)) {
            throw ApiException.conflict(MessageKey.TAG_SLUG_EXISTS, slug);
        }

        tag.update(name, slug, request.getDescription(), request.getDisplayOrder());
        tag = tagRepository.save(tag);
        log.info("Tag updated: id={}, name={}, slug={}", tag.getId(), tag.getName(), tag.getSlug());

        return toDto(tag);
    }

    @Transactional
    public void deleteTag(String tagId) {
        UUID id = UUID.fromString(tagId);
        Tag tag = tagRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound(MessageKey.TAG_NOT_FOUND));

        if (tagRepository.isTagReferencedByVotes(id)) {
            throw ApiException.conflict(MessageKey.TAG_IN_USE);
        }

        tagRepository.delete(tag);
        log.info("Tag deleted: id={}", tagId);
    }

    @Transactional(readOnly = true)
    public List<TagDto> getAllTags() {
        return tagRepository.findAllByOrderByDisplayOrderAsc()
                .stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public CursorResponse<TagDto> getPopularTags(String cursor, Integer limit) {
        int size = CursorResponse.limit(limit);
        Cursor after = Cursor.decode(cursor);
        List<Tag> rows = tagRepository.findPopularPage(after == null ? null : (int) after.longKey(),
                after == null ? null : after.id(), Limit.of(size + 1));
        return CursorResponse.of(rows, size, t -> Cursor.of(t.getUsageCount(), t.getId()),
                page -> page.stream().map(this::toDto).toList());
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
