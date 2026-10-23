package com.vomatt.admin;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vomatt.admin.dto.AdminFeedbackResponse;
import com.vomatt.common.response.PageResponse;
import com.vomatt.repository.FeedbackRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminFeedbackService {

    private final FeedbackRepository feedbackRepository;
    private final AdminMapper adminMapper;

    public PageResponse<AdminFeedbackResponse> listFeedback(UUID userId, Integer page, Integer limit) {
        return PageResponse.from(feedbackRepository.findPage(userId, PageResponse.pageable(page, limit)),
                adminMapper::toFeedbackResponse);
    }
}
