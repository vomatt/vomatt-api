package com.vomattapi.application.service;

import com.vomattapi.application.dto.request.CreateTagRequest;
import com.vomattapi.application.dto.request.UpdateTagRequest;
import com.vomattapi.application.dto.response.TagDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface TagService {
    TagDto createTag(CreateTagRequest request);
    TagDto updateTag(String tagId, UpdateTagRequest request);
    void deleteTag(String tagId);
    List<TagDto> getAllTags();
    Page<TagDto> getPopularTags(Pageable pageable);
}
