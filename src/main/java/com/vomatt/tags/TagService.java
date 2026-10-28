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
import com.vomatt.repository.UserRepository;
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
    private final UserRepository userRepository;

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

        return TagDto.from(tag);
    }

    @Transactional
    public TagDto updateTag(String tagId, UpdateTagRequest request) {
        UUID id = UUID.fromString(tagId);
        Tag tag = findTag(id);

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

        return TagDto.from(tag);
    }

    @Transactional
    public void deleteTag(String tagId) {
        UUID id = UUID.fromString(tagId);
        Tag tag = findTag(id);

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
                .map(TagDto::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public CursorResponse<TagDto> getPopularTags(String cursor, Integer limit) {
        int size = CursorResponse.limit(limit);
        Cursor after = Cursor.decode(cursor, Cursor.Scope.POPULAR_TAGS);
        List<Tag> rows = tagRepository.findPopularPage(after == null ? null : (int) after.longKey(),
                after == null ? null : after.id(), Limit.of(size + 1));
        return CursorResponse.of(rows, size, Cursor.Scope.POPULAR_TAGS, t -> Cursor.of(t.getUsageCount(), t.getId()),
                page -> page.stream().map(TagDto::from).toList());
    }

    /** Follows a tag, so its Open Public Polls join the caller's Following list; idempotent. */
    @Transactional
    public void followTag(String userId, String tagId) {
        UUID id = UUID.fromString(tagId);
        UUID userUuid = UUID.fromString(userId);
        if (tagRepository.follow(userUuid, id) > 0) {
            log.info("User {} followed tag {}", userId, tagId);
        } else if (!tagRepository.isFollowing(userUuid, id)) {
            requireUserExists(userUuid);
            if (!tagRepository.existsById(id)) {
                throw ApiException.notFound(MessageKey.TAG_NOT_FOUND);
            }
        }
    }

    /** Unfollows a tag; idempotent, whether or not the tag exists. */
    @Transactional
    public void unfollowTag(String userId, String tagId) {
        if (tagRepository.unfollow(UUID.fromString(userId), UUID.fromString(tagId)) > 0) {
            log.info("User {} unfollowed tag {}", userId, tagId);
        }
    }

    @Transactional(readOnly = true)
    public List<TagDto> getFollowedTags(String userId) {
        return tagRepository.findFollowedBy(UUID.fromString(userId)).stream().map(TagDto::from).toList();
    }

    /**
     * For a write that inserted nothing although the signed-in user's row guarded it: an access token outlives account
     * deletion, so the user may be gone since the request passed the filter.
     */
    private void requireUserExists(UUID userId) {
        if (!userRepository.existsById(userId)) {
            throw ApiException.notFound(MessageKey.USER_NOT_FOUND);
        }
    }

    private Tag findTag(UUID id) {
        return tagRepository.findById(id).orElseThrow(() -> ApiException.notFound(MessageKey.TAG_NOT_FOUND));
    }

    private String resolveSlug(String providedSlug, String name) {
        if (providedSlug != null && !providedSlug.isBlank()) {
            return providedSlug;
        }
        return Tag.generateSlug(name);
    }
}
